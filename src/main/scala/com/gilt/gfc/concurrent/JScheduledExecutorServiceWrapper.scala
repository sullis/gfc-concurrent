package com.gilt.gfc.concurrent

import java.util.concurrent.{Delayed, Callable, ScheduledFuture, TimeUnit, ScheduledExecutorService => JScheduledExecutorService}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

/**
 * Wrapper of a java.util.concurrent.ScheduledExecutorService implementing the
 * com.gilt.gfc.concurrent.AsyncScheduledExecutorService trait.
 */
trait JScheduledExecutorServiceWrapper extends JExecutorServiceWrapper with AsyncScheduledExecutorService {
  override def executorService: JScheduledExecutorService

  override def scheduleWithFixedDelay(r: Runnable, initialDelay: Long, delay: Long, timeUnit: TimeUnit): ScheduledFuture[_] = executorService.scheduleWithFixedDelay(r, initialDelay, delay, timeUnit)
  override def scheduleAtFixedRate(r: Runnable, initialDelay: Long, period: Long, timeUnit: TimeUnit): ScheduledFuture[_] = executorService.scheduleAtFixedRate(r, initialDelay, period, timeUnit)
  override def schedule[V](c: Callable[V], delay: Long, timeUnit: TimeUnit): ScheduledFuture[V] = executorService.schedule(c, delay, timeUnit)
  override def schedule(r: Runnable, delay: Long, timeUnit: TimeUnit): ScheduledFuture[_] = executorService.schedule(r, delay, timeUnit)

  override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, timeUnit: TimeUnit)(f: => Unit): ScheduledFuture[_] = {
    executorService.scheduleWithFixedDelay(asRunnable(f), initialDelay, delay, timeUnit)
  }

  override def scheduleAtFixedRate(initialDelay: Long, period: Long, timeUnit: TimeUnit)(f: => Unit): ScheduledFuture[_] = {
    executorService.scheduleAtFixedRate(asRunnable(f), initialDelay, period, timeUnit)
  }

  override def schedule[V](delay: Long, timeUnit: TimeUnit)(f: => V): ScheduledFuture[V] = {
    executorService.schedule(asCallable(f), delay, timeUnit)
  }

  override def asyncSchedule(initialDelay: FiniteDuration, delayUntilNext: FiniteDuration => FiniteDuration)
                            (futureTask: => Future[_])
                            (implicit executor: ExecutionContext): ScheduledFuture[_] = {
    val wrapper: ScheduledFutureWrapper[Unit] = new ScheduledFutureWrapper()
    def doSchedule(delay: FiniteDuration): Unit = {
      val delayMs = {
        val ms = delay.toMillis
        if (ms < 0) 0 else ms
      }
      if (!wrapper.isCancelled) {
        val future: ScheduledFuture[Unit] = schedule(delayMs, TimeUnit.MILLISECONDS) {
          val start = System.currentTimeMillis()
          try {
            futureTask.onComplete { _ =>
              // Task complete: Schedule again
              doSchedule(delayUntilNext(FiniteDuration(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)))
            }
          } catch {
            case e: Throwable =>
              // Exception in futureTask(): Schedule again
              doSchedule(delayUntilNext(FiniteDuration(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)))
              throw e
          }
        }
        // store future in wrapper so that it can be cancelled
        wrapper.set(future)
      }
    }
    doSchedule(initialDelay)
    wrapper
  }

  private class ScheduledFutureWrapper[V] extends ScheduledFuture[V] {
    @volatile private var delegate: ScheduledFuture[V] = _
    @volatile private var cancelled: Boolean = false

    def set(future: ScheduledFuture[V]): Unit = this.synchronized {
      if (!cancelled) {
        delegate = future
      } else {
        future.cancel(true)
      }
    }

    override def getDelay(p1: TimeUnit): Long = delegate.getDelay(p1)

    override def isCancelled: Boolean = cancelled

    override def get(): V = delegate.get

    override def get(p1: Long, p2: TimeUnit): V = delegate.get(p1, p2)

    override def cancel(p1: Boolean): Boolean = this.synchronized {
      cancelled = true
      delegate.cancel(p1)
    }

    override def isDone: Boolean = cancelled && delegate.isDone

    override def compareTo(p1: Delayed): Int = delegate.compareTo(p1)
  }
}
