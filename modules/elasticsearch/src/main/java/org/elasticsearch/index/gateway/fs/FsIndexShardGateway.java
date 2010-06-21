/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.gateway.fs;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.gateway.IndexGateway;
import org.elasticsearch.index.gateway.blobstore.BlobStoreIndexShardGateway;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.indices.recovery.throttler.RecoveryThrottler;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * @author kimchy (shay.banon)
 */
public class FsIndexShardGateway extends BlobStoreIndexShardGateway {

    @Inject public FsIndexShardGateway(ShardId shardId, @IndexSettings Settings indexSettings, ThreadPool threadPool, IndexGateway fsIndexGateway,
                                       IndexShard indexShard, Store store, RecoveryThrottler recoveryThrottler) {
        super(shardId, indexSettings, threadPool, fsIndexGateway, indexShard, store, recoveryThrottler);
    }

    @Override public String type() {
        return "fs";
    }
}
