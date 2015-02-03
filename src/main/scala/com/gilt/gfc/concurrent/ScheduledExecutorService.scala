package com.gilt.gfc.concurrent

import java.util.concurrent.{ScheduledExecutorService => JScheduledExecutorService, ScheduledFuture, TimeUnit}

/**
 * Scala adaptations of j.u.c.ScheduledExecutorService.
 */
trait ScheduledExecutorService extends JScheduledExecutorService with ExecutorService {
  def scheduleWithFixedDelay(initialDelay: Long, delay: Long, timeUnit: TimeUnit)(f: => Unit): ScheduledFuture[_]
  def scheduleAtFixedRate(initialDelay: Long, period: Long, timeUnit: TimeUnit)(f: => Unit): ScheduledFuture[_]
  def schedule[V](delay: Long, timeUnit: TimeUnit)(f: => V): ScheduledFuture[V]
}
