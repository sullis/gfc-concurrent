package com.gilt.gfc.concurrent

import java.util.concurrent.{ScheduledExecutorService => JScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.concurrent.duration.FiniteDuration

/**
 * Scala adaptations of j.u.c.ScheduledExecutorService.
 */
trait ScheduledExecutorService extends JScheduledExecutorService with ExecutorService {
  def scheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration)(f: => Unit): ScheduledFuture[_]

  @deprecated("Use scheduleWithFixedDelay(FiniteDuration, FiniteDuration)")
  def scheduleWithFixedDelay(initialDelay: Long, delay: Long, timeUnit: TimeUnit)(f: => Unit): ScheduledFuture[_]

  def scheduleAtFixedRate(initialDelay: FiniteDuration, period: FiniteDuration)(f: => Unit): ScheduledFuture[_]

  @deprecated("Use scheduleAtFixedRate(FiniteDuration, FiniteDuration)")
  def scheduleAtFixedRate(initialDelay: Long, period: Long, timeUnit: TimeUnit)(f: => Unit): ScheduledFuture[_]

  def schedule[V](delay: FiniteDuration)(f: => V): ScheduledFuture[V]

  @deprecated("Use schedule(FiniteDuration)")
  def schedule[V](delay: Long, timeUnit: TimeUnit)(f: => V): ScheduledFuture[V]
}
