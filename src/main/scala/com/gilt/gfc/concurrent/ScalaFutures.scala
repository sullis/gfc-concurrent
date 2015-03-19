package com.gilt.gfc.concurrent

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Little helpers for scala futures
 *
 * @author Gregor Heine
 * @since 11/Jul/2014 13:25
 */
object ScalaFutures {
  implicit class FutureOps[A](val f: Future[A]) extends AnyVal {
    /**
     * Create a new Future that times out with a [[java.util.concurrent.TimeoutException]] after the given FiniteDuration
     */
    def withTimeout(after: FiniteDuration)(implicit ec: ExecutionContext): Future[A] =
      Future.firstCompletedOf(Seq(f, Timeouts.timeout(after)))
  }

  implicit class AsFuture[A](val a: A) extends AnyVal {
    @inline def asFuture: Future[A] = Future.successful(a)
  }

  /**
   * Asynchronously tests whether a predicate holds for some of the elements of a collection of futures
   */
  def exists[T](futures: TraversableOnce[Future[T]])
               (predicate: T => Boolean)
               (implicit executor: ExecutionContext): Future[Boolean] = {
    if (futures.isEmpty) Future.successful(false)
    else Future.find(futures)(predicate).map(_.isDefined)
  }

  /**
   * Asynchronously tests whether a predicate holds for all elements of a collection of futures
   */
  def forall[T](futures: TraversableOnce[Future[T]])
               (predicate: T => Boolean)
               (implicit executor: ExecutionContext): Future[Boolean] = {
    if (futures.isEmpty) Future.successful(true)
    else Future.find(futures)(!predicate(_)).map(_.isEmpty)
  }

  /**
   * Future of an empty Option
   */
  val FutureNone: Future[Option[Nothing]] = Future.successful(None)

  /**
   * Convert a Try into a Future
   */
  def fromTry[T](t: Try[T]): Future[T] = t match {
    case Success(s) => Future.successful(s)
    case Failure(e) => Future.failed(e)
  }

  /**
   * Improved version of [[scala.concurrent.Future.fold]], that fails the resulting Future as soon as one of the input Futures fails.
   */
  def foldFast[T, R >: T](futures: TraversableOnce[Future[T]])(zero: R)(foldFun: (R, T) => R)(implicit executor: ExecutionContext): Future[R] = {
    if (futures.isEmpty) Future.successful(zero)
    else {
      val atomic = new AtomicReference[R](zero)
      val promise = Promise[R]()
      val counter = new AtomicInteger()

      @tailrec def update(f: R => R): R = {
        val oldValue = atomic.get()
        val newValue = f(oldValue)
        if (atomic.compareAndSet(oldValue, newValue)) newValue else update(f)
      }

      futures.foreach { _.onComplete {
        // succeed slow: only succeed when all futures have succeeded
        case Success(v) =>
          update(foldFun(_, v))
          if (counter.incrementAndGet() == futures.size) {
            promise.trySuccess(atomic.get)
          }
        // fail fast: fail as soon as the first future has failed
        case Failure(t) =>
          promise.tryFailure(t)
      }}

      promise.future
    }
  }

  def withRetry[T](maxRetryTimes: Long = Long.MaxValue)
                  (f: => Future[T])
                  (implicit ec: ExecutionContext): Future[T] = {
    if(maxRetryTimes <= 0) {
      f
    } else {
      f.recoverWith {
        case NonFatal(e) => withRetry(maxRetryTimes - 1)(f)
      }
    }
  }

  def withExponentialRetry[T](maxRetryTimes: Long = Long.MaxValue,
                              initialDelay: Duration = 1 nanosecond,
                              maxDelay: FiniteDuration = 1 day,
                              exponentFactor: Double = 2)
                             (f: => Future[T])
                             (implicit ec: ExecutionContext): Future[T] = {
    require(exponentFactor >= 1)
    if (maxRetryTimes <= 0) {
      f
    } else {
      f.recoverWith {
        case NonFatal(e) =>
          val p = Promise[T]

          val delay = if (initialDelay > maxDelay) { maxDelay } else { initialDelay }
          Timeouts.scheduledExecutor.schedule(new Runnable() {
            override def run() {
              p.completeWith(withExponentialRetry(maxRetryTimes - 1, delay * exponentFactor, maxDelay, exponentFactor)(f))
            }
          }, delay.toNanos, TimeUnit.NANOSECONDS)

          p.future
      }
    }
  }

  object Implicits {
    implicit val sameThreadExecutionContext = SameThreadExecutionContext
  }
}
