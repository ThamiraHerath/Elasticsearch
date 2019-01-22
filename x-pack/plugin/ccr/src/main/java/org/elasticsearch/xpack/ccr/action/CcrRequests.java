/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ccr.action;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.admin.indices.mapping.put.MappingRequestOriginValidator;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.Index;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.ccr.CcrSettings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class CcrRequests {

    private CcrRequests() {}

    public static ClusterStateRequest metaDataRequest(String leaderIndex) {
        ClusterStateRequest clusterStateRequest = new ClusterStateRequest();
        clusterStateRequest.clear();
        clusterStateRequest.metaData(true);
        clusterStateRequest.indices(leaderIndex);
        return clusterStateRequest;
    }

    public static PutMappingRequest putMappingRequest(String followerIndex, MappingMetaData mappingMetaData) {
        PutMappingRequest putMappingRequest = new PutMappingRequest(followerIndex);
        putMappingRequest.origin("ccr");
        putMappingRequest.type(mappingMetaData.type());
        putMappingRequest.source(mappingMetaData.source().string(), XContentType.JSON);
        return putMappingRequest;
    }

    public static final MappingRequestOriginValidator CCR_PUT_MAPPING_REQUEST_VALIDATOR = (request, state, indices) -> {
        final List<Index> followingIndices = Arrays.stream(indices)
            .filter(index -> {
                final IndexMetaData indexMetaData = state.metaData().index(index);
                return indexMetaData != null && CcrSettings.CCR_FOLLOWING_INDEX_SETTING.get(indexMetaData.getSettings());
            }).collect(Collectors.toList());
        if (followingIndices.isEmpty() == false && "ccr".equals(request.origin()) == false) {
            final String errorMessage = "can't put mapping to the following indices "
                + "[" + followingIndices.stream().map(Index::getName).collect(Collectors.joining(", ")) + "]; "
                + "the mapping of the following indices are self-replicated from its leader indices";
            return new ElasticsearchStatusException(errorMessage, RestStatus.FORBIDDEN);
        }
        return null;
    };
}
