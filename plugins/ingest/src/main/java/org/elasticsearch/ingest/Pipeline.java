/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.elasticsearch.ingest;

import org.elasticsearch.ingest.processor.ConfigurationUtils;
import org.elasticsearch.ingest.processor.Processor;
import org.elasticsearch.ingest.processor.HandledProcessor;

import java.util.*;

/**
 * A pipeline is a list of {@link Processor} instances grouped under a unique id.
 */
public final class Pipeline {

    private final String id;
    private final String description;
    private final List<HandledProcessor> processors;

    public Pipeline(String id, String description, List<HandledProcessor> processors) {
        this.id = id;
        this.description = description;
        this.processors = processors;
    }

    /**
     * Modifies the data of a document to be indexed based on the processor this pipeline holds
     */
    public void execute(IngestDocument ingestDocument) throws Exception {
        for (HandledProcessor processor : processors) {
            processor.execute(ingestDocument);
        }
    }

    /**
     * The unique id of this pipeline
     */
    public String getId() {
        return id;
    }

    /**
     * An optional description of what this pipeline is doing to the data gets processed by this pipeline.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Unmodifiable list containing each processor that operates on the data.
     */
    public List<HandledProcessor> getProcessors() {
        return processors;
    }

    public final static class Factory {

        private HandledProcessor readHandledProcessor(Map<String, Processor.Factory> processorRegistry, String type, Map<String, Object> config) throws Exception {
            Processor.Factory factory = processorRegistry.get(type);
            if (factory != null) {
                List<Map<String, Map<String, Object>>> onFailureProcessorConfigs = ConfigurationUtils.readOptionalList(config, "on_failure");
                List<HandledProcessor> onFailureProcessors = new ArrayList<>();
                if (onFailureProcessorConfigs != null) {
                    for (Map<String, Map<String, Object>> processorConfigWithKey : onFailureProcessorConfigs) {
                        for (Map.Entry<String, Map<String, Object>> entry : processorConfigWithKey.entrySet()) {
                            onFailureProcessors.add(readHandledProcessor(processorRegistry, entry.getKey(), entry.getValue()));
                        }
                    }
                }

                Processor p = factory.create(config);
                if (config.isEmpty() == false) {
                    throw new IllegalArgumentException("processor [" + type + "] doesn't support one or more provided configuration parameters " + Arrays.toString(config.keySet().toArray()));
                }
                return new HandledProcessor(p, onFailureProcessors);
            } else {
                throw new IllegalArgumentException("No processor type exist with name [" + type + "]");
            }
        }

        public Pipeline create(String id, Map<String, Object> config, Map<String, Processor.Factory> processorRegistry) throws Exception {
            String description = ConfigurationUtils.readOptionalStringProperty(config, "description");
            List<HandledProcessor> processors = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Map<String, Object>>> processorConfigs = (List<Map<String, Map<String, Object>>>) config.get("processors");
            if (processorConfigs != null ) {
                for (Map<String, Map<String, Object>> processor : processorConfigs) {
                    for (Map.Entry<String, Map<String, Object>> entry : processor.entrySet()) {
                        processors.add(readHandledProcessor(processorRegistry, entry.getKey(), entry.getValue()));
                    }
                }
            }
            return new Pipeline(id, description, Collections.unmodifiableList(processors));
        }

    }
}
