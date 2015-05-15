package com.gilt.gfc.concurrent

import com.gilt.gfc.concurrent.FutureBuilder.PartialFunctionToExceptionExtractor
import com.gilt.gfc.concurrent.trace.{FutureGenericErrorTrace, FutureRecoverableErrorTrace, FutureTrace}
import com.gilt.gfc.logging.OpenLoggable

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * A convenience wrapper around Future-based calls.
 *
 * Typically we have an API that returns some Future[OpResult] and we need to set an operation timeout,
 * possibly provide a default value, possibly log how long it took for debugging, maybe retry.
 *
 * By default it builds a Future[Try[A]] from Future[A].
 * When default value is provided it builds Future[A] from Future[A].
 * At the very minimum it'll apply a mandatory timeout but you can add retry logic, call tracer, etc.
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
  callName: String // this'll show up in wrapped exceptions and logs

, additionalMessage: String // this'll show up in wrapped exceptions and logs

// N.B. below 'by name' parameters are to support 'retry' functionality

// How we process errors changes depending on whether or not there's a default
// value. When there is one we want to stay with A as a Result type,
// i.e. Future[A] => Future[A]
// OTOH, when we don't have any defaults - we want to bubble that decision
// up to a typesystem level. Scala doesn't have checked exceptions but
// we can express a mandatory check with a Try[A] type.
, errorHandler: (=> Future[Try[A]]) => Future[R]

// As it happens some of the exceptions are actually data,
// APIs that generate them expect users to implement some control flow around them.
// In those cases we need to pass them 'un-wrapped'.
// Implementing this as a partial function also gives an opportunity to 'map' them
// to some other type of exception if needed.
, passThroughExceptionHandler: PartialFunction[Throwable, Throwable]

// Allows us to retry calls when we bump into errors, but see passThroughExceptionHandler.
, addNumRetries: (=> Future[Try[A]]) => Future[Try[A]]

// Allows us to log service call times, to debug site problems.
// All of this async stuff is notorious for not having comprehensible stack traces.
, addTraceCalls: (=> Future[Try[A]]) => Future[Try[A]]

