/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.migration;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.action.admin.cluster.migration.GetFeatureUpgradeStatusResponse.UpgradeStatus.NO_MIGRATION_NEEDED;
import static org.elasticsearch.action.admin.cluster.migration.GetFeatureUpgradeStatusResponse.UpgradeStatus.MIGRATION_NEEDED;

/**
 * Transport class for the get feature upgrade status action
 */
public class TransportGetFeatureUpgradeStatusAction extends TransportMasterNodeAction<
        GetFeatureUpgradeStatusRequest,
        GetFeatureUpgradeStatusResponse> {

    public static final Version NO_UPGRADE_REQUIRED_VERSION = Version.V_7_0_0;

    private final SystemIndices systemIndices;

    @Inject
    public TransportGetFeatureUpgradeStatusAction(
        TransportService transportService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ClusterService clusterService,
        IndexNameExpressionResolver indexNameExpressionResolver,
        SystemIndices systemIndices
    ) {
        super(
            GetFeatureUpgradeStatusAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            GetFeatureUpgradeStatusRequest::new,
            indexNameExpressionResolver,
            GetFeatureUpgradeStatusResponse::new,
            ThreadPool.Names.SAME
        );
        this.systemIndices = systemIndices;
    }

    @Override
    protected void masterOperation(GetFeatureUpgradeStatusRequest request, ClusterState state,
                                   ActionListener<GetFeatureUpgradeStatusResponse> listener) throws Exception {

        List<GetFeatureUpgradeStatusResponse.FeatureUpgradeStatus> features = systemIndices.getFeatures().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> getFeatureUpgradeStatus(state, entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

        boolean isUpgradeNeeded = features.stream()
                .map(GetFeatureUpgradeStatusResponse.FeatureUpgradeStatus::getMinimumIndexVersion)
                .min(Version::compareTo)
                .orElse(Version.CURRENT)
                .before(Version.V_7_0_0);

        listener.onResponse(new GetFeatureUpgradeStatusResponse(features, isUpgradeNeeded ? MIGRATION_NEEDED : NO_MIGRATION_NEEDED));
    }

    // visible for testing
    static GetFeatureUpgradeStatusResponse.FeatureUpgradeStatus getFeatureUpgradeStatus(
        ClusterState state, String featureName, SystemIndices.Feature feature) {

        List<GetFeatureUpgradeStatusResponse.IndexVersion> indexVersions = getIndexVersions(state, feature);

        Version minimumVersion = indexVersions.stream()
            .map(GetFeatureUpgradeStatusResponse.IndexVersion::getVersion)
            .min(Version::compareTo)
            .orElse(Version.CURRENT);

        return new GetFeatureUpgradeStatusResponse.FeatureUpgradeStatus(
            featureName,
            minimumVersion,
            minimumVersion.before(Version.V_7_0_0) ? MIGRATION_NEEDED : NO_MIGRATION_NEEDED,
            indexVersions
        );
    }

    // visible for testing
    static List<GetFeatureUpgradeStatusResponse.IndexVersion> getIndexVersions(ClusterState state, SystemIndices.Feature feature) {
        return Stream.of(feature.getIndexDescriptors(), feature.getAssociatedIndexDescriptors())
            .flatMap(Collection::stream)
            .flatMap(descriptor -> descriptor.getMatchingIndices(state.metadata()).stream())
            .sorted(String::compareTo)
            .map(index -> state.metadata().index(index))
            .map(indexMetadata -> new GetFeatureUpgradeStatusResponse.IndexVersion(
                indexMetadata.getIndex().getName(),
                indexMetadata.getCreationVersion()))
            .collect(Collectors.toList());
    }

    @Override
    protected ClusterBlockException checkBlock(GetFeatureUpgradeStatusRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }
}
