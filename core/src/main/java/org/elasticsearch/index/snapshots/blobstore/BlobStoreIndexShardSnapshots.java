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

package org.elasticsearch.index.snapshots.blobstore;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot.FileInfo;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

/**
 * Contains information about all snapshot for the given shard in repository
 * <p/>
 * This class is used to find files that were already snapshoted and clear out files that no longer referenced by any
 * snapshots
 */
public class BlobStoreIndexShardSnapshots implements Iterable<SnapshotFiles>, ToXContent {
    private final ImmutableList<SnapshotFiles> shardSnapshots;
    private final ImmutableMap<String, FileInfo> files;
    private final ImmutableMap<String, ImmutableList<FileInfo>> physicalFiles;

    public BlobStoreIndexShardSnapshots(List<SnapshotFiles> shardSnapshots) {
        this.shardSnapshots = ImmutableList.copyOf(shardSnapshots);
        // Map between blob names and file info
        Map<String, FileInfo> newFiles = newHashMap();
        // Map between original physical names and file info
        Map<String, List<FileInfo>> physicalFiles = newHashMap();
        for (SnapshotFiles snapshot : shardSnapshots) {
            // First we build map between filenames in the repo and their original file info
            // this map will be used in the next loop
            for (FileInfo fileInfo : snapshot.indexFiles()) {
                FileInfo oldFile = newFiles.put(fileInfo.name(), fileInfo);
                assert oldFile == null || oldFile.isSame(fileInfo);
            }
            // We are doing it in two loops here so we keep only one copy of the fileInfo per blob
            // the first loop de-duplicates fileInfo objects that were loaded from different snapshots but refer to
            // the same blob
            for (FileInfo fileInfo : snapshot.indexFiles()) {
                List<FileInfo> physicalFileList = physicalFiles.get(fileInfo.physicalName());
                if (physicalFileList == null) {
                    physicalFileList = newArrayList();
                    physicalFiles.put(fileInfo.physicalName(), physicalFileList);
                }
                physicalFileList.add(newFiles.get(fileInfo.name()));
            }
        }
        ImmutableMap.Builder<String, ImmutableList<FileInfo>> mapBuilder = ImmutableMap.builder();
        for (Map.Entry<String, List<FileInfo>> entry : physicalFiles.entrySet()) {
            mapBuilder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
        }
        this.physicalFiles = mapBuilder.build();
        this.files = ImmutableMap.copyOf(newFiles);
    }

    private BlobStoreIndexShardSnapshots(ImmutableMap<String, FileInfo> files, ImmutableList<SnapshotFiles> shardSnapshots) {
        this.shardSnapshots = shardSnapshots;
        this.files = files;
        Map<String, List<FileInfo>> physicalFiles = newHashMap();
        for (SnapshotFiles snapshot : shardSnapshots) {
            for (FileInfo fileInfo : snapshot.indexFiles()) {
                List<FileInfo> physicalFileList = physicalFiles.get(fileInfo.physicalName());
                if (physicalFileList == null) {
                    physicalFileList = newArrayList();
                    physicalFiles.put(fileInfo.physicalName(), physicalFileList);
                }
                physicalFileList.add(files.get(fileInfo.name()));
            }
        }
        ImmutableMap.Builder<String, ImmutableList<FileInfo>> mapBuilder = ImmutableMap.builder();
        for (Map.Entry<String, List<FileInfo>> entry : physicalFiles.entrySet()) {
            mapBuilder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
        }
        this.physicalFiles = mapBuilder.build();
    }


    /**
     * Returns list of snapshots
     *
     * @return list of snapshots
     */
    public List<SnapshotFiles> snapshots() {
        return this.shardSnapshots;
    }

    /**
     * Finds reference to a snapshotted file by its original name
     *
     * @param physicalName original name
     * @return a list of file infos that match specified physical file or null if the file is not present in any of snapshots
     */
    public List<FileInfo> findPhysicalIndexFiles(String physicalName) {
        return physicalFiles.get(physicalName);
    }

    /**
     * Finds reference to a snapshotted file by its snapshot name
     *
     * @param name file name
     * @return file info or null if file is not present in any of snapshots
     */
    public FileInfo findNameFile(String name) {
        return files.get(name);
    }

    @Override
    public Iterator<SnapshotFiles> iterator() {
        return shardSnapshots.iterator();
    }

    static final class Fields {
        static final XContentBuilderString FILES = new XContentBuilderString("files");
        static final XContentBuilderString SNAPSHOTS = new XContentBuilderString("snapshots");
    }

    static final class ParseFields {
        static final ParseField FILES = new ParseField("files");
        static final ParseField SNAPSHOTS = new ParseField("snapshots");
    }

