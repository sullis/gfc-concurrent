package com.gilt.gfc.concurrent

import java.util.concurrent.{Future, ExecutorService => JExecutorService}

/**
 * Scala adaptations of j.u.c.ExecutorService.
 */
trait ExecutorService extends JExecutorService {
  def execute(f: => Unit): Unit
  def submit[T](f: => T): Future[T]
}

