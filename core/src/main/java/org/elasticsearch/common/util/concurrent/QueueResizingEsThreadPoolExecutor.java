/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.util.concurrent;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ResizableBlockingQueue;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An extension to thread pool executor, which automatically adjusts the queue size of the
 * {@code ResizableBlockingQueue} according to Little's Law.
 */
public final class QueueResizingEsThreadPoolExecutor extends EsThreadPoolExecutor {

    private static final Logger logger =
            ESLoggerFactory.getLogger(QueueResizingEsThreadPoolExecutor.class);

    private final Function<Runnable, Runnable> runnableWrapper;
    private final ResizableBlockingQueue<Runnable> workQueue;
    private final int tasksPerFrame;
    private final int minQueueSize;
    private final int maxQueueSize;
    private final long targetedResponseTimeNanos;
    // The amount the queue size is adjusted by for each calcuation
    private static final int QUEUE_ADJUSTMENT_AMOUNT = 50;

    private final AtomicLong totalTaskNanos = new AtomicLong(0);
    private final AtomicInteger taskCount = new AtomicInteger(0);

    private long startNs;

    QueueResizingEsThreadPoolExecutor(String name, int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                      ResizableBlockingQueue<Runnable> workQueue, int minQueueSize, int maxQueueSize,
                                      Function<Runnable, Runnable> runnableWrapper, final int tasksPerFrame,
                                      TimeValue targetedResponseTime, ThreadFactory threadFactory, XRejectedExecutionHandler handler,
                                      ThreadContext contextHolder) {
        super(name, corePoolSize, maximumPoolSize, keepAliveTime, unit,
                workQueue, threadFactory, handler, contextHolder);
        this.runnableWrapper = runnableWrapper;
        this.workQueue = workQueue;
        this.tasksPerFrame = tasksPerFrame;
        this.startNs = System.nanoTime();
        this.minQueueSize = minQueueSize;
        this.maxQueueSize = maxQueueSize;
        this.targetedResponseTimeNanos = targetedResponseTime.getNanos();
        logger.debug("thread pool [{}] will adjust queue by [{}] when determining automatic queue size",
                name, QUEUE_ADJUSTMENT_AMOUNT);
    }

    @Override
    protected void doExecute(final Runnable command) {
        // we are submitting a task, it has not yet started running (because super.excute() has not
        // been called), but it could be immediately run, or run at a later time. We need the time
        // this task entered the queue, which we get by creating a TimedRunnable, which starts the
        // clock as soon as it is created.
        super.doExecute(this.runnableWrapper.apply(command));
    }

    /**
     * Calculate task rate (λ), for a fixed number of tasks and time it took those tasks to be measured
     *
     * @param totalNumberOfTasks total number of tasks that were measured
     * @param totalFrameTaskNanos nanoseconds during which the tasks were received
     * @return the rate of tasks in the system
     */
    static double calculateLambda(final int totalNumberOfTasks, final long totalFrameTaskNanos) {
        assert totalFrameTaskNanos > 0 : "cannot calculate for instantaneous tasks, got: " + totalFrameTaskNanos;
        assert totalNumberOfTasks > 0 : "cannot calculate for no tasks, got: " + totalNumberOfTasks;
        // There is no set execution time, instead we adjust the time window based on the
        // number of completed tasks, so there is no background thread required to update the
        // queue size at a regular interval. This means we need to calculate our λ by the
        // total runtime, rather than a fixed interval.

        // λ = total tasks divided by measurement time
        return (double) totalNumberOfTasks / totalFrameTaskNanos;
    }

    /**
     * Calculate Little's Law (L), which is the "optimal" queue size for a particular task rate (lambda) and targeted response time.
     *
     * @param lambda the arrival rate of tasks in nanoseconds
     * @param targetedResponseTimeNanos nanoseconds for the average targeted response rate of requests
     * @return the optimal queue size for the give task rate and targeted response time
     */
    static int calculateL(final double lambda, final long targetedResponseTimeNanos) {
        assert targetedResponseTimeNanos > 0 : "cannot calculate for instantaneous requests";
        // L = λ * W
        return Math.toIntExact((long)(lambda * targetedResponseTimeNanos));
    }

