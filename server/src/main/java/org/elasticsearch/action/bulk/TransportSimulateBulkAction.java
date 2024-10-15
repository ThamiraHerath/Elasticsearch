/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.bulk;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.template.post.TransportSimulateIndexTemplateAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.ingest.SimulateIndexResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ComponentTemplate;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.IndexAbstraction;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettingProvider;
import org.elasticsearch.index.IndexSettingProviders;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexingPressure;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.ingest.IngestService;
import org.elasticsearch.ingest.SimulateIngestService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.plugins.internal.XContentMeteringParserDecorator;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static java.util.stream.Collectors.toList;
import static org.elasticsearch.cluster.metadata.DataStreamLifecycle.isDataStreamsLifecycleOnlyMode;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findV1Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findV2Template;

/**
 * This action simulates bulk indexing data. Pipelines are executed for all indices that the request routes to, but no data is actually
 * indexed and no state is changed. Unlike TransportBulkAction, this does not push the work out to the nodes where the shards live (since
 * shards are not actually modified).
 */
public class TransportSimulateBulkAction extends TransportAbstractBulkAction {
    public static final NodeFeature SIMULATE_MAPPING_VALIDATION = new NodeFeature("simulate.mapping.validation");
    public static final NodeFeature SIMULATE_MAPPING_VALIDATION_TEMPLATES = new NodeFeature("simulate.mapping.validation.templates");
    public static final NodeFeature SIMULATE_COMPONENT_TEMPLATE_SUBSTITUTIONS = new NodeFeature(
        "simulate.component.template.substitutions"
    );
    public static final NodeFeature SIMULATE_INDEX_TEMPLATE_SUBSTITUTIONS = new NodeFeature("simulate.index.template.substitutions");
    public static final NodeFeature SIMULATE_MAPPING_ADDITION = new NodeFeature("simulate.mapping.addition");
    private final IndicesService indicesService;
    private final NamedXContentRegistry xContentRegistry;
    private final Set<IndexSettingProvider> indexSettingProviders;

    @Inject
    public TransportSimulateBulkAction(
        ThreadPool threadPool,
        TransportService transportService,
        ClusterService clusterService,
        IngestService ingestService,
        ActionFilters actionFilters,
        IndexingPressure indexingPressure,
        SystemIndices systemIndices,
        IndicesService indicesService,
        NamedXContentRegistry xContentRegistry,
        IndexSettingProviders indexSettingProviders
    ) {
        super(
            SimulateBulkAction.INSTANCE,
            transportService,
            actionFilters,
            SimulateBulkRequest::new,
            threadPool,
            clusterService,
            ingestService,
            indexingPressure,
            systemIndices,
            threadPool::relativeTimeInNanos
        );
        this.indicesService = indicesService;
        this.xContentRegistry = xContentRegistry;
        this.indexSettingProviders = indexSettingProviders.getIndexSettingProviders();
    }

    @Override
    protected void doInternalExecute(
        Task task,
        BulkRequest bulkRequest,
        Executor executor,
        ActionListener<BulkResponse> listener,
        long relativeStartTimeNanos
    ) throws IOException {
        assert bulkRequest instanceof SimulateBulkRequest
            : "TransportSimulateBulkAction should only ever be called with a SimulateBulkRequest but got a " + bulkRequest.getClass();
        final AtomicArray<BulkItemResponse> responses = new AtomicArray<>(bulkRequest.requests.size());
        Map<String, ComponentTemplate> componentTemplateSubstitutions = bulkRequest.getComponentTemplateSubstitutions();
        Map<String, ComposableIndexTemplate> indexTemplateSubstitutions = bulkRequest.getIndexTemplateSubstitutions();
        CompressedXContent mappingAddition = ((SimulateBulkRequest) bulkRequest).getMappingAddition();
        for (int i = 0; i < bulkRequest.requests.size(); i++) {
            DocWriteRequest<?> docRequest = bulkRequest.requests.get(i);
            assert docRequest instanceof IndexRequest : "TransportSimulateBulkAction should only ever be called with IndexRequests";
            IndexRequest request = (IndexRequest) docRequest;
            Exception mappingValidationException = validateMappings(
                componentTemplateSubstitutions,
                indexTemplateSubstitutions,
                mappingAddition,
                request
            );
            responses.set(
                i,
                BulkItemResponse.success(
                    0,
                    DocWriteRequest.OpType.CREATE,
                    new SimulateIndexResponse(
                        request.id(),
                        request.index(),
                        request.version(),
                        request.source(),
                        request.getContentType(),
                        request.getExecutedPipelines(),
                        mappingValidationException
                    )
                )
            );
        }
        listener.onResponse(
            new BulkResponse(responses.toArray(new BulkItemResponse[responses.length()]), buildTookInMillis(relativeStartTimeNanos))
        );
    }

