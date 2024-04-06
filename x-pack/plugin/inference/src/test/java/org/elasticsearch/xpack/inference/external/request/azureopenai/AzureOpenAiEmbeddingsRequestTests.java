/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.external.request.azureopenai;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpPost;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.inference.common.Truncator;
import org.elasticsearch.xpack.inference.common.TruncatorTests;
import org.elasticsearch.xpack.inference.external.azureopenai.AzureOpenAiAccount;
import org.elasticsearch.xpack.inference.services.azureopenai.embeddings.AzureOpenAiEmbeddingsModelTests;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static org.elasticsearch.xpack.inference.external.http.Utils.entityAsMap;
import static org.elasticsearch.xpack.inference.external.request.azureopenai.AzureOpenAiUtils.API_KEY_HEADER;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class AzureOpenAiEmbeddingsRequestTests extends ESTestCase {
    public void testCreateRequest_WithApiKeyDefined() throws URISyntaxException, IOException {
        var request = createRequest("www.google.com", "resource", "deployment", "apiVersion", "apikey", null, "abc", "user");
        var httpRequest = request.createHttpRequest();

        assertThat(httpRequest.httpRequestBase(), instanceOf(HttpPost.class));
        var httpPost = (HttpPost) httpRequest.httpRequestBase();

        assertThat(httpPost.getURI().toString(), is("www.google.com"));
        assertThat(httpPost.getLastHeader(HttpHeaders.CONTENT_TYPE).getValue(), is(XContentType.JSON.mediaType()));
        assertThat(httpPost.getLastHeader(API_KEY_HEADER).getValue(), is("apikey"));

        var requestMap = entityAsMap(httpPost.getEntity().getContent());
        assertThat(requestMap, aMapWithSize(2));
        assertThat(requestMap.get("input"), is(List.of("abc")));
        assertThat(requestMap.get("user"), is("user"));
    }

    public void testCreateRequest_WithEntraIdDefined() throws URISyntaxException, IOException {
        var request = createRequest("www.google.com", "resource", "deployment", "apiVersion", null, "entraId", "abc", "user");
        var httpRequest = request.createHttpRequest();

        assertThat(httpRequest.httpRequestBase(), instanceOf(HttpPost.class));
        var httpPost = (HttpPost) httpRequest.httpRequestBase();

        assertThat(httpPost.getURI().toString(), is("www.google.com"));
        assertThat(httpPost.getLastHeader(HttpHeaders.CONTENT_TYPE).getValue(), is(XContentType.JSON.mediaType()));
        assertThat(httpPost.getLastHeader(HttpHeaders.AUTHORIZATION).getValue(), is("Bearer entraId"));

        var requestMap = entityAsMap(httpPost.getEntity().getContent());
        assertThat(requestMap, aMapWithSize(2));
        assertThat(requestMap.get("input"), is(List.of("abc")));
        assertThat(requestMap.get("user"), is("user"));
    }

    public void testTruncate_ReducesInputTextSizeByHalf() throws URISyntaxException, IOException {
        var request = createRequest("www.google.com", "resource", "deployment", "apiVersion", "apikey", null, "abcd", null);
        var truncatedRequest = request.truncate();

        var httpRequest = truncatedRequest.createHttpRequest();
        assertThat(httpRequest.httpRequestBase(), instanceOf(HttpPost.class));

        var httpPost = (HttpPost) httpRequest.httpRequestBase();
        var requestMap = entityAsMap(httpPost.getEntity().getContent());
        assertThat(requestMap, aMapWithSize(1));
        assertThat(requestMap.get("input"), is(List.of("ab")));
    }

    public void testIsTruncated_ReturnsTrue() {
        var request = createRequest("www.google.com", "resource", "deployment", "apiVersion", "apikey", null, "abcd", null);
        assertFalse(request.getTruncationInfo()[0]);

        var truncatedRequest = request.truncate();
        assertTrue(truncatedRequest.getTruncationInfo()[0]);
    }

    public static AzureOpenAiEmbeddingsRequest createRequest(
        String url,
        String resourceName,
        String deploymentId,
        String apiVersion,
        @Nullable String apiKey,
        @Nullable String entraId,
        String input,
        @Nullable String user
    ) {
        var embeddingsModel = AzureOpenAiEmbeddingsModelTests.createModel(
            resourceName,
            deploymentId,
            apiVersion,
            user,
            apiKey,
            entraId,
            "id"
        );
        var account = new AzureOpenAiAccount(
            embeddingsModel.getServiceSettings().resourceName(),
            embeddingsModel.getServiceSettings().deploymentId(),
            embeddingsModel.getServiceSettings().apiVersion(),
            embeddingsModel.getSecretSettings().apiKey(),
            embeddingsModel.getSecretSettings().entraId()
        );

        try {
            return new AzureOpenAiEmbeddingsRequest(
                TruncatorTests.createTruncator(),
                account,
                new Truncator.TruncationResult(List.of(input), new boolean[] { false }),
                embeddingsModel,
                new URI(url)
            );
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
