/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.logsdb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexSettingProvider;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.MapperService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_ROUTING_PATH;

/**
 * An index setting provider that overwrites the source mode from synthetic to stored if synthetic source isn't allowed to be used.
 */
final class SyntheticSourceIndexSettingsProvider implements IndexSettingProvider {

    private static final Logger LOGGER = LogManager.getLogger(SyntheticSourceIndexSettingsProvider.class);

    private final SyntheticSourceLicenseService syntheticSourceLicenseService;
    private final CheckedFunction<IndexMetadata, MapperService, IOException> mapperServiceFactory;

    SyntheticSourceIndexSettingsProvider(
        SyntheticSourceLicenseService syntheticSourceLicenseService,
        CheckedFunction<IndexMetadata, MapperService, IOException> mapperServiceFactory
    ) {
        this.syntheticSourceLicenseService = syntheticSourceLicenseService;
        this.mapperServiceFactory = mapperServiceFactory;
    }

    @Override
    public Settings getAdditionalIndexSettings(
        String indexName,
        String dataStreamName,
        boolean isTimeSeries,
        Metadata metadata,
        Instant resolvedAt,
        Settings indexTemplateAndCreateRequestSettings,
        List<CompressedXContent> combinedTemplateMappings
    ) {
        if (newIndexHasSyntheticSourceUsage(indexName, isTimeSeries, indexTemplateAndCreateRequestSettings, combinedTemplateMappings)
            && syntheticSourceLicenseService.fallbackToStoredSource()) {
            LOGGER.debug("creation of index [{}] with synthetic source without it being allowed", indexName);
            // TODO: handle falling back to stored source
        }
        return Settings.EMPTY;
    }

    boolean newIndexHasSyntheticSourceUsage(
        String indexName,
        boolean isTimeSeries,
        Settings indexTemplateAndCreateRequestSettings,
        List<CompressedXContent> combinedTemplateMappings
    ) {
        if ("validate-index-name".equals(indexName)) {
            // This index name is used when validating component and index templates, we should skip this check in that case.
            // (See MetadataIndexTemplateService#validateIndexTemplateV2(...) method)
            return false;
        }

        var tmpIndexMetadata = IndexMetadata.builder(indexName);

        int dummyPartitionSize = IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING.get(indexTemplateAndCreateRequestSettings);
        int dummyShards = indexTemplateAndCreateRequestSettings.getAsInt(
            IndexMetadata.SETTING_NUMBER_OF_SHARDS,
            dummyPartitionSize == 1 ? 1 : dummyPartitionSize + 1
        );
        int shardReplicas = indexTemplateAndCreateRequestSettings.getAsInt(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0);
        var finalResolvedSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(indexTemplateAndCreateRequestSettings)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, dummyShards)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, shardReplicas)
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID());

        if (isTimeSeries) {
            finalResolvedSettings.put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES);
            // Avoid failing because index.routing_path is missing
            finalResolvedSettings.putList(INDEX_ROUTING_PATH.getKey(), List.of("path"));
        }

        tmpIndexMetadata.settings(finalResolvedSettings);
        // Create MapperService just to extract keyword dimension fields:
        try (var mapperService = mapperServiceFactory.apply(tmpIndexMetadata.build())) {
            if (combinedTemplateMappings.isEmpty()) {
                // this can happen when creating a normal index that doesn't match any template and without mapping.
                combinedTemplateMappings = List.of(new CompressedXContent("{}"));
            }
            mapperService.merge(MapperService.SINGLE_MAPPING_NAME, combinedTemplateMappings, MapperService.MergeReason.INDEX_TEMPLATE);
            return mapperService.documentMapper().sourceMapper().isSynthetic();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
