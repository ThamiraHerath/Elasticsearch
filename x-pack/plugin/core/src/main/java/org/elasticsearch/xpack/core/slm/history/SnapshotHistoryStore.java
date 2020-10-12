/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.slm.history;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import java.io.IOException;

import static org.elasticsearch.xpack.core.ilm.LifecycleSettings.SLM_HISTORY_INDEX_ENABLED_SETTING;
import static org.elasticsearch.xpack.core.slm.history.SnapshotLifecycleTemplateRegistry.INDEX_TEMPLATE_VERSION;
import static org.elasticsearch.xpack.core.slm.history.SnapshotLifecycleTemplateRegistry.SLM_TEMPLATE_NAME;

/**
 * Records Snapshot Lifecycle Management actions as represented by {@link SnapshotHistoryItem} into an index
 * for the purposes of querying and alerting.
 */
public class SnapshotHistoryStore {
    private static final Logger logger = LogManager.getLogger(SnapshotHistoryStore.class);

    public static final String SLM_HISTORY_DATA_STREAM = "slm-history-" + INDEX_TEMPLATE_VERSION;

    private final Client client;
    private final ClusterService clusterService;
    private final boolean slmHistoryEnabled;

    public SnapshotHistoryStore(Settings nodeSettings, Client client, ClusterService clusterService) {
        this.client = client;
        this.clusterService = clusterService;
        slmHistoryEnabled = SLM_HISTORY_INDEX_ENABLED_SETTING.get(nodeSettings);
    }

    /**
     * Attempts to asynchronously index a snapshot lifecycle management history entry
     *
     * @param item The entry to index
     */
    public void putAsync(SnapshotHistoryItem item) {
        if (slmHistoryEnabled == false) {
            logger.trace("not recording snapshot history item because [{}] is [false]: [{}]",
                SLM_HISTORY_INDEX_ENABLED_SETTING.getKey(), item);
            return;
        }
        logger.trace("about to index snapshot history item in data stream [{}]: [{}]", SLM_HISTORY_DATA_STREAM, item);
        Metadata metadata = clusterService.state().getMetadata();
        if (metadata.dataStreams().containsKey(SLM_HISTORY_DATA_STREAM) == false &&
            metadata.templatesV2().containsKey(SLM_TEMPLATE_NAME) == false) {
            logger.error(new ParameterizedMessage("failed to index snapshot history item, data stream [{}] and template [{}] don't exist",
                SLM_HISTORY_DATA_STREAM, SLM_TEMPLATE_NAME));
            return;
        }
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            item.toXContent(builder, ToXContent.EMPTY_PARAMS);
            IndexRequest request = new IndexRequest(SLM_HISTORY_DATA_STREAM)
                .opType(DocWriteRequest.OpType.CREATE)
                .source(builder);
            client.index(request, ActionListener.wrap(indexResponse -> {
                logger.debug("successfully indexed snapshot history item with id [{}] in data stream [{}]: [{}]",
                    indexResponse.getId(), SLM_HISTORY_DATA_STREAM, item);
            }, exception -> {
                logger.error(new ParameterizedMessage("failed to index snapshot history item in data stream [{}]: [{}]",
                    SLM_HISTORY_DATA_STREAM, item), exception);
            }));
        } catch (IOException exception) {
            logger.error(new ParameterizedMessage("failed to index snapshot history item in data stream [{}]: [{}]",
                SLM_HISTORY_DATA_STREAM, item), exception);
        }
    }
}