    /**
     * This creates a temporary index with the mappings of the index in the request, and then attempts to index the source from the request
     * into it. If there is a mapping exception, that exception is returned. On success the returned exception is null.
     * @parem componentTemplateSubstitutions The component template definitions to use in place of existing ones for validation
     * @param request The IndexRequest whose source will be validated against the mapping (if it exists) of its index
     * @return a mapping exception if the source does not match the mappings, otherwise null
     */
    private Exception validateMappings(
        Map<String, ComponentTemplate> componentTemplateSubstitutions,
        Map<String, ComposableIndexTemplate> indexTemplateSubstitutions,
        CompressedXContent mappingAddition,
        IndexRequest request
    ) {
        final SourceToParse sourceToParse = new SourceToParse(
            request.id(),
            request.source(),
            request.getContentType(),
            request.routing(),
            request.getDynamicTemplates(),
            XContentMeteringParserDecorator.NOOP
        );

        ClusterState state = clusterService.state();
        Exception mappingValidationException = null;
        IndexAbstraction indexAbstraction = state.metadata().getIndicesLookup().get(request.index());
        try {
            if (indexAbstraction != null
                && componentTemplateSubstitutions.isEmpty()
                && indexTemplateSubstitutions.isEmpty()
                && mappingAddition == null) {
                /*
                 * In this case the index exists and we don't have any component template overrides. So we can just use withTempIndexService
                 * to do the mapping validation, using all the existing logic for validation.
                 */
                IndexMetadata imd = state.metadata().getIndexSafe(indexAbstraction.getWriteIndex(request, state.metadata()));
                indicesService.withTempIndexService(imd, indexService -> {
                    indexService.mapperService().updateMapping(null, imd);
                    return IndexShard.prepareIndex(
                        indexService.mapperService(),
                        sourceToParse,
                        SequenceNumbers.UNASSIGNED_SEQ_NO,
                        -1,
                        -1,
                        VersionType.INTERNAL,
                        Engine.Operation.Origin.PRIMARY,
                        Long.MIN_VALUE,
                        false,
                        request.ifSeqNo(),
                        request.ifPrimaryTerm(),
                        0
                    );
                });
            } else {
                /*
                 * The index did not exist, or we have component template substitutions, so we put together the mappings from existing
                 * templates This reproduces a lot of the mapping resolution logic in MetadataCreateIndexService.applyCreateIndexRequest().
                 * However, it does not deal with aliases (since an alias cannot be created if an index does not exist, and this is the
                 * path for when the index does not exist). And it does not deal with system indices since we do not intend for users to
                 * simulate writing to system indices.
                 */
                ClusterState.Builder simulatedClusterStateBuilder = new ClusterState.Builder(state);
                Metadata.Builder simulatedMetadata = Metadata.builder(state.metadata());
                if (indexAbstraction != null) {
                    /*
                     * We remove the index or data stream from the cluster state so that we are forced to fall back to the templates to get
                     * mappings.
                     */
                    String indexRequest = request.index();
                    assert indexRequest != null : "Index requests cannot be null in a simulate bulk call";
                    if (indexRequest != null) {
                        simulatedMetadata.remove(indexRequest);
                        simulatedMetadata.removeDataStream(indexRequest);
                    }
                }
                if (componentTemplateSubstitutions.isEmpty() == false) {
                    /*
                     * We put the template substitutions into the cluster state. If they have the same name as an existing one, the
                     * existing one is replaced.
                     */
                    Map<String, ComponentTemplate> updatedComponentTemplates = new HashMap<>();
                    updatedComponentTemplates.putAll(state.metadata().componentTemplates());
                    updatedComponentTemplates.putAll(componentTemplateSubstitutions);
                    simulatedMetadata.componentTemplates(updatedComponentTemplates);
                }
                if (indexTemplateSubstitutions.isEmpty() == false) {
                    Map<String, ComposableIndexTemplate> updatedIndexTemplates = new HashMap<>();
                    updatedIndexTemplates.putAll(state.metadata().templatesV2());
                    updatedIndexTemplates.putAll(indexTemplateSubstitutions);
                    simulatedMetadata.indexTemplates(updatedIndexTemplates);
                }
                ClusterState simulatedState = simulatedClusterStateBuilder.metadata(simulatedMetadata).build();

                String matchingTemplate = findV2Template(simulatedState.metadata(), request.index(), false);
                if (matchingTemplate != null) {
                    final Template template = TransportSimulateIndexTemplateAction.resolveTemplate(
                        matchingTemplate,
                        request.index(),
                        simulatedState,
                        isDataStreamsLifecycleOnlyMode(clusterService.getSettings()),
                        xContentRegistry,
                        indicesService,
                        systemIndices,
                        indexSettingProviders
                    );
                    CompressedXContent mappings = template.mappings();
                    if (mappings != null || mappingAddition != null) {
                        CompressedXContent mergedMappings = mergeCompressedXContents(mappings, mappingAddition);
                        MappingMetadata mappingMetadata = new MappingMetadata(mergedMappings);
                        Settings dummySettings = Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
                            .build();
                        final IndexMetadata imd = IndexMetadata.builder(request.index())
                            .settings(dummySettings)
                            .putMapping(mappingMetadata)
                            .build();
                        indicesService.withTempIndexService(imd, indexService -> {
                            indexService.mapperService().updateMapping(null, reSortMappingXContent(indexService, imd));
                            return IndexShard.prepareIndex(
                                indexService.mapperService(),
                                sourceToParse,
                                SequenceNumbers.UNASSIGNED_SEQ_NO,
                                -1,
                                -1,
                                VersionType.INTERNAL,
                                Engine.Operation.Origin.PRIMARY,
                                Long.MIN_VALUE,
                                false,
                                request.ifSeqNo(),
                                request.ifPrimaryTerm(),
                                0
                            );
                        });
                    }
                } else {
                    List<IndexTemplateMetadata> matchingTemplates = findV1Templates(simulatedState.metadata(), request.index(), false);
                    final Map<String, Object> mappingsMap = MetadataCreateIndexService.parseV1Mappings(
                        "{}",
                        matchingTemplates.stream().map(IndexTemplateMetadata::getMappings).collect(toList()),
                        xContentRegistry
                    );
                    final CompressedXContent combinedMappings = mergeCompressedXContents(
                        new CompressedXContent(mappingsMap),
                        mappingAddition
                    );
                    Settings dummySettings = Settings.builder()
                        .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
                        .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                        .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
                        .build();
                    MappingMetadata mappingMetadata = combinedMappings == null ? null : new MappingMetadata(combinedMappings);
                    final IndexMetadata imd = IndexMetadata.builder(request.index())
                        .putMapping(mappingMetadata)
                        .settings(dummySettings)
                        .build();
                    indicesService.withTempIndexService(imd, indexService -> {
                        indexService.mapperService().updateMapping(null, reSortMappingXContent(indexService, imd));
                        return IndexShard.prepareIndex(
                            indexService.mapperService(),
                            sourceToParse,
                            SequenceNumbers.UNASSIGNED_SEQ_NO,
                            -1,
                            -1,
                            VersionType.INTERNAL,
                            Engine.Operation.Origin.PRIMARY,
                            Long.MIN_VALUE,
                            false,
                            request.ifSeqNo(),
                            request.ifPrimaryTerm(),
                            0
                        );
                    });
                }
            }
        } catch (Exception e) {
            mappingValidationException = e;
        }
        return mappingValidationException;
    }

