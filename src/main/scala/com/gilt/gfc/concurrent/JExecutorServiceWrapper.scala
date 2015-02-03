package com.gilt.gfc.concurrent

import java.util.{List => JList, Collection => JCollection}
import java.util.concurrent.{ExecutorService => JExecutorService, TimeUnit, Callable, Future}

/**
 * Wrapper of a java.util.concurrent.ExecutorService implementing the
 * com.gilt.gfc.concurrent.ExecutorService trait.
 */
trait JExecutorServiceWrapper extends ExecutorService {
  def executorService: JExecutorService

  protected def asCallable[T](f: => T): Callable[T] = new Callable[T] {
    override def call() = f
  }
  protected def asRunnable[T](f: => T): Runnable = new Runnable {
    override def run(): Unit = f
  }

  override def execute(f: => Unit): Unit = executorService.execute(asRunnable(f))
  override def submit[T](f: => T): Future[T] = executorService.submit(asCallable(f))

  override def invokeAny[T](tasks: JCollection[_ <: Callable[T]], timeout: Long, timeUnit: TimeUnit): T = executorService.invokeAny(tasks, timeout, timeUnit)
  override def invokeAny[T](tasks: JCollection[_ <: Callable[T]]): T = executorService.invokeAny(tasks)

  override def invokeAll[T](tasks: JCollection[_ <: Callable[T]], timeout: Long, timeUnit: TimeUnit): JList[Future[T]] = executorService.invokeAll(tasks, timeout, timeUnit)
  override def invokeAll[T](tasks: JCollection[_ <: Callable[T]]): JList[Future[T]] = executorService.invokeAll(tasks)

  override def submit(r: Runnable): Future[_] = executorService.submit(r)
  override def submit[T](r: Runnable, default: T): Future[T] = executorService.submit(r, default)
  override def submit[T](c: Callable[T]): Future[T] = executorService.submit(c)

  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = executorService.awaitTermination(timeout, unit)
  override def isTerminated(): Boolean = executorService.isTerminated
  override def isShutdown(): Boolean = executorService.isShutdown
  override def shutdownNow(): JList[Runnable] = executorService.shutdownNow
  override def shutdown(){ executorService.shutdown }
  override def execute(r: Runnable) { executorService.execute(r) }
}
