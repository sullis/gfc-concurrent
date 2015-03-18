package com.gilt.gfc.concurrent

import java.util.concurrent.{TimeUnit, TimeoutException, Executors}

import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.FiniteDuration

/**
 * Factory module to build timing out Futures.
 *
 * @author umatrangolo@gilt.com
 * @since 22-Nov-2014
 */
object Timeouts {
  private[concurrent] val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

  /**
   * Returns a timinig out Future.
   *
   * A failing Future is returned that will throw a TimeoutException after the given expiration time.
   *
   * @param after a FiniteDuration instance with the ttl of this Future.
   */
  def timeout[T](after: FiniteDuration): Future[T] = scheduleTimeout(after)

  // TODO unclear if an HashedWheelTimer would be more efficient
  private def scheduleTimeout[T](after: FiniteDuration): Future[T] = {
    val timingOut = Promise()
    val now = System.currentTimeMillis()

    scheduledExecutor.schedule(new Runnable() {
      override def run() {
        val elapsed = System.currentTimeMillis() - now
        timingOut.tryFailure(new TimeoutException(s"""Timeout after ${after} (real: ${elapsed} ms.)"""))
      }
    }, after.toMillis, TimeUnit.MILLISECONDS)

    timingOut.future
  }
}
