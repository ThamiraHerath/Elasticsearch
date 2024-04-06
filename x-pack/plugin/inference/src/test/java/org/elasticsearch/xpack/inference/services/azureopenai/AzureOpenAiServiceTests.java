/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 *
 * this file was contributed to by a generative AI
 */

package org.elasticsearch.xpack.inference.services.azureopenai;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ModelSecrets;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.http.MockWebServer;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.inference.external.http.HttpClientManager;
import org.elasticsearch.xpack.inference.external.http.sender.HttpRequestSender;
import org.elasticsearch.xpack.inference.external.http.sender.Sender;
import org.elasticsearch.xpack.inference.logging.ThrottlerManager;
import org.elasticsearch.xpack.inference.services.azureopenai.embeddings.AzureOpenAiEmbeddingsModel;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.xpack.inference.Utils.inferenceUtilityPool;
import static org.elasticsearch.xpack.inference.Utils.mockClusterServiceEmpty;
import static org.elasticsearch.xpack.inference.services.ServiceComponentsTests.createWithEmptySettings;
import static org.elasticsearch.xpack.inference.services.Utils.getInvalidModel;
import static org.elasticsearch.xpack.inference.services.azureopenai.embeddings.AzureOpenAiEmbeddingsServiceSettingsTests.getAzureOpenAiServiceSettingsMap;
import static org.elasticsearch.xpack.inference.services.azureopenai.embeddings.AzureOpenAiEmbeddingsTaskSettingsTests.getAzureOpenAiRequestTaskSettingsMap;
import static org.elasticsearch.xpack.inference.services.settings.AzureOpenAiSecretSettingsTests.getAzureOpenAiSecretSettingsMap;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AzureOpenAiServiceTests extends ESTestCase {
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

    public void testParseRequestConfig_CreatesAnOpenAiEmbeddingsModel() throws IOException {
        try (var service = createAzureOpenAiService()) {
            ActionListener<Model> modelVerificationListener = ActionListener.wrap(model -> {
                assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

                var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
                assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
                assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
                assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
                assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
                assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            }, exception -> fail("Unexpected exception: " + exception));

            service.parseRequestConfig(
                "id",
                TaskType.TEXT_EMBEDDING,
                getRequestConfigMap(
                    getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                    getAzureOpenAiRequestTaskSettingsMap("user"),
                    getAzureOpenAiSecretSettingsMap("secret", null)
                ),
                Set.of(),
                modelVerificationListener
            );
        }
    }

    public void testParseRequestConfig_ThrowsUnsupportedModelType() throws IOException {
        try (var service = createAzureOpenAiService()) {
            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(exception.getMessage(), is("The [azureopenai] service does not support task type [sparse_embedding]"));
                }
            );

            service.parseRequestConfig(
                "id",
                TaskType.SPARSE_EMBEDDING,
                getRequestConfigMap(
                    getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                    getAzureOpenAiRequestTaskSettingsMap("user"),
                    getAzureOpenAiSecretSettingsMap("secret", null)
                ),
                Set.of(),
                modelVerificationListener
            );
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInConfig() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var config = getRequestConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );
            config.put("extra_key", "value");

            ActionListener<Model> modelVerificationListener = ActionListener.wrap(
                model -> fail("Expected exception, but got model: " + model),
                exception -> {
                    assertThat(exception, instanceOf(ElasticsearchStatusException.class));
                    assertThat(
                        exception.getMessage(),
                        is("Model configuration contains settings [{extra_key=value}] unknown to the [azureopenai] service")
                    );
                }
            );

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInServiceSettingsMap() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var serviceSettings = getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null);
            serviceSettings.put("extra_key", "value");

            var config = getRequestConfigMap(
                serviceSettings,
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );

            ActionListener<Model> modelVerificationListener = ActionListener.<Model>wrap((model) -> {
                fail("Expected exception, but got model: " + model);
            }, e -> {
                assertThat(e, instanceOf(ElasticsearchStatusException.class));
                assertThat(
                    e.getMessage(),
                    is("Model configuration contains settings [{extra_key=value}] unknown to the [azureopenai] service")
                );
            });

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInTaskSettingsMap() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var taskSettingsMap = getAzureOpenAiRequestTaskSettingsMap("user");
            taskSettingsMap.put("extra_key", "value");

            var config = getRequestConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                taskSettingsMap,
                getAzureOpenAiSecretSettingsMap("secret", null)
            );

            ActionListener<Model> modelVerificationListener = ActionListener.<Model>wrap((model) -> {
                fail("Expected exception, but got model: " + model);
            }, e -> {
                assertThat(e, instanceOf(ElasticsearchStatusException.class));
                assertThat(
                    e.getMessage(),
                    is("Model configuration contains settings [{extra_key=value}] unknown to the [azureopenai] service")
                );
            });

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParseRequestConfig_ThrowsWhenAnExtraKeyExistsInSecretSettingsMap() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var secretSettingsMap = getAzureOpenAiSecretSettingsMap("secret", null);
            secretSettingsMap.put("extra_key", "value");

            var config = getRequestConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                secretSettingsMap
            );

            ActionListener<Model> modelVerificationListener = ActionListener.<Model>wrap((model) -> {
                fail("Expected exception, but got model: " + model);
            }, e -> {
                assertThat(e, instanceOf(ElasticsearchStatusException.class));
                assertThat(
                    e.getMessage(),
                    is("Model configuration contains settings [{extra_key=value}] unknown to the [azureopenai] service")
                );
            });

            service.parseRequestConfig("id", TaskType.TEXT_EMBEDDING, config, Set.of(), modelVerificationListener);
        }
    }

    public void testParseRequestConfig_MovesModel() throws IOException {
        try (var service = createAzureOpenAiService()) {
            ActionListener<Model> modelVerificationListener = ActionListener.wrap(model -> {
                assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

                var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
                assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
                assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
                assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
                assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
                assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            }, exception -> fail("Unexpected exception: " + exception));

            service.parseRequestConfig(
                "id",
                TaskType.TEXT_EMBEDDING,
                getRequestConfigMap(
                    getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                    getAzureOpenAiRequestTaskSettingsMap("user"),
                    getAzureOpenAiSecretSettingsMap("secret", null)
                ),
                Set.of(),
                modelVerificationListener
            );
        }
    }

    public void testParsePersistedConfigWithSecrets_CreatesAnAzureOpenAiEmbeddingsModel() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", 100, "float", 512),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );

            var model = service.parsePersistedConfigWithSecrets(
                "id",
                TaskType.TEXT_EMBEDDING,
                persistedConfig.config(),
                persistedConfig.secrets()
            );

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(100));
            assertThat(embeddingsModel.getServiceSettings().encodingFormat(), is("float"));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfigWithSecrets_ThrowsErrorTryingToParseInvalidModel() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );

            var thrownException = expectThrows(
                ElasticsearchStatusException.class,
                () -> service.parsePersistedConfigWithSecrets(
                    "id",
                    TaskType.SPARSE_EMBEDDING,
                    persistedConfig.config(),
                    persistedConfig.secrets()
                )
            );

            assertThat(
                thrownException.getMessage(),
                is("Failed to parse stored model [id] for [azureopenai] service, please delete and add the service again")
            );
        }
    }

    public void testParsePersistedConfigWithSecrets_DoesNotThrowWhenAnExtraKeyExistsInConfig() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", 100, "float", 512),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );
            persistedConfig.config().put("extra_key", "value");

            var model = service.parsePersistedConfigWithSecrets(
                "id",
                TaskType.TEXT_EMBEDDING,
                persistedConfig.config(),
                persistedConfig.secrets()
            );

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(100));
            assertThat(embeddingsModel.getServiceSettings().encodingFormat(), is("float"));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfigWithSecrets_DoesNotThrowWhenAnExtraKeyExistsInSecretsSettings() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var secretSettingsMap = getAzureOpenAiSecretSettingsMap("secret", null);
            secretSettingsMap.put("extra_key", "value");

            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", 100, "float", 512),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                secretSettingsMap
            );

            var model = service.parsePersistedConfigWithSecrets(
                "id",
                TaskType.TEXT_EMBEDDING,
                persistedConfig.config(),
                persistedConfig.secrets()
            );

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(100));
            assertThat(embeddingsModel.getServiceSettings().encodingFormat(), is("float"));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfigWithSecrets_NotThrowWhenAnExtraKeyExistsInSecrets() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", 100, "float", 512),
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );
            persistedConfig.secrets.put("extra_key", "value");

            var model = service.parsePersistedConfigWithSecrets(
                "id",
                TaskType.TEXT_EMBEDDING,
                persistedConfig.config(),
                persistedConfig.secrets()
            );

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(100));
            assertThat(embeddingsModel.getServiceSettings().encodingFormat(), is("float"));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfigWithSecrets_NotThrowWhenAnExtraKeyExistsInServiceSettings() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var serviceSettingsMap = getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", 100, "float", 512);
            serviceSettingsMap.put("extra_key", "value");

            var persistedConfig = getPersistedConfigMap(
                serviceSettingsMap,
                getAzureOpenAiRequestTaskSettingsMap("user"),
                getAzureOpenAiSecretSettingsMap("secret", null)
            );

            var model = service.parsePersistedConfigWithSecrets(
                "id",
                TaskType.TEXT_EMBEDDING,
                persistedConfig.config(),
                persistedConfig.secrets()
            );

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(100));
            assertThat(embeddingsModel.getServiceSettings().encodingFormat(), is("float"));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfigWithSecrets_NotThrowWhenAnExtraKeyExistsInTaskSettings() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var taskSettingsMap = getAzureOpenAiRequestTaskSettingsMap("user");
            taskSettingsMap.put("extra_key", "value");

            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", 100, "float", 512),
                taskSettingsMap,
                getAzureOpenAiSecretSettingsMap("secret", null)
            );

            var model = service.parsePersistedConfigWithSecrets(
                "id",
                TaskType.TEXT_EMBEDDING,
                persistedConfig.config(),
                persistedConfig.secrets()
            );

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getServiceSettings().dimensions(), is(100));
            assertThat(embeddingsModel.getServiceSettings().encodingFormat(), is("float"));
            assertThat(embeddingsModel.getServiceSettings().maxInputTokens(), is(512));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertThat(embeddingsModel.getSecretSettings().apiKey().toString(), is("secret"));
        }
    }

    public void testParsePersistedConfig_CreatesAnAzureOpenAiEmbeddingsModel() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                getAzureOpenAiRequestTaskSettingsMap("user")
            );

            var model = service.parsePersistedConfig("id", TaskType.TEXT_EMBEDDING, persistedConfig.config());

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertNull(embeddingsModel.getSecretSettings());
        }
    }

    public void testParsePersistedConfig_ThrowsErrorTryingToParseInvalidModel() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                getAzureOpenAiRequestTaskSettingsMap("user")
            );

            var thrownException = expectThrows(
                ElasticsearchStatusException.class,
                () -> service.parsePersistedConfig("id", TaskType.SPARSE_EMBEDDING, persistedConfig.config())
            );

            assertThat(
                thrownException.getMessage(),
                is("Failed to parse stored model [id] for [azureopenai] service, please delete and add the service again")
            );
        }
    }

    public void testParsePersistedConfig_DoesNotThrowWhenAnExtraKeyExistsInConfig() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                getAzureOpenAiRequestTaskSettingsMap("user")
            );
            persistedConfig.config().put("extra_key", "value");

            var model = service.parsePersistedConfig("id", TaskType.TEXT_EMBEDDING, persistedConfig.config());

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertNull(embeddingsModel.getSecretSettings());
        }
    }

    public void testParsePersistedConfig_NotThrowWhenAnExtraKeyExistsInServiceSettings() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var serviceSettingsMap = getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null);
            serviceSettingsMap.put("extra_key", "value");

            var persistedConfig = getPersistedConfigMap(serviceSettingsMap, getAzureOpenAiRequestTaskSettingsMap("user"));

            var model = service.parsePersistedConfig("id", TaskType.TEXT_EMBEDDING, persistedConfig.config());

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertNull(embeddingsModel.getSecretSettings());
        }
    }

    public void testParsePersistedConfig_NotThrowWhenAnExtraKeyExistsInTaskSettings() throws IOException {
        try (var service = createAzureOpenAiService()) {
            var taskSettingsMap = getAzureOpenAiRequestTaskSettingsMap("user");
            taskSettingsMap.put("extra_key", "value");

            var persistedConfig = getPersistedConfigMap(
                getAzureOpenAiServiceSettingsMap("resource_name", "deployment_id", "api_version", null, null, null),
                taskSettingsMap
            );

            var model = service.parsePersistedConfig("id", TaskType.TEXT_EMBEDDING, persistedConfig.config());

            assertThat(model, instanceOf(AzureOpenAiEmbeddingsModel.class));

            var embeddingsModel = (AzureOpenAiEmbeddingsModel) model;
            assertThat(embeddingsModel.getServiceSettings().resourceName(), is("resource_name"));
            assertThat(embeddingsModel.getServiceSettings().deploymentId(), is("deployment_id"));
            assertThat(embeddingsModel.getServiceSettings().apiVersion(), is("api_version"));
            assertThat(embeddingsModel.getTaskSettings().user(), is("user"));
            assertNull(embeddingsModel.getSecretSettings());
        }
    }

    public void testInfer_ThrowsErrorWhenModelIsNotAzureOpenAiModel() throws IOException {
        var sender = mock(Sender.class);

        var factory = mock(HttpRequestSender.Factory.class);
        when(factory.createSender(anyString())).thenReturn(sender);

        var mockModel = getInvalidModel("model_id", "service_name");

        try (var service = new AzureOpenAiService(factory, createWithEmptySettings(threadPool))) {
            PlainActionFuture<InferenceServiceResults> listener = new PlainActionFuture<>();
            service.infer(mockModel, null, List.of(""), new HashMap<>(), InputType.INGEST, listener);

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

    /*
    public void testInfer_SendsRequest() throws IOException {
        var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

        try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

            String responseJson = """
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
            webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

            // TODO - override URL as Azure will define its own...
            // TODO - override URL as Azure will define its own...

            var model = AzureOpenAiEmbeddingsModelTests.createModel("resource", "deployment", "apiversion", "user", "apikey", "id");
            PlainActionFuture<InferenceServiceResults> listener = new PlainActionFuture<>();
            service.infer(model, null, List.of("abc"), new HashMap<>(), InputType.INGEST, listener);

            var result = listener.actionGet(TIMEOUT);

            assertThat(result.asMap(), Matchers.is(buildExpectation(List.of(List.of(0.0123F, -0.0123F)))));
            assertThat(webServer.requests(), hasSize(1));
            assertNull(webServer.requests().get(0).getUri().getQuery());
            assertThat(webServer.requests().get(0).getHeader(HttpHeaders.CONTENT_TYPE), equalTo(XContentType.JSON.mediaType()));
            assertThat(webServer.requests().get(0).getHeader(API_KEY_HEADER), equalTo("apikey"));

            var requestMap = entityAsMap(webServer.requests().get(0).getBody());
            assertThat(requestMap.size(), Matchers.is(3));
            assertThat(requestMap.get("input"), Matchers.is(List.of("abc")));
            assertThat(requestMap.get("user"), Matchers.is("user"));
        }
    }

    /*

    public void testCheckModelConfig_IncludesMaxTokens() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(getUrl(webServer), "org", "secret", "model", "user", 100);
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var result = listener.actionGet(TIMEOUT);
    assertThat(
    result,
    is(AzureOpenAiEmbeddingsModelTests.createModel(getUrl(webServer), "org", "secret", "model", "user", 100, 2))
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    MatcherAssert.assertThat(requestMap, Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user")));
    }
    }

    public void testCheckModelConfig_ThrowsIfEmbeddingSizeDoesNotMatchValueSetByUser() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    null,
    100,
    3,
    true
    );
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var exception = expectThrows(ElasticsearchStatusException.class, () -> listener.actionGet(TIMEOUT));
    assertThat(
    exception.getMessage(),
    is(
    "The retrieved embeddings size [2] does not match the size specified in the settings [3]. "
    + "Please recreate the [id] configuration with the correct dimensions"
    )
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    MatcherAssert.assertThat(
    requestMap,
    Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user", "dimensions", 3))
    );
    }
    }

    public void testCheckModelConfig_ReturnsModelWithDimensionsSetTo2_AndDocProductSet_IfDimensionsSetByUser_ButSetToNull()
    throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    null,
    100,
    null,
    true
    );
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var returnedModel = listener.actionGet(TIMEOUT);
    assertThat(
    returnedModel,
    is(
    AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    SimilarityMeasure.DOT_PRODUCT,
    100,
    2,
    true
    )
    )
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    // since dimensions were null they should not be sent in the request
    MatcherAssert.assertThat(requestMap, Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user")));
    }
    }

    public void testCheckModelConfig_ReturnsModelWithSameDimensions_AndDocProductSet_IfDimensionsSetByUser_AndTheyMatchReturnedSize()
    throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    null,
    100,
    2,
    true
    );
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var returnedModel = listener.actionGet(TIMEOUT);
    assertThat(
    returnedModel,
    is(
    AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    SimilarityMeasure.DOT_PRODUCT,
    100,
    2,
    true
    )
    )
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    MatcherAssert.assertThat(
    requestMap,
    Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user", "dimensions", 2))
    );
    }
    }

    public void testCheckModelConfig_ReturnsNewModelReference_AndDoesNotSendDimensionsField_WhenNotSetByUser() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    null,
    100,
    100,
    false
    );
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var returnedModel = listener.actionGet(TIMEOUT);
    assertThat(
    returnedModel,
    is(
    AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    SimilarityMeasure.DOT_PRODUCT,
    100,
    2,
    false
    )
    )
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    MatcherAssert.assertThat(requestMap, Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user")));
    }
    }

    public void testCheckModelConfig_ReturnsNewModelReference_SetsSimilarityToDocProduct_WhenNull() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    null,
    100,
    100,
    false
    );
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var returnedModel = listener.actionGet(TIMEOUT);
    assertThat(
    returnedModel,
    is(
    AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    SimilarityMeasure.DOT_PRODUCT,
    100,
    2,
    false
    )
    )
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    MatcherAssert.assertThat(requestMap, Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user")));
    }
    }

    public void testCheckModelConfig_ReturnsNewModelReference_DoesNotOverrideSimilarity_WhenNotNull() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

    String responseJson = """
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
    webServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseJson));

    var model = AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    SimilarityMeasure.COSINE,
    100,
    100,
    false
    );
    PlainActionFuture<Model> listener = new PlainActionFuture<>();
    service.checkModelConfig(model, listener);

    var returnedModel = listener.actionGet(TIMEOUT);
    assertThat(
    returnedModel,
    is(
    AzureOpenAiEmbeddingsModelTests.createModel(
    getUrl(webServer),
    "org",
    "secret",
    "model",
    "user",
    SimilarityMeasure.COSINE,
    100,
    2,
    false
    )
    )
    );

    assertThat(webServer.requests(), hasSize(1));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    MatcherAssert.assertThat(requestMap, Matchers.is(Map.of("input", List.of("how big"), "model", "model", "user", "user")));
    }
    }

    public void testInfer_UnauthorisedResponse() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

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

    var model = AzureOpenAiEmbeddingsModelTests.createModel(getUrl(webServer), "org", "secret", "model", "user");
    PlainActionFuture<InferenceServiceResults> listener = new PlainActionFuture<>();
    service.infer(model, null, List.of("abc"), new HashMap<>(), InputType.INGEST, listener);

    var error = expectThrows(ElasticsearchException.class, () -> listener.actionGet(TIMEOUT));
    assertThat(error.getMessage(), containsString("Received an authentication error status code for request"));
    assertThat(error.getMessage(), containsString("Error message: [Incorrect API key provided:]"));
    assertThat(webServer.requests(), hasSize(1));
    }
    }

    public void testMoveModelFromTaskToServiceSettings() {
    var taskSettings = new HashMap<String, Object>();
    taskSettings.put(ServiceFields.MODEL_ID, "model");
    var serviceSettings = new HashMap<String, Object>();
    AzureOpenAiService.moveModelFromTaskToServiceSettings(taskSettings, serviceSettings);
    assertThat(taskSettings.keySet(), empty());
    assertEquals("model", serviceSettings.get(ServiceFields.MODEL_ID));
    }

    public void testMoveModelFromTaskToServiceSettings_OldID() {
    var taskSettings = new HashMap<String, Object>();
    taskSettings.put("model", "model");
    var serviceSettings = new HashMap<String, Object>();
    AzureOpenAiService.moveModelFromTaskToServiceSettings(taskSettings, serviceSettings);
    assertThat(taskSettings.keySet(), empty());
    assertEquals("model", serviceSettings.get(ServiceFields.MODEL_ID));
    }

    public void testMoveModelFromTaskToServiceSettings_AlreadyMoved() {
    var taskSettings = new HashMap<String, Object>();
    var serviceSettings = new HashMap<String, Object>();
    taskSettings.put(ServiceFields.MODEL_ID, "model");
    AzureOpenAiService.moveModelFromTaskToServiceSettings(taskSettings, serviceSettings);
    assertThat(taskSettings.keySet(), empty());
    assertEquals("model", serviceSettings.get(ServiceFields.MODEL_ID));
    }

    public void testChunkedInfer_CallsInfer_ConvertsFloatResponse() throws IOException {
    var senderFactory = HttpRequestSenderTests.createSenderFactory(threadPool, clientManager);

    try (var service = new AzureOpenAiService(senderFactory, createWithEmptySettings(threadPool))) {

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

    var model = AzureOpenAiEmbeddingsModelTests.createModel(getUrl(webServer), "org", "secret", "model", "user");
    PlainActionFuture<List<ChunkedInferenceServiceResults>> listener = new PlainActionFuture<>();
    service.chunkedInfer(model, List.of("abc"), new HashMap<>(), InputType.INGEST, new ChunkingOptions(null, null), listener);

    var result = listener.actionGet(TIMEOUT).get(0);
    assertThat(result, CoreMatchers.instanceOf(ChunkedTextEmbeddingResults.class));

    assertThat(
    asMapWithListsInsteadOfArrays((ChunkedTextEmbeddingResults) result),
    Matchers.is(
    Map.of(
    ChunkedTextEmbeddingResults.FIELD_NAME,
    List.of(
    Map.of(
    ChunkedNlpInferenceResults.TEXT,
    "abc",
    ChunkedNlpInferenceResults.INFERENCE,
    List.of((double) 0.123f, (double) -0.123f)
    )
    )
    )
    )
    );
    assertThat(webServer.requests(), hasSize(1));
    assertNull(webServer.requests().get(0).getUri().getQuery());
    assertThat(webServer.requests().get(0).getHeader(HttpHeaders.CONTENT_TYPE), equalTo(XContentType.JSON.mediaType()));
    assertThat(webServer.requests().get(0).getHeader(HttpHeaders.AUTHORIZATION), equalTo("Bearer secret"));
    assertThat(webServer.requests().get(0).getHeader(ORGANIZATION_HEADER), equalTo("org"));

    var requestMap = entityAsMap(webServer.requests().get(0).getBody());
    assertThat(requestMap.size(), Matchers.is(3));
    assertThat(requestMap.get("input"), Matchers.is(List.of("abc")));
    assertThat(requestMap.get("model"), Matchers.is("model"));
    assertThat(requestMap.get("user"), Matchers.is("user"));
    }
    }
    */

    private AzureOpenAiService createAzureOpenAiService() {
        return new AzureOpenAiService(mock(HttpRequestSender.Factory.class), createWithEmptySettings(threadPool));
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

    private PeristedConfig getPersistedConfigMap(
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        Map<String, Object> secretSettings
    ) {

        return new PeristedConfig(
            new HashMap<>(Map.of(ModelConfigurations.SERVICE_SETTINGS, serviceSettings, ModelConfigurations.TASK_SETTINGS, taskSettings)),
            new HashMap<>(Map.of(ModelSecrets.SECRET_SETTINGS, secretSettings))
        );
    }

    private PeristedConfig getPersistedConfigMap(Map<String, Object> serviceSettings, Map<String, Object> taskSettings) {

        return new PeristedConfig(
            new HashMap<>(Map.of(ModelConfigurations.SERVICE_SETTINGS, serviceSettings, ModelConfigurations.TASK_SETTINGS, taskSettings)),
            null
        );
    }

    private record PeristedConfig(Map<String, Object> config, Map<String, Object> secrets) {}
}
