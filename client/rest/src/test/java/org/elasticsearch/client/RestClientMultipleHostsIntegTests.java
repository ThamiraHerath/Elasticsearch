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

package org.elasticsearch.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.mocksocket.MockHttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.client.RestClientTestUtil.getAllStatusCodes;
import static org.elasticsearch.client.RestClientTestUtil.randomErrorNoRetryStatusCode;
import static org.elasticsearch.client.RestClientTestUtil.randomOkStatusCode;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test to check interaction between {@link RestClient} and {@link org.apache.http.client.HttpClient}.
 * Works against real http servers, multiple hosts. Also tests failover by randomly shutting down hosts.
 */
public class RestClientMultipleHostsIntegTests extends RestClientTestCase {

    private static HttpServer[] httpServers;
    private static HttpHost[] httpHosts;
    private static boolean stoppedFirstHost = false;
    private static String pathPrefixWithoutLeadingSlash;
    private static String pathPrefix;
    private static RestClient restClient;

    @BeforeClass
    public static void startHttpServer() throws Exception {
        if (randomBoolean()) {
            pathPrefixWithoutLeadingSlash = "testPathPrefix/" + randomAsciiLettersOfLengthBetween(1, 5);
            pathPrefix = "/" + pathPrefixWithoutLeadingSlash;
        } else {
            pathPrefix = pathPrefixWithoutLeadingSlash = "";
        }
        int numHttpServers = randomIntBetween(2, 4);
        httpServers = new HttpServer[numHttpServers];
        httpHosts = new HttpHost[numHttpServers];
        for (int i = 0; i < numHttpServers; i++) {
            HttpServer httpServer = createHttpServer();
            httpServers[i] = httpServer;
            httpHosts[i] = new HttpHost(httpServer.getAddress().getHostString(), httpServer.getAddress().getPort());
        }
        restClient = buildRestClient();
    }

    private static RestClient buildRestClient() {
        RestClientBuilder restClientBuilder = RestClient.builder(httpHosts);
        if (pathPrefix.length() > 0) {
            restClientBuilder.setPathPrefix((randomBoolean() ? "/" : "") + pathPrefixWithoutLeadingSlash);
        }
        return restClientBuilder.build();
    }

    private static HttpServer createHttpServer() throws Exception {
        HttpServer httpServer = MockHttpServer.createHttp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.start();
        //returns a different status code depending on the path
        for (int statusCode : getAllStatusCodes()) {
            httpServer.createContext(pathPrefix + "/" + statusCode, new ResponseHandler(statusCode));
        }
        return httpServer;
    }

    private static class ResponseHandler implements HttpHandler {
        private final int statusCode;

        ResponseHandler(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            httpExchange.getRequestBody().close();
            httpExchange.sendResponseHeaders(statusCode, -1);
            httpExchange.close();
        }
    }

    @AfterClass
    public static void stopHttpServers() throws IOException {
        restClient.close();
        restClient = null;
        for (HttpServer httpServer : httpServers) {
            httpServer.stop(0);
        }
        httpServers = null;
    }

    @Before
    public void stopRandomHost() {
        //verify that shutting down some hosts doesn't matter as long as one working host is left behind
        if (httpServers.length > 1 && randomBoolean()) {
            List<HttpServer> updatedHttpServers = new ArrayList<>(httpServers.length - 1);
            int nodeIndex = randomInt(httpServers.length - 1);
            if (0 == nodeIndex) {
                stoppedFirstHost = true;
            }
            for (int i = 0; i < httpServers.length; i++) {
                HttpServer httpServer = httpServers[i];
                if (i == nodeIndex) {
                    httpServer.stop(0);
                } else {
                    updatedHttpServers.add(httpServer);
                }
            }
            httpServers = updatedHttpServers.toArray(new HttpServer[updatedHttpServers.size()]);
        }
    }

