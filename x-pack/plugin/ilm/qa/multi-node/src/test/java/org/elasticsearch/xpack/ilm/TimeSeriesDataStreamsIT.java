/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ilm;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.PhaseCompleteStep;
import org.elasticsearch.xpack.core.ilm.ReplaceDataStreamBackingIndexStep;
import org.elasticsearch.xpack.core.ilm.RolloverAction;
import org.elasticsearch.xpack.core.ilm.ShrinkAction;
import org.elasticsearch.xpack.core.ilm.ShrunkenIndexCheckStep;

import java.util.concurrent.TimeUnit;

import static org.elasticsearch.xpack.TimeSeriesRestDriver.createComposableTemplate;
import static org.elasticsearch.xpack.TimeSeriesRestDriver.createNewSingletonPolicy;
import static org.elasticsearch.xpack.TimeSeriesRestDriver.explainIndex;
import static org.elasticsearch.xpack.TimeSeriesRestDriver.getStepKeyForIndex;
import static org.elasticsearch.xpack.TimeSeriesRestDriver.indexDocument;
import static org.elasticsearch.xpack.TimeSeriesRestDriver.rolloverMaxOneDocCondition;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class TimeSeriesDataStreamsIT extends ESRestTestCase {

    private static final String FAILED_STEP_RETRY_COUNT_FIELD = "failed_step_retry_count";

    public void testRolloverAction() throws Exception {
        String policyName = "logs-policy";
        createNewSingletonPolicy(client(), policyName, "hot", new RolloverAction(null, null, 1L));

        Settings lifecycleNameSetting = Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, policyName).build();
        Template template = new Template(lifecycleNameSetting, null, null);
        createComposableTemplate(client(), "logs-template", "logs-foo*", template);

        String dataStream = "logs-foo";
        indexDocument(client(), dataStream, true);

        assertBusy(() -> assertTrue(indexExists("logs-foo-000002")));
        assertBusy(() -> assertTrue(Boolean.parseBoolean((String) getIndexSettingsAsMap("logs-foo-000002").get("index.hidden"))));
        assertBusy(() -> assertThat(getStepKeyForIndex(client(), "logs-foo-000001"), equalTo(PhaseCompleteStep.finalStep("hot").getKey())));
    }

    public void testShrinkAction() throws Exception {
        String policyName = "logs-policy";
        createNewSingletonPolicy(client(), policyName, "warm", new ShrinkAction(1));

        Settings settings = Settings.builder()
            .put(LifecycleSettings.LIFECYCLE_NAME, policyName)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 3)
            .build();
        Template template = new Template(settings, null, null);
        createComposableTemplate(client(), "logs-template", "logs-foo*", template);

        String dataStream = "logs-foo";
        indexDocument(client(), dataStream, true);

        String backingIndexName = "logs-foo-000001";
        String shrunkenIndex = ShrinkAction.SHRUNKEN_INDEX_PREFIX + backingIndexName;
        assertBusy(() -> assertTrue(indexExists(shrunkenIndex)), 30, TimeUnit.SECONDS);
        assertBusy(() ->
                assertThat("shrunk index must wait for the original index to be deleted in the " + ShrunkenIndexCheckStep.NAME + " step",
                    getStepKeyForIndex(client(), shrunkenIndex).getName(), is(ShrunkenIndexCheckStep.NAME)),
            30, TimeUnit.SECONDS);
        assertBusy(() -> assertThat("original index must wait in the " + ReplaceDataStreamBackingIndexStep.NAME + " until it is not " +
                "the write index anymore so it can be replaced by the shrunken index",
            (Integer) explainIndex(client(), backingIndexName).get(FAILED_STEP_RETRY_COUNT_FIELD), greaterThanOrEqualTo(1)), 30, TimeUnit.SECONDS);

        // Manual rollover the original index such that it's not the write index in the data stream anymore
        rolloverMaxOneDocCondition(client(), dataStream);

        assertBusy(() -> assertThat(getStepKeyForIndex(client(), shrunkenIndex), equalTo(PhaseCompleteStep.finalStep("warm").getKey())));
        assertThat(indexExists(backingIndexName), is(false));
    }
}