    /**
     * Writes index file for the shard in the following format.
     * <pre>
     * {@code
     * {
     *     "files": [{
     *         "name": "__3",
     *         "physical_name": "_0.si",
     *         "length": 310,
     *         "checksum": "1tpsg3p",
     *         "written_by": "5.1.0",
     *         "meta_hash": "P9dsFxNMdWNlb......"
     *     }, {
     *         "name": "__2",
     *         "physical_name": "segments_2",
     *         "length": 150,
     *         "checksum": "11qjpz6",
     *         "written_by": "5.1.0",
     *         "meta_hash": "P9dsFwhzZWdtZ......."
     *     }, {
     *         "name": "__1",
     *         "physical_name": "_0.cfe",
     *         "length": 363,
     *         "checksum": "er9r9g",
     *         "written_by": "5.1.0"
     *     }, {
     *         "name": "__0",
     *         "physical_name": "_0.cfs",
     *         "length": 3354,
     *         "checksum": "491liz",
     *         "written_by": "5.1.0"
     *     }, {
     *         "name": "__4",
     *         "physical_name": "segments_3",
     *         "length": 150,
     *         "checksum": "134567",
     *         "written_by": "5.1.0",
     *         "meta_hash": "P9dsFwhzZWdtZ......."
     *     }],
     *     "snapshots": {
     *         "snapshot_1": {
     *             "files": ["__0", "__1", "__2", "__3"]
     *         },
     *         "snapshot_2": {
     *             "files": ["__0", "__1", "__2", "__4"]
     *         }
     *     }
     * }
     * }
     * </pre>
     */
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        // First we list all blobs with their file infos:
        builder.startArray(Fields.FILES);
        for (Map.Entry<String, FileInfo> entry : files.entrySet()) {
            FileInfo.toXContent(entry.getValue(), builder, params);
        }
        builder.endArray();
        // Then we list all snapshots with list of all blobs that are used by the snapshot
        builder.startObject(Fields.SNAPSHOTS);
        for (SnapshotFiles snapshot : shardSnapshots) {
            builder.startObject(snapshot.snapshot(), XContentBuilder.FieldCaseConversion.NONE);
            builder.startArray(Fields.FILES);
            for (FileInfo fileInfo : snapshot.indexFiles()) {
                builder.value(fileInfo.name());
            }
            builder.endArray();
            builder.endObject();
        }
        builder.endObject();

        builder.endObject();
        return builder;
    }

    public static BlobStoreIndexShardSnapshots fromXContent(XContentParser parser) throws IOException {
        XContentParser.Token token = parser.currentToken();
        Map<String, List<String>> snapshotsMap = newHashMap();
        ImmutableMap.Builder<String, FileInfo> filesBuilder = ImmutableMap.builder();
        if (token == XContentParser.Token.START_OBJECT) {
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token != XContentParser.Token.FIELD_NAME) {
                    throw new ElasticsearchParseException("unexpected token  [" + token + "]");
                }
                String currentFieldName = parser.currentName();
                token = parser.nextToken();
                if (token == XContentParser.Token.START_ARRAY) {
                    if (ParseFields.FILES.match(currentFieldName) == false) {
                        throw new ElasticsearchParseException("unknown array [" + currentFieldName + "]");
                    }
                    while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                        FileInfo fileInfo = FileInfo.fromXContent(parser);
                        filesBuilder.put(fileInfo.name(), fileInfo);
                    }
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (ParseFields.SNAPSHOTS.match(currentFieldName) == false) {
                        throw new ElasticsearchParseException("unknown object [" + currentFieldName + "]");
                    }
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token != XContentParser.Token.FIELD_NAME) {
                            throw new ElasticsearchParseException("unknown object [" + currentFieldName + "]");
                        }
                        String snapshot = parser.currentName();
                        if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                            throw new ElasticsearchParseException("unknown object [" + currentFieldName + "]");
                        }
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentFieldName = parser.currentName();
                                if (parser.nextToken() == XContentParser.Token.START_ARRAY) {
                                    if (ParseFields.FILES.match(currentFieldName) == false) {
                                        throw new ElasticsearchParseException("unknown array [" + currentFieldName + "]");
                                    }
                                    List<String> fileNames = newArrayList();
                                    while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                                        fileNames.add(parser.text());
                                    }
                                    snapshotsMap.put(snapshot, fileNames);
                                }
                            }
                        }
                    }
                } else {
                    throw new ElasticsearchParseException("unexpected token  [" + token + "]");
                }
            }
        }

        ImmutableMap<String, FileInfo> files = filesBuilder.build();
        ImmutableList.Builder<SnapshotFiles> snapshots = ImmutableList.builder();
        for (Map.Entry<String, List<String>> entry : snapshotsMap.entrySet()) {
            ImmutableList.Builder<FileInfo> fileInfosBuilder = ImmutableList.builder();
            for (String file : entry.getValue()) {
                FileInfo fileInfo = files.get(file);
                assert fileInfo != null;
                fileInfosBuilder.add(fileInfo);
            }
            snapshots.add(new SnapshotFiles(entry.getKey(), fileInfosBuilder.build()));
        }
        return new BlobStoreIndexShardSnapshots(files, snapshots.build());
    }

}