    public void testSyncRequests() throws IOException {
        int numRequests = randomIntBetween(5, 20);
        for (int i = 0; i < numRequests; i++) {
            final String method = RestClientTestUtil.randomHttpMethod(getRandom());
            //we don't test status codes that are subject to retries as they interfere with hosts being stopped
            final int statusCode = randomBoolean() ? randomOkStatusCode(getRandom()) : randomErrorNoRetryStatusCode(getRandom());
            Response response;
            try {
                response = restClient.performRequest(new Request(method, "/" + statusCode));
            } catch(ResponseException responseException) {
                response = responseException.getResponse();
            }
            assertEquals(method, response.getRequestLine().getMethod());
            assertEquals(statusCode, response.getStatusLine().getStatusCode());
            assertEquals((pathPrefix.length() > 0 ? pathPrefix : "") + "/" + statusCode, response.getRequestLine().getUri());
        }
    }

    public void testAsyncRequests() throws Exception {
        int numRequests = randomIntBetween(5, 20);
        final CountDownLatch latch = new CountDownLatch(numRequests);
        final List<TestResponse> responses = new CopyOnWriteArrayList<>();
        for (int i = 0; i < numRequests; i++) {
            final String method = RestClientTestUtil.randomHttpMethod(getRandom());
            //we don't test status codes that are subject to retries as they interfere with hosts being stopped
            final int statusCode = randomBoolean() ? randomOkStatusCode(getRandom()) : randomErrorNoRetryStatusCode(getRandom());
            restClient.performRequestAsync(new Request(method, "/" + statusCode), new ResponseListener() {
                @Override
                public void onSuccess(Response response) {
                    responses.add(new TestResponse(method, statusCode, response));
                    latch.countDown();
                }

                @Override
                public void onFailure(Exception exception) {
                    responses.add(new TestResponse(method, statusCode, exception));
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(numRequests, responses.size());
        for (TestResponse testResponse : responses) {
            Response response = testResponse.getResponse();
            assertEquals(testResponse.method, response.getRequestLine().getMethod());
            assertEquals(testResponse.statusCode, response.getStatusLine().getStatusCode());
            assertEquals((pathPrefix.length() > 0 ? pathPrefix : "") + "/" + testResponse.statusCode,
                    response.getRequestLine().getUri());
        }
    }

    /**
     * Test host selector against a real server <strong>and</strong>
     * test what happens after calling
     */
    public void testNodeSelector() throws IOException {
        Request request = new Request("GET", "/200");
        RequestOptions.Builder options = request.getOptions().toBuilder();
        options.setNodeSelector(firstPositionNodeSelector());
        request.setOptions(options);
        int rounds = between(1, 10);
        for (int i = 0; i < rounds; i++) {
            /*
             * Run the request more than once to verify that the
             * NodeSelector overrides the round robin behavior.
             */
            if (stoppedFirstHost) {
                try {
                    restClient.performRequest(request);
                    fail("expected to fail to connect");
                } catch (ConnectException e) {
                    // This is different in windows and linux but this matches both.
                    assertThat(e.getMessage(), startsWith("Connection refused"));
                }
            } else {
                Response response = restClient.performRequest(request);
                assertEquals(httpHosts[0], response.getHost());
            }
        }
    }

    private static class TestResponse {
        private final String method;
        private final int statusCode;
        private final Object response;

        TestResponse(String method, int statusCode, Object response) {
            this.method = method;
            this.statusCode = statusCode;
            this.response = response;
        }

        Response getResponse() {
            if (response instanceof Response) {
                return (Response) response;
            }
            if (response instanceof ResponseException) {
                return ((ResponseException) response).getResponse();
            }
            throw new AssertionError("unexpected response " + response.getClass());
        }
    }

    private NodeSelector firstPositionNodeSelector() {
        return new NodeSelector() {
            @Override
            public void select(Iterable<Node> nodes) {
                for (Iterator<Node> itr = nodes.iterator(); itr.hasNext();) {
                    if (httpHosts[0] != itr.next().getHost()) {
                        itr.remove();
                    }
                }
            }
        };
    }
}
