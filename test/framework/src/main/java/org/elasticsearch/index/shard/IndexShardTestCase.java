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
package org.elasticsearch.index.shard;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingHelper;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.MapperTestUtils;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.cache.IndexCache;
import org.elasticsearch.index.cache.query.DisabledQueryCache;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.UidFieldMapper;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.index.store.DirectoryService;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.indices.fielddata.cache.IndicesFieldDataCache;
import org.elasticsearch.indices.recovery.FileAndOpsRecoveryTarget;
import org.elasticsearch.indices.recovery.FileAndOpsRecoveryTargetHandler;
import org.elasticsearch.indices.recovery.FileRecoverySourceHandler;
import org.elasticsearch.indices.recovery.OpsRecoverySourceHandler;
import org.elasticsearch.indices.recovery.OpsRecoveryTarget;
import org.elasticsearch.indices.recovery.OpsRecoveryTargetHandler;
import org.elasticsearch.indices.recovery.PeerRecoveryTargetService;
import org.elasticsearch.indices.recovery.RecoveryFailedException;
import org.elasticsearch.indices.recovery.RecoverySourceHandler;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.indices.recovery.RecoveryTarget;
import org.elasticsearch.indices.recovery.StartFileRecoveryRequest;
import org.elasticsearch.indices.recovery.StartOpsRecoveryRequest;
import org.elasticsearch.indices.recovery.StartRecoveryRequest;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.DummyShardLock;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

/**
 * A base class for unit tests that need to create and shutdown {@link IndexShard} instances easily,
 * containing utilities for shard creation and recoveries. See {{@link #newShard(boolean)}} and
 * {@link #newStartedShard()} for a good starting points
 */
public abstract class IndexShardTestCase extends ESTestCase {

    protected static final PeerRecoveryTargetService.RecoveryListener recoveryListener = new PeerRecoveryTargetService.RecoveryListener() {
        @Override
        public void onRecoveryDone(RecoveryState state) {

        }

        @Override
        public void onRecoveryFailure(RecoveryState state, RecoveryFailedException e, boolean sendShardFailure) {
            throw new AssertionError(e);
        }
    };

