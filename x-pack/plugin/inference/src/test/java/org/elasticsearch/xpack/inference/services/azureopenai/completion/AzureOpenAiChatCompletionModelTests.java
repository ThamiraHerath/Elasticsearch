/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.azureopenai.completion;

import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.inference.services.azureopenai.AzureOpenAiSecretSettings;
import org.elasticsearch.xpack.inference.services.azureopenai.AzureOpenAiServiceFields;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class AzureOpenAiChatCompletionModelTests extends ESTestCase {

    public void testOverrideWith_UpdatedTaskSettings_OverridesUser() {
        var resource = "resource";
        var deploymentId = "deployment";
        var apiVersion = "api version";
        var apiKey = "api key";
        var entraId = "entra id";
        var inferenceEntityId = "inference entity id";

        var user = "user";
        var userOverride = "user override";

        var model = createCompletionModel(resource, deploymentId, apiVersion, user, apiKey, entraId, inferenceEntityId);
        var requestTaskSettingsMap = taskSettingsMap(userOverride);
        var overriddenModel = AzureOpenAiChatCompletionModel.of(model, requestTaskSettingsMap);

        assertThat(
            overriddenModel,
            equalTo(createCompletionModel(resource, deploymentId, apiVersion, userOverride, apiKey, entraId, inferenceEntityId))
        );
    }

    public void testOverrideWith_EmptyMap_OverridesNothing() {
        var model = createCompletionModel("resource", "deployment", "api version", "user", "api key", "entra id", "inference entity id");
        var requestTaskSettingsMap = Map.<String, Object>of();
        var overriddenModel = AzureOpenAiChatCompletionModel.of(model, requestTaskSettingsMap);

        assertThat(overriddenModel, sameInstance(model));
    }

    public void testOverrideWith_NullMap_OverridesNothing() {
        var model = createCompletionModel("resource", "deployment", "api version", "user", "api key", "entra id", "inference entity id");
        var overriddenModel = AzureOpenAiChatCompletionModel.of(model, null);

        assertThat(overriddenModel, sameInstance(model));
    }

    public void testOverrideWith_UpdatedServiceSettings_OverridesApiVersion() {
        var resource = "resource";
        var deploymentId = "deployment";
        var apiKey = "api key";
        var user = "user";
        var entraId = "entra id";
        var inferenceEntityId = "inference entity id";

        var apiVersion = "api version";
        var updatedApiVersion = "updated api version";

        var updatedServiceSettings = new AzureOpenAiChatCompletionServiceSettings(resource, deploymentId, updatedApiVersion, null);

        var model = createCompletionModel(resource, deploymentId, apiVersion, user, apiKey, entraId, inferenceEntityId);
        var overriddenModel = new AzureOpenAiChatCompletionModel(model, updatedServiceSettings);

        assertThat(
            overriddenModel,
            is(createCompletionModel(resource, deploymentId, updatedApiVersion, user, apiKey, entraId, inferenceEntityId))
        );
    }

    public void testBuildUriString() throws URISyntaxException {
        var resource = "resource";
        var deploymentId = "deployment";
        var apiKey = "api key";
        var user = "user";
        var entraId = "entra id";
        var inferenceEntityId = "inference entity id";
        var apiVersion = "2024";

        var model = createCompletionModel(resource, deploymentId, apiVersion, user, apiKey, entraId, inferenceEntityId);

        assertThat(
            model.buildUriString().toString(),
            is("https://resource.openai.azure.com/openai/deployments/deployment/chat/completions?api-version=2024")
        );
    }

    public static AzureOpenAiChatCompletionModel createModelWithRandomValues() {
        return createCompletionModel(
            randomAlphaOfLength(10),
            randomAlphaOfLength(10),
            randomAlphaOfLength(10),
            randomAlphaOfLength(10),
            randomAlphaOfLength(10),
            randomAlphaOfLength(10),
            randomAlphaOfLength(10)
        );
    }

    public static AzureOpenAiChatCompletionModel createCompletionModel(
        String resourceName,
        String deploymentId,
        String apiVersion,
        String user,
        @Nullable String apiKey,
        @Nullable String entraId,
        String inferenceEntityId
    ) {
        var secureApiKey = apiKey != null ? new SecureString(apiKey.toCharArray()) : null;
        var secureEntraId = entraId != null ? new SecureString(entraId.toCharArray()) : null;

        return new AzureOpenAiChatCompletionModel(
            inferenceEntityId,
            TaskType.COMPLETION,
            "service",
            new AzureOpenAiChatCompletionServiceSettings(resourceName, deploymentId, apiVersion, null),
            new AzureOpenAiChatCompletionTaskSettings(user),
            new AzureOpenAiSecretSettings(secureApiKey, secureEntraId)
        );
    }

    private Map<String, Object> taskSettingsMap(String user) {
        Map<String, Object> taskSettingsMap = new HashMap<>();
        taskSettingsMap.put(AzureOpenAiServiceFields.USER, user);
        return taskSettingsMap;
    }

}
