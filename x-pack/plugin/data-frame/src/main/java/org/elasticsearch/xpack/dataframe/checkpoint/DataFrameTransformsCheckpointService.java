/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.dataframe.checkpoint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.LatchedActionListener;
import org.elasticsearch.action.admin.indices.get.GetIndexAction;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsAction;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.core.ClientHelper;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformCheckpointStats;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformConfig;
import org.elasticsearch.xpack.core.dataframe.transforms.SingleCheckpointStats;
import org.elasticsearch.xpack.dataframe.persistence.DataFrameTransformsConfigManager;
import org.elasticsearch.xpack.dataframe.transforms.DataFrameTransformCheckpoint;
import org.elasticsearch.xpack.dataframe.transforms.DataFrameTransformTask;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DataFrameTransform Checkpoint Service
 *
 * Allows checkpointing a source of a data frame transform which includes all relevant checkpoints of the source.
 *
 * This will be used to checkpoint a transform, detect changes, run the transform in continuous mode.
 *
 */
public class DataFrameTransformsCheckpointService {

    private class Checkpoints {
        DataFrameTransformCheckpoint currentCheckpoint = DataFrameTransformCheckpoint.EMPTY;
        DataFrameTransformCheckpoint inProgressCheckpoint = DataFrameTransformCheckpoint.EMPTY;
        DataFrameTransformCheckpoint sourceCheckpoint = DataFrameTransformCheckpoint.EMPTY;
    }

    private static final Logger logger = LogManager.getLogger(DataFrameTransformsCheckpointService.class);

    // timeout for retrieving checkpoint information
    private static final int CHECKPOINT_STATS_TIMEOUT_SECONDS = 5;

    private final Client client;
    private final DataFrameTransformsConfigManager dataFrameTransformsConfigManager;

    public DataFrameTransformsCheckpointService(final Client client,
            final DataFrameTransformsConfigManager dataFrameTransformsConfigManager) {
        this.client = client;
        this.dataFrameTransformsConfigManager = dataFrameTransformsConfigManager;
    }

    /**
     * Get an unnumbered checkpoint. These checkpoints are for persistence but comparing state.
     *
     * @param transformConfig the @link{DataFrameTransformConfig}
     * @param listener listener to call after inner request returned
     */
    public void getCheckpoint(DataFrameTransformConfig transformConfig, ActionListener<DataFrameTransformCheckpoint> listener) {
        getCheckpoint(transformConfig, -1L, listener);
    }

    /**
     * Get a checkpoint, used to store a checkpoint.
     *
     * @param transformConfig the @link{DataFrameTransformConfig}
     * @param checkpoint the number of the checkpoint
     * @param listener listener to call after inner request returned
     */
    public void getCheckpoint(DataFrameTransformConfig transformConfig, long checkpoint,
            ActionListener<DataFrameTransformCheckpoint> listener) {
        long timestamp = System.currentTimeMillis();

        // placeholder for time based synchronization
        long timeUpperBound = 0;

        // 1st get index to see the indexes the user has access to
        GetIndexRequest getIndexRequest = new GetIndexRequest().indices(transformConfig.getSource().getIndex());

        ClientHelper.executeWithHeadersAsync(transformConfig.getHeaders(), ClientHelper.DATA_FRAME_ORIGIN, client, GetIndexAction.INSTANCE,
                getIndexRequest, ActionListener.wrap(getIndexResponse -> {
                    Set<String> userIndices = new HashSet<>(Arrays.asList(getIndexResponse.getIndices()));

                    // 2nd get stats request
                    ClientHelper.executeAsyncWithOrigin(client, ClientHelper.DATA_FRAME_ORIGIN, IndicesStatsAction.INSTANCE,
                            new IndicesStatsRequest().indices(transformConfig.getSource().getIndex()), ActionListener.wrap(response -> {
                                if (response.getFailedShards() != 0) {
                                    throw new CheckpointException("Source has [" + response.getFailedShards() + "] failed shards");
                                }

                                Map<String, long[]> checkpointsByIndex = extractIndexCheckPoints(response.getShards(), userIndices);
                                DataFrameTransformCheckpoint checkpointDoc = new DataFrameTransformCheckpoint(transformConfig.getId(),
                                        timestamp, checkpoint, checkpointsByIndex, timeUpperBound);
                                listener.onResponse(checkpointDoc);

                            }, IndicesStatsRequestException -> {
                                throw new CheckpointException("Failed to retrieve indices stats", IndicesStatsRequestException);
                            }));

                }, getIndexException -> {
                    throw new CheckpointException("Failed to retrieve list of indices", getIndexException);
                }));

    }

