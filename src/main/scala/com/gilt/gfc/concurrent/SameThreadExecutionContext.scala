package com.gilt.gfc.concurrent

import scala.concurrent.ExecutionContext
import com.gilt.gfc.logging.Loggable

/**
 * For small code blocks that don't need to be run on a separate thread.
 */
object SameThreadExecutionContext extends ExecutionContext with Loggable {
  override def execute(runnable: Runnable): Unit = runnable.run
  override def reportFailure(t: Throwable): Unit = error(t.getMessage, t)
}
