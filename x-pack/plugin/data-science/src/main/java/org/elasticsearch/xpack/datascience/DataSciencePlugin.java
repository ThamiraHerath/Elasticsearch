/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.datascience;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.action.XPackInfoFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.datascience.cumulativecardinality.CumulativeCardinalityPipelineAggregationBuilder;
import org.elasticsearch.xpack.datascience.cumulativecardinality.CumulativeCardinalityPipelineAggregator;

import java.util.Arrays;
import java.util.List;

import static java.util.Collections.singletonList;

public class DataSciencePlugin extends Plugin implements SearchPlugin, ActionPlugin {

    // volatile so all threads can see changes
    protected static volatile boolean isDataScienceAllowed;

    public DataSciencePlugin() {
        registerLicenseListener();
    }

    /**
     * Protected for test over-riding
     */
    protected void registerLicenseListener() {
        // Add a listener to the license state and cache it when there is a change.
        // Aggs could be called in high numbers so we don't want them contending on
        // the synchronized isFooAllowed() methods
        XPackPlugin.getSharedLicenseState()
            .addListener(() -> isDataScienceAllowed = XPackPlugin.getSharedLicenseState().isDataScienceAllowed());
    }

    public static boolean isIsDataScienceAllowed() {
        return isDataScienceAllowed;
    }

    @Override
    public List<PipelineAggregationSpec> getPipelineAggregations() {
        return singletonList(new PipelineAggregationSpec(
            CumulativeCardinalityPipelineAggregationBuilder.NAME,
            CumulativeCardinalityPipelineAggregationBuilder::new,
            CumulativeCardinalityPipelineAggregator::new,
            CumulativeCardinalityPipelineAggregationBuilder::parse));
    }

    @Override
    public List<ActionPlugin.ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return Arrays.asList(
            new ActionPlugin.ActionHandler<>(XPackUsageFeatureAction.DATA_SCIENCE, DataScienceUsageTransportAction.class),
            new ActionPlugin.ActionHandler<>(XPackInfoFeatureAction.DATA_SCIENCE, DataScienceInfoTransportAction.class));
    }
}
