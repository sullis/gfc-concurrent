package com.gilt.gfc.concurrent

import org.scalatest.{Matchers, FunSuite}

class NamedThreadFactoryTest extends FunSuite with Matchers {
  test("name, groupname and daemon") {
    val f = new NamedThreadFactory("name", "groupname", true)
    f.getThreadGroup.getName shouldBe "groupname"
    f.getThreadGroup.isDaemon shouldBe false
    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName should fullyMatch regex "name\\-\\d+"
    t.isDaemon shouldBe true
    t.getThreadGroup should be theSameInstanceAs f.getThreadGroup
  }

  test("name and daemon") {
    val f = new NamedThreadFactory("name", true)
    f.getThreadGroup.getName shouldBe "name"
    f.getThreadGroup.isDaemon shouldBe false
    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName should fullyMatch regex "name\\-\\d+"
    t.isDaemon shouldBe true
    t.getThreadGroup should be theSameInstanceAs f.getThreadGroup
  }

  test("name, groupname, daemon and priority") {
    val f = new NamedThreadFactory("name", "groupname", true, Thread.MIN_PRIORITY)
    f.getThreadGroup.getName shouldBe "groupname"
    f.getThreadGroup.isDaemon shouldBe false
    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName should fullyMatch regex "name\\-\\d+"
    t.isDaemon shouldBe true
    t.getPriority shouldBe Thread.MIN_PRIORITY
    t.getThreadGroup should be theSameInstanceAs f.getThreadGroup
  }

  test("name, daemon and priority") {
    val f = new NamedThreadFactory("name", true, Thread.MIN_PRIORITY)
    f.getThreadGroup.getName shouldBe "name"
    f.getThreadGroup.isDaemon shouldBe false
    val t = f.newThread(new Runnable {
      override def run(): Unit = {}
    })
    t.getName should fullyMatch regex "name\\-\\d+"
    t.isDaemon shouldBe true
    t.getPriority shouldBe Thread.MIN_PRIORITY
    t.getThreadGroup should be theSameInstanceAs f.getThreadGroup
  }
}
