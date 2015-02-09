package com.gilt.gfc.concurrent

import java.lang.Thread.UncaughtExceptionHandler
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FunSuite}

class ThreadFactoryBuilderTest extends FunSuite with Matchers with MockitoSugar {
  test("name and groupname") {
    val f = ThreadFactoryBuilder("groupname", "name").withDaemonFlag(false).build()

    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName should fullyMatch regex "name\\-\\d+"
    t.getThreadGroup.getName shouldBe "groupname"
  }

  test("All defaults") {
    val f = ThreadFactoryBuilder().build()

    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName should fullyMatch regex "Thread\\-\\d+"
    t.isDaemon shouldBe true
    t.getPriority shouldBe Thread.NORM_PRIORITY
    t.getThreadGroup should be theSameInstanceAs Thread.currentThread.getThreadGroup
    t.getUncaughtExceptionHandler shouldBe Thread.currentThread.getThreadGroup
  }

  test("withXXX") {
    val group = new ThreadGroup("foo")
    val exh = mock[UncaughtExceptionHandler]
    val f = ThreadFactoryBuilder().
      withNameFormat("bar-%d").
      withDaemonFlag(false).
      withPriority(Thread.MIN_PRIORITY).
      withThreadGroup(group).
      withUncaughtExceptionHandler(exh).
      build

    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName shouldBe "bar-0"
    t.isDaemon shouldBe false
    t.getPriority shouldBe Thread.MIN_PRIORITY
    t.getThreadGroup should be theSameInstanceAs group
    t.getUncaughtExceptionHandler shouldBe exh
  }
}
