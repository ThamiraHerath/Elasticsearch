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

package org.elasticsearch.repositories.blobstore;

import org.apache.lucene.store.RateLimiter;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.SnapshotName;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.compress.NotXContentException;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.OutputStreamStreamOutput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.metrics.CounterMetric;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.IndexShardRepository;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardRepository;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardRepository.RateLimiterListener;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryException;
import org.elasticsearch.repositories.RepositorySettings;
import org.elasticsearch.repositories.RepositoryVerificationException;
import org.elasticsearch.snapshots.InvalidSnapshotNameException;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotCreationException;
import org.elasticsearch.snapshots.SnapshotException;
import org.elasticsearch.snapshots.SnapshotMissingException;
import org.elasticsearch.snapshots.SnapshotShardFailure;
import org.elasticsearch.snapshots.SnapshotId;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BlobStore - based implementation of Snapshot Repository
 * <p>
 * This repository works with any {@link BlobStore} implementation. The blobStore should be initialized in the derived
 * class before {@link #doStart()} is called.
 * <p>
 * BlobStoreRepository maintains the following structure in the blob store
 * <pre>
 * {@code
 *   STORE_ROOT
 *   |- index             - list of all snapshot name as JSON array
 *   |- snapshot-20131010 - JSON serialized Snapshot for snapshot "20131010"
 *   |- meta-20131010.dat - JSON serialized MetaData for snapshot "20131010" (includes only global metadata)
 *   |- snapshot-20131011 - JSON serialized Snapshot for snapshot "20131011"
 *   |- meta-20131011.dat - JSON serialized MetaData for snapshot "20131011"
 *   .....
 *   |- indices/ - data for all indices
 *      |- foo/ - data for index "foo"
 *      |  |- meta-20131010.dat - JSON Serialized IndexMetaData for index "foo"
 *      |  |- 0/ - data for shard "0" of index "foo"
 *      |  |  |- __1 \
 *      |  |  |- __2 |
 *      |  |  |- __3 |- files from different segments see snapshot-* for their mappings to real segment files
 *      |  |  |- __4 |
 *      |  |  |- __5 /
 *      |  |  .....
 *      |  |  |- snap-20131010.dat - JSON serialized BlobStoreIndexShardSnapshot for snapshot "20131010"
 *      |  |  |- snap-20131011.dat - JSON serialized BlobStoreIndexShardSnapshot for snapshot "20131011"
 *      |  |  |- list-123 - JSON serialized BlobStoreIndexShardSnapshot for snapshot "20131011"
 *      |  |
 *      |  |- 1/ - data for shard "1" of index "foo"
 *      |  |  |- __1
 *      |  |  .....
 *      |  |
 *      |  |-2/
 *      |  ......
 *      |
 *      |- bar/ - data for index bar
 *      ......
 * }
 * </pre>
 */
public abstract class BlobStoreRepository extends AbstractLifecycleComponent<Repository> implements Repository, RateLimiterListener {

    private BlobContainer snapshotsBlobContainer;

    protected final String repositoryName;

    private static final String LEGACY_SNAPSHOT_PREFIX = "snapshot-";

    private static final String SNAPSHOT_PREFIX = "snap-";

    private static final String SNAPSHOT_SUFFIX = ".dat";

    private static final String SNAPSHOT_CODEC = "snapshot";

    private static final String SNAPSHOTS_FILE = "index";

    private static final String TESTS_FILE = "tests-";

    private static final String METADATA_NAME_FORMAT = "meta-%s.dat";

    private static final String LEGACY_METADATA_NAME_FORMAT = "metadata-%s";

    private static final String METADATA_CODEC = "metadata";

    private static final String INDEX_METADATA_CODEC = "index-metadata";

    private static final String SNAPSHOT_NAME_FORMAT = SNAPSHOT_PREFIX + "%s" + SNAPSHOT_SUFFIX;

    private static final String LEGACY_SNAPSHOT_NAME_FORMAT = LEGACY_SNAPSHOT_PREFIX + "%s";


    private final BlobStoreIndexShardRepository indexShardRepository;

    private final RateLimiter snapshotRateLimiter;

    private final RateLimiter restoreRateLimiter;

    private final CounterMetric snapshotRateLimitingTimeInNanos = new CounterMetric();

    private final CounterMetric restoreRateLimitingTimeInNanos = new CounterMetric();

    private ChecksumBlobStoreFormat<MetaData> globalMetaDataFormat;

    private LegacyBlobStoreFormat<MetaData> globalMetaDataLegacyFormat;

    private ChecksumBlobStoreFormat<IndexMetaData> indexMetaDataFormat;

    private LegacyBlobStoreFormat<IndexMetaData> indexMetaDataLegacyFormat;

    private ChecksumBlobStoreFormat<Snapshot> snapshotFormat;

    private LegacyBlobStoreFormat<Snapshot> snapshotLegacyFormat;

    private final boolean readOnly;

    /**
     * Constructs new BlobStoreRepository
     *
     * @param repositoryName       repository name
     * @param repositorySettings   repository settings
     * @param indexShardRepository an instance of IndexShardRepository
     */
    protected BlobStoreRepository(String repositoryName, RepositorySettings repositorySettings, IndexShardRepository indexShardRepository) {
        super(repositorySettings.globalSettings());
        this.repositoryName = repositoryName;
        this.indexShardRepository = (BlobStoreIndexShardRepository) indexShardRepository;
        snapshotRateLimiter = getRateLimiter(repositorySettings, "max_snapshot_bytes_per_sec", new ByteSizeValue(40, ByteSizeUnit.MB));
        restoreRateLimiter = getRateLimiter(repositorySettings, "max_restore_bytes_per_sec", new ByteSizeValue(40, ByteSizeUnit.MB));
        readOnly = repositorySettings.settings().getAsBoolean("readonly", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart() {
        this.snapshotsBlobContainer = blobStore().blobContainer(basePath());
        indexShardRepository.initialize(blobStore(), basePath(), chunkSize(), snapshotRateLimiter, restoreRateLimiter, this, isCompress());

        ParseFieldMatcher parseFieldMatcher = new ParseFieldMatcher(settings);
        globalMetaDataFormat = new ChecksumBlobStoreFormat<>(METADATA_CODEC, METADATA_NAME_FORMAT, MetaData.PROTO, parseFieldMatcher, isCompress());
        globalMetaDataLegacyFormat = new LegacyBlobStoreFormat<>(LEGACY_METADATA_NAME_FORMAT, MetaData.PROTO, parseFieldMatcher);

        indexMetaDataFormat = new ChecksumBlobStoreFormat<>(INDEX_METADATA_CODEC, METADATA_NAME_FORMAT, IndexMetaData.PROTO, parseFieldMatcher, isCompress());
        indexMetaDataLegacyFormat = new LegacyBlobStoreFormat<>(LEGACY_SNAPSHOT_NAME_FORMAT, IndexMetaData.PROTO, parseFieldMatcher);

        snapshotFormat = new ChecksumBlobStoreFormat<>(SNAPSHOT_CODEC, SNAPSHOT_NAME_FORMAT, Snapshot.PROTO, parseFieldMatcher, isCompress());
        snapshotLegacyFormat = new LegacyBlobStoreFormat<>(LEGACY_SNAPSHOT_NAME_FORMAT, Snapshot.PROTO, parseFieldMatcher);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStop() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doClose() {
        try {
            blobStore().close();
        } catch (Throwable t) {
            logger.warn("cannot close blob store", t);
        }
    }

    /**
     * Returns initialized and ready to use BlobStore
     * <p>
     * This method is first called in the {@link #doStart()} method.
     *
     * @return blob store
     */
    abstract protected BlobStore blobStore();

    /**
     * Returns base path of the repository
     */
    abstract protected BlobPath basePath();

    /**
     * Returns true if metadata and snapshot files should be compressed
     *
     * @return true if compression is needed
     */
    protected boolean isCompress() {
        return false;
    }

    /**
     * Returns data file chunk size.
     * <p>
     * This method should return null if no chunking is needed.
     *
     * @return chunk size
     */
    protected ByteSizeValue chunkSize() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initializeSnapshot(SnapshotId snapshotId, List<String> indices, MetaData metaData) {
        if (readOnly()) {
            throw new RepositoryException(this.repositoryName, "cannot create snapshot in a readonly repository");
        }
        try {
            if (snapshotFormat.exists(snapshotsBlobContainer, snapshotId.blobId()) ||
                    snapshotLegacyFormat.exists(snapshotsBlobContainer, snapshotId.getName())) {
                throw new InvalidSnapshotNameException(snapshotId.getSnapshotName(), "snapshot with such name already exists");
            }
            // Write Global MetaData
            globalMetaDataFormat.write(metaData, snapshotsBlobContainer, snapshotId.blobId());
            for (String index : indices) {
                final IndexMetaData indexMetaData = metaData.index(index);
                final BlobPath indexPath = basePath().add("indices").add(index);
                final BlobContainer indexMetaDataBlobContainer = blobStore().blobContainer(indexPath);
                indexMetaDataFormat.write(indexMetaData, indexMetaDataBlobContainer, snapshotId.blobId());
            }
        } catch (IOException ex) {
            throw new SnapshotCreationException(snapshotId.getSnapshotName(), ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteSnapshot(final SnapshotId snapshotId) {
        if (readOnly()) {
            throw new RepositoryException(this.repositoryName, "cannot delete snapshot from a readonly repository");
        }
        List<String> indices = Collections.emptyList();
        Snapshot snapshot = null;
        try {
            snapshot = readSnapshot(snapshotId);
            indices = snapshot.indices();
        } catch (SnapshotMissingException ex) {
            throw ex;
        } catch (IllegalStateException | SnapshotException | ElasticsearchParseException ex) {
            logger.warn("cannot read snapshot file [{}]", ex, snapshotId);
        }
        MetaData metaData = null;
        try {
            metaData = readSnapshotMetaData(snapshotId, indices, true);
        } catch (IOException | SnapshotException ex) {
            logger.warn("cannot read metadata for snapshot [{}]", ex, snapshotId);
        }
        try {
            // Delete snapshot file first so we wouldn't end up with partially deleted snapshot that looks OK
            if (snapshot != null) {
                snapshotFormat(snapshot.version()).delete(snapshotsBlobContainer, snapshotId.blobId());
                globalMetaDataFormat(snapshot.version()).delete(snapshotsBlobContainer, snapshotId.blobId());
            } else {
                // We don't know which version was the snapshot created with - try deleting both current and legacy formats
                snapshotFormat.delete(snapshotsBlobContainer, snapshotId.blobId());
                snapshotLegacyFormat.delete(snapshotsBlobContainer, snapshotId.blobId());
                globalMetaDataLegacyFormat.delete(snapshotsBlobContainer, snapshotId.blobId());
                globalMetaDataFormat.delete(snapshotsBlobContainer, snapshotId.blobId());
            }
            // Delete snapshot from the snapshot list
            Map<String, SnapshotId> snapshotIds = readSnapshotList();
            if (snapshotIds.containsKey(snapshotId.getName())) {
                Map<String, SnapshotId> builder = new HashMap<>(snapshotIds);
                builder.remove(snapshotId.getName());
                writeSnapshotList(builder.values());
            }
            // Now delete all indices
            for (String index : indices) {
                BlobPath indexPath = basePath().add("indices").add(index);
                BlobContainer indexMetaDataBlobContainer = blobStore().blobContainer(indexPath);
                try {
                    indexMetaDataFormat.delete(indexMetaDataBlobContainer, snapshotId.blobId());
                } catch (IOException ex) {
                    logger.warn("[{}] failed to delete metadata for index [{}]", ex, snapshotId, index);
                }
                if (metaData != null) {
                    IndexMetaData indexMetaData = metaData.index(index);
                    if (indexMetaData != null) {
                        for (int shardId = 0; shardId < indexMetaData.getNumberOfShards(); shardId++) {
                            try {
                                indexShardRepository.delete(snapshotId, snapshot.version(), new ShardId(indexMetaData.getIndex(), shardId));
                            } catch (SnapshotException ex) {
                                logger.warn("[{}] failed to delete shard data for shard [{}][{}]", ex, snapshotId, index, shardId);
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new RepositoryException(this.repositoryName, "failed to update snapshot in repository", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snapshot finalizeSnapshot(SnapshotId snapshotId, List<String> indices, long startTime, String failure, int totalShards, List<SnapshotShardFailure> shardFailures) {
        try {
            Snapshot blobStoreSnapshot = new Snapshot(snapshotId.getName(), snapshotId.getUUID(), indices, startTime, failure, System.currentTimeMillis(), totalShards, shardFailures);
            snapshotFormat.write(blobStoreSnapshot, snapshotsBlobContainer, snapshotId.blobId());
            Map<String, SnapshotId> snapshots = readSnapshotList();
            if (!snapshots.containsKey(snapshotId.getName())) {
                List<SnapshotId> builder = new ArrayList<>(snapshots.values());
                builder.add(snapshotId);
                writeSnapshotList(builder);
            }
            return blobStoreSnapshot;
        } catch (IOException ex) {
            throw new RepositoryException(this.repositoryName, "failed to update snapshot in repository", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SnapshotId> snapshots() {
        try {
            return Collections.unmodifiableList(new ArrayList<>(readSnapshotList().values()));
        } catch (IOException ex) {
            throw new RepositoryException(repositoryName, "failed to list snapshots in repository", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MetaData readSnapshotMetaData(SnapshotId snapshotId, List<String> indices) throws IOException {
        return readSnapshotMetaData(snapshotId, indices, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snapshot readSnapshot(SnapshotId snapshotId) {
        try {
            return snapshotFormat.read(snapshotsBlobContainer, snapshotId.blobId());
        } catch (FileNotFoundException | NoSuchFileException ex) {
            // File is missing - let's try legacy format instead
            try {
                return snapshotLegacyFormat.read(snapshotsBlobContainer, snapshotId.getName());
            } catch (FileNotFoundException | NoSuchFileException ex1) {
                throw new SnapshotMissingException(snapshotId.getSnapshotName(), ex);
            } catch (IOException | NotXContentException ex1) {
                throw new SnapshotException(snapshotId.getSnapshotName(), "failed to get snapshots", ex1);
            }
        } catch (IOException | NotXContentException ex) {
            throw new SnapshotException(snapshotId.getSnapshotName(), "failed to get snapshots", ex);
        }
    }

    private MetaData readSnapshotMetaData(SnapshotId snapshotId, List<String> indices, boolean ignoreIndexErrors) throws IOException {
        MetaData metaData;

        try {
            metaData = globalMetaDataFormat.read(snapshotsBlobContainer, snapshotId.blobId());
        } catch (FileNotFoundException | NoSuchFileException ex) {
            throw new SnapshotMissingException(snapshotId.getSnapshotName(), ex);
        } catch (IOException ex) {
            throw new SnapshotException(snapshotId.getSnapshotName(), "failed to get snapshots", ex);
        }
        MetaData.Builder metaDataBuilder = MetaData.builder(metaData);
        for (String index : indices) {
            BlobPath indexPath = basePath().add("indices").add(index);
            BlobContainer indexMetaDataBlobContainer = blobStore().blobContainer(indexPath);
            try {
                metaDataBuilder.put(indexMetaDataFormat.read(indexMetaDataBlobContainer, snapshotId.blobId()), false);
            } catch (ElasticsearchParseException | IOException ex) {
                if (ignoreIndexErrors) {
                    logger.warn("[{}] [{}] failed to read metadata for index", ex, snapshotId, index);
                } else {
                    throw ex;
                }
            }
        }
        return metaDataBuilder.build();
    }

    /**
     * Reads snapshot index file.  This file contains the authoritative set of snapshots
     * in the repository.  We no longer scan the contents of the repository looking for blobs
     * with a matching SNAPSHOT file prefix, because any number of errors could occur through
     * this method including (1) failure to properly delete snapshot files will lead to
     * incorrectly thinking those snapshots are still valid and (2) write-once file systems
     * will not support the deleting of old snapshot files.
     *
     * @return map of snapshots in the repository
     * @throws IOException I/O errors
     */
    protected Map<String, SnapshotId> readSnapshotList() throws IOException {
        try {
            if (snapshotsBlobContainer.blobExists(SNAPSHOTS_FILE) == false) {
                return Collections.emptyMap();
            }
        } catch (UnsupportedOperationException e) {
            // the BlobContainer does not support the blobExists operation, the best we
            // can do is assume the snapshots index file is there and try to read it
            logger.debug("Unsupported blobExists operation to verify the index file exists on disk.");
        }

        try (InputStream blob = snapshotsBlobContainer.readBlob(SNAPSHOTS_FILE)) {
            BytesStreamOutput out = new BytesStreamOutput();
            Streams.copy(blob, out);
            ArrayList<SnapshotId> snapshots = new ArrayList<>();
            try (XContentParser parser = XContentHelper.createParser(out.bytes())) {
                if (parser.nextToken() == XContentParser.Token.START_OBJECT) {
                    if (parser.nextToken() == XContentParser.Token.FIELD_NAME) {
                        String currentFieldName = parser.currentName();
                        if ("snapshots".equals(currentFieldName)) {
                            if (parser.nextToken() == XContentParser.Token.START_ARRAY) {
                                while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                                    // the new format from 5.0 which contains the snapshot name and uuid
                                    String name = null;
                                    String uuid = null;
                                    if (parser.currentToken() == XContentParser.Token.START_OBJECT) {
                                        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                                            currentFieldName = parser.currentName();
                                            parser.nextToken();
                                            if (SnapshotId.Fields.NAME.equals(currentFieldName)) {
                                                name = parser.text();
                                            } else if (SnapshotId.Fields.UUID.equals(currentFieldName)) {
                                                uuid = parser.text();
                                            }
                                        }
                                        snapshots.add(SnapshotId.get(new SnapshotName(repositoryName, name), uuid));
                                    }
                                    // the old format pre 5.0 that only contains the snapshot name, use the name as the uuid too
                                    else {
                                        name = parser.text();
                                        snapshots.add(SnapshotId.get(new SnapshotName(repositoryName, name), SnapshotId.UNASSIGNED_UUID));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return Collections.unmodifiableMap(snapshots.stream().collect(Collectors.toMap(SnapshotId::getName, snapshot -> snapshot)));
        } catch (IOException e) {
            logger.warn("Unable to read from snapshots file", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Writes snapshot index file.  This file contains the authoritative set of snapshots
     * in the repository.
     *
     * @param snapshots snapshots to write to the snapshot index file
     * @throws IOException I/O errors
     */
    private void writeSnapshotList(Collection<SnapshotId> snapshots) throws IOException {
        final BytesReference bRef;
        try(BytesStreamOutput bStream = new BytesStreamOutput()) {
            try(StreamOutput stream = new OutputStreamStreamOutput(bStream)) {
                XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON, stream);
                builder.startObject();
                builder.startArray("snapshots");
                for (SnapshotId snapshot : snapshots) {
                    builder.startObject();
                    builder.field(SnapshotId.Fields.NAME, snapshot.getName());
                    builder.field(SnapshotId.Fields.UUID, snapshot.getUUID());
                    builder.endObject();
                }
                builder.endArray();
                builder.endObject();
                builder.close();
            }
            bRef = bStream.bytes();
        }
        if (snapshotsBlobContainer.blobExists(SNAPSHOTS_FILE)) {
            snapshotsBlobContainer.deleteBlob(SNAPSHOTS_FILE);
        }
        snapshotsBlobContainer.writeBlob(SNAPSHOTS_FILE, bRef);
    }

    /**
     * Configures RateLimiter based on repository and global settings
     *
     * @param repositorySettings repository settings
     * @param setting            setting to use to configure rate limiter
     * @param defaultRate        default limiting rate
     * @return rate limiter or null of no throttling is needed
     */
    private RateLimiter getRateLimiter(RepositorySettings repositorySettings, String setting, ByteSizeValue defaultRate) {
        ByteSizeValue maxSnapshotBytesPerSec = repositorySettings.settings().getAsBytesSize(setting,
                settings.getAsBytesSize(setting, defaultRate));
        if (maxSnapshotBytesPerSec.bytes() <= 0) {
            return null;
        } else {
            return new RateLimiter.SimpleRateLimiter(maxSnapshotBytesPerSec.mbFrac());
        }
    }

    /**
     * Returns appropriate global metadata format based on the provided version of the snapshot
     */
    private BlobStoreFormat<MetaData> globalMetaDataFormat(Version version) {
        if(legacyMetaData(version)) {
            return globalMetaDataLegacyFormat;
        } else {
            return globalMetaDataFormat;
        }
    }

    /**
     * Returns appropriate snapshot format based on the provided version of the snapshot
     */
    private BlobStoreFormat<Snapshot> snapshotFormat(Version version) {
        if(legacyMetaData(version)) {
            return snapshotLegacyFormat;
        } else {
            return snapshotFormat;
        }
    }

    /**
     * In v2.0.0 we changed the metadata file format
     * @return true if legacy version should be used false otherwise
     */
    public static boolean legacyMetaData(Version version) {
        return version.before(Version.V_2_0_0_beta1);
    }

    /**
     * Returns appropriate index metadata format based on the provided version of the snapshot
     */
    private BlobStoreFormat<IndexMetaData> indexMetaDataFormat(Version version) {
        if(legacyMetaData(version)) {
            return indexMetaDataLegacyFormat;
        } else {
            return indexMetaDataFormat;
        }
    }

    @Override
    public void onRestorePause(long nanos) {
        restoreRateLimitingTimeInNanos.inc(nanos);
    }

    @Override
    public void onSnapshotPause(long nanos) {
        snapshotRateLimitingTimeInNanos.inc(nanos);
    }

    @Override
    public long snapshotThrottleTimeInNanos() {
        return snapshotRateLimitingTimeInNanos.count();
    }

    @Override
    public long restoreThrottleTimeInNanos() {
        return restoreRateLimitingTimeInNanos.count();
    }

    @Override
    public String startVerification() {
        try {
            if (readOnly()) {
                // It's readonly - so there is not much we can do here to verify it
                return null;
            } else {
                String seed = UUIDs.randomBase64UUID();
                byte[] testBytes = Strings.toUTF8Bytes(seed);
                BlobContainer testContainer = blobStore().blobContainer(basePath().add(testBlobPrefix(seed)));
                String blobName = "master.dat";
                testContainer.writeBlob(blobName + "-temp", new BytesArray(testBytes));
                // Make sure that move is supported
                testContainer.move(blobName + "-temp", blobName);
                return seed;
            }
        } catch (IOException exp) {
            throw new RepositoryVerificationException(repositoryName, "path " + basePath() + " is not accessible on master node", exp);
        }
    }

    @Override
    public void endVerification(String seed) {
        if (readOnly()) {
            throw new UnsupportedOperationException("shouldn't be called");
        }
        try {
            blobStore().delete(basePath().add(testBlobPrefix(seed)));
        } catch (IOException exp) {
            throw new RepositoryVerificationException(repositoryName, "cannot delete test data at " + basePath(), exp);
        }
    }

    public static String testBlobPrefix(String seed) {
        return TESTS_FILE + seed;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public List<SnapshotId> resolveSnapshotsIds(final String... snapshotNames) {
        List<SnapshotId> ids = new ArrayList<>();
        final List<SnapshotId> snapshotIds = snapshots();
        for (String snapshotName : snapshotNames) {
            SnapshotId resolvedSnapshotId = null;
            for (SnapshotId snapshotId : snapshotIds) {
                if (snapshotName.equals(snapshotId.getName())) {
                    resolvedSnapshotId = snapshotId;
                }
            }
            if (resolvedSnapshotId == null) {
                throw new SnapshotMissingException(new SnapshotName(repositoryName, snapshotName));
            }
            ids.add(resolvedSnapshotId);
        }
        return Collections.unmodifiableList(ids);
    }
}
