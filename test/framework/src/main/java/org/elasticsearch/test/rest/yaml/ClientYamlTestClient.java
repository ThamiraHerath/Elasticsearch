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
package org.elasticsearch.test.rest.yaml;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.NodeSelector;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.test.rest.yaml.restspec.ClientYamlSuiteRestApi;
import org.elasticsearch.test.rest.yaml.restspec.ClientYamlSuiteRestPath;
import org.elasticsearch.test.rest.yaml.restspec.ClientYamlSuiteRestSpec;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Used by {@link ESClientYamlSuiteTestCase} to execute REST requests according to the tests written in yaml suite files. Wraps a
 * {@link RestClient} instance used to send the REST requests. Holds the {@link ClientYamlSuiteRestSpec} used to translate api calls into
 * REST calls.
 */
public class ClientYamlTestClient implements Closeable {
    private static final Logger logger = Loggers.getLogger(ClientYamlTestClient.class);

    private static final ContentType YAML_CONTENT_TYPE = ContentType.create("application/yaml");

    private final ClientYamlSuiteRestSpec restSpec;
    private final Map<NodeSelector, RestClient> restClients = new HashMap<>();
    private final Version esVersion;
    private final Version masterVersion;
    private final CheckedConsumer<RestClientBuilder, IOException> clientBuilderConsumer;

    ClientYamlTestClient(
            final ClientYamlSuiteRestSpec restSpec,
            final RestClient restClient,
            final List<HttpHost> hosts,
            final Version esVersion,
            final Version masterVersion,
            final CheckedConsumer<RestClientBuilder, IOException> clientBuilderConsumer) {
        assert hosts.size() > 0;
        this.restSpec = restSpec;
        this.restClients.put(NodeSelector.ANY, restClient);
        this.esVersion = esVersion;
        this.masterVersion = masterVersion;
        this.clientBuilderConsumer = clientBuilderConsumer;
    }

    public Version getEsVersion() {
        return esVersion;
    }

    public Version getMasterVersion() {
        return masterVersion;
    }

    /**
     * Calls an api with the provided parameters and body
     */
    public ClientYamlTestResponse callApi(String apiName, Map<String, String> params, HttpEntity entity,
            Map<String, String> headers, NodeSelector nodeSelector) throws IOException {

        ClientYamlSuiteRestApi restApi = restApi(apiName);

        //divide params between ones that go within query string and ones that go within path
        Map<String, String> pathParts = new HashMap<>();
        Map<String, String> queryStringParams = new HashMap<>();

        Set<String> apiRequiredPathParts = restApi.getPathParts().entrySet().stream().filter(Entry::getValue).map(Entry::getKey)
                .collect(Collectors.toSet());
        Set<String> apiRequiredParameters = restApi.getParams().entrySet().stream().filter(Entry::getValue).map(Entry::getKey)
                .collect(Collectors.toSet());

        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (restApi.getPathParts().containsKey(entry.getKey())) {
                pathParts.put(entry.getKey(), entry.getValue());
                apiRequiredPathParts.remove(entry.getKey());
            } else if (restApi.getParams().containsKey(entry.getKey())
                    || restSpec.isGlobalParameter(entry.getKey())
                    || restSpec.isClientParameter(entry.getKey())) {
                queryStringParams.put(entry.getKey(), entry.getValue());
                apiRequiredParameters.remove(entry.getKey());
            } else {
                throw new IllegalArgumentException(
                        "path/param [" + entry.getKey() + "] not supported by [" + restApi.getName() + "] " + "api");
            }
        }

        if (false == apiRequiredPathParts.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing required path part: " + apiRequiredPathParts + " by [" + restApi.getName() + "] api");
        }
        if (false == apiRequiredParameters.isEmpty()) {
            throw new IllegalArgumentException(
                    "missing required parameter: " + apiRequiredParameters + " by [" + restApi.getName() + "] api");
        }

