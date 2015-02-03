package com.gilt.gfc.concurrent

import java.util.concurrent.{ExecutorService => JExecutorService, ScheduledExecutorService => JScheduledExecutorService}

/**
 * Implicit conversions from java.util.concurrent.ExecutorService and java.util.concurrent.ScheduledExecutorService to
 * com.gilt.gfc.concurrent.ExecutorService and com.gilt.gfc.concurrent.AsyncScheduledExecutorService
 */
object JavaConversions {
  import scala.language.implicitConversions

  implicit def asScalaExecutorService(jes: JExecutorService): ExecutorService = new JExecutorServiceWrapper {
    override val executorService = jes
  }

  implicit def asScalaScheduledExecutorService(jses: JScheduledExecutorService): ScheduledExecutorService = new JScheduledExecutorServiceWrapper {
    override val executorService = jses
  }

  implicit def asScalaAsyncScheduledExecutorService(jses: JScheduledExecutorService): AsyncScheduledExecutorService = new JScheduledExecutorServiceWrapper {
    override val executorService = jses
  }
}
