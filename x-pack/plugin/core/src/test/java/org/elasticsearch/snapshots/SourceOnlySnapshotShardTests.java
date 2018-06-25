package org.elasticsearch.snapshots;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.RepositoryMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.fs.FsRepository;

import java.io.IOException;
import java.nio.file.Path;

public class SourceOnlySnapshotShardTests extends IndexShardTestCase {

    public void testSourceIncomplete() throws IOException {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(new ShardId("index", "_na_", 0), randomAlphaOfLength(10), true,
            ShardRoutingState.INITIALIZING, RecoverySource.StoreRecoverySource.EMPTY_STORE_INSTANCE);
        Settings settings = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetaData metaData = IndexMetaData.builder(shardRouting.getIndexName())
            .settings(settings)
            .primaryTerm(0, primaryTerm)
            .putMapping("_doc",
                "{\"_source\":{\"enabled\": false}}").build();
        IndexShard shard = newShard(shardRouting, metaData);
        recoverShardFromStore(shard);

        for (int i = 0; i < 1; i++) {
            final String id = Integer.toString(i);
            indexDoc(shard, "_doc", id);
        }
        SnapshotId snapshotId = new SnapshotId("test", "test");
        IndexId indexId = new IndexId(shard.shardId().getIndexName(), shard.shardId().getIndex().getUUID());
        SourceOnlySnapshotRepository repository = new SourceOnlySnapshotRepository(createRepository(), false);
        repository.start();
        try (Engine.IndexCommitRef snapshotRef = shard.acquireLastIndexCommit(true)) {
            IndexShardSnapshotStatus indexShardSnapshotStatus = IndexShardSnapshotStatus.newInitializing();
            IllegalStateException illegalStateException = expectThrows(IllegalStateException.class, () ->
                repository.snapshotShard(shard, shard.store(), snapshotId, indexId, snapshotRef.getIndexCommit(),
                    indexShardSnapshotStatus));
            assertEquals("Can't snapshot _source only on an index that has incomplete source ie. has _source disabled or filters the source"
                , illegalStateException.getMessage());
        }
        closeShards(shard);
    }

    public void testIncrementalSnapshot() throws IOException {
        IndexShard shard = newStartedShard();
        for (int i = 0; i < 10; i++) {
            final String id = Integer.toString(i);
            indexDoc(shard, "_doc", id);
        }

        SnapshotId snapshotId = new SnapshotId("test", "test");
        IndexId indexId = new IndexId(shard.shardId().getIndexName(), shard.shardId().getIndex().getUUID());
        SourceOnlySnapshotRepository repository = new SourceOnlySnapshotRepository(createRepository(), false);
        repository.start();
        int totalFileCount = -1;
        try (Engine.IndexCommitRef snapshotRef = shard.acquireLastIndexCommit(true)) {
            IndexShardSnapshotStatus indexShardSnapshotStatus = IndexShardSnapshotStatus.newInitializing();
            repository.snapshotShard(shard, shard.store(), snapshotId, indexId, snapshotRef.getIndexCommit(), indexShardSnapshotStatus);
            IndexShardSnapshotStatus.Copy copy = indexShardSnapshotStatus.asCopy();
            assertEquals(copy.getTotalFileCount(), copy.getIncrementalFileCount());
            totalFileCount = copy.getTotalFileCount();
            assertEquals(copy.getStage(), IndexShardSnapshotStatus.Stage.DONE);
        }

        indexDoc(shard, "_doc", Integer.toString(10));
        indexDoc(shard, "_doc", Integer.toString(11));
        snapshotId = new SnapshotId("test_1", "test_1");
        try (Engine.IndexCommitRef snapshotRef = shard.acquireLastIndexCommit(true)) {
            IndexShardSnapshotStatus indexShardSnapshotStatus = IndexShardSnapshotStatus.newInitializing();
            repository.snapshotShard(shard, shard.store(), snapshotId, indexId, snapshotRef.getIndexCommit(), indexShardSnapshotStatus);
            IndexShardSnapshotStatus.Copy copy = indexShardSnapshotStatus.asCopy();
            // we processed the segments_N file plus _1.si, _1.fdx, _1.fnm, _1.fdt
            assertEquals(5, copy.getIncrementalFileCount());
            // in total we have 4 more files than the previous snap since we don't count the segments_N twice
            assertEquals(totalFileCount+4, copy.getTotalFileCount());
            assertEquals(copy.getStage(), IndexShardSnapshotStatus.Stage.DONE);
        }
        deleteDoc(shard, "_doc", Integer.toString(10));
        snapshotId = new SnapshotId("test_2", "test_2");
        try (Engine.IndexCommitRef snapshotRef = shard.acquireLastIndexCommit(true)) {
            IndexShardSnapshotStatus indexShardSnapshotStatus = IndexShardSnapshotStatus.newInitializing();
            repository.snapshotShard(shard, shard.store(), snapshotId, indexId, snapshotRef.getIndexCommit(), indexShardSnapshotStatus);
            IndexShardSnapshotStatus.Copy copy = indexShardSnapshotStatus.asCopy();
            // we processed the segments_N file plus _1_1.liv
            assertEquals(2, copy.getIncrementalFileCount());
            // in total we have 5 more files than the previous snap since we don't count the segments_N twice
            assertEquals(totalFileCount+5, copy.getTotalFileCount());
            assertEquals(copy.getStage(), IndexShardSnapshotStatus.Stage.DONE);
        }
        closeShards(shard);
    }