        List<String> supportedMethods = restApi.getSupportedMethods(pathParts.keySet());
        String requestMethod;
        if (entity != null) {
            if (false == restApi.isBodySupported()) {
                throw new IllegalArgumentException("body is not supported by [" + restApi.getName() + "] api");
            }
            String contentType = entity.getContentType().getValue();
            //randomly test the GET with source param instead of GET/POST with body
            if (sendBodyAsSourceParam(supportedMethods, contentType, entity.getContentLength())) {
                logger.debug("sending the request body as source param with GET method");
                queryStringParams.put("source", EntityUtils.toString(entity));
                queryStringParams.put("source_content_type", contentType);
                requestMethod = HttpGet.METHOD_NAME;
                entity = null;
            } else {
                requestMethod = RandomizedTest.randomFrom(supportedMethods);
            }
        } else {
            if (restApi.isBodyRequired()) {
                throw new IllegalArgumentException("body is required by [" + restApi.getName() + "] api");
            }
            requestMethod = RandomizedTest.randomFrom(supportedMethods);
        }

        //the rest path to use is randomized out of the matching ones (if more than one)
        ClientYamlSuiteRestPath restPath = RandomizedTest.randomFrom(restApi.getFinalPaths(pathParts));
        //Encode rules for path and query string parameters are different. We use URI to encode the path.
        //We need to encode each path part separately, as each one might contain slashes that need to be escaped, which needs to
        //be done manually.
        String requestPath;
        if (restPath.getPathParts().length == 0) {
            requestPath = "/";
        } else {
            StringBuilder finalPath = new StringBuilder();
            for (String pathPart : restPath.getPathParts()) {
                try {
                    finalPath.append('/');
                    // We prepend "/" to the path part to handle parts that start with - or other invalid characters
                    URI uri = new URI(null, null, null, -1, "/" + pathPart, null, null);
                    //manually escape any slash that each part may contain
                    finalPath.append(uri.getRawPath().substring(1).replaceAll("/", "%2F"));
                } catch (URISyntaxException e) {
                    throw new RuntimeException("unable to build uri", e);
                }
            }
            requestPath = finalPath.toString();
        }

        logger.debug("calling api [{}]", apiName);
        Request request = new Request(requestMethod, requestPath);
        for (Map.Entry<String, String> param : queryStringParams.entrySet()) {
            request.addParameter(param.getKey(), param.getValue());
        }
        request.setEntity(entity);
        setOptions(request, headers);

        try {
            Response response = getRestClient(nodeSelector).performRequest(request);
            return new ClientYamlTestResponse(response);
        } catch(ResponseException e) {
            throw new ClientYamlTestResponseException(e);
        }
    }

    protected RestClient getRestClient(NodeSelector nodeSelector) {
        //lazily build a new client in case we need to point to some specific node
        return restClients.computeIfAbsent(nodeSelector, selector -> {
            RestClient anyClient = restClients.get(NodeSelector.ANY);
            RestClientBuilder builder = RestClient.builder(anyClient.getNodes().toArray(new Node[0]));
            try {
                clientBuilderConsumer.accept(builder);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            builder.setNodeSelector(selector);
            return builder.build();
        });
    }

    protected static void setOptions(Request request, Map<String, String> headers) {
        RequestOptions.Builder options = request.getOptions().toBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            logger.debug("Adding header {} with value {}", header.getKey(), header.getValue());
            options.addHeader(header.getKey(), header.getValue());
        }
        request.setOptions(options);
    }

    private static boolean sendBodyAsSourceParam(List<String> supportedMethods, String contentType, long contentLength) {
        if (false == supportedMethods.contains(HttpGet.METHOD_NAME)) {
            // The API doesn't claim to support GET anyway
            return false;
        }
        if (contentLength < 0) {
            // Negative length means "unknown" or "huge" in this case. Either way we can't send it as a parameter
            return false;
        }
        if (contentLength > 2000) {
            // Long bodies won't fit in the parameter and will cause a too_long_frame_exception
            return false;
        }
        if (false == contentType.startsWith(ContentType.APPLICATION_JSON.getMimeType())
                && false == contentType.startsWith(YAML_CONTENT_TYPE.getMimeType())) {
            // We can only encode JSON or YAML this way.
            return false;
        }
        return RandomizedTest.rarely();
    }

    private ClientYamlSuiteRestApi restApi(String apiName) {
        ClientYamlSuiteRestApi restApi = restSpec.getApi(apiName);
        if (restApi == null) {
            throw new IllegalArgumentException("rest api [" + apiName + "] doesn't exist in the rest spec");
        }
        return restApi;
    }

    @Override
    public void close() throws IOException {
        for (RestClient restClient : restClients.values()) {
            restClient.close();
        }
    }
}
