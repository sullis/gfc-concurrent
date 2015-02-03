package com.gilt.gfc.concurrent

import java.util.concurrent.TimeoutException
import scala.concurrent.{ Future, Await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import org.scalatest.{WordSpec, Matchers}

class TimeLimitedFutureSpec extends WordSpec with Matchers {
  import TimeLimitedFutureSpec._

  "RichFuture" when {
    import ScalaFutures._

    "waiting for a result to happen" should {
      "return the completed original Future if it completes before the given timeout" in {
        val now = System.currentTimeMillis
        val future: Future[String] = (Future { Thread.sleep(1000); "Here I am" }).withTimeout(Duration(2, "seconds"))
        val msg: String = Await.result(future, Duration(10, "seconds"))
        val elapsed = (System.currentTimeMillis - now)
        msg should equal ("Here I am")
        (elapsed < (1000 + 100)) should be (true)
      }

      "return the failure of the original Future if it fails before the given timeout" in {
        val now = System.currentTimeMillis
        val future = (Future { Thread.sleep(1000); throw new NullPointerException("That hurts!") }).withTimeout(Duration(2, "seconds"))
        a [NullPointerException] should be thrownBy { Await.result(future, Duration(10, "seconds")) }
        val elapsed = (System.currentTimeMillis - now)
        (elapsed < (1000 + 100)) should be (true)
      }

      "return the timeout of the original Future if it had one and it went off and was shorter than the given one" in {
        val now = System.currentTimeMillis
        val timingOutEarlier = Timeouts.timeout(Duration(500, "milliseconds"))
        val future = timingOutEarlier.withTimeout(Duration(1, "seconds"))
        a [TimeoutException] should be thrownBy { Await.result(future, Duration(10, "seconds")) }
        val elapsed: Long = (System.currentTimeMillis - now)
        elapsed should be >= 500l
        elapsed should be <= 600l
      }

      "return the timeout if the original Future does not timeouts of its own" in {
        val now = System.currentTimeMillis
        val timingOutLater = Timeouts.timeout(Duration(1500, "milliseconds"))
        val future = timingOutLater.withTimeout(Duration(1, "seconds"))
        a [TimeoutException] should be thrownBy  { Await.result(future, Duration(10, "seconds")) }
        val elapsed: Long = (System.currentTimeMillis - now)
        elapsed should be >= 1000l
        elapsed should be <= 1100l
      }
    }

    // an example of how it could be used
    "used in our most common use case" should {
      "fit nicely" in {
        val call: Future[String] = svcCall(10).withTimeout(Duration(50, "milliseconds")).recover {
          case t: TimeoutException => "recover.timeout"
          case other => s"recover.${other.getMessage}"
        }
        call.onSuccess { case r: String => "ok" }
        Await.result(call, Duration.Inf) should be ("data-10")

        val call2: Future[String] = svcCall(100).withTimeout(Duration(50, "milliseconds")).recover {
          case t: TimeoutException => "recover.timeout"
          case other => s"recover.${other.getMessage}"
        }
        call2.onSuccess { case r: String => "ok" }
        Await.result(call2, Duration.Inf) should be ("recover.timeout")
      }
    }
  }
}

object TimeLimitedFutureSpec {
  def svcCall(latency: Long): Future[String] = Future { Thread.sleep(latency); s"data-${latency}" }
}
