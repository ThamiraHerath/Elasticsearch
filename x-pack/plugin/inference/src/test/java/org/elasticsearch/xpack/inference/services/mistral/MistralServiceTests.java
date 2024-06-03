/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.mistral;

import org.apache.http.HttpHeaders;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.inference.ChunkedInferenceServiceResults;
import org.elasticsearch.inference.ChunkingOptions;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ModelSecrets;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.http.MockResponse;
import org.elasticsearch.test.http.MockWebServer;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.action.InferenceAction;
import org.elasticsearch.xpack.core.inference.results.ChunkedTextEmbeddingFloatResults;
import org.elasticsearch.xpack.inference.external.http.HttpClientManager;
import org.elasticsearch.xpack.inference.external.http.sender.HttpRequestSender;
import org.elasticsearch.xpack.inference.external.http.sender.HttpRequestSenderTests;
import org.elasticsearch.xpack.inference.external.http.sender.Sender;
import org.elasticsearch.xpack.inference.logging.ThrottlerManager;
import org.elasticsearch.xpack.inference.services.mistral.embeddings.MistralEmbeddingModelTests;
import org.elasticsearch.xpack.inference.services.mistral.embeddings.MistralEmbeddingsModel;
import org.elasticsearch.xpack.inference.services.mistral.embeddings.MistralEmbeddingsServiceSettings;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.xpack.inference.Utils.getInvalidModel;
import static org.elasticsearch.xpack.inference.Utils.inferenceUtilityPool;
import static org.elasticsearch.xpack.inference.Utils.mockClusterServiceEmpty;
import static org.elasticsearch.xpack.inference.external.http.Utils.entityAsMap;
import static org.elasticsearch.xpack.inference.external.http.Utils.getUrl;
import static org.elasticsearch.xpack.inference.services.ServiceComponentsTests.createWithEmptySettings;
import static org.elasticsearch.xpack.inference.services.mistral.MistralConstants.API_KEY_FIELD;
import static org.elasticsearch.xpack.inference.services.mistral.embeddings.MistralEmbeddingsServiceSettingsTests.createRequestSettingsMap;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class MistralServiceTests extends ESTestCase {
    private static final TimeValue TIMEOUT = new TimeValue(30, TimeUnit.SECONDS);
    private final MockWebServer webServer = new MockWebServer();
    private ThreadPool threadPool;
    private HttpClientManager clientManager;

    @Before
    public void init() throws Exception {
        webServer.start();
        threadPool = createThreadPool(inferenceUtilityPool());
        clientManager = HttpClientManager.create(Settings.EMPTY, threadPool, mockClusterServiceEmpty(), mock(ThrottlerManager.class));
    }

    @After
    public void shutdown() throws IOException {
        clientManager.close();
        terminate(threadPool);
        webServer.close();
    }

    public void testParseRequestConfig_CreatesAMistralEmbeddingsModel() throws IOException {
        try (var service = createService()) {
            ActionListener<Model> modelVerificationListener = ActionListener.wrap(model -> {
                assertThat(model, instanceOf(MistralEmbeddingsModel.class));

                var embeddingsModel = (MistralEmbeddingsModel) model;
                var serviceSettings = (MistralEmbeddingsServiceSettings) model.getServiceSettings();
                assertThat(serviceSettings.model(), is("mistral-embed"));
                assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
            }, exception -> fail("Unexpected exception: " + exception));

            service.parseRequestConfig(
                "id",
                TaskType.TEXT_EMBEDDING,
                getRequestConfigMap(
                    getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                    getEmbeddingsTaskSettingsMap(),
                    getSecretSettingsMap("secret")
                ),
                Set.of(),
                modelVerificationListener
            );
        }
    }

    public void testParseRequestConfig_ThrowsUnsupportedModelType() throws IOException {
        try (var service = createService()) {
            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(exception.getMessage(), is("The [mistral] service does not support task type [sparse_embedding]"));
                }
            );

            service.parseRequestConfig(
                "id",
                TaskType.SPARSE_EMBEDDING,
                getRequestConfigMap(
                    getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                    getEmbeddingsTaskSettingsMap(),
                    getSecretSettingsMap("secret")
                ),
                Set.of(),
                modelVerificationListener
            );
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInConfig() throws IOException {
        try (var service = createService()) {
            var config = getRequestConfigMap(
                getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                getEmbeddingsTaskSettingsMap(),
                getSecretSettingsMap("secret")
            );
            config.put("extra_key", "value");

            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(
                        exception.getMessage(),
                        is("Model configuration contains settings [{extra_key=value}] unknown to the [mistral] service")
                    );
                }
            );

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInEmbeddingTaskSettingsMap() throws IOException {
        try (var service = createService()) {
            var taskSettings = new HashMap<String, Object>();
            taskSettings.put("extra_key", "value");

            var config = getRequestConfigMap(
                getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                taskSettings,
                getSecretSettingsMap("secret")
            );

            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(
                        exception.getMessage(),
                        is("Model configuration contains settings [{extra_key=value}] unknown to the [mistral] service")
                    );
                }
            );

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInEmbeddingSecretSettingsMap() throws IOException {
        try (var service = createService()) {
            var secretSettings = getSecretSettingsMap("secret");
            secretSettings.put("extra_key", "value");

            var config = getRequestConfigMap(
                getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                getEmbeddingsTaskSettingsMap(),
                secretSettings
            );

            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(
                        exception.getMessage(),
                        is("Model configuration contains settings [{extra_key=value}] unknown to the [mistral] service")
                    );
                }
            );

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParsePersistedConfig_CreatesAMistralEmbeddingsModel() throws IOException {
        try (var service = createService()) {
            var config = getPersistedConfigMap(
                getEmbeddingsServiceSettingsMap("mistral-embed", 1024, 512, null),
                getEmbeddingsTaskSettingsMap(),
                getSecretSettingsMap("secret")
            );

            var model = service.parsePersistedConfigWithSecrets("id", TaskType.TEXT_EMBEDDING, config.config(), config.secrets());

            assertThat(model, instanceOf(MistralEmbeddingsModel.class));

            var embeddingsModel = (MistralEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().model(), is("mistral-embed"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(1024));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfig_ThrowsUnsupportedModelType() throws IOException {
        try (var service = createService()) {
            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(exception.getMessage(), is("The [mistral] service does not support task type [sparse_embedding]"));
                }
            );

            service.parseRequestConfig(
                "id",
                TaskType.SPARSE_EMBEDDING,
                getRequestConfigMap(
                    getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                    getEmbeddingsTaskSettingsMap(),
                    getSecretSettingsMap("secret")
                ),
                Set.of(),
                modelVerificationListener
            );
        }
    }

    public void testParsePersistedConfigWithSecrets_ThrowsErrorTryingToParseInvalidModel() throws IOException {
        try (var service = createService()) {
            var config = getPersistedConfigMap(
                getEmbeddingsServiceSettingsMap("mistral-embed", null, null, null),
                getEmbeddingsTaskSettingsMap(),
                getSecretSettingsMap("secret")
            );

            var thrownException = expectThrows(
                ElasticsearchStatusException.class,
                () -> service.parsePersistedConfigWithSecrets("id", TaskType.SPARSE_EMBEDDING, config.config(), config.secrets())
            );

            assertThat(
                thrownException.getMessage(),
                is("Failed to parse stored model [id] for [mistral] service, please delete and add the service again")
            );
        }
    }

    public void testParsePersistedConfig_DoesNotThrowWhenAnExtraKeyExistsInConfig() throws IOException {
        try (var service = createService()) {
            var serviceSettings = getEmbeddingsServiceSettingsMap("mistral-embed", 1024, 512, null);
            var taskSettings = getEmbeddingsTaskSettingsMap();
            var secretSettings = getSecretSettingsMap("secret");
            var config = getPersistedConfigMap(serviceSettings, taskSettings, secretSettings);
            config.config().put("extra_key", "value");

            var model = service.parsePersistedConfigWithSecrets("id", TaskType.TEXT_EMBEDDING, config.config(), config.secrets());

            assertThat(model, instanceOf(MistralEmbeddingsModel.class));
        }
    }

    public void testParsePersistedConfig_DoesNotThrowWhenExtraKeyExistsInEmbeddingServiceSettingsMap() throws IOException {
        try (var service = createService()) {
            var serviceSettings = getEmbeddingsServiceSettingsMap("mistral-embed", 1024, 512, null);
            serviceSettings.put("extra_key", "value");

            var taskSettings = getEmbeddingsTaskSettingsMap();
            var secretSettings = getSecretSettingsMap("secret");
            var config = getPersistedConfigMap(serviceSettings, taskSettings, secretSettings);

            var model = service.parsePersistedConfigWithSecrets("id", TaskType.TEXT_EMBEDDING, config.config(), config.secrets());

            assertThat(model, instanceOf(MistralEmbeddingsModel.class));
        }
    }

    public void testParsePersistedConfig_DoesNotThrowWhenAnExtraKeyExistsInEmbeddingTaskSettingsMap() throws IOException {
        try (var service = createService()) {
            var serviceSettings = getEmbeddingsServiceSettingsMap("mistral-embed", 1024, 512, null);
            var taskSettings = new HashMap<String, Object>();
            taskSettings.put("extra_key", "value");

            var secretSettings = getSecretSettingsMap("secret");
            var config = getPersistedConfigMap(serviceSettings, taskSettings, secretSettings);

            var model = service.parsePersistedConfigWithSecrets("id", TaskType.TEXT_EMBEDDING, config.config(), config.secrets());

            assertThat(model, instanceOf(MistralEmbeddingsModel.class));
        }
    }

    public void testParsePersistedConfig_DoesNotThrowWhenAnExtraKeyExistsInEmbeddingSecretSettingsMap() throws IOException {
        try (var service = createService()) {
            var serviceSettings = getEmbeddingsServiceSettingsMap("mistral-embed", 1024, 512, null);
            var taskSettings = getEmbeddingsTaskSettingsMap();
            var secretSettings = getSecretSettingsMap("secret");
            secretSettings.put("extra_key", "value");

            var config = getPersistedConfigMap(serviceSettings, taskSettings, secretSettings);

            var model = service.parsePersistedConfigWithSecrets("id", TaskType.TEXT_EMBEDDING, config.config(), config.secrets());

            assertThat(model, instanceOf(MistralEmbeddingsModel.class));
        }
    }

    public void testParsePersistedConfig_WithoutSecretsCreatesEmbeddingsModel() throws IOException {
        try (var service = createService()) {
            var config = getPersistedConfigMap(
                getEmbeddingsServiceSettingsMap("mistral-embed", 1024, 512, null),
                getEmbeddingsTaskSettingsMap(),
                Map.of()
            );

            var model = service.parsePersistedConfig("id", TaskType.TEXT_EMBEDDING, config.config());

            assertThat(model, instanceOf(MistralEmbeddingsModel.class));

            var embeddingsModel = (MistralEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().model(), is("mistral-embed"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(1024));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
        }
    }

    public void testCheckModelConfig_ForEmbeddingsModel_Works() throws IOException {
        var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

        try (var service = new MistralService(senderFactory, createWithEmptySettings(threadPool))) {
            webServer.enqueue(new MockResponse().setResponseCode(200).setBody(testEmbeddingResultJson));

            var model = MistralEmbeddingModelTests.createModel("id", "mistral-embed", "apikey", null, null, null, null);
            model.setURI(getUrl(webServer));

            PlainActionFuture<Model> listener = new PlainActionFuture<>();
            service.checkModelConfig(model, listener);

            var result = listener.actionGet(TIMEOUT);
            assertThat(
                result,
                is(MistralEmbeddingModelTests.createModel("id", "mistral-embed", "apikey", 2, null, SimilarityMeasure.DOT_PRODUCT, null))
            );

            assertThat(webServer.requests(), hasSize(1));

            var requestMap = entityAsMap(webServer.requests().get(0).getBody());
            MatcherAssert.assertThat(
                requestMap,
                Matchers.is(Map.of("input", List.of("how big"), "encoding_format", "float", "model", "mistral-embed"))
            );
        }
    }

    public void testInfer_ThrowsErrorWhenModelIsNotMistralEmbeddingsModel() throws IOException {
        var sender = mock(Sender.class);

        var factory = mock(HttpRequestSender.Factory.class);
        when(factory.createSender(anyString())).thenReturn(sender);

        var mockModel = getInvalidModel("model_id", "service_name");

        try (var service = new MistralService(factory, createWithEmptySettings(threadPool))) {
            PlainActionFuture<InferenceServiceResults> listener = new PlainActionFuture<>();
            service.infer(
                mockModel,
                null,
                List.of(""),
                new HashMap<>(),
                InputType.INGEST,
                InferenceAction.Request.DEFAULT_TIMEOUT,
                listener
            );

            var thrownException = expectThrows(ElasticsearchStatusException.class, () -> listener.actionGet(TIMEOUT));
            assertThat(
                thrownException.getMessage(),
                is("The internal model was invalid, please delete the service [service_name] with id [model_id] and add it again.")
            );

            verify(factory, times(1)).createSender(anyString());
            verify(sender, times(1)).start();
        }

        verify(sender, times(1)).close();
        verifyNoMoreInteractions(factory);
        verifyNoMoreInteractions(sender);
    }

    public void testChunkedInfer_Embeddings_CallsInfer_ConvertsFloatResponse() throws IOException, URISyntaxException {
        var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

        try (var service = new MistralService(senderFactory, createWithEmptySettings(threadPool))) {

            String responseJson = """
                {
                    "object": "list",
                    "data": [
                        {
                            "object": "embedding",
                            "index": 0,
                            "embedding": [
                                0.123,
                                -0.123
                            ]
                        },
                        {
                            "object": "embedding",
                            "index": 1,
                            "embedding": [
                                0.223,
                                -0.223
                            ]
                        }
                    ],
                    "model": "text-embedding-ada-002-v2",
                    "usage": {
                        "prompt_tokens": 8,
                        "total_tokens": 8
                    }
                }
                """;
            webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

            var model = MistralEmbeddingModelTests.createModel("id", "mistral-embed", "apikey", null, null, null, null);
            model.setURI(getUrl(webServer));

            PlainActionFuture<List<ChunkedInferenceServiceResults>> listener = new PlainActionFuture<>();
            service.chunkedInfer(
                model,
                List.of("abc", "def"),
                new HashMap<>(),
                InputType.INGEST,
                new ChunkingOptions(null, null),
                InferenceAction.Request.DEFAULT_TIMEOUT,
                listener
            );

            var results = listener.actionGet(TIMEOUT);

            assertThat(results, hasSize(2));
            {
                assertThat(results.get(0), CoreMatchers.instanceOf(ChunkedTextEmbeddingFloatResults.class));
                var floatResult = (ChunkedTextEmbeddingFloatResults) results.get(0);
                assertThat(floatResult.chunks(), hasSize(1));
                assertTrue(Arrays.equals(new float[] { 0.123f, -0.123f }, floatResult.chunks().get(0).embedding()));
            }
            {
                assertThat(results.get(1), CoreMatchers.instanceOf(ChunkedTextEmbeddingFloatResults.class));
                var floatResult = (ChunkedTextEmbeddingFloatResults) results.get(1);
                assertThat(floatResult.chunks(), hasSize(1));
                assertTrue(Arrays.equals(new float[] { 0.223f, -0.223f }, floatResult.chunks().get(0).embedding()));
            }

            assertThat(webServer.requests(), hasSize(1));
            assertNull(webServer.requests().get(0).getUri().getQuery());
            assertThat(webServer.requests().get(0).getHeader(HttpHeaders.CONTENT_TYPE), equalTo(XContentType.JSON.mediaType()));
            assertThat(webServer.requests().get(0).getHeader(HttpHeaders.AUTHORIZATION), equalTo("Bearer apikey"));

            var requestMap = entityAsMap(webServer.requests().get(0).getBody());
            assertThat(requestMap.size(), Matchers.is(3));
            assertThat(requestMap.get("input"), Matchers.is(List.of("abc", "def")));
            assertThat(requestMap.get("encoding_format"), Matchers.is("float"));
            assertThat(requestMap.get("model"), Matchers.is("mistral-embed"));
        }
    }

    public void testInfer_ThrowsWhenQueryIsPresent() throws IOException {
        var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

        try (var service = new MistralService(senderFactory, createWithEmptySettings(threadPool))) {
            webServer.enqueue(new MockResponse().setResponseCode(200).setBody(testEmbeddingResultJson));

            var model = MistralEmbeddingModelTests.createModel("id", "mistral-embed", "apikey", null, null, null, null);
            model.setURI(getUrl(webServer));

            PlainActionFuture<InferenceServiceResults> listener = new PlainActionFuture<>();
            UnsupportedOperationException exception = expectThrows(
                UnsupportedOperationException.class,
                () -> service.infer(
                    model,
                    "should throw",
                    List.of("abc"),
                    new HashMap<>(),
                    InputType.INGEST,
                    InferenceAction.Request.DEFAULT_TIMEOUT,
                    listener
                )
            );

            assertThat(exception.getMessage(), is("Mistral service does not support inference with query input"));
        }
    }

    public void testInfer_UnauthorisedResponse() throws IOException {
        var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

        try (var service = new MistralService(senderFactory, createWithEmptySettings(threadPool))) {

            String responseJson = """
                {
                    "error": {
                        "message": "Incorrect API key provided:",
                        "type": "invalid_request_error",
                        "param": null,
                        "code": "invalid_api_key"
                    }
                }
                """;
            webServer.enqueue(new MockResponse().setResponseCode(401).setBody(responseJson));

            var model = MistralEmbeddingModelTests.createModel("id", "mistral-embed", "apikey", null, null, null, null);
            model.setURI(getUrl(webServer));

            PlainActionFuture<InferenceServiceResults> listener = new PlainActionFuture<>();
            service.infer(
                model,
                null,
                List.of("abc"),
                new HashMap<>(),
                InputType.INGEST,
                InferenceAction.Request.DEFAULT_TIMEOUT,
                listener
            );

            var error = expectThrows(ElasticsearchException.class, () -> listener.actionGet(TIMEOUT));
            assertThat(error.getMessage(), containsString("Received an authentication error status code for request"));
            assertThat(error.getMessage(), containsString("Error message: [Incorrect API key provided:]"));
            assertThat(webServer.requests(), hasSize(1));
        }
    }

    // ----------------------------------------------------------------

    private MistralService createService() {
        return new MistralService(mock(HttpRequestSender.Factory.class), createWithEmptySettings(threadPool));
    }

    private Map<String, Object> getRequestConfigMap(
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        Map<String, Object> secretSettings
    ) {
        var builtServiceSettings = new HashMap<>();
        builtServiceSettings.putAll(serviceSettings);
        builtServiceSettings.putAll(secretSettings);

        return new HashMap<>(
            Map.of(ModelConfigurations.SERVICE_SETTINGS, builtServiceSettings, ModelConfigurations.TASK_SETTINGS, taskSettings)
        );
    }

    private record PeristedConfigRecord(Map<String, Object> config, Map<String, Object> secrets) {}

    private PeristedConfigRecord getPersistedConfigMap(
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        Map<String, Object> secretSettings
    ) {

        return new PeristedConfigRecord(
            new HashMap<>(Map.of(ModelConfigurations.SERVICE_SETTINGS, serviceSettings, ModelConfigurations.TASK_SETTINGS, taskSettings)),
            new HashMap<>(Map.of(ModelSecrets.SECRET_SETTINGS, secretSettings))
        );
    }

    private PeristedConfigRecord getPersistedConfigMap(Map<String, Object> serviceSettings, Map<String, Object> taskSettings) {

        return new PeristedConfigRecord(
            new HashMap<>(Map.of(ModelConfigurations.SERVICE_SETTINGS, serviceSettings, ModelConfigurations.TASK_SETTINGS, taskSettings)),
            null
        );
    }

    private static Map<String, Object> getEmbeddingsServiceSettingsMap(
        String model,
        @Nullable Integer dimensions,
        @Nullable Integer maxTokens,
        @Nullable SimilarityMeasure similarityMeasure
    ) {
        return createRequestSettingsMap(model, dimensions, maxTokens, similarityMeasure);
    }

    private static Map<String, Object> getEmbeddingsTaskSettingsMap() {
        // no task settings for Mistral embeddings
        return Map.of();
    }

    private static Map<String, Object> getSecretSettingsMap(String apiKey) {
        return new HashMap<>(Map.of(API_KEY_FIELD, apiKey));
    }

    private static final String testEmbeddingResultJson = """
        {
          "object": "list",
          "data": [
              {
                  "object": "embedding",
                  "index": 0,
                  "embedding": [
                      0.0123,
                      -0.0123
                  ]
              }
          ],
          "model": "text-embedding-ada-002-v2",
          "usage": {
              "prompt_tokens": 8,
              "total_tokens": 8
          }
        }
        """;

}
