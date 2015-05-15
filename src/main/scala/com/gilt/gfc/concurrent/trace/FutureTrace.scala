package com.gilt.gfc.concurrent.trace

import com.gilt.gfc.logging.OpenLoggable


/** A trace of a single Future-based call.
  * @see  {{{FutureBuilder}}}
  */
case class FutureTrace (
  callName: String
, additionalMessage: String
, durationMillis: Long
, error: Option[FutureErrorTrace]
)


/** A 'union' type of Future-based call errors we differentiate. */
sealed trait FutureErrorTrace
case class FutureRecoverableErrorTrace(throwable: Throwable) extends FutureErrorTrace
case class FutureGenericErrorTrace(throwable: Throwable) extends FutureErrorTrace


/** Logs FutureTraces, @see {{{FutureBuilder}}} */
object LogFutureTraces {

  def apply( trace: FutureTrace
           ): Unit = {
    trace match {
      case FutureTrace(n, m, t, None) =>
        Logger.info(s"${n}(${m}) took ${t} ms")

      case FutureTrace(n, m, t, Some(FutureRecoverableErrorTrace(e))) =>
        Logger.info(s"${n}(${m}) took ${t} ms, resulted in a recoverable error: ${e.getMessage}}.")

      case FutureTrace(n, m, t, Some(FutureGenericErrorTrace(e))) =>
        Logger.info(s"${n}(${m}) took ${t} ms, resulted in a generic error: ${e.getMessage}")
    }
  }


  private
  val Logger = new AnyRef with OpenLoggable
}

