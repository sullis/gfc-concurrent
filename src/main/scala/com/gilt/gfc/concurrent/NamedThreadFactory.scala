package com.gilt.gfc.concurrent

import java.util.concurrent.ThreadFactory
import com.gilt.gfc.logging.Loggable

/**
 * Base thread factory that allows to create a set of thread with a common name, group and daemon properties.
 * <p/>
 * This is mostly useful to identify background threads and make sure they do not prevent the jvm from shutting down
 * or for debugging/logging purposes to identify clearly what are the active threads.
 */
object NamedThreadFactory extends Loggable {
  /**
   * Called to create the thread group all threads are created in.
   *
   * @param name        the name of the group
   * @param daemon      true if threads will be created as daemons; this default implementation does not create a
   *                    daemon thread group as such a group will destroy itself if it ever becomes empty after having
   *                    at least one thread, for example if the factory is used to create threads for an
   *                    infrequently-used cached thread pool
   * @param maxPriority maximum priority for the group
   * @return the created thread group
   */
  private def newThreadGroup(name: String, daemon: Boolean, maxPriority: Int): ThreadGroup = {
    val g = new ThreadGroup(name)
    g.setDaemon(daemon)
    g.setMaxPriority(maxPriority)
    g
  }

  val LogExceptionHandler = new Thread.UncaughtExceptionHandler {
    override def uncaughtException(t: Thread, e: Throwable): Unit = {
      error("Failed to catch exception in thread " + t.getName(), e)
    }
  }
}

/**
 * Create a new factory with a given base name, thread group and daemon status.
 *
 * @param name  the base name of the thread, all the threads will end up having names such "name-xxx"
 * @param group the group where this thread will be added
 */
class NamedThreadFactory(name: String, group: ThreadGroup, daemon: Boolean) extends ThreadFactory {
  private val factory = ThreadFactoryBuilder().
    withNameFormat(name + "-%d").
    withDaemonFlag(daemon).
    withPriority(group.getMaxPriority).
    withUncaughtExceptionHandler(NamedThreadFactory.LogExceptionHandler).
    withThreadFactory(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        new Thread(group, r)
      }
    }).build

  /**
   * Create a new factory with a given base name and thread group.
   *
   * @param name  the base name of the thread, all the threads will end up having names such "name-xxx"
   * @param group the group where this thread will be added; threads are created with the same daemon status as the
   *              group
   */
  def this(name: String, group: ThreadGroup) = this(name, group, group.isDaemon)

  /**
   * Create a new factory with a given base name, group name, daemon status and maximum priority.
   *
   * @param name        the base name of the thread, all the threads will end up having names such "name-xxx"
   * @param groupName   the name of the group where this thread will be added
   * @param daemon      true for the threads to be created to be daemons, false otherwise
   * @param maxPriority maximum priority for the group
   */
  def this(name: String, groupName: String, daemon: Boolean, maxPriority: Int) = this(name, NamedThreadFactory.newThreadGroup(groupName, false, maxPriority), daemon)

  /**
   * Create a new factory with a given base name, daemon status and maximum priority.
   *
   * @param name        the base name of the thread, all the threads will end up having names such "name-xxx"
   * @param daemon      true for the threads to be created to be daemons, false otherwise
   * @param maxPriority maximum priority for the group
   */
  def this(name: String, daemon: Boolean, maxPriority: Int) = this(name, name, daemon, maxPriority)

  /**
   * Create a new factory with a given base name, group name and daemon status.
   *
   * @param name      the base name of the thread, all the threads will end up having names such "name-xxx"
   * @param groupName the name of the group where this thread will be added
   * @param daemon    true for the threads to be created to be daemons, false otherwise
   */
  def this(name: String, groupName: String, daemon: Boolean) = this(name, groupName, daemon, Thread.NORM_PRIORITY)

  /**
   * Create a new factory with a given base name and daemon status.
   *
   * @param name      the base name of the thread and thread group, all the threads will end up having names such "name-xxx"
   * @param daemon    true for the threads to be created to be daemons, false otherwise
   */
  def this(name: String, daemon: Boolean) = this(name, name, daemon)

  /**
   * Create a new factory with a given base name, which creates daemon threads
   *
   * @param name      the base name of the thread and thread group, all the threads will end up having names such "name-xxx"
   */
  def this(name: String) = this(name, name, true)

  /**
   * Return the thread group associated with this thread factory.
   *
   * @return the thread group associated to the thread factory
   */
  def getThreadGroup: ThreadGroup = group

  def newThread(r: Runnable): Thread = {
    return factory.newThread(r)
  }
}

