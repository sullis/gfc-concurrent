package com.gilt.gfc.concurrent

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.concurrent.{Promise, ExecutionContext, Await, Future}
import scala.util.{Failure, Success, Try}

/**
 * Little helpers for scala futures
 *
 * @author Gregor Heine
 * @since 11/Jul/2014 13:25
 */
object ScalaFutures {
  implicit class AwaitableFuture[A](val f: Future[A]) extends AnyVal {
    @inline def await: A = Await.result(f, Duration.Inf)
  }

  implicit class AsFuture[A](val a: A) extends AnyVal {
    @inline def asFuture: Future[A] = Future.successful(a)
  }

  implicit class TimeLimitedFuture[T](val future: Future[T]) extends AnyVal {
    def withTimeout(after: FiniteDuration)(implicit ec: ExecutionContext): Future[T] =
      Future.firstCompletedOf(Seq(future, Timeouts.timeout(after)))
  }

  def exists[T](futures: TraversableOnce[Future[T]])
               (predicate: T => Boolean)
               (implicit executor: ExecutionContext): Future[Boolean] = Future.find(futures)(predicate).map(_.isDefined)

  def forall[T](futures: TraversableOnce[Future[T]])
               (predicate: T => Boolean)
               (implicit executor: ExecutionContext): Future[Boolean] = Future.find(futures)(!predicate(_)).map(_.isEmpty)

  def eq[A](f1: Future[_ >: A], f2: Future[_ >: A])
           (implicit executor: ExecutionContext): Future[Boolean] = for {
    one <- f1
    two <- f2
  } yield(one == two)

  val FutureNone: Future[Option[Nothing]] = Future.successful(None)

  def fromTry[T](t: Try[T]): Future[T] = t match {
    case Success(s) => Future.successful(s)
    case Failure(e) => Future.failed(e)
  }

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

  object Implicits {
    implicit val sameThreadExecutionContext = SameThreadExecutionContext
  }
}
