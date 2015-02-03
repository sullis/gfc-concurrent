package com.gilt.gfc.concurrent

import java.util.concurrent.ScheduledFuture

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * Functions for scheduling of asynchronous tasks
 */
trait AsyncScheduledExecutorService extends ScheduledExecutorService {
  /**
   * Similar to java.util.concurrent.ScheduledExecutorService.scheduleWithFixedDelay
   * this function executes an asynchronous periodic task.
   * It is triggered first after the given initial delay, and subsequently after
   * the given delay between the completion of one execution and the
   * commencement of the next.
   * Different to scheduleWithFixedDelay, Exceptions while triggering a Future
   * and failed Futures will _not_ suppress scheduling of subsequent executions.
   * The task can be terminated via cancellation of the returned ScheduledFuture
   * or via termination of the executor.
   *
   * @param initialDelay the time to delay first execution
   * @param delay the delay between the completion of one Future
   *              and the triggering of the next
   * @param futureTask function that returns a Future that asynchronously
   *                   executes a task iteration
   * @param ec implicit ExecutionContext to schedule the Future's completion
   *           handler. Recommended to use ExecutionContext.fromExecutor(thisExecutor)
   * @return a ScheduledFuture representing pending completion of
   *         the task.
   */
  def asyncScheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration)
                                 (futureTask: => Future[_])
                                 (implicit ec: ExecutionContext = ExecutionContext.fromExecutor(this)): ScheduledFuture[_] =
    asyncSchedule(initialDelay, _ => delay)(futureTask)

  /**
   * Similar to java.util.concurrent.ScheduledExecutorService.scheduleAtFixedRate
   * this function executes an asynchronous periodic task.
   * It is triggered first after the given initial delay, and subsequently with
   * the given period. The next task is only scheduled when the previous
   * task's future has completed. Hence, if any execution of this task
   * takes longer than its period, then subsequent executions
   * will start late and will not concurrently execute.
   * Different to scheduleAtFixedRate, Exceptions while triggering a Future
   * and failed Futures will _not_ suppress scheduling of subsequent executions.
   * The task can be terminated via cancellation of the returned ScheduledFuture
   * or via termination of the executor.
   *
   * @param initialDelay the time to delay first execution
   * @param period the period between successive executions
   * @param futureTask function that returns a Future that asynchronously
   *                   executes a task iteration
   * @param ec implicit ExecutionContext to schedule the Future's completion
   *           handler. Recommended to use ExecutionContext.fromExecutor(thisExecutor)
   * @return a ScheduledFuture representing pending completion of
   *         the task.
   */
  def asyncScheduleAtFixedRate(initialDelay: FiniteDuration, period: FiniteDuration)
                              (futureTask: => Future[_])
                              (implicit ec: ExecutionContext): ScheduledFuture[_] =
    asyncSchedule(initialDelay, timeSincePrev => period - timeSincePrev)(futureTask)

  /**
   * Similar to asyncScheduleWithFixedDelay and asyncScheduleWithFixedDelay
   * this function repeatedly executes an asynchronous task.
   * It is triggered first after the given initial delay, and subsequently after
   * the duration returned by calling the delayUntilNext function. The parameter
   * passed into the function is the duration that the task took to complete,
   * i.e. the time between triggering a new Future and the completion of the
   * same future.
   * Exceptions while triggering a Future and failed Futures will _not_ suppress
   * scheduling of subsequent executions.
   * The task can be terminated via cancellation of the returned ScheduledFuture
   * or via termination of the executor.
   *
   * @param initialDelay the time to delay first execution
   * @param delayUntilNext function to compute the delay until the next task is
   *                       scheduled. The parameter is the duration of the previous
   *                       task. The function may return a negative duration in
   *                       which case the next Future is triggered immediately.
   * @param futureTask function that returns a Future that asynchronously
   *                   executes a task iteration
   * @param ec implicit ExecutionContext to schedule the Future's completion
   *           handler. By default uses this Executor.
   * @return a ScheduledFuture representing pending completion of
   *         the task.
   */
  def asyncSchedule(initialDelay: FiniteDuration, delayUntilNext: FiniteDuration => FiniteDuration)
                   (futureTask: => Future[_])
                   (implicit ec: ExecutionContext = ExecutionContext.fromExecutor(this)): ScheduledFuture[_]
}