    public DataFrameTransformCheckpointStats getCheckpointStats(DataFrameTransformTask task) {

        long current = task.getCheckpoint();
        long inProgress = task.getInProgressCheckpoint();

        // dependent on the indexer we need 1, 2 or 3 async operations
        int numberOfOperations = 1;
        numberOfOperations += current != 0 ? 1 : 0;
        numberOfOperations += inProgress != 0 ? 1 : 0;

        CountDownLatch latch = new CountDownLatch(numberOfOperations);
        Checkpoints checkpoints = new Checkpoints();

        // get the current checkpoint
        dataFrameTransformsConfigManager.getTransformCheckpoint(task.getTransformId(), current,
                new LatchedActionListener<>(ActionListener.wrap(checkpoint -> checkpoints.currentCheckpoint = checkpoint, e -> {
                    logger.warn("Failed to retrieve checkpoint [" + current + "] for data frame []" + task.getTransformId(), e);
                }), latch));

        // get the in-progress checkpoint
        dataFrameTransformsConfigManager.getTransformCheckpoint(task.getTransformId(), task.getInProgressCheckpoint(),
                new LatchedActionListener<>(ActionListener.wrap(checkpoint -> checkpoints.inProgressCheckpoint = checkpoint, e -> {
                    logger.warn(
                            "Failed to retrieve in progress checkpoint [" + current + "] for data frame [" + task.getTransformId() + "]",
                            e);
                }), latch));

        // get the current state
        dataFrameTransformsConfigManager.getTransformConfiguration(task.getTransformId(),
                new LatchedActionListener<>(ActionListener.wrap(transformConfig -> {
                    getCheckpoint(transformConfig, ActionListener.wrap(checkpoint -> checkpoints.sourceCheckpoint = checkpoint, e2 -> {
                        logger.warn("Failed to retrieve checkpoint for data frame [" + task.getTransformId() + "]", e2);
                    }));

                }, e -> {
                    logger.warn("Failed to retrieve configuration for data frame [" + task.getTransformId() + "]", e);
                }), latch));

        try {
            latch.await(CHECKPOINT_STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Failed to retrieve checkpoints for data frame [" + task.getTransformId() + "]", e);
        }

        return new DataFrameTransformCheckpointStats(
                new SingleCheckpointStats(checkpoints.currentCheckpoint.getTimestamp(), checkpoints.currentCheckpoint.getTimeUpperBound()),
                new SingleCheckpointStats(checkpoints.inProgressCheckpoint.getTimestamp(),
                        checkpoints.inProgressCheckpoint.getTimeUpperBound()),
                checkpoints.currentCheckpoint.getBehind(checkpoints.sourceCheckpoint));
    }

    static Map<String, long[]> extractIndexCheckPoints(ShardStats[] shards, Set<String> userIndices) {
        Map<String, TreeMap<Integer, Long>> checkpointsByIndex = new TreeMap<>();

        for (ShardStats shard : shards) {
            String indexName = shard.getShardRouting().getIndexName();
            if (userIndices.contains(indexName)) {
                if (checkpointsByIndex.containsKey(indexName)) {
                    // we have already seen this index, just check/add shards
                    TreeMap<Integer, Long> checkpoints = checkpointsByIndex.get(indexName);
                    if (checkpoints.containsKey(shard.getShardRouting().getId())) {
                        // there is already a checkpoint entry for this index/shard combination, check if they match
                        if (checkpoints.get(shard.getShardRouting().getId()) != shard.getSeqNoStats().getGlobalCheckpoint()) {
                            throw new CheckpointException("Global checkpoints mismatch for index [" + indexName + "] between shards of id ["
                                    + shard.getShardRouting().getId() + "]");
                        }
                    } else {
                        // 1st time we see this shard for this index, add the entry for the shard
                        checkpoints.put(shard.getShardRouting().getId(), shard.getSeqNoStats().getGlobalCheckpoint());
                    }
                } else {
                    // 1st time we see this index, create an entry for the index and add the shard checkpoint
                    checkpointsByIndex.put(indexName, new TreeMap<>());
                    checkpointsByIndex.get(indexName).put(shard.getShardRouting().getId(), shard.getSeqNoStats().getGlobalCheckpoint());
                }
            }
        }

        // create the final structure
        Map<String, long[]> checkpointsByIndexReduced = new TreeMap<>();

        checkpointsByIndex.forEach((indexName, checkpoints) -> {
            checkpointsByIndexReduced.put(indexName, checkpoints.values().stream().mapToLong(l -> l).toArray());
        });

        return checkpointsByIndexReduced;
    }

}