    private String randomDoc() {
        return "{ \"value\" : \"" + randomAlphaOfLength(10) + "\"}";
    }

    public void testRestoreAndReindex() throws IOException {
        IndexShard shard = newStartedShard(true);
        int numInitialDocs = randomIntBetween(10, 100);
        for (int i = 0; i < numInitialDocs; i++) {
            final String id = Integer.toString(i);
            indexDoc(shard, "_doc", id, randomDoc());
            if (randomBoolean()) {
                shard.refresh("test");
            }
        }
        for (int i = 0; i < numInitialDocs; i++) {
            final String id = Integer.toString(i);
            if (randomBoolean()) {
                if (rarely()) {
                    deleteDoc(shard, "_doc", id);
                } else {
                    indexDoc(shard, "_doc", id, randomDoc());
                }
            }
            if (frequently()) {
                shard.refresh("test");
            }
        }
        SnapshotId snapshotId = new SnapshotId("test", "test");
        IndexId indexId = new IndexId(shard.shardId().getIndexName(), shard.shardId().getIndex().getUUID());
        SourceOnlySnapshotRepository repository = new SourceOnlySnapshotRepository(createRepository(), false);
        repository.start();
        try (Engine.IndexCommitRef snapshotRef = shard.acquireLastIndexCommit(true)) {
            IndexShardSnapshotStatus indexShardSnapshotStatus = IndexShardSnapshotStatus.newInitializing();
            repository.snapshotShard(shard, shard.store(), snapshotId, indexId, snapshotRef.getIndexCommit(), indexShardSnapshotStatus);
            IndexShardSnapshotStatus.Copy copy = indexShardSnapshotStatus.asCopy();
            assertEquals(copy.getTotalFileCount(), copy.getIncrementalFileCount());
            assertEquals(copy.getStage(), IndexShardSnapshotStatus.Stage.DONE);
        }
        shard.refresh("test");
        ShardRouting shardRouting = TestShardRouting.newShardRouting(new ShardId("index", "_na_", 0), randomAlphaOfLength(10), true,
            ShardRoutingState.INITIALIZING,
            new RecoverySource.SnapshotRecoverySource(new Snapshot("src_only", snapshotId), Version.CURRENT, indexId.getId()));
        IndexShard restoredShard = newShard(shardRouting);
        restoredShard.mapperService().merge(shard.indexSettings().getIndexMetaData(), MapperService.MergeReason.MAPPING_RECOVERY);
        DiscoveryNode discoveryNode = new DiscoveryNode("node_g", buildNewFakeTransportAddress(), Version.CURRENT);
        restoredShard.markAsRecovering("test from snap", new RecoveryState(restoredShard.routingEntry(), discoveryNode, null));
        assertTrue(restoredShard.restoreFromRepository(repository));
        assertEquals(restoredShard.recoveryState().getStage(), RecoveryState.Stage.DONE);
        assertEquals(restoredShard.recoveryState().getTranslog().recoveredOperations(), shard.docStats().getCount());
        assertEquals(IndexShardState.POST_RECOVERY, restoredShard.state());
        restoredShard.refresh("test");
        assertEquals(restoredShard.docStats().getCount(), shard.docStats().getCount());
        assertEquals(0, restoredShard.docStats().getDeleted());
        for (int i = 0; i < numInitialDocs; i++) {
            Engine.Get get = new Engine.Get(false, false, "_doc", Integer.toString(i), new Term("_id", Uid.encodeId(Integer.toString(i))));
            Engine.GetResult original = shard.get(get);
            Engine.GetResult restored = restoredShard.get(get);
            assertEquals(original.exists(), restored.exists());
            if (original.exists()) {
                Document document = original.docIdAndVersion().reader.document(original.docIdAndVersion().docId);
                Document restoredDocument = restored.docIdAndVersion().reader.document(restored.docIdAndVersion().docId);
                for (IndexableField field : document) {
                    assertEquals(document.get(field.name()), restoredDocument.get(field.name()));
                }
            }
            IOUtils.close(original, restored);
        }

        closeShards(shard, restoredShard);
    }


    /** Create a {@link Environment} with random path.home and path.repo **/
    private Environment createEnvironment() {
        Path home = createTempDir();
        return TestEnvironment.newEnvironment(Settings.builder()
            .put(Environment.PATH_HOME_SETTING.getKey(), home.toAbsolutePath())
            .put(Environment.PATH_REPO_SETTING.getKey(), home.resolve("repo").toAbsolutePath())
            .build());
    }

    /** Create a {@link Repository} with a random name **/
    private Repository createRepository() throws IOException {
        Settings settings = Settings.builder().put("location", randomAlphaOfLength(10)).build();
        RepositoryMetaData repositoryMetaData = new RepositoryMetaData(randomAlphaOfLength(10), FsRepository.TYPE, settings);
        return new FsRepository(repositoryMetaData, createEnvironment(), xContentRegistry());
    }

}
