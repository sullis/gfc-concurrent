package com.gilt.gfc.concurrent

import java.util.concurrent.{ExecutorService => JExecutorService, ScheduledExecutorService => JScheduledExecutorService}

import com.gilt.gfc.concurrent.JavaConversions._

/**
 * Implicit conversions between scala functions and Java equivalents.
 */
object JavaConverters {

  implicit class ScalaExecutorServiceConverter[T](val jes: JExecutorService) extends AnyVal {
    @inline def asScala = asScalaExecutorService(jes)
  }

  implicit class ScalaScheduledExecutorServiceConverter[T](val jses: JScheduledExecutorService) extends AnyVal {
    @inline def asScala = asScalaAsyncScheduledExecutorService(jses)
  }
}
