package com.gilt.gfc.concurrent

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ThreadFactory

/**
 * Simple ThreadFactoryBuilder, analogous to guava ThreadFactoryBuilder
 */
object ThreadFactoryBuilder {
  def apply(): ThreadFactoryBuilder = ThreadFactoryBuilder(None, None, None, None, true)

  def apply(groupName: String, threadName: String): ThreadFactoryBuilder = {
    val group = ThreadGroupBuilder().withName(groupName).build()
    ThreadFactoryBuilder().withNameFormat(threadName + "-%s").withThreadGroup(group)
  }
}

case class ThreadFactoryBuilder private (private val nameFormat: Option[String],
                                         private val priority: Option[Int],
                                         private val exceptionHandler: Option[UncaughtExceptionHandler],
                                         private val threadGroup: Option[ThreadGroup],
                                         private val daemon: Boolean) {
  def withNameFormat(nameFormat: String): ThreadFactoryBuilder = copy(nameFormat = Some(nameFormat))

  def withPriority(priority: Int): ThreadFactoryBuilder = copy(priority = Some(priority))

  def withUncaughtExceptionHandler(exceptionHandler: UncaughtExceptionHandler): ThreadFactoryBuilder = copy(exceptionHandler = Some(exceptionHandler))

  def withThreadGroup(threadGroup: ThreadGroup): ThreadFactoryBuilder = copy(threadGroup = Some(threadGroup))

  def withDaemonFlag(isDaemon: Boolean): ThreadFactoryBuilder = copy(daemon = isDaemon)

  def build: ThreadFactory = {
    val nameF: Option[() => String] = nameFormat.map { nf =>
      val count = new AtomicLong(0)
      () => nf.format(count.getAndDecrement)
    }

    new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = {
        val group = threadGroup.getOrElse(ThreadGroupBuilder.currentThreadGroup())
        val thread = new Thread(group, runnable)
        nameF.foreach(f => thread.setName(f()))
        priority.foreach(thread.setPriority)
        exceptionHandler.foreach(thread.setUncaughtExceptionHandler)
        thread.setDaemon(daemon)
        thread
      }
    }
  }
}
