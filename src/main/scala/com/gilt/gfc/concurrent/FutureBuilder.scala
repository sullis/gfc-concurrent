package com.gilt.gfc.concurrent

import com.gilt.gfc.concurrent.FutureBuilder.PartialFunctionToExceptionExtractor
import com.gilt.gfc.concurrent.trace.{FutureGenericErrorTrace, FutureRecoverableErrorTrace, FutureTrace}
import com.gilt.gfc.logging.OpenLoggable

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scalaz._

/**
 * A convenience wrapper around Future-based RPC calls.
 *
 * Typically we have an API that returns some Future[OpResult] and we need to set an operation timeout,
 * possibly provide a default value, log how long it took for debugging, maybe retry.
 *
 * By default it builds a Future[Throwable \/ A] from Future[A].
 * When default value is provided it builds Future[A] from Future[A].
 * At the very minimum it'll apply a mandatory timeout but you can add retry logic, RPC call tracer, etc.
 *
 * It is important to specify type A _at_the_time_of_creation_, type inference doesn't pick it up.
 * {{{
 *   FutureBuilder[Option[Foo]]("FooCall")......
 * }}}
 *
 * We want to apply timeouts to *all* Futures, so,
 * {{{
 *   ...futureBuilder.runWithTimeout(100 milliseconds){ .... something that returns Future ... }
 * }}}
 *
 */
case class FutureBuilder[A,R] private (
  rpcCallName: String // this'll show up in wrapped exceptions and logs

, additionalMessage: String // this'll show up in wrapped exceptions and logs

// N.B. below 'by name' parameters are to support 'retry' functionality

// How we process errors changes depending on whether or not there's a default
// value. When there is one we want to stay with A as a Result type,
// i.e. Future[A] => Future[A]
// OTOH, when we don't have any defaults - we want to bubble that decision
// up to a typesystem level. Scala doesn't have checked exceptions but
// it does have 'union' (e.g. Either) types, which are an FP alternative.
, rpcErrorHandler:             (=> Future[Throwable \/ A]) => Future[R]

// As it happens some of the exceptions are actually data,
// e.g. subclasses of UserException are needed for registration control flow.
// Would be much easier if these cases were encoded in the return type but we are where we are,
// so, need to be able to pass some of them through unwrapped and avoid running 'retry' logic in those cases.
, passThroughExceptionHandler: PartialFunction[Throwable, Throwable]


// Allows us to retry calls when we bump into errors, but see passThroughExceptionHandler.
, addNumRetries:               (=> Future[Throwable \/ A]) => Future[Throwable \/ A]


// Allows us to log service call times, to debug site problems.
// All of this async stuff is notorious for not having comprehensible stack traces.
, addTraceCalls:               (=> Future[Throwable \/ A]) => Future[Throwable \/ A]

// Puts a timeout on call Future.
, addSingleCallTimeout:        (=> Future[Throwable \/ A]) => Future[Throwable \/ A]

) {

  /** Composes all the Future transformations, gives resulting Future back.
    * Applies timeout to a service call, this
    *
    * By default NonFatal RPC failures are caught and represented as an Either type R (either Throwable or A).
    * OTOH if a serviceErrorDefaultValue is provided than we log errors and default to that, result type remains A.
    *
    * @param after mandatory timeout we set on 'service call' Futures
    * @param rpcCall 'by name' parameter that evaluates to a Future[SomeServiceCallResult],
    *                this may be called multiple times if retry() is enabled.
    *
    * @return Future[SomeServiceCallResult] if a default value is provided or
    *         Future[Throwable \/ SomeServiceCallResult] in case there's no default,
    *         indicating a failure branch that needs to be handled.
    *         Scalaz's Either type (\/) is used instead of built-in because it composes better.
    */
  def runWithTimeout( after: FiniteDuration
                   )( rpcCall: => Future[A]
                    ): Future[R] = {
    import ScalaFutures._

    this.copy[A,R](addSingleCallTimeout = { f =>
      implicit val executor: ExecutionContext = ExecutionContext.Implicits.global
      f.withTimeout(after)
    }).run(rpcCall)
  }

  /** Will return this value if RPC call fails or we fail to interpret results. */
  def withServiceErrorDefaultValue( v: A )
                                  : FutureBuilder[A,A] = {

    implicit val executor: ExecutionContext = ExecutionContext.Implicits.global

    copy(rpcErrorHandler = { f =>
      f recover {

        // Un-expected exceptions
        case NonFatal(e) =>
          blocking{ FutureBuilder.Logger.error(s"${rpcCallName}(${additionalMessage}): ${e.getMessage}", e) }
          \/-(v) // we have a default value to fill in in case of generic server errors

      } map {

        case -\/(e) => throw e // 'pass through' exception, need to re-throw
        case \/-(v) => v // normal service call result
      }
    })
  }

  /** Will retry this many times before giving up and returning an error. */
  def retry( n: Int )
           ( implicit ec: ExecutionContext )
           : FutureBuilder[A,R] = {
    require(n>0, "Num retries must be > 0")
    copy( addNumRetries = { f => ScalaFutures.retry(n)(f) } )
  }

  /** Unfortunately, some exceptions are 'data' and are important for control flow, in their unmodified/unwrapped form,
    * this allows caller to enumerate them and even (optionally) transform to another exception type.
    *
    * When we get one of these we don't want to retry() service calls, we don't want to wrap them with additional
    * user message, we just want to pass them through.
    *
    * An example would be subclasses of UserException in commons.
    */
  def withPassThroughExceptions( handlePassThroughExceptions: PartialFunction[Throwable, Throwable]
                               ): FutureBuilder[A, R] = {
    copy(passThroughExceptionHandler = handlePassThroughExceptions)
  }


  /** Disable/enable RPC call tracing (simply logging call times for now).
    * The intention is to try to integrate these with CloudWatch logs and get some idea about
    * RPC call timing.
    *
    * @param callTracer will be called with the results of an RPC call.
    */
  def withTraceCalls( callTracer: (FutureTrace) => Unit
                    ): FutureBuilder[A,R] = {

    copy(addTraceCalls = { f =>
      implicit val executor: ExecutionContext = ExecutionContext.Implicits.global

      val beginTime = System.currentTimeMillis

      val fRef = f // grab result of a 'by name' function call, onComplete is Unit type, this avoids firing call twice

      fRef onComplete { res: Try[Throwable \/ A] =>
        val endTime = System.currentTimeMillis
        val dt = endTime - beginTime

        val trace = res match {
          case Success(\/-(_)) => FutureTrace(rpcCallName, additionalMessage, dt, None)
          case Success(-\/(e)) => FutureTrace(rpcCallName, additionalMessage, dt, Some(FutureRecoverableErrorTrace(e)))
          case Failure(e)      => FutureTrace(rpcCallName, additionalMessage, dt, Some(FutureGenericErrorTrace(e)))
        }

        blocking {
          try {
            callTracer.apply(trace)
          } catch {
            case NonFatal(e) =>
              FutureBuilder.Logger.error(s"Call tracer failed: ${e.getMessage}", e)
          }
        }
      }

      fRef
    })
  }


  /** We hide this method to force runWithTimeout() instead, this way timeouts are always applied. */
  private
  def run( rpcCall: => Future[A]
         ): Future[R] = {

    // order matters, e.g. we want to apply single call timeout before any retries
    rpcErrorHandler(
      addNumRetries(
        addTraceCalls(
          addSingleCallTimeout(
            addErrorMessage(
              callService(rpcCall
    ))))))
  }



  /** Adds more context to thrown exceptions. */
  private[this]
  def addErrorMessage(f: => Future[Throwable \/ A]
                     ): Future[Throwable \/ A] = {
    implicit val ec = SameThreadExecutionContext

    f transform (
      s = identity,
      f = (t: Throwable) => new Exception(s"${rpcCallName}(${additionalMessage}): ${t.getMessage}", t)
    )
  }


  private[this]
  def callService( rpcCall: => Future[A]
                 ): Future[Throwable \/ A] = {

    val PassThroughExceptionExtractor = PartialFunctionToExceptionExtractor(passThroughExceptionHandler)

    implicit val gec = ExecutionContext.Implicits.global
    val stec = com.gilt.gfc.concurrent.SameThreadExecutionContext

    // Recover from pass-through exceptions here.
    //
    // There are 3 cases:
    // - no errors -> { right result }
    // - exception that we need to pass through -> { left exception }
    // - generic errors -> leave alone, let them bubble up to retry logic and be wrapped with additional user message
    //
    // In other words, return values and exceptions that are 'data' get converted to result of the future, via Either,
    // so, both cases are treated as 'data' and produce 'a value'
    //
    rpcCall.map(\/-(_))(stec) recover {
      case PassThroughExceptionExtractor(e) => -\/(e)
    }
  }
}

