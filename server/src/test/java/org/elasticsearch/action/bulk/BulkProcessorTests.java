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

package org.elasticsearch.action.bulk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class BulkProcessorTests extends ESTestCase {

    private ThreadPool threadPool;
    private final Logger logger = LogManager.getLogger(BulkProcessorTests.class);

    @Before
    public void startThreadPool() {
        threadPool = new TestThreadPool("BulkProcessorTests");
    }

    @After
    public void stopThreadPool() throws InterruptedException {
        terminate(threadPool);
    }

    public void testBulkProcessorFlushPreservesContext() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final String headerKey = randomAlphaOfLengthBetween(1, 8);
        final String transientKey = randomAlphaOfLengthBetween(1, 8);
        final String headerValue = randomAlphaOfLengthBetween(1, 32);
        final Object transientValue = new Object();

        BiConsumer<BulkRequest, ActionListener<BulkResponse>> consumer = (request, listener) -> {
            ThreadContext threadContext = threadPool.getThreadContext();
            assertEquals(headerValue, threadContext.getHeader(headerKey));
            assertSame(transientValue, threadContext.getTransient(transientKey));
            latch.countDown();
        };

        final int bulkSize = randomIntBetween(2, 32);
        final TimeValue flushInterval = TimeValue.timeValueSeconds(1L);
        final BulkProcessor bulkProcessor;
        assertNull(threadPool.getThreadContext().getHeader(headerKey));
        assertNull(threadPool.getThreadContext().getTransient(transientKey));
        try (ThreadContext.StoredContext ignore = threadPool.getThreadContext().stashContext()) {
            threadPool.getThreadContext().putHeader(headerKey, headerValue);
            threadPool.getThreadContext().putTransient(transientKey, transientValue);
            bulkProcessor = new BulkProcessor(consumer, BackoffPolicy.noBackoff(), emptyListener(),
                1, bulkSize, new ByteSizeValue(5, ByteSizeUnit.MB), flushInterval,
                threadPool, () -> {}, BulkRequest::new);
        }
        assertNull(threadPool.getThreadContext().getHeader(headerKey));
        assertNull(threadPool.getThreadContext().getTransient(transientKey));

        // add a single item which won't be over the size or number of items
        bulkProcessor.add(new IndexRequest());

        // wait for flush to execute
        latch.await();

        assertNull(threadPool.getThreadContext().getHeader(headerKey));
        assertNull(threadPool.getThreadContext().getTransient(transientKey));
        bulkProcessor.close();
    }

    public void testConcurrentExecutions() throws Exception {
        final AtomicBoolean called = new AtomicBoolean(false);
        final int maxBatchSize = randomIntBetween(1, 1000);
        final int maxDocuments = randomIntBetween(maxBatchSize, 1000000);
        final int concurrentClients = randomIntBetween(1, 20);
        final int concurrentBulkRequests = randomIntBetween(0, 20);
        final int expectedExecutions = maxDocuments / maxBatchSize;
        BulkResponse bulkResponse = new BulkResponse(new BulkItemResponse[]{new BulkItemResponse()}, 0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicInteger docCount = new AtomicInteger(0);
        BiConsumer<BulkRequest, ActionListener<BulkResponse>> consumer = (request, listener) -> listener.onResponse(bulkResponse);
        BulkProcessor bulkProcessor = new BulkProcessor(consumer, BackoffPolicy.noBackoff(),
            countingListener(requestCount, successCount, failureCount, docCount),
            concurrentBulkRequests, maxBatchSize, new ByteSizeValue(Integer.MAX_VALUE), null,
            (command, delay, executor) -> null, () -> called.set(true), BulkRequest::new);

        ExecutorService executorService = Executors.newFixedThreadPool(concurrentClients);

        IndexRequest indexRequest = new IndexRequest();
        String bulkRequest = "{ \"index\" : { \"_index\" : \"test\", \"_id\" : \"1\" } }\n" +  "{ \"field1\" : \"value1\" }\n";
        BytesReference bytesReference = BytesReference.fromByteBuffers(new ByteBuffer[]{ByteBuffer.wrap(bulkRequest.getBytes())});
        List<Future> futures = new ArrayList<>();
        for (final AtomicInteger i = new AtomicInteger(0); i.getAndIncrement() < maxDocuments;) {
            //alternate between ways to add to the bulk processor
            futures.add(executorService.submit(() -> {
                if(i.get() % 2 == 0) {
                    bulkProcessor.add(indexRequest);
                }else{
                    try {
                        bulkProcessor.add(bytesReference, null, null, XContentType.JSON);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }

        for (Future f : futures) {
            try {
                f.get(1, TimeUnit.SECONDS);
            }catch (Exception e){
                failureCount.incrementAndGet();
                logger.error("failure while getting future", e);
            }
        }
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);

        if (failureCount.get() > 0 || successCount.get() != expectedExecutions || requestCount.get() != successCount.get()) {
            fail("\nExpected Bulks: " + expectedExecutions + "\n" +
                "Requested Bulks: " + requestCount.get() + "\n" +
                "Successful Bulks: " + successCount.get() + "\n" +
                "Failed Bulks: " + failureCount.get() + "\n" +
                "Max Documents: " + maxDocuments + "\n" +
                "Max Batch Size: " + maxBatchSize + "\n" +
                "Concurrent Clients: " + concurrentClients + "\n" +
                "Concurrent Bulk Requests: " + concurrentBulkRequests + "\n"
            );
        }
        bulkProcessor.close();
        //count total docs after processor is closed since there may have been partial batches that are flushed on close.
        assertEquals(docCount.get(), maxDocuments);
    }

    public void testConcurrentExecutionsWithFlush() throws Exception {
        final int maxDocuments = 100000;
        final int concurrentClients = 2;
        final int maxBatchSize = maxDocuments / (concurrentClients * 2); //flush at least once based on size
        final int concurrentBulkRequests = randomIntBetween(0, 20);
        BulkResponse bulkResponse = new BulkResponse(new BulkItemResponse[]{new BulkItemResponse()}, 0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicInteger docCount = new AtomicInteger(0);
        BiConsumer<BulkRequest, ActionListener<BulkResponse>> consumer = (request, listener) ->
        {
            try {
                //sleep for 2 ms to give the flush a chance to flush
                listener.onResponse(bulkResponse);
                Thread.sleep(2);
            } catch (InterruptedException e) {
                failureCount.getAndIncrement();
                logger.error("interrupted while sleeping. There is likely something wrong with this test!", e);
            }
        };
        ScheduledExecutorService flushExecutor = Executors.newScheduledThreadPool(1);
        BulkProcessor bulkProcessor = new BulkProcessor(consumer, BackoffPolicy.noBackoff(),
            countingListener(requestCount, successCount, failureCount, docCount),
            concurrentBulkRequests, maxBatchSize, new ByteSizeValue(Integer.MAX_VALUE), TimeValue.timeValueMillis(1),
            (command, delay, executor) ->
                Scheduler.wrapAsScheduledCancellable(flushExecutor
                    .schedule(command, delay.millis(), TimeUnit.MILLISECONDS)), flushExecutor::shutdownNow, BulkRequest::new);

        ExecutorService executorService = Executors.newFixedThreadPool(concurrentClients);

        IndexRequest indexRequest = new IndexRequest();
        String bulkRequest = "{ \"index\" : { \"_index\" : \"test\", \"_id\" : \"1\" } }\n" +  "{ \"field1\" : \"value1\" }\n";
        BytesReference bytesReference = BytesReference.fromByteBuffers(new ByteBuffer[]{ByteBuffer.wrap(bulkRequest.getBytes())});
        List<Future> futures = new ArrayList<>();
        for (final AtomicInteger i = new AtomicInteger(0); i.getAndIncrement() < maxDocuments;) {
            //alternate between ways to add to the bulk processor
            futures.add(executorService.submit(() -> {
                if(i.get() % 2 == 0) {
                    bulkProcessor.add(indexRequest);
                }else{
                    try {
                        bulkProcessor.add(bytesReference, null, null, XContentType.JSON);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }

        for (Future f : futures) {
            try {
                f.get(1, TimeUnit.SECONDS);
            }catch (Exception e){
                failureCount.incrementAndGet();
                logger.error("failure while getting future", e);
            }
        }
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);
        bulkProcessor.close();

        if (failureCount.get() > 0 || requestCount.get() != successCount.get() || maxDocuments != docCount.get()) {
            fail("\nRequested Bulks: " + requestCount.get() + "\n" +
                "Successful Bulks: " + successCount.get() + "\n" +
                "Failed Bulks: " + failureCount.get() + "\n" +
                "Total Documents: " + docCount.get() + "\n" +
                "Max Documents: " + maxDocuments + "\n" +
                "Max Batch Size: " + maxBatchSize + "\n" +
                "Concurrent Clients: " + concurrentClients + "\n" +
                "Concurrent Bulk Requests: " + concurrentBulkRequests + "\n"
            );
        }
    }

    public void testAwaitOnCloseCallsOnClose() throws Exception {
        final AtomicBoolean called = new AtomicBoolean(false);
        BiConsumer<BulkRequest, ActionListener<BulkResponse>> consumer = (request, listener) -> {};
        BulkProcessor bulkProcessor = new BulkProcessor(consumer, BackoffPolicy.noBackoff(), emptyListener(),
            0, 10, new ByteSizeValue(1000), null,
            (command, delay, executor) -> null, () -> called.set(true), BulkRequest::new);

        assertFalse(called.get());
        bulkProcessor.awaitClose(100, TimeUnit.MILLISECONDS);
        assertTrue(called.get());
    }

    private BulkProcessor.Listener emptyListener() {
        return new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
            }
        };
    }

    private BulkProcessor.Listener countingListener(AtomicInteger requestCount, AtomicInteger successCount, AtomicInteger failureCount,
                                                    AtomicInteger docCount) {

        return new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                requestCount.incrementAndGet();
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                successCount.incrementAndGet();
                docCount.addAndGet(request.requests().size());
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                if (failure != null) {
                    failureCount.incrementAndGet();
                }
            }
        };
    }
}
