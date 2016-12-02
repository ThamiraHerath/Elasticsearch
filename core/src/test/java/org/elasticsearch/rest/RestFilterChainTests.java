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

package org.elasticsearch.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.rest.FakeRestChannel;
import org.elasticsearch.test.rest.FakeRestRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.CoreMatchers.equalTo;

public class RestFilterChainTests extends ESTestCase {
    public void testRestFilters() throws Exception {

        RestController restController = new RestController(Settings.EMPTY, Collections.emptySet());

        int numFilters = randomInt(10);
        Set<Integer> orders = new HashSet<>(numFilters);
        while (orders.size() < numFilters) {
            orders.add(randomInt(10));
        }

        List<RestFilter> filters = new ArrayList<>();
        for (Integer order : orders) {
            TestFilter testFilter = new TestFilter(order, randomFrom(Operation.values()));
            filters.add(testFilter);
            restController.registerFilter(testFilter);
        }

        ArrayList<RestFilter> restFiltersByOrder = new ArrayList<>(filters);
        Collections.sort(restFiltersByOrder, (o1, o2) -> Integer.compare(o1.order(), o2.order()));

        List<RestFilter> expectedRestFilters = new ArrayList<>();
        for (RestFilter filter : restFiltersByOrder) {
            TestFilter testFilter = (TestFilter) filter;
            expectedRestFilters.add(testFilter);
            if (!(testFilter.callback == Operation.CONTINUE_PROCESSING) ) {
                break;
            }
        }

        restController.registerHandler(RestRequest.Method.GET, "/", (request, channel, client) -> {
            channel.sendResponse(new TestResponse());
        });

        FakeRestRequest fakeRestRequest = new FakeRestRequest();
        FakeRestChannel fakeRestChannel = new FakeRestChannel(fakeRestRequest, randomBoolean(), 1);
        restController.dispatchRequest(fakeRestRequest, fakeRestChannel, null, new ThreadContext(Settings.EMPTY));
        assertThat(fakeRestChannel.await(), equalTo(true));


        List<TestFilter> testFiltersByLastExecution = new ArrayList<>();
        for (RestFilter restFilter : filters) {
            testFiltersByLastExecution.add((TestFilter)restFilter);
        }
        Collections.sort(testFiltersByLastExecution, (o1, o2) -> Long.compare(o1.executionToken, o2.executionToken));

        ArrayList<TestFilter> finalTestFilters = new ArrayList<>();
        for (RestFilter filter : testFiltersByLastExecution) {
            TestFilter testFilter = (TestFilter) filter;
            finalTestFilters.add(testFilter);
            if (!(testFilter.callback == Operation.CONTINUE_PROCESSING) ) {
                break;
            }
        }

        assertThat(finalTestFilters.size(), equalTo(expectedRestFilters.size()));

        for (int i = 0; i < finalTestFilters.size(); i++) {
            TestFilter testFilter = finalTestFilters.get(i);
            assertThat(testFilter, equalTo(expectedRestFilters.get(i)));
            assertThat(testFilter.runs.get(), equalTo(1));
        }
    }

    public void testTooManyContinueProcessing() throws Exception {

        final int additionalContinueCount = randomInt(10);

        TestFilter testFilter = new TestFilter(randomInt(), (request, channel, client, filterChain) -> {
            for (int i = 0; i <= additionalContinueCount; i++) {
                filterChain.continueProcessing(request, channel, null);
            }
        });

        RestController restController = new RestController(Settings.EMPTY, Collections.emptySet());
        restController.registerFilter(testFilter);

        restController.registerHandler(RestRequest.Method.GET, "/", (request, channel, client) -> channel.sendResponse(new TestResponse()));

        FakeRestRequest fakeRestRequest = new FakeRestRequest();
        FakeRestChannel fakeRestChannel = new FakeRestChannel(fakeRestRequest, randomBoolean(), additionalContinueCount + 1);
        restController.dispatchRequest(fakeRestRequest, fakeRestChannel, null, new ThreadContext(Settings.EMPTY));
        fakeRestChannel.await();

        assertThat(testFilter.runs.get(), equalTo(1));

        assertThat(fakeRestChannel.responses().get(), equalTo(1));
        assertThat(fakeRestChannel.errors().get(), equalTo(additionalContinueCount));
    }

    private enum Operation implements Callback {
        CONTINUE_PROCESSING {
            @Override
            public void execute(RestRequest request, RestChannel channel, NodeClient client, RestFilterChain filterChain) throws Exception {
                filterChain.continueProcessing(request, channel, client);
            }
        },
        CHANNEL_RESPONSE {
            @Override
            public void execute(RestRequest request, RestChannel channel, NodeClient client, RestFilterChain filterChain) throws Exception {
                channel.sendResponse(new TestResponse());
            }
        }
    }

    private interface Callback {
        void execute(RestRequest request, RestChannel channel, NodeClient client, RestFilterChain filterChain) throws Exception;
    }

    private final AtomicInteger counter = new AtomicInteger();

    private class TestFilter extends RestFilter {
        private final int order;
        private final Callback callback;
        AtomicInteger runs = new AtomicInteger();
        volatile int executionToken = Integer.MAX_VALUE; //the filters that don't run will go last in the sorted list

        TestFilter(int order, Callback callback) {
            this.order = order;
            this.callback = callback;
        }

        @Override
        public void process(RestRequest request, RestChannel channel, NodeClient client, RestFilterChain filterChain) throws Exception {
            this.runs.incrementAndGet();
            this.executionToken = counter.incrementAndGet();
            this.callback.execute(request, channel, client, filterChain);
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public String toString() {
            return "[order:" + order + ", executionToken:" + executionToken + "]";
        }
    }

    private static class TestResponse extends RestResponse {
        @Override
        public String contentType() {
            return null;
        }

        @Override
        public BytesReference content() {
            return null;
        }

        @Override
        public RestStatus status() {
            return RestStatus.OK;
        }
    }
}