object FutureBuilder {

  /** Constructs FutureBuilder.
    *
    * @param rpcCallName name of the RPC call, shows up in the logs and traces
    * @param additionalMessage some interesting identifying data, if any, e.g. user guid
    * @tparam A type the result
    */
  def apply[A]( rpcCallName: String
              , additionalMessage: String = ""
              ): FutureBuilder[A, Throwable \/ A] = {

    new FutureBuilder[A, Throwable \/ A](
      rpcCallName                 = rpcCallName
    , additionalMessage           = additionalMessage
    , rpcErrorHandler             = defaultRPCErrorHandler[A] _
    , passThroughExceptionHandler = PartialFunction.empty
    , addNumRetries               = byNameId[A] _
    , addTraceCalls               = byNameId[A] _
    , addSingleCallTimeout        = byNameId[A] _
    )
  }


  private
  val Logger = new AnyRef with OpenLoggable

  private // there's a subtle difference here from built-in 'identity' function in that this is a 'by-name' call
  def byNameId[A](f: => Future[Throwable \/ A]
                 ): Future[Throwable \/ A] = {
    f
  }


  private
  def defaultRPCErrorHandler[A](f: => Future[Throwable \/ A]
                               ): Future[Throwable \/ A] = {

    implicit val ec = com.gilt.gfc.concurrent.SameThreadExecutionContext

    // 'pass through' exceptions are already captured as a value here,
    // catch the rest and turn everything into a value (except for fatal exceptions)

    f recover { // successful results are 'right'
      case NonFatal(e) => -\/(e) // non-fatal exceptions are 'left', fatal should bubble up
    }
  }


  /** This works similar to .filter.map(), i.e. for exceptions that we want to pass through
   *  (the ones for which a partial function is defined) we apply it, thus getting .map() behavior
   *
   *  Exceptions that are not explicitly handled here are 'generic IO exceptions' eligible for
   *  retry and eligible to be wrapped with additional user message.
   *
   *  OTOH the ones that match we need to preserve 'as is', no retries, should be passed
   *  to caller unmodified.
   */
  case class PartialFunctionToExceptionExtractor (
    passThroughExceptionHandler: PartialFunction[Throwable, Throwable]
  ) {

    def unapply(e: Throwable
               ): Option[Throwable] = {
      passThroughExceptionHandler.lift(e)
    }
  }
}