    private static CompressedXContent mergeCompressedXContents(
        @Nullable CompressedXContent compressedXContent1,
        @Nullable CompressedXContent compressedXContent2
    ) throws IOException {
        Map<String, Object> map1;
        if (compressedXContent1 == null) {
            map1 = new HashMap<>();
        } else {
            map1 = XContentHelper.convertToMap(compressedXContent1.uncompressed(), true, XContentType.JSON).v2();
        }
        Map<String, Object> map2;
        if (compressedXContent2 == null) {
            map2 = new HashMap<>();
        } else {
            map2 = XContentHelper.convertToMap(compressedXContent2.uncompressed(), true, XContentType.JSON).v2();
        }
        XContentHelper.update(map1, map2, true);
        if (map1.isEmpty()) {
            return null;
        } else {
            return convertRawAdditionalMappingToXContent(map1);
        }
    }

    private static IndexMetadata reSortMappingXContent(IndexService indexService, IndexMetadata originalIndexMetadata) {
        /*
         * This is needed because DocumentMapper asserts that if you create a Mapping from CompressedXContent, then you can get that same
         * CompressedXContent back out of the Mapping. But the Mapping orders the map keys in its toXContent the natural order of their
         * full paths (see ObjectMapper::serializeMappers). XContentParser (used to create the initial CompressedXContent) only supports
         * ordering by insertion order though! So this method rewrites the map ordered by Mapping.parseMapping. This way when the
         * XContentParser reads them, they are inserted in the correct order, and the roundtrip to Mapping works.
         */
        if (originalIndexMetadata.mapping() == null) {
            return originalIndexMetadata;
        }
        CompressedXContent compressedXContent = indexService.mapperService()
            .parseMapping(
                originalIndexMetadata.mapping().type(),
                MapperService.MergeReason.MAPPING_UPDATE,
                originalIndexMetadata.mapping().source()
            )
            .toCompressedXContent();
        return IndexMetadata.builder(originalIndexMetadata.getIndex().getName())
            .settings(originalIndexMetadata.getSettings())
            .putMapping(new MappingMetadata(compressedXContent))
            .build();
    }

