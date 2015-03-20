# gfc-concurrent

A library that contains scala concurrency helper code. Part of the gilt foundation classes.

## Contents and Example Usage

### com.gilt.gfc.concurrent.ScalaFutures

This object contains a bunch of sugar and little helpers that make working with scala futures a bit easier:

* Limit how long a scala Future can take by giving it a timeout Duration, after which it fails with a `java.util.concurrent.TimeoutException`
```
    import scala.concurrent.duration._
    import com.gilt.gfc.concurrent.ScalaFutures._
    val futureWithTimeout = myFuture.withTimeout(1 minute)
```
* Retry a Future until it succeeds, with or without delay:
```
    import scala.concurrent.duration._
    import com.gilt.gfc.concurrent.ScalaFutures._
    def remoteCall: Future[Response] = ???
    // Retry the remote call up to 10 times until it succeeds
    val response: Future[Response] = retry(10)(remoteCall)
```
```
    import scala.concurrent.duration._
    import com.gilt.gfc.concurrent.ScalaFutures._
    def remoteCall: Future[Response] = ???
    // Retry the remote call up to 10 times until it succeeds, with an exponential backoff,
    // starting at 10 ms and doubling each iteration until it reaches 1 second, i.e.
    // 10ms, 20ms, 40ms, 80ms, 160ms, 320ms, 640ms, 1s, 1s, 1s
    val response: Future[Response] = retryWithExponentialBackoff(10, 10 millis, 1 minute, 2)(remoteCall)
```
* Higher-order functions missing in the scala.concurrent.Future object:
```
    // Asynchronously tests whether a predicate holds for some of the elements of a collection of futures
    val futures: Seq[Future[String]] = ???
    ScalaFutures.exists(futures, _.contains("x"))
```
```
    // Asynchronously tests whether a predicate holds for all elements of a collection of futures
    val futures: Seq[Future[String]] = ???
    ScalaFutures.forall(futures, _.contains("x"))
```
* Enhanced fold that fails fast, as soon as a Future in the input collection fails. The "normal" scala.concurrent.Future.fold() will always take as long as the longest running Future, even if another Future has already failed. This implementation of fold will shortcut if any of the futures in the input collection fails:
```
    val futures: Seq[Future[String]] = ???
    val totalLength: Future[Int] = ScalaFutures.foldFast(futures)(0)((sum, str) => sum + str.length)
```
* Convert a `scala.util.Try` into a Future. If the Try is a Success, the Future is successful, if the Try is a Failure,
the Future is a failed Future with the same Exception.
```
    val someTry: Try[String] = Try(???)
    val someFuture: Future[String] = ScalaFutures.fromTry(someTry)
```
* Future of an empty `Option`
```
    val noString: Future[Option[String]] = ScalaFuture.FutureNone
```

### com.gilt.gfc.concurrent.SameThreadExecutionContext

`ExecutionContext` that executes an asynchronous action synchronously on the same `Thread`. This can be
useful for small code blocks that don't need to be run on a separate thread.
The object can either be used explicitly or imported implicitly like this:
```
    import com.gilt.gfc.concurrent.ScalaFutures.Implicits._
    someFuture.map(_ + 1)
```
__Note__: Using this ExecutionContext does _not_ mean that the Thread that executes this piece of code will execute the
map() function. It rather means that the Future's completion handler (the Thread that calls the registered onComplete
functions) does _not_ hand of the execution of the map() function to another thread and instead executes it synchronously.
As a result this may delay onComplete notifications for other interested parties and thus should only be used in cases
where a small piece of code needs to be executed.

### com.gilt.gfc.concurrent.ExecutorService / ScheduledExecutorService / AsyncScheduledExecutorService

These are scala adaptations and enhancements of `java.util.concurrent.ExecutorService` and `java.util.concurrent.ScheduledExecutorService`.
Besides offering functions to execute and schedule the execution of scala functions, the `AsyncScheduledExecutorService`
allows scheduling of asynchronous tasks, represented by a scala `Future`, that are scheduled with the same guarantees
as the (synchronous) scheduling functions. I.e. they are guaranteed to not execute concurrently. Example:
```
    // Have a AsyncScheduledExecutorService
    val scalaExecutor: AsyncScheduledExecutorService = ???

    // Have a function that kicks off a new asynchrouous Task
    def newTask(): Future[Any] = ???
    
    // Run this task every minute, stating in 1 minute
    import scala.concurrent.duration._
    val future = scalaExecutor.asyncScheduleAtFixedRate(1 minute, 1 minute)(newTask)
```

### com.gilt.gfc.concurrent.JavaConverters / JavaConversions

Implicit and explicit functions to convert java.util.concurrent.(Scheduled)ExecutorService instances to the above enhanced types.
```
    // Have a new ScheduledExecutorService
    val javaExecutor: ScheduledExecutorService = ???
    
    // Convert it into an AsyncScheduledExecutorService (explicit)
    import com.gilt.gfc.concurrent.JavaConverters._
    val scalaExecutor1: AsyncScheduledExecutorService = javaExecutor.asScala

    // Convert it into an AsyncScheduledExecutorService (implicit)
    import com.gilt.gfc.concurrent.JavaConversions._
    val scalaExecutor2: AsyncScheduledExecutorService = javaExecutor 
```
### com.gilt.gfc.concurrent.ThreadFactoryBuilder and ThreadGroupBuilder

Factories that allow the creation of a set of threads with a common name, group, daemon and other properties.
This is e.g. useful to identify background threads and make sure they do not prevent the jvm from shutting down
or for debugging/logging purposes to identify clearly what are the active threads.
```
    // Create a new ThreadGroup (all "with" functions are optional)
    val threadGroup = ThreadGroupBuilder().
                        withName("foo").
                        withDaemonFlag(false).
                        withParent(otherThreadGroup).
                        withMaxPriority(Thread.MIN_PRIORITY).
                        build()

    // Create a new ThreadFactory (all "with" functions are optional)
    val threadFactory = ThreadFactoryBuilder().
                            withNameFormat("bar-%s").
                            withPriority(Thread.MAX_PRIORITY).
                            withUncaughtExceptionHandler(anUncaughtExceptionHandler).
                            withThreadGroup(threadGroup).
                            withDaemonFlag(false).
                            build()
```

## License
Copyright 2015 Gilt Groupe, Inc.

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
