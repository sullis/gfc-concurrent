package com.gilt.gfc.concurrent

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Awaitable}


/**
 * Curried version of Scala's Await, for convenience.
 * {{{
 *   AwaitResult(1 second) { futureBasedCall() }
 * }}}
 */
object AwaitResult {

  def apply[T]( atMost: Duration
             )( awaitable: Awaitable[T]
              ): T = {
    Await.result(awaitable, atMost)
  }
}