// Puts a timeout on call Future.
, addSingleCallTimeout: (=> Future[Try[A]]) => Future[Try[A]]

) {

  /** Composes all the Future transformations, gives resulting Future back.
    * This is the only 'run' function here, so, effectively we insist on timeouts for all futures.
    *
    * By default NonFatal failures are caught and represented as a Try[A].
    * OTOH if a serviceErrorDefaultValue is provided than we log errors and default to that, result type remains A.
    *
    * @param after mandatory timeout we set on 'service call' Futures
    * @param call 'by name' parameter that evaluates to a Future[SomeServiceCallResult],
    *             this may be called multiple times if retry() is enabled.
    *
    * @return Future[SomeServiceCallResult] if a default value is provided or
    *         Future[Try[SomeServiceCallResult]] in case there's no default,
    *         a 'checked exception' of sorts.
    */
  def runWithTimeout( after: FiniteDuration
                   )( call: => Future[A]
                    ): Future[R] = {
    import ScalaFutures._

    this.copy[A,R](addSingleCallTimeout = { f =>
      implicit val executor: ExecutionContext = ExecutionContext.Implicits.global
      f.withTimeout(after)
    }).run(call)
  }

  /** Will return this value if a call fails or we fail to interpret results. */
  def withServiceErrorDefaultValue( v: A )
                                  : FutureBuilder[A,A] = {

    implicit val executor: ExecutionContext = ExecutionContext.Implicits.global

    copy(errorHandler = { f =>
      f recover {

        // Un-expected exceptions
        case NonFatal(e) =>
          blocking{ FutureBuilder.Logger.error(s"${callName}(${additionalMessage}): ${e.getMessage}", e) }
          Success(v) // we have a default value to fill in in case of generic server errors

      } map {

        case Failure(e) => throw e // 'pass through' exception, need to re-throw
        case Success(v) => v // normal service call result
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
    */
  def withPassThroughExceptions( handlePassThroughExceptions: PartialFunction[Throwable, Throwable]
                               ): FutureBuilder[A, R] = {
    copy(passThroughExceptionHandler = handlePassThroughExceptions)
  }


  /** Enables call tracing via provided callback function.
    * E.g. you can log call times or send metrics somewhere.
    *
    * @param callTracer will be called with the results of a call.
    */
  def withTraceCalls( callTracer: (FutureTrace) => Unit
                    ): FutureBuilder[A,R] = {

    copy(addTraceCalls = { f =>
      implicit val executor: ExecutionContext = ExecutionContext.Implicits.global

      val beginTime = System.currentTimeMillis

      val fRef = f // grab result of a 'by name' function call, onComplete is Unit type, this avoids firing call twice

      fRef onComplete { res: Try[Try[A]] =>
        val endTime = System.currentTimeMillis
        val dt = endTime - beginTime

        val trace = res match {
          case Success(Success(_)) => FutureTrace(callName, additionalMessage, dt, None)
          case Success(Failure(e)) => FutureTrace(callName, additionalMessage, dt, Some(FutureRecoverableErrorTrace(e)))
          case Failure(e)      => FutureTrace(callName, additionalMessage, dt, Some(FutureGenericErrorTrace(e)))
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
  def run( call: => Future[A]
         ): Future[R] = {

    // order matters, e.g. we want to apply single call timeout before any retries
    errorHandler(
      addNumRetries(
        addTraceCalls(
          addSingleCallTimeout(
            addErrorMessage(
              callService(call
    ))))))
  }



  /** Adds more context to thrown exceptions. */
  private[this]
  def addErrorMessage(f: => Future[Try[A]]
                     ): Future[Try[A]] = {
    implicit val ec = SameThreadExecutionContext

    f transform (
      s = identity,
      f = (t: Throwable) => new Exception(s"${callName}(${additionalMessage}): ${t.getMessage}", t)
    )
  }


  private[this]
  def callService( call: => Future[A]
                 ): Future[Try[A]] = {

    val PassThroughExceptionExtractor = PartialFunctionToExceptionExtractor(passThroughExceptionHandler)

    implicit val gec = ExecutionContext.Implicits.global
    val stec = com.gilt.gfc.concurrent.SameThreadExecutionContext

    // Recover from pass-through exceptions here.
    //
    // There are 3 cases:
    // - no errors -> Success(result)
    // - exception that we need to pass through -> Failure(exception)
    // - generic errors -> leave alone, let them bubble up to retry logic and be wrapped with additional user message
    //
    // In other words, return values and exceptions that are 'data' get converted to result of the future, via Try,
    // so, both cases are treated as 'data' and produce 'a value'
    //
    call.map(Success(_))(stec) recover {
      case PassThroughExceptionExtractor(e) => Failure(e)
    }
  }
}

object FutureBuilder {

  /** Constructs FutureBuilder.
    *
    * @param callName name of the future-based call, shows up in the logs and traces
    * @param additionalMessage some interesting identifying data, if any, e.g. user guid
    * @tparam A type the result
    */
  def apply[A]( callName: String
              , additionalMessage: String = ""
              ): FutureBuilder[A, Try[A]] = {

    new FutureBuilder[A, Try[A]](
      callName                    = callName
    , additionalMessage           = additionalMessage
    , errorHandler                = defaultErrorHandler[A] _
    , passThroughExceptionHandler = PartialFunction.empty
    , addNumRetries               = byNameId[A] _
    , addTraceCalls               = byNameId[A] _
    , addSingleCallTimeout        = byNameId[A] _
    )
  }


  private
  val Logger = new AnyRef with OpenLoggable

  private // there's a subtle difference here from built-in 'identity' function in that this is a 'by-name' call
  def byNameId[A](f: => Future[Try[A]]
                 ): Future[Try[A]] = {
    f
  }


  private
  def defaultErrorHandler[A](f: => Future[Try[A]]
                            ): Future[Try[A]] = {

    implicit val ec = com.gilt.gfc.concurrent.SameThreadExecutionContext

    // 'pass through' exceptions are already captured as a value here,
    // catch the rest and turn everything into a value (except for fatal exceptions)

    f recover {
      case NonFatal(e) => Failure(e)
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