    /*
     * This overrides TransportSimulateBulkAction's getIngestService to allow us to provide an IngestService that handles pipeline
     * substitutions defined in the request.
     */
    @Override
    protected IngestService getIngestService(BulkRequest request) {
        IngestService rawIngestService = super.getIngestService(request);
        return new SimulateIngestService(rawIngestService, request);
    }

    @Override
    protected Boolean resolveFailureStore(String indexName, Metadata metadata, long epochMillis) {
        // A simulate bulk request should not change any persistent state in the system, so we never write to the failure store
        return null;
    }

    public static CompressedXContent convertRawAdditionalMappingToXContent(Map<String, Object> rawAdditionalMapping) throws IOException {
        CompressedXContent compressedXContent;
        if (rawAdditionalMapping == null || rawAdditionalMapping.isEmpty()) {
            compressedXContent = null;
        } else {
            try (var parser = XContentHelper.mapToXContentParser(XContentParserConfiguration.EMPTY, rawAdditionalMapping)) {
                compressedXContent = mappingFromXContent(parser);
            }
        }
        return compressedXContent;
    }

    private static CompressedXContent mappingFromXContent(XContentParser parser) throws IOException {
        XContentParser.Token token = parser.nextToken();
        if (token == XContentParser.Token.START_OBJECT) {
            return new CompressedXContent(Strings.toString(XContentFactory.jsonBuilder().map(parser.mapOrdered())));
        } else {
            throw new IllegalArgumentException("Unexpected token: " + token);
        }
    }
}
