package com.gilt.gfc.concurrent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ScheduledExecutorService => JScheduledExecutorService, CyclicBarrier, CountDownLatch, Executors, Callable, TimeUnit}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalactic.source.Position
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import com.gilt.gfc.time.Timer
import org.mockito.ArgumentCaptor
import org.scalatest.FunSuite
import org.scalatest.{Matchers => ScalaTestMatchers}
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers

class ScheduledExecutorServiceTest extends FunSuite with ScalaTestMatchers with MockitoSugar with Eventually {
  val javaService = Executors.newScheduledThreadPool(20)

  val TimeStepMs = 500
  val FuzzFactor = 200

  test("asyncScheduleWithFixedDelay with mocks") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val mockJavaService = mock[JScheduledExecutorService]
    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = mockJavaService
    }

    val callCounter = new AtomicInteger

    def newFuture(): Future[Int] = {
      callCounter.incrementAndGet
      Future.successful(1)
    }

    service.asyncScheduleWithFixedDelay(1 second, 2 seconds)(newFuture)
    val callable = ArgumentCaptor.forClass(classOf[Callable[Unit]])

    verify(mockJavaService).schedule(callable.capture, Matchers.eq(1000L), Matchers.eq(TimeUnit.MILLISECONDS))
    verifyNoMoreInteractions(mockJavaService)
    reset(mockJavaService)
    callCounter.get should be(0)

    callable.getValue.call

    verify(mockJavaService).schedule(callable.capture, Matchers.eq(2000L), Matchers.eq(TimeUnit.MILLISECONDS))
    verifyNoMoreInteractions(mockJavaService)
    reset(mockJavaService)
    callCounter.get should be(1)

    callable.getValue.call

    verify(mockJavaService).schedule(callable.capture, Matchers.eq(2000L), Matchers.eq(TimeUnit.MILLISECONDS))
    verifyNoMoreInteractions(mockJavaService)
    reset(mockJavaService)
    callCounter.get should be(2)
  }

  test("asyncScheduleAtFixedRate with mocks") {
    import ScalaFutures.Implicits.sameThreadExecutionContext

    val mockJavaService = mock[JScheduledExecutorService]
    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = mockJavaService
    }

    val callCounter = new AtomicInteger

    def newFuture(): Future[Int] = {
      callCounter.incrementAndGet
      Future.successful(1)
    }

    service.asyncScheduleAtFixedRate(1 second, 2 seconds)(newFuture)
    val callable = ArgumentCaptor.forClass(classOf[Callable[Unit]])

    verify(mockJavaService).schedule(callable.capture, Matchers.eq(1000L), Matchers.eq(TimeUnit.MILLISECONDS))
    verifyNoMoreInteractions(mockJavaService)
    reset(mockJavaService)
    callCounter.get should be(0)

    callable.getValue.call

    val rate = ArgumentCaptor.forClass(classOf[Long])
    verify(mockJavaService).schedule(callable.capture, rate.capture, Matchers.eq(TimeUnit.MILLISECONDS))
    verifyNoMoreInteractions(mockJavaService)
    reset(mockJavaService)
    rate.getValue should be <= (2000L)
    rate.getValue should be > (1750L)
    callCounter.get should be(1)

    callable.getValue.call

    verify(mockJavaService).schedule(callable.capture, rate.capture, Matchers.eq(TimeUnit.MILLISECONDS))
    verifyNoMoreInteractions(mockJavaService)
    reset(mockJavaService)
    rate.getValue should be <= (2000L)
    rate.getValue should be > (1750L)
    callCounter.get should be(2)
  }

  test("blows on schedule") {
    val toThrow = new RuntimeException("boom")
    val mockJavaService = mock[JScheduledExecutorService]
    when(mockJavaService.schedule(Matchers.any[Callable[_]], Matchers.anyLong, Matchers.any)).thenThrow(toThrow)
    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = mockJavaService
    }

    def newFuture(): Future[Int] = fail("should not have called newFuture")

    val caught = the [RuntimeException] thrownBy {
      service.asyncScheduleWithFixedDelay(1 second, 2 seconds)(newFuture)
    }

    caught should be(toThrow)
  }

  test("exception thrown in futureTask") {
    val toThrow = new RuntimeException("boom")
    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val latch = new CountDownLatch(3)
    def newFuture(): Future[Int] = {
      latch.countDown
      throw toThrow
    }

    service.asyncScheduleWithFixedDelay(0 millis, TimeStepMs millis)(newFuture)

    latch.await(10 * TimeStepMs, TimeUnit.MILLISECONDS) should be(true)
  }

  test("asyncScheduleWithFixedDelay sticks to delay") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(TimeStepMs)
      1
    }

    service.asyncScheduleWithFixedDelay(TimeStepMs millis, TimeStepMs millis)(newFuture)

    checkFuzzyTiming(TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    checkFuzzyTiming(2 * TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
  }

  test("asyncScheduleAtFixedRate sticks to rate") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(TimeStepMs / 2)
      1
    }

    service.asyncScheduleAtFixedRate(TimeStepMs millis, TimeStepMs millis)(newFuture)

    checkFuzzyTiming(TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    checkFuzzyTiming(TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
  }

  test("asyncScheduleAtFixedRate reschedules immediately if task overruns rate") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(2 * TimeStepMs)
      1
    }

    service.asyncScheduleAtFixedRate(0 millis, TimeStepMs millis)(newFuture)

    checkFuzzyTiming(0)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    checkFuzzyTiming(2 * TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    checkFuzzyTiming(2 * TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
  }

  test("cancel cancels scheduled task") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = {
      barrier.await()
      Future.successful(1)
    }

    val future = service.asyncScheduleAtFixedRate(0 millis, TimeStepMs millis)(newFuture)

    checkFuzzyTiming(0)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    checkFuzzyTiming(TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    future.cancel(false)

    Thread.sleep(2 * TimeStepMs)
    barrier.getNumberWaiting should be(0)
  }

  test("single-thread scheduled executor #submit Scala function sanity check") {
    import com.gilt.gfc.concurrent.JavaConverters._
    val n = new AtomicInteger(0)
    val javaExecutor = Executors.newSingleThreadScheduledExecutor
    val scalaExecutor = javaExecutor.asScala
    scalaExecutor.submit {
      n.incrementAndGet
    }
    val patienceConfig = PatienceConfig(timeout = scaled(Span(3, Seconds)), interval = scaled(Span(3, Seconds)))
    eventually({ n.intValue should be > 0 })(patienceConfig, Position.here)
  }

  test("single-thread scheduled executor #execute(javaRunnable) sanity check") {
    import com.gilt.gfc.concurrent.JavaConverters._
    val n = new AtomicInteger(0)
    val javaExecutor = Executors.newSingleThreadScheduledExecutor
    val scalaExecutor = javaExecutor.asScala
    val runnable = new Runnable() {
      override def run(): Unit = n.incrementAndGet
    }
    scalaExecutor.execute(runnable)
    val patienceConfig = PatienceConfig(timeout = scaled(Span(3, Seconds)), interval = scaled(Span(3, Seconds)))
    eventually({ n.intValue should be > 0 })(patienceConfig, Position.here)
  }

  test("cancel does not reschedule") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(TimeStepMs)
      1
    }

    val future = service.asyncScheduleAtFixedRate(0 millis, TimeStepMs millis)(newFuture)

    checkFuzzyTiming(0)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    checkFuzzyTiming(TimeStepMs)(barrier.await(5 * TimeStepMs, TimeUnit.MILLISECONDS))
    future.cancel(false)

    Thread.sleep(2 * TimeStepMs)
    barrier.getNumberWaiting should be(0)
  }

  def checkFuzzyTiming[T](exactMs: Long, fuzziness: Long = FuzzFactor)(f: => T): T = {
    val minMs = Seq(0, exactMs - fuzziness).max
    val maxMs = exactMs + fuzziness
    Timer.time { nanos =>
      (nanos / 1000000) should ((be >= (minMs)) and (be <=(maxMs)))
    }(f)
  }
}
