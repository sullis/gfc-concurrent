package com.gilt.gfc.concurrent

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import scala.collection.immutable.VectorBuilder
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import org.scalatest.FunSuite
import org.scalatest.Matchers

class ScalaFuturesTest extends FunSuite with Matchers {
  implicit def logSuppressor(t: Throwable): Unit = {}

  import ScalaFutures._

  private def await[T](f: Future[T]): T = Await.result(f, Duration.Inf)

  test("implicit asFuture") {
    val future: Future[Int] = 1.asFuture
    await(future) should be(1)
  }

  test("implicit FutureTry flatten") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val f1: Future[Try[String]] = Future.successful(Success("ok"))
    val f2: Future[Try[String]] = Future.successful(Failure(new RuntimeException("boom")))
    val f3: Future[Try[String]] = Future.failed(new RuntimeException("bang"))

    await(f1.flatten) shouldBe "ok"

    (the [RuntimeException] thrownBy {
      await(f2.flatten)
    }).getMessage shouldBe "boom"

    (the [RuntimeException] thrownBy {
      await(f3.flatten)
    }).getMessage shouldBe "bang"
  }

  test("implicit FutureFuture flatten") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val t1: Future[Future[String]] = Future.successful(Future.successful("ok"))
    val t2: Future[Future[String]] = Future.successful(Future.failed(new RuntimeException("boom")))
    val t3: Future[Future[String]] = Future.failed(new RuntimeException("bang"))

    await(t1.flatten) shouldBe "ok"

    (the [RuntimeException] thrownBy {
      await(t2.flatten)
    }).getMessage shouldBe "boom"

    (the [RuntimeException] thrownBy {
      await(t3.flatten)
    }).getMessage shouldBe "bang"
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

  test("retry should retry future until it succeeds") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val futures = Iterator(Future.failed(new Exception("boom")), Future.failed(new Exception("crash")), Future.successful("foo"), Future.successful("bar"))

    await(ScalaFutures.retry()(futures.next)) shouldBe "foo"
  }

  test("retry should retry until maxRetries") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val futures = Iterator(Future.failed(new Exception("boom")), Future.failed(new Exception("crash")), Future.successful("foo"), Future.successful("bar"))

    val thrown = the [Exception] thrownBy { await(ScalaFutures.retry(1)(futures.next)) }
    thrown.getMessage shouldBe "crash"
  }

  test("retry retries if future function throws") {
    val counter = new AtomicInteger(0)
    def nextFuture: Future[String] = {
      counter.getAndIncrement match {
        case 0 => Future.failed(new Exception("boom"))
        case 1 => throw new Exception("bam")
        case _ => Future.successful("ok")
      }
    }

    import ScalaFutures.Implicits.sameThreadExecutionContext
    await(ScalaFutures.retry()(nextFuture)) shouldBe "ok"
  }

  test("retryWithExponentialDelay should retry future until it succeeds") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val futures = Iterator(Future.failed(new Exception("boom")), Future.failed(new Exception("crash")), Future.successful("foo"), Future.successful("bar"))

    await(ScalaFutures.retryWithExponentialDelay()(futures.next)) shouldBe "foo"
  }

  test("retryWithExponentialDelay should retry until maxRetryTimes") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val futures = Iterator(Future.failed(new Exception("boom")), Future.failed(new Exception("crash")), Future.successful("foo"), Future.successful("bar"))

    val thrown = the [Exception] thrownBy { await(ScalaFutures.retryWithExponentialDelay(maxRetryTimes = 1)(futures.next)) }
    thrown.getMessage shouldBe "crash"
  }

  test("retryWithExponentialDelay should retry until maxRetryTimeout") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    var count = 0
    def function = {
      count += 1
      Future.failed(new Exception("boom"))
    }

    val start = System.currentTimeMillis()
    val thrown = the [Exception] thrownBy { await(ScalaFutures.retryWithExponentialDelay(maxRetryTimeout = 100 millis fromNow)(function)) }
    thrown.getMessage shouldBe "boom"
    (System.currentTimeMillis() - start) should be (120L +- 20L)
    count shouldBe (8 +- 1)
  }

  test("retryWithExponentialDelay should apply exponential backoff") {
    val times = new VectorBuilder[Long]

    val counter = new AtomicInteger(0)
    def nextFuture: Future[String] = {
      val c = counter.incrementAndGet()
      times += System.currentTimeMillis()
      if (c >= 7) {
        Future.successful("ok")
      } else {
        Future.failed(new Exception(s"error $c"))
      }
    }

    import ScalaFutures.Implicits.sameThreadExecutionContext
    import scala.concurrent.duration._

    times += System.currentTimeMillis()

    // Delay series should be (ms): 100, 150, 225, 337, 500, 500
    val future = ScalaFutures.retryWithExponentialDelay(initialDelay = 100 millis,
                                                        maxDelay = 500 millis,
                                                        exponentFactor = 1.5,
                                                        jitter = false)(nextFuture)

    await(future) shouldBe "ok"

    val timeDeltas = times.result().sliding(2).map(v => v(1) - v(0)).toList
    timeDeltas.length shouldBe 7
    timeDeltas(0) shouldBe 20L  +- 20L
    timeDeltas(1) shouldBe 120L +- 20L
    timeDeltas(2) shouldBe 170L +- 20L
    timeDeltas(3) shouldBe 245L +- 20L
    timeDeltas(4) shouldBe 357L +- 20L
    timeDeltas(5) shouldBe 520L +- 20L
    timeDeltas(6) shouldBe 520L +- 20L
  }

  test("retryWithExponentialDelay retries if future function throws") {
    val counter = new AtomicInteger(0)
    def nextFuture: Future[String] = {
      counter.getAndIncrement match {
        case 0 => Future.failed(new Exception("boom"))
        case 1 => throw new Exception("bam")
        case _ => Future.successful("ok")
      }
    }

    import ScalaFutures.Implicits.sameThreadExecutionContext
    await(ScalaFutures.retryWithExponentialDelay()(nextFuture)) shouldBe "ok"
  }
}
