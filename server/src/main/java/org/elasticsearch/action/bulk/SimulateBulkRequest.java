/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.bulk;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.cluster.metadata.ComponentTemplate;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * This extends BulkRequest with support for providing substitute pipeline definitions, component template definitions, and index template
 * substitutions. In a user request, the substitutions will look something like this:
 *
 *   "pipeline_substitutions": {
 *     "my-pipeline-1": {
 *       "processors": [
 *         {
 *           "set": {
 *             "field": "my-new-boolean-field",
 *             "value": true
 *           }
 *         }
 *       ]
 *     },
 *     "my-pipeline-2": {
 *       "processors": [
 *         {
 *           "set": {
 *             "field": "my-new-boolean-field",
 *             "value": true
 *           },
 *           "rename": {
 *               "field": "old_field",
 *               "target_field": "new field"
 *           }
 *         }
 *       ]
 *     }
 *   },
 *   "component_template_substitutions": {
 *     "my-template-1": {
 *       "template": {
 *         "settings": {
 *           "number_of_shards": 1
 *         },
 *         "mappings": {
 *           "_source": {
 *             "enabled": false
 *           },
 *           "properties": {
 *             "host_name": {
 *               "type": "keyword"
 *             },
 *             "created_at": {
 *               "type": "date",
 *               "format": "EEE MMM dd HH:mm:ss Z yyyy"
 *             }
 *           }
 *         }
 *       }
 *     }
 *   },
 *   "index_template_substitutions": {
 *     "my-index-template-1": {
 *       "template": {
 *         "index_patterns": ["foo*", "bar*"]
 *         "composed_of": [
 *           "component-template-1",
 *           "component-template-2"
 *         ]
 *       }
 *     }
 *   },
 *   "mapping_addition": {
 *     "dynamic": "strict",
 *     "properties": {
 *       "foo": {
 *         "type": "keyword"
 *       }
 *   }
 *
 *   The pipelineSubstitutions Map held by this class is intended to be the result of XContentHelper.convertToMap(). The top-level keys
 *   are the pipelineIds ("my-pipeline-1" and "my-pipeline-2" in the example above). The values are the Maps of "processors" to the List of
 *   processor definitions.
 */
public class SimulateBulkRequest extends BulkRequest {
    private final Map<String, Map<String, Object>> pipelineSubstitutions;
    private final Map<String, Map<String, Object>> componentTemplateSubstitutions;
    private final Map<String, Map<String, Object>> indexTemplateSubstitutions;
    private final Map<String, Object> mappingAddition;

    /**
     * @param pipelineSubstitutions The pipeline definitions that are to be used in place of any pre-existing pipeline definitions with
     *                              the same pipelineId. The key of the map is the pipelineId, and the value the pipeline definition as
     *                              parsed by XContentHelper.convertToMap().
     * @param componentTemplateSubstitutions The component template definitions that are to be used in place of any pre-existing
     *                                       component template definitions with the same name.
     * @param indexTemplateSubstitutions The index template definitions that are to be used in place of any pre-existing
     *                                       index template definitions with the same name.
     * @param mappingAddition   A mapping that will be merged into the final index's mapping for mapping validation
     */
    public SimulateBulkRequest(
        @Nullable Map<String, Map<String, Object>> pipelineSubstitutions,
        @Nullable Map<String, Map<String, Object>> componentTemplateSubstitutions,
        @Nullable Map<String, Map<String, Object>> indexTemplateSubstitutions,
        @Nullable Map<String, Object> mappingAddition
    ) {
        super();
        this.pipelineSubstitutions = pipelineSubstitutions;
        this.componentTemplateSubstitutions = componentTemplateSubstitutions;
        this.indexTemplateSubstitutions = indexTemplateSubstitutions;
        this.mappingAddition = mappingAddition;
    }

    @SuppressWarnings("unchecked")
    public SimulateBulkRequest(StreamInput in) throws IOException {
        super(in);
        this.pipelineSubstitutions = (Map<String, Map<String, Object>>) in.readGenericValue();
        if (in.getTransportVersion().onOrAfter(TransportVersions.SIMULATE_COMPONENT_TEMPLATES_SUBSTITUTIONS)) {
            this.componentTemplateSubstitutions = (Map<String, Map<String, Object>>) in.readGenericValue();
        } else {
            componentTemplateSubstitutions = Map.of();
        }
        if (in.getTransportVersion().onOrAfter(TransportVersions.SIMULATE_INDEX_TEMPLATES_SUBSTITUTIONS)) {
            this.indexTemplateSubstitutions = (Map<String, Map<String, Object>>) in.readGenericValue();
        } else {
            indexTemplateSubstitutions = Map.of();
        }
        if (in.getTransportVersion().onOrAfter(TransportVersions.SIMULATE_MAPPING_ADDITION)) {
            this.mappingAddition = (Map<String, Object>) in.readGenericValue();
        } else {
            mappingAddition = Map.of();
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeGenericValue(pipelineSubstitutions);
        if (out.getTransportVersion().onOrAfter(TransportVersions.SIMULATE_COMPONENT_TEMPLATES_SUBSTITUTIONS)) {
            out.writeGenericValue(componentTemplateSubstitutions);
        }
        if (out.getTransportVersion().onOrAfter(TransportVersions.SIMULATE_INDEX_TEMPLATES_SUBSTITUTIONS)) {
            out.writeGenericValue(indexTemplateSubstitutions);
        }
        if (out.getTransportVersion().onOrAfter(TransportVersions.SIMULATE_MAPPING_ADDITION)) {
            out.writeGenericValue(mappingAddition);
        }
    }

    public Map<String, Map<String, Object>> getPipelineSubstitutions() {
        return pipelineSubstitutions;
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public Map<String, ComponentTemplate> getComponentTemplateSubstitutions() throws IOException {
        if (componentTemplateSubstitutions == null) {
            return Map.of();
        }
        Map<String, ComponentTemplate> result = new HashMap<>(componentTemplateSubstitutions.size());
        for (Map.Entry<String, Map<String, Object>> rawEntry : componentTemplateSubstitutions.entrySet()) {
            result.put(rawEntry.getKey(), convertRawTemplateToComponentTemplate(rawEntry.getValue()));
        }
        return result;
    }

    @Override
    public Map<String, ComposableIndexTemplate> getIndexTemplateSubstitutions() throws IOException {
        if (indexTemplateSubstitutions == null) {
            return Map.of();
        }
        Map<String, ComposableIndexTemplate> result = new HashMap<>(indexTemplateSubstitutions.size());
        for (Map.Entry<String, Map<String, Object>> rawEntry : indexTemplateSubstitutions.entrySet()) {
            result.put(rawEntry.getKey(), convertRawTemplateToIndexTemplate(rawEntry.getValue()));
        }
        return result;
    }

    public Map<String, Object> getMappingAddition() throws IOException {
        return mappingAddition;
    }

    private static ComponentTemplate convertRawTemplateToComponentTemplate(Map<String, Object> rawTemplate) throws IOException {
        ComponentTemplate componentTemplate;
        try (var parser = XContentHelper.mapToXContentParser(XContentParserConfiguration.EMPTY, rawTemplate)) {
            componentTemplate = ComponentTemplate.parse(parser);
        }
        return componentTemplate;
    }

    private static ComposableIndexTemplate convertRawTemplateToIndexTemplate(Map<String, Object> rawTemplate) throws IOException {
        ComposableIndexTemplate indexTemplate;
        try (var parser = XContentHelper.mapToXContentParser(XContentParserConfiguration.EMPTY, rawTemplate)) {
            indexTemplate = ComposableIndexTemplate.parse(parser);
        }
        return indexTemplate;
    }

    @Override
    public BulkRequest shallowClone() {
        BulkRequest bulkRequest = new SimulateBulkRequest(
            pipelineSubstitutions,
            componentTemplateSubstitutions,
            indexTemplateSubstitutions,
            mappingAddition
        );
        bulkRequest.setRefreshPolicy(getRefreshPolicy());
        bulkRequest.waitForActiveShards(waitForActiveShards());
        bulkRequest.timeout(timeout());
        bulkRequest.pipeline(pipeline());
        bulkRequest.routing(routing());
        bulkRequest.requireAlias(requireAlias());
        bulkRequest.requireDataStream(requireDataStream());
        return bulkRequest;
    }
}