    protected ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getClass().getName());
    }

    @Override
    public void tearDown() throws Exception {
        try {
            ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        } finally {
            super.tearDown();
        }
    }

    private Store createStore(IndexSettings indexSettings, ShardPath shardPath) throws IOException {
        final ShardId shardId = shardPath.getShardId();
        final DirectoryService directoryService = new DirectoryService(shardId, indexSettings) {
            @Override
            public Directory newDirectory() throws IOException {
                return newFSDirectory(shardPath.resolveIndex());
            }
        };
        return new Store(shardId, indexSettings, directoryService, new DummyShardLock(shardId));
    }

    /**
     * creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                (ready to recover from another shard)
     */
    protected IndexShard newShard(boolean primary) throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(new ShardId("index", "_na_", 0), "n1", primary,
            ShardRoutingState.INITIALIZING,
            primary ? RecoverySource.StoreRecoverySource.EMPTY_STORE_INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE);
        return newShard(shardRouting);
    }

    /**
     * creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param shardRouting the {@link ShardRouting} to use for this shard
     * @param listeners    an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting shardRouting, IndexingOperationListener... listeners) throws IOException {
        assert shardRouting.initializing() : shardRouting;
        Settings settings = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetaData.Builder metaData = IndexMetaData.builder(shardRouting.getIndexName())
            .settings(settings)
            .primaryTerm(0, 1);
        return newShard(shardRouting, metaData.build(), listeners);
    }

    /**
     * creates a new initializing shard. The shard will have its own unique data path.
     *
     * @param shardId   the shard id to use
     * @param primary   indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                  (ready to recover from another shard)
     * @param listeners an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardId shardId, boolean primary, IndexingOperationListener... listeners) throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, randomAlphaOfLength(5), primary,
            ShardRoutingState.INITIALIZING,
            primary ? RecoverySource.StoreRecoverySource.EMPTY_STORE_INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE);
        return newShard(shardRouting, listeners);
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * supplied node id.
     *
     * @param shardId the shard id to use
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                (ready to recover from another shard)
     */
    protected IndexShard newShard(ShardId shardId, boolean primary, String nodeId, IndexMetaData indexMetaData,
                                  @Nullable IndexSearcherWrapper searcherWrapper) throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, nodeId, primary, ShardRoutingState.INITIALIZING,
            primary ? RecoverySource.StoreRecoverySource.EMPTY_STORE_INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE);
        return newShard(shardRouting, indexMetaData, searcherWrapper, () -> {
        }, null);
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * supplied node id.
     *
     * @param shardId the shard id to use
     * @param primary indicates whether to a primary shard (ready to recover from an empty store) or a replica
     *                (ready to recover from another shard)
     */
    protected IndexShard newShard(ShardId shardId, boolean primary, String nodeId, IndexMetaData indexMetaData,
                                  Runnable globalCheckpointSyncer,
                                  @Nullable IndexSearcherWrapper searcherWrapper) throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, nodeId, primary, ShardRoutingState.INITIALIZING,
            primary ? RecoverySource.StoreRecoverySource.EMPTY_STORE_INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE);
        return newShard(shardRouting, indexMetaData, searcherWrapper, globalCheckpointSyncer, null);
    }


    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * current node id the shard is assigned to.
     *
     * @param routing       shard routing to use
     * @param indexMetaData indexMetaData for the shard, including any mapping
     * @param listeners     an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting routing, IndexMetaData indexMetaData, IndexingOperationListener... listeners)
        throws IOException {
        return newShard(routing, indexMetaData, null, () -> {
        }, null, listeners);
    }

    /**
     * creates a new initializing shard. The shard will will be put in its proper path under the
     * current node id the shard is assigned to.
     *
     * @param routing                shard routing to use
     * @param indexMetaData          indexMetaData for the shard, including any mapping
     * @param indexSearcherWrapper   an optional wrapper to be used during searchers
     * @param globalCheckpointSyncer an runnable to run when the global check point needs syncing
     * @param listeners              an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting routing, IndexMetaData indexMetaData,
                                  @Nullable IndexSearcherWrapper indexSearcherWrapper, Runnable globalCheckpointSyncer,
                                  @Nullable EngineFactory engineFactory,
                                  IndexingOperationListener... listeners)
        throws IOException {
        // add node id as name to settings for popper logging
        final ShardId shardId = routing.shardId();
        final NodeEnvironment.NodePath nodePath = new NodeEnvironment.NodePath(createTempDir());
        ShardPath shardPath = new ShardPath(false, nodePath.resolve(shardId), nodePath.resolve(shardId), shardId);
        return newShard(routing, shardPath, indexMetaData, indexSearcherWrapper, globalCheckpointSyncer, engineFactory, listeners);
    }

    /**
     * creates a new initializing shard.
     *
     * @param routing              shard routing to use
     * @param shardPath            path to use for shard data
     * @param indexMetaData        indexMetaData for the shard, including any mapping
     * @param indexSearcherWrapper an optional wrapper to be used during searchers
     * @param listeners            an optional set of listeners to add to the shard
     */
    protected IndexShard newShard(ShardRouting routing, ShardPath shardPath, IndexMetaData indexMetaData,
                                  @Nullable IndexSearcherWrapper indexSearcherWrapper,
                                  Runnable globalCheckpointSyncer,
                                  @Nullable EngineFactory engineFactory,
                                  IndexingOperationListener... listeners) throws IOException {
        final Settings nodeSettings = Settings.builder().put("node.name", routing.currentNodeId()).build();
        final IndexSettings indexSettings = new IndexSettings(indexMetaData, nodeSettings);
        final IndexShard indexShard;
        final Store store = createStore(indexSettings, shardPath);
        boolean success = false;
        try {
            IndexCache indexCache = new IndexCache(indexSettings, new DisabledQueryCache(indexSettings), null);
            MapperService mapperService = MapperTestUtils.newMapperService(xContentRegistry(), createTempDir(),
                    indexSettings.getSettings(), "index");
            mapperService.merge(indexMetaData, MapperService.MergeReason.MAPPING_RECOVERY, true);
            SimilarityService similarityService = new SimilarityService(indexSettings, Collections.emptyMap());
            final IndexEventListener indexEventListener = new IndexEventListener() {
            };
            final Engine.Warmer warmer = searcher -> {
            };
            IndicesFieldDataCache indicesFieldDataCache = new IndicesFieldDataCache(nodeSettings, new IndexFieldDataCache.Listener() {
            });
            IndexFieldDataService indexFieldDataService = new IndexFieldDataService(indexSettings, indicesFieldDataCache,
                new NoneCircuitBreakerService(), mapperService);
            indexShard = new IndexShard(routing, indexSettings, shardPath, store, indexCache, mapperService, similarityService,
                indexFieldDataService, engineFactory, indexEventListener, indexSearcherWrapper, threadPool,
                BigArrays.NON_RECYCLING_INSTANCE, warmer, globalCheckpointSyncer, Collections.emptyList(), Arrays.asList(listeners));
            success = true;
        } finally {
            if (success == false) {
                IOUtils.close(store);
            }
        }
        return indexShard;
    }

    /**
     * Takes an existing shard, closes it and and starts a new initialing shard at the same location
     *
     * @param listeners new listerns to use for the newly created shard
     */
    protected IndexShard reinitShard(IndexShard current, IndexingOperationListener... listeners) throws IOException {
        final ShardRouting shardRouting = current.routingEntry();
        return reinitShard(current, ShardRoutingHelper.initWithSameId(shardRouting,
            shardRouting.primary() ? RecoverySource.StoreRecoverySource.EXISTING_STORE_INSTANCE : RecoverySource.PeerRecoverySource.INSTANCE
        ), listeners);
    }

    /**
     * Takes an existing shard, closes it and and starts a new initialing shard at the same location
     *
     * @param routing   the shard routing to use for the newly created shard.
     * @param listeners new listerns to use for the newly created shard
     */
    protected IndexShard reinitShard(IndexShard current, ShardRouting routing, IndexingOperationListener... listeners) throws IOException {
        closeShards(current);
        return newShard(routing, current.shardPath(), current.indexSettings().getIndexMetaData(), null,
            current.getGlobalCheckpointSyncer(), current.engineFactory, listeners);
    }

    /**
     * creates a new empyu shard and starts it. The shard will be either a replica or a primary.
     */
    protected IndexShard newStartedShard() throws IOException {
        return newStartedShard(randomBoolean());
    }

    /**
     * creates a new empty shard and starts it.
     *
     * @param primary controls whether the shard will be a primary or a replica.
     */
    protected IndexShard newStartedShard(boolean primary) throws IOException {
        IndexShard shard = newShard(primary);
        if (primary) {
            recoveryShardFromStore(shard);
        } else {
            recoveryEmptyReplica(shard);
        }
        return shard;
    }

    protected void closeShards(IndexShard... shards) throws IOException {
        closeShards(Arrays.asList(shards));
    }

    protected void closeShards(Iterable<IndexShard> shards) throws IOException {
        for (IndexShard shard : shards) {
            if (shard != null) {
                try {
                    shard.close("test", false);
                } finally {
                    IOUtils.close(shard.store());
                }
            }
        }
    }

    protected void recoveryShardFromStore(IndexShard primary) throws IOException {
        primary.markAsRecovering("store", new RecoveryState(primary.routingEntry(),
            getFakeDiscoNode(primary.routingEntry().currentNodeId()),
            null));
        primary.recoverFromStore();
        primary.updateRoutingEntry(ShardRoutingHelper.moveToStarted(primary.routingEntry()));
    }

    protected void recoveryEmptyReplica(IndexShard replica) throws IOException {
        IndexShard primary = null;
        try {
            primary = newStartedShard(true);
            recoverReplica(replica, primary);
        } finally {
            closeShards(primary);
        }
    }

    private DiscoveryNode getFakeDiscoNode(String id) {
        return new DiscoveryNode(id, id, buildNewFakeTransportAddress(), Collections.emptyMap(), EnumSet.allOf(DiscoveryNode.Role.class),
            Version.CURRENT);
    }

    /** recovers a replica from the given primary **/
    protected void recoverReplica(IndexShard replica, IndexShard primary) throws IOException {
        boolean opsRecoverySuccessful = false;
        markReplicaAsRecovering(replica, primary);
        if (PeerRecoveryTargetService.shouldTryOpsRecovery(replica)) {
            try {
                recoverReplica(replica, primary,
                    (r, sourceNode) -> {
                        r.prepareForIndexRecovery();
                        // nocommit: think of a cleaner way to deal with these instances of
                        replica.skipTranslogRecovery(IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP);

                        return new OpsRecoveryTarget(replica, sourceNode, recoveryListener);
                    }, false);
                opsRecoverySuccessful = true;
            } catch (RecoveryFailedException e) {
                logger.debug("ops based recovery failed, falling back to file based recovery", e);
                replica.performRecoveryRestart();
            }
        }
        if (opsRecoverySuccessful == false) {
            recoverReplica(replica, primary,
                (r, sourceNode) -> {
                    r.prepareForIndexRecovery();
                    return new FileAndOpsRecoveryTarget(r, sourceNode, recoveryListener);
                }, false);
        }
    }

    /**
     * Recovers a replica from the give primary, allow the user to supply a custom recovery target.
     * A typical usage of a custom recovery target is to assert things in the various stages of
     * recovery.
     *
     * @param replica          the recovery target shard
     * @param primary          the recovery source shard
     * @param prepareShardForRecovery  prepares the shard for the relevant recovery and returns an instance of {@link RecoveryTarget}
     * @param markAsRecovering true if the replica should be marked as recovering as well.
     */
    protected final void recoverReplica(final IndexShard replica,
                                        final IndexShard primary,
                                        final ReplicaRecoveryPreparer prepareShardForRecovery,
                                        final boolean markAsRecovering) throws IOException {
        if (markAsRecovering) {
            markReplicaAsRecovering(replica, primary);
        }
        assertNotNull(replica.recoveryState());
        assertEquals("shard state is [" + replica.state() + "] but should be recovering", replica.state(), IndexShardState.RECOVERING);
        final DiscoveryNode pNode = replica.recoveryState().getSourceNode();
        final DiscoveryNode rNode = replica.recoveryState().getTargetNode();
        final RecoveryTarget recoveryTarget = prepareShardForRecovery.prepare(replica, pNode);
        final StartRecoveryRequest request = recoveryTarget.createStartRecoveryRequest(logger, rNode);
        final Settings nodeSettings = Settings.builder()
            .put(Node.NODE_NAME_SETTING.getKey(), pNode.getName()).build();
        final RecoverySourceHandler sourceHandler;
        if (request instanceof StartOpsRecoveryRequest) {
            sourceHandler = new OpsRecoverySourceHandler(primary,
                (OpsRecoveryTargetHandler) recoveryTarget, (StartOpsRecoveryRequest) request,
                (int) ByteSizeUnit.MB.toBytes(1), nodeSettings);
        } else if (request instanceof StartFileRecoveryRequest) {
            sourceHandler = new FileRecoverySourceHandler(primary,
                (FileAndOpsRecoveryTargetHandler) recoveryTarget, (StartFileRecoveryRequest) request,
                (int) ByteSizeUnit.MB.toBytes(1), nodeSettings);
        } else {
            throw new UnsupportedOperationException("recovery type [" +
                recoveryTarget.getClass().getName() + "] is not yet supported");
        }
        sourceHandler.recoverToTarget();
        recoveryTarget.markAsDone();
        replica.postRecovery("peer recovery done");
        replica.updateRoutingEntry(ShardRoutingHelper.moveToStarted(replica.routingEntry()));
    }

    private void markReplicaAsRecovering(IndexShard replica, IndexShard primary) {
        final DiscoveryNode pNode = getFakeDiscoNode(primary.routingEntry().currentNodeId());
        final DiscoveryNode rNode = getFakeDiscoNode(replica.routingEntry().currentNodeId());
        replica.markAsRecovering("peer_ops", new RecoveryState(replica.shardRouting, rNode, pNode));
    }

    protected interface ReplicaRecoveryPreparer {

        /**
         * prepares the shard for the peer recovery and returns the desired {@link RecoveryTarget}.
         * When done, the shard should be in the {@link IndexShardState#RECOVERING} and {@link IndexShard#recoveryState()}
         * should be set.
         *
         * @param replica    replica to be prepared
         * @param sourceNode {@link DiscoveryNode} of the primary
         * @return {@link RecoveryTarget} for this shard.
         */
        RecoveryTarget prepare(IndexShard replica, DiscoveryNode sourceNode) throws IOException;
    }

    protected Set<Uid> getShardDocUIDs(final IndexShard shard) throws IOException {
        shard.refresh("get_uids");
        try (Engine.Searcher searcher = shard.acquireSearcher("test")) {
            Set<Uid> ids = new HashSet<>();
            for (LeafReaderContext leafContext : searcher.reader().leaves()) {
                LeafReader reader = leafContext.reader();
                Bits liveDocs = reader.getLiveDocs();
                for (int i = 0; i < reader.maxDoc(); i++) {
                    if (liveDocs == null || liveDocs.get(i)) {
                        Document uuid = reader.document(i, Collections.singleton(UidFieldMapper.NAME));
                        ids.add(Uid.createUid(uuid.get(UidFieldMapper.NAME)));
                    }
                }
            }
            return ids;
        }
    }

    protected void assertDocCount(IndexShard shard, int docDount) throws IOException {
        assertThat(getShardDocUIDs(shard), hasSize(docDount));
    }

    protected void assertDocs(IndexShard shard, Uid... uids) throws IOException {
        final Set<Uid> shardDocUIDs = getShardDocUIDs(shard);
        assertThat(shardDocUIDs, contains(uids));
        assertThat(shardDocUIDs, hasSize(uids.length));
    }


    protected Engine.Index indexDoc(IndexShard shard, String type, String id) throws IOException {
        return indexDoc(shard, type, id, "{}");
    }

    protected Engine.Index indexDoc(IndexShard shard, String type, String id, String source) throws IOException {
        return indexDoc(shard, type, id, source, XContentType.JSON);
    }

    protected Engine.Index indexDoc(IndexShard shard, String type, String id, String source, XContentType xContentType) throws IOException {
        final Engine.Index index;
        if (shard.routingEntry().primary()) {
            index = shard.prepareIndexOnPrimary(
                SourceToParse.source(SourceToParse.Origin.PRIMARY, shard.shardId().getIndexName(), type, id, new BytesArray(source),
                    xContentType),
                Versions.MATCH_ANY,
                VersionType.INTERNAL,
                IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP,
                false);
        } else {
            index = shard.prepareIndexOnReplica(
                SourceToParse.source(SourceToParse.Origin.PRIMARY, shard.shardId().getIndexName(), type, id, new BytesArray(source),
                    xContentType),
                randomInt(1 << 10), 1, VersionType.EXTERNAL, IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP, false);
        }
        shard.index(index);
        return index;
    }

    protected Engine.Delete deleteDoc(IndexShard shard, String type, String id) throws IOException {
        final Engine.Delete delete;
        if (shard.routingEntry().primary()) {
            delete = shard.prepareDeleteOnPrimary(type, id, Versions.MATCH_ANY, VersionType.INTERNAL);
        } else {
            delete = shard.prepareDeleteOnPrimary(type, id, 1, VersionType.EXTERNAL);
        }
        shard.delete(delete);
        return delete;
    }

    protected void flushShard(IndexShard shard) {
        flushShard(shard, false);
    }

    protected void flushShard(IndexShard shard, boolean force) {
        shard.flush(new FlushRequest(shard.shardId().getIndexName()).force(force));
    }
}