    /**
     * Returns the current queue capacity
     */
    public int getCurrentCapacity() {
        return workQueue.capacity();
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);
        // A task has been completed, it has left the building. We should now be able to get the
        // total time as a combination of the time in the queue and time spent running the task. We
        // only want runnables that did not throw errors though, because they could be fast-failures
        // that throw off our timings, so only check when t is null.
        assert r instanceof TimedRunnable : "expected only TimedRunnables in queue";
        final long taskNanos = ((TimedRunnable) r).getTotalNanos();
        final long totalNanos = totalTaskNanos.addAndGet(taskNanos);
        if (taskCount.incrementAndGet() == this.tasksPerFrame) {
            final long endTimeNs = System.nanoTime();
            final long totalRuntime = endTimeNs - this.startNs;
            // Reset the start time for all tasks. At first glance this appears to need to be
            // volatile, since we are reading from a different thread when it is set, but it
            // is protected by the taskCount memory barrier.
            // See: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/package-summary.html
            startNs = endTimeNs;

            // Calculate the new desired queue size
            try {
                final double lambda = calculateLambda(tasksPerFrame, totalNanos);
                final int desiredQueueSize = calculateL(lambda, targetedResponseTimeNanos);
                if (logger.isDebugEnabled()) {
                    final long avgTaskTime = totalNanos / tasksPerFrame;
                    logger.debug("[{}]: there were [{}] tasks in [{}], avg task time: [{}], [{} tasks/s], " +
                                    "optimal queue is [{}]",
                            name,
                            tasksPerFrame,
                            TimeValue.timeValueNanos(totalRuntime),
                            TimeValue.timeValueNanos(avgTaskTime),
                            String.format(Locale.ROOT, "%.2f", lambda * TimeValue.timeValueSeconds(1).nanos()),
                            desiredQueueSize);
                }

                final int oldCapacity = workQueue.capacity();

                // Adjust the queue size towards the desired capacity using an adjust of
                // QUEUE_ADJUSTMENT_AMOUNT (either up or down), keeping in mind the min and max
                // values the queue size can have.
                final int newCapacity =
                        workQueue.adjustCapacity(desiredQueueSize, QUEUE_ADJUSTMENT_AMOUNT, minQueueSize, maxQueueSize);
                if (oldCapacity != newCapacity && logger.isDebugEnabled()) {
                    logger.debug("adjusted [{}] queue size by [{}], old capacity: [{}], new capacity: [{}]", name,
                            newCapacity > oldCapacity ? QUEUE_ADJUSTMENT_AMOUNT : -QUEUE_ADJUSTMENT_AMOUNT,
                            oldCapacity, newCapacity);
                }
            } catch (ArithmeticException e) {
                // There was an integer overflow, so just log about it, rather than adjust the queue size
                logger.warn((Supplier<?>) () -> new ParameterizedMessage(
                                "failed to calculate optimal queue size for [{}] thread pool, " +
                                "total frame time [{}ns], tasks [{}], task execution time [{}ns]",
                                name, totalRuntime, tasksPerFrame, totalNanos),
                        e);
            } finally {
                // Finally, decrement the task count and time back to their starting values. We
                // do this at the end so there is no concurrent adjustments happening. We also
                // decrement them instead of resetting them back to zero, as resetting them back
                // to zero causes operations that came in during the adjustment to be uncounted
                int tasks = taskCount.addAndGet(-this.tasksPerFrame);
                assert tasks >= 0 : "tasks should never be negative, got: " + tasks;

                if (tasks >= this.tasksPerFrame) {
                    // Start over, because we can potentially reach a "never adjusting" state,
                    //
                    // consider the following:
                    // - If the frame window is 10, and there are 10 tasks, then an adjustment will begin. (taskCount == 10)
                    // - Prior to the adjustment being done, 15 more tasks come in, the taskCount is now 25
                    // - Adjustment happens and we decrement the tasks by 10, taskCount is now 15
                    // - Since taskCount will now be incremented forever, it will never be 10 again,
                    //   so there will be no further adjustments
                    logger.debug("[{}]: too many incoming tasks while queue size adjustment occurs, resetting measurements to 0", name);
                    totalTaskNanos.getAndSet(0);
                    taskCount.getAndSet(0);
                    startNs = System.nanoTime();
                } else {
                    // Do a regular adjustment
                    totalTaskNanos.addAndGet(-totalNanos);
                }
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append(getClass().getSimpleName()).append('[');
        b.append(name).append(", ");

        @SuppressWarnings("rawtypes")
        ResizableBlockingQueue queue = (ResizableBlockingQueue) getQueue();

        b.append("queue capacity = ").append(getCurrentCapacity()).append(", ");
        b.append("min queue capacity = ").append(minQueueSize).append(", ");
        b.append("max queue capacity = ").append(maxQueueSize).append(", ");
        b.append("frame size = ").append(tasksPerFrame).append(", ");
        b.append("targeted response rate = ").append(TimeValue.timeValueNanos(targetedResponseTimeNanos)).append(", ");
        b.append("adjustment amount = ").append(QUEUE_ADJUSTMENT_AMOUNT).append(", ");
        /*
         * ThreadPoolExecutor has some nice information in its toString but we
         * can't get at it easily without just getting the toString.
         */
        b.append(super.toString()).append(']');
        return b.toString();
    }
}
