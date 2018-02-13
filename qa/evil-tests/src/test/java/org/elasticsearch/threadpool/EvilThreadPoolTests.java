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

package org.elasticsearch.threadpool;

import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.instanceOf;

public class EvilThreadPoolTests extends ESTestCase {

    private ThreadPool threadPool;

    @Before
    public void setUpThreadPool() {
        threadPool = new TestThreadPool(EvilThreadPoolTests.class.getName());
    }

    @After
    public void tearDownThreadPool() throws InterruptedException {
        terminate(threadPool);
    }

    public void testExecutionException() throws InterruptedException {
        runExecutionExceptionTest(
                () -> {
                    throw new Error("future error");
                },
                true,
                o -> {
                    assertTrue(o.isPresent());
                    assertThat(o.get(), instanceOf(Error.class));
                    assertThat(o.get(), hasToString(containsString("future error")));
                });
        runExecutionExceptionTest(
                () -> {
                    throw new IllegalStateException("future exception");
                },
                false,
                o -> assertFalse(o.isPresent()));
    }

    private void runExecutionExceptionTest(
            final Supplier<Throwable> supplier,
            final boolean expectThrowable,
            final Consumer<Optional<Throwable>> consumer) throws InterruptedException {
        final AtomicReference<Throwable> throwableReference = new AtomicReference<>();
        final Thread.UncaughtExceptionHandler uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                assertTrue(expectThrowable);
                throwableReference.set(e);
                latch.countDown();
            });

            threadPool.generic().submit(supplier::get);

            if (expectThrowable) {
                latch.await();
                consumer.accept(Optional.of(throwableReference.get()));
            } else {
                consumer.accept(Optional.empty());
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        }
    }

}
