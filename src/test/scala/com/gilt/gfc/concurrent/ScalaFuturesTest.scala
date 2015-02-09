package com.gilt.gfc.concurrent

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try
import org.scalatest.FunSuite
import org.scalatest.Matchers

class ScalaFuturesTest extends FunSuite with Matchers {

  import ScalaFutures._

  private def await[T](f: Future[T]): T = Await.result(f, Duration.Inf)

  test("implicit asFuture") {
    val future: Future[Int] = 1.asFuture
    await(future) should be(1)
  }

  test("exists") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures: Seq[Future[Int]] = Seq(1.asFuture, 2.asFuture, 3.asFuture)

    val trueFuture: Future[Boolean] = ScalaFutures.exists(futures)(_ == 3)
    await(trueFuture) should be(true)

    val falseFuture: Future[Boolean] = ScalaFutures.exists(futures)(_ == 4)
    await(falseFuture) should be(false)
  }

  test("forall") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures: Seq[Future[Int]] = Seq(1.asFuture, 2.asFuture, 3.asFuture)

    val trueFuture: Future[Boolean] = ScalaFutures.forall(futures)(_ < 4)
    await(trueFuture) should be(true)

    val falseFuture: Future[Boolean] = ScalaFutures.forall(futures)(_ < 3)
    await(falseFuture) should be(false)
  }

  test("FutureNone") {
    await(FutureNone) should be(None)
  }

  test("fromTry") {
    val success: Try[Int] = Try { 1 }
    val successFuture: Future[Int] = ScalaFutures.fromTry(success)
    await(successFuture) should be(1)

    val failure: Try[Int] = Try { throw new RuntimeException("boom") }
    val failureFuture: Future[Int] = ScalaFutures.fromTry(failure)
    val thrown = the [RuntimeException] thrownBy { await(failureFuture) }
    thrown.getMessage should be("boom")
  }

  test("foldFast succeeds simple") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures: Seq[Future[Int]] = Seq(1.asFuture, 2.asFuture, 3.asFuture)
    val result: Future[Int] = ScalaFutures.foldFast(futures)(0)(_ + _)
    await(result) should be(6)
  }

  test("foldFast fails simple") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures: Seq[Future[Int]] = Seq(1.asFuture, 2.asFuture, Future.failed(new RuntimeException("boom")))
    val result: Future[Int] = ScalaFutures.foldFast(futures)(0)(_ + _)
    val thrown = the [RuntimeException] thrownBy { await(result) }
    thrown.getMessage should be("boom")
  }

  def newFuture[T](result: => T, timeout: Long): Future[T] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    Future {
      Thread.sleep(timeout)
      result
    }
  }

  test("foldFast succeeds slow") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val now = System.currentTimeMillis()
    val futures: Seq[Future[Int]] = Seq(newFuture(1, 400), newFuture(2, 400), newFuture(3, 400))
    val result: Future[Int] = ScalaFutures.foldFast(futures)(0)(_ + _)
    System.currentTimeMillis() should be <(now + 200)
    await(result) should be(6)
    System.currentTimeMillis() should be >=(now + 400)
    System.currentTimeMillis() should be <(now + 600)
  }

  test("foldFast fails fast") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val now = System.currentTimeMillis()
    val futures: Seq[Future[Int]] = Seq(newFuture(1, 400), newFuture(2, 1200), newFuture(throw new RuntimeException("boom"), 400))
    val result: Future[Int] = ScalaFutures.foldFast(futures)(0)(_ + _)
    System.currentTimeMillis() should be <(now + 200)
    val thrown = the [RuntimeException] thrownBy { await(result) }
    thrown.getMessage should be("boom")
    System.currentTimeMillis() should be >=(now + 400)
    System.currentTimeMillis() should be <(now + 600)
  }

  test("Same thread execution context") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val toggle = new AtomicBoolean(false)

    val f = Future {
      Thread.sleep(500)
      toggle.set(true)
    }

    f.isCompleted should be(true)
    toggle.get() should be(true)
  }
}
