package com.gilt.gfc.concurrent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ScheduledExecutorService => JScheduledExecutorService, CyclicBarrier, CountDownLatch, Executors, Callable, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import com.gilt.gfc.time.Timer
import org.mockito.ArgumentCaptor
import org.scalatest.FunSuite
import org.scalatest.{Matchers => ScalaTestMatchers}
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers

class ScheduledExecutorServiceTest extends FunSuite with ScalaTestMatchers with MockitoSugar {
  val javaService = Executors.newScheduledThreadPool(10)

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

    service.asyncScheduleWithFixedDelay(0 millis, 50 millis)(newFuture)

    latch.await(500, TimeUnit.MILLISECONDS) should be(true)
  }

  test("asyncScheduleWithFixedDelay sticks to delay") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(50)
      1
    }

    service.asyncScheduleWithFixedDelay(100 millis, 100 millis)(newFuture)

    checkTimeRange(80, 130)(barrier.await(500, TimeUnit.MILLISECONDS))
    checkTimeRange(130, 180)(barrier.await(500, TimeUnit.MILLISECONDS))
  }

  test("asyncScheduleAtFixedRate sticks to rate") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(50)
      1
    }

    service.asyncScheduleAtFixedRate(100 millis, 100 millis)(newFuture)

    checkTimeRange(80, 130)(barrier.await(500, TimeUnit.MILLISECONDS))
    checkTimeRange(80, 130)(barrier.await(500, TimeUnit.MILLISECONDS))
  }

  test("asyncScheduleAtFixedRate reschedules immediately if task overruns rate") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(150)
      1
    }

    service.asyncScheduleAtFixedRate(0 millis, 100 millis)(newFuture)

    checkTimeRange(0, 30)(barrier.await(500, TimeUnit.MILLISECONDS))
    checkTimeRange(130, 180)(barrier.await(500, TimeUnit.MILLISECONDS))
    checkTimeRange(130, 180)(barrier.await(500, TimeUnit.MILLISECONDS))
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

    val future = service.asyncScheduleAtFixedRate(0 millis, 100 millis)(newFuture)

    checkTimeRange(0, 30)(barrier.await(500, TimeUnit.MILLISECONDS))
    checkTimeRange(80, 130)(barrier.await(500, TimeUnit.MILLISECONDS))
    Thread.sleep(50)
    future.cancel(false)

    Thread.sleep(150)
    barrier.getNumberWaiting should be(0)
  }

  test("cancel does not reschedule") {
    implicit val executor = ExecutionContext.fromExecutorService(javaService)

    val service = new JScheduledExecutorServiceWrapper {
      override val executorService: JScheduledExecutorService = javaService
    }

    val barrier = new CyclicBarrier(2)
    def newFuture(): Future[Int] = Future {
      barrier.await()
      Thread.sleep(100)
      1
    }

    val future = service.asyncScheduleAtFixedRate(0 millis, 100 millis)(newFuture)

    checkTimeRange(0, 30)(barrier.await(500, TimeUnit.MILLISECONDS))
    checkTimeRange(80, 130)(barrier.await(500, TimeUnit.MILLISECONDS))
    Thread.sleep(50)
    future.cancel(false)

    Thread.sleep(150)
    barrier.getNumberWaiting should be(0)
  }

  def checkTimeRange[T](minMs: Long, maxMs: Long)(f: => T): T = {
    Timer.time { nanos =>
      (nanos / 1000000) should ((be >= (minMs)) and (be <=(maxMs)))
    }(f)
  }
}
