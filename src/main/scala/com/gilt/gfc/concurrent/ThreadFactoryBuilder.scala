package com.gilt.gfc.concurrent

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ThreadFactory}

/**
 * Simple ThreadFactoryBuilder, analogous to guava ThreadFactoryBuilder
 */
object ThreadFactoryBuilder {
  def apply(): ThreadFactoryBuilder = ThreadFactoryBuilder(None, None, None, Executors.defaultThreadFactory, true)
}
case class ThreadFactoryBuilder private (private val nameFormat: Option[String],
                                         private val priority: Option[Int],
                                         private val exceptionHandler: Option[UncaughtExceptionHandler],
                                         private val threadFactory: ThreadFactory,
                                         private val daemon: Boolean) {
  def withNameFormat(nameFormat: String): ThreadFactoryBuilder = copy(nameFormat = Some(nameFormat))

  def withPriority(priority: Int): ThreadFactoryBuilder = copy(priority = Some(priority))

  def withUncaughtExceptionHandler(exceptionHandler: UncaughtExceptionHandler): ThreadFactoryBuilder = copy(exceptionHandler = Some(exceptionHandler))

  def withThreadFactory(threadFactory: ThreadFactory): ThreadFactoryBuilder = copy(threadFactory = threadFactory)

  def withDaemonFlag(isDaemon: Boolean): ThreadFactoryBuilder = copy(daemon = isDaemon)

  def build: ThreadFactory = {
    val nameF: Option[() => String] = nameFormat.map { nf =>
      val count = new AtomicLong(0)
      () => nf.format(count.getAndDecrement)
    }

    new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = {
        val thread = threadFactory.newThread(runnable)
        nameF.foreach(f => thread.setName(f()))
        priority.foreach(thread.setPriority)
        exceptionHandler.foreach(thread.setUncaughtExceptionHandler)
        thread.setDaemon(daemon)
        thread
      }
    }
  }
}
