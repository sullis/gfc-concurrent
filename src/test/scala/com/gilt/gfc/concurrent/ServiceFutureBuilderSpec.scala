package com.gilt.gfc.concurrent

import com.gilt.gfc.concurrent.Timeouts.timeout
import com.gilt.gfc.concurrent.trace.LogFutureTraces
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.{postfixOps, reflectiveCalls}
import scala.util.Success
import scala.util.control.NonFatal


class FutureBuilderSpec
  extends FlatSpec
     with MockitoSugar {

  implicit private
  val executor: ExecutionContext = ExecutionContext.Implicits.global

  "The FutureBuilder" should "not blow up when call tracing is enabled" in {
    AwaitResult(10 seconds) {
      FutureBuilder[String]("test").withTraceCalls(LogFutureTraces(_)).runWithTimeout(10 seconds) {
        delayResult(100 milliseconds)("foobar")
      }
    } should === (Success("foobar"))
  }


  it should "not blow up when tracing timed out futures" in {
    (AwaitResult(10 seconds) {
      FutureBuilder[String]("test").withTraceCalls(LogFutureTraces(_)).runWithTimeout(10 seconds) {
        timeout(100 milliseconds)
      }
    }).failed.get.getMessage should startWith("test(): Timeout after")
  }


  it should "make use of default value" in {
    AwaitResult(10 seconds) {
      FutureBuilder[String]("test").
        withTraceCalls(LogFutureTraces(_)).
        withServiceErrorDefaultValue("mooohahaha").
        runWithTimeout(10 seconds) {
          timeout(100 milliseconds)
        }
    } should === ("mooohahaha")
  }


  it should "make use of service call timeout" in {
    AwaitResult(10 seconds) {
      FutureBuilder[String]("test").
        withTraceCalls(LogFutureTraces(_)).
        withServiceErrorDefaultValue("default").
        runWithTimeout(100 milliseconds) { // timeout and get a default value
          delayResult(3 seconds)("test") // far out, we won't wait that long in this test
        }
    } should === ("default")
  }


  it should "make use of additional error message" in {
    var numTries = 0

    (AwaitResult(10 seconds) {
      FutureBuilder[String]("TEST: It's Ok!").
        withTraceCalls(LogFutureTraces(_)).
        runWithTimeout(10 seconds) {
          numTries = numTries + 1
          timeout(100 milliseconds)
        }
    }).failed.get.getMessage should startWith("TEST: It's Ok!")

    numTries should ===(1) // we didn't ask for a retry, so, should only see 1 service call
  }


  it should "retry" in {

    var numTries = 0

    AwaitResult(10 seconds) {

      FutureBuilder[String]("test").
        retry(1).
        runWithTimeout(100 milliseconds) {
          numTries = numTries + 1

          if (numTries > 1) {
            delayResult(10 milliseconds)("test")
          } else {
            delayResult(3 seconds)("BOO!")
          }
        }

    } should === (Success("test"))

    numTries should ===(2)
  }


  it should "pass selected exceptions through unmodified to the caller" in {

    var numTries = 0
    var wasCalled = false

    (AwaitResult(10 seconds) {

      FutureBuilder[String]("test").
        withTraceCalls(LogFutureTraces(_)).
        withPassThroughExceptions({
          case e: IllegalArgumentException =>
            wasCalled = true
            e
        }).retry(10).
        runWithTimeout(100 milliseconds) {
          numTries = numTries + 1 // we want to make sure it doesn't retry
          delayResult(10 milliseconds)("whatever") map {
            _ => throw new IllegalArgumentException("INTERESTING EXCEPTION")
          }
      }

    }).failed.get.getMessage should === ("INTERESTING EXCEPTION")

    wasCalled should === (true)
    numTries should === (1) // don't retry if we pass it through
  }


  private[this]
  def delayResult[A]( after: FiniteDuration
                   )( a: => A
                    ): Future[A] = {
    timeout(after) recover {
      case NonFatal(_) => a
    }
  }

}
