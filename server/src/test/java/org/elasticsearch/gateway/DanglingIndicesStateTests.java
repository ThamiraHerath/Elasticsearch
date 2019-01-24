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
package org.elasticsearch.gateway;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexGraveyard;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.Index;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ESTestCase;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;

public class DanglingIndicesStateTests extends ESTestCase {

    private static Settings indexSettings = Settings.builder()
            .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .build();

    public void testCleanupWhenEmpty() throws Exception {
        try (NodeEnvironment env = newNodeEnvironment()) {
            MetaStateService metaStateService = new MetaStateService(env, xContentRegistry());
            DanglingIndicesState danglingState = createDanglingIndicesState(env, metaStateService);

            assertTrue(danglingState.getDanglingIndices().isEmpty());
            MetaData metaData = MetaData.builder().build();
            danglingState.cleanupAllocatedDangledIndices(metaData);
            assertTrue(danglingState.getDanglingIndices().isEmpty());
        }
    }
    public void testDanglingIndicesDiscovery() throws Exception {
        try (NodeEnvironment env = newNodeEnvironment()) {
            MetaStateService metaStateService = new MetaStateService(env, xContentRegistry());
            DanglingIndicesState danglingState = createDanglingIndicesState(env, metaStateService);

            assertTrue(danglingState.getDanglingIndices().isEmpty());
            MetaData metaData = MetaData.builder().build();
            final Settings.Builder settings = Settings.builder().put(indexSettings).put(IndexMetaData.SETTING_INDEX_UUID, "test1UUID");
            IndexMetaData dangledIndex = IndexMetaData.builder("test1").settings(settings).build();
            metaStateService.writeIndex("test_write", dangledIndex);
            Map<Index, IndexMetaData> newDanglingIndices = danglingState.findNewDanglingIndices(metaData);
            assertTrue(newDanglingIndices.containsKey(dangledIndex.getIndex()));
            metaData = MetaData.builder().put(dangledIndex, false).build();
            newDanglingIndices = danglingState.findNewDanglingIndices(metaData);
            assertFalse(newDanglingIndices.containsKey(dangledIndex.getIndex()));
        }
    }

    public void testInvalidIndexFolder() throws Exception {
        try (NodeEnvironment env = newNodeEnvironment()) {
            MetaStateService metaStateService = new MetaStateService(env, xContentRegistry());
            DanglingIndicesState danglingState = createDanglingIndicesState(env, metaStateService);

            MetaData metaData = MetaData.builder().build();
            final String uuid = "test1UUID";
            final Settings.Builder settings = Settings.builder().put(indexSettings).put(IndexMetaData.SETTING_INDEX_UUID, uuid);
            IndexMetaData dangledIndex = IndexMetaData.builder("test1").settings(settings).build();
            metaStateService.writeIndex("test_write", dangledIndex);
            for (Path path : env.resolveIndexFolder(uuid)) {
                if (Files.exists(path)) {
                    Files.move(path, path.resolveSibling("invalidUUID"), StandardCopyOption.ATOMIC_MOVE);
                }
            }
            try {
                danglingState.findNewDanglingIndices(metaData);
                fail("no exception thrown for invalid folder name");
            } catch (IllegalStateException e) {
                assertThat(e.getMessage(), equalTo("[invalidUUID] invalid index folder name, rename to [test1UUID]"));
            }
        }
    }

    public void testDanglingProcessing() throws Exception {
        try (NodeEnvironment env = newNodeEnvironment()) {
            MetaStateService metaStateService = new MetaStateService(env, xContentRegistry());
            DanglingIndicesState danglingState = createDanglingIndicesState(env, metaStateService);

            MetaData metaData = MetaData.builder().build();

            final Settings.Builder settings = Settings.builder().put(indexSettings).put(IndexMetaData.SETTING_INDEX_UUID, "test1UUID");
            IndexMetaData dangledIndex = IndexMetaData.builder("test1").settings(settings).build();
            metaStateService.writeIndex("test_write", dangledIndex);

            // check that several runs when not in the metadata still keep the dangled index around
            int numberOfChecks = randomIntBetween(1, 10);
            for (int i = 0; i < numberOfChecks; i++) {
                Map<Index, IndexMetaData> newDanglingIndices = danglingState.findNewDanglingIndices(metaData);
                assertThat(newDanglingIndices.size(), equalTo(1));
                assertThat(newDanglingIndices.keySet(), Matchers.hasItems(dangledIndex.getIndex()));
                assertTrue(danglingState.getDanglingIndices().isEmpty());
            }

            for (int i = 0; i < numberOfChecks; i++) {
                danglingState.findNewAndAddDanglingIndices(metaData);

                assertThat(danglingState.getDanglingIndices().size(), equalTo(1));
                assertThat(danglingState.getDanglingIndices().keySet(), Matchers.hasItems(dangledIndex.getIndex()));
            }

            // simulate allocation to the metadata
            metaData = MetaData.builder(metaData).put(dangledIndex, true).build();

            // check that several runs when in the metadata, but not cleaned yet, still keeps dangled
            for (int i = 0; i < numberOfChecks; i++) {
                Map<Index, IndexMetaData> newDanglingIndices = danglingState.findNewDanglingIndices(metaData);
                assertTrue(newDanglingIndices.isEmpty());

                assertThat(danglingState.getDanglingIndices().size(), equalTo(1));
                assertThat(danglingState.getDanglingIndices().keySet(), Matchers.hasItems(dangledIndex.getIndex()));
            }

            danglingState.cleanupAllocatedDangledIndices(metaData);
            assertTrue(danglingState.getDanglingIndices().isEmpty());
        }
    }

    public void testDanglingIndicesNotImportedWhenTombstonePresent() throws Exception {
        try (NodeEnvironment env = newNodeEnvironment()) {
            MetaStateService metaStateService = new MetaStateService(env, xContentRegistry());
            DanglingIndicesState danglingState = createDanglingIndicesState(env, metaStateService);

            final Settings.Builder settings = Settings.builder().put(indexSettings).put(IndexMetaData.SETTING_INDEX_UUID, "test1UUID");
            IndexMetaData dangledIndex = IndexMetaData.builder("test1").settings(settings).build();
            metaStateService.writeIndex("test_write", dangledIndex);

            final IndexGraveyard graveyard = IndexGraveyard.builder().addTombstone(dangledIndex.getIndex()).build();
            final MetaData metaData = MetaData.builder().indexGraveyard(graveyard).build();
            assertThat(danglingState.findNewDanglingIndices(metaData).size(), equalTo(0));

        }
    }

    public void testDanglingIndicesIgnoredWhenObsolete() throws IOException {
        Settings noDataNoMasterSettings = Settings.builder()
            .put(Node.NODE_DATA_SETTING.getKey(), false)
            .put(Node.NODE_MASTER_SETTING.getKey(), false)
            .build();

        Settings noDataSettings = Settings.builder()
            .put(Node.NODE_DATA_SETTING.getKey(), false)
            .build();

        Settings noMasterSettings = Settings.builder()
            .put(Node.NODE_MASTER_SETTING.getKey(), false)
            .build();

        verifyDanglingIndicesIgnoredWhenObsolete(noDataNoMasterSettings, 0,
            "node.data=false and node.master=false nodes should not detect any dangling indices");
        verifyDanglingIndicesIgnoredWhenObsolete(noDataSettings, 1,
            "node.data=false and node.master=true nodes should detect dangling indices");
        verifyDanglingIndicesIgnoredWhenObsolete(noMasterSettings, 1,
            "node.data=true and node.master=false nodes should detect dangling indices");
        // also validated by #testDanglingIndicesDiscovery, included for completeness.
        verifyDanglingIndicesIgnoredWhenObsolete(Settings.EMPTY, 1,
            "node.data=true and node.master=true nodes should detect dangling indices");
    }

    private void verifyDanglingIndicesIgnoredWhenObsolete(Settings settings, int expected, String reason) throws IOException {
        try (NodeEnvironment env = newNodeEnvironment(settings)) {
            MetaStateService metaStateService = new MetaStateService(env, xContentRegistry());
            DanglingIndicesState danglingState = createDanglingIndicesState(env, metaStateService, settings);

            final Settings.Builder testIndexSettings = Settings.builder().put(indexSettings)
                .put(IndexMetaData.SETTING_INDEX_UUID, "test1UUID");
            IndexMetaData dangledIndex = IndexMetaData.builder("test1").settings(testIndexSettings).build();
            metaStateService.writeIndex("test_write", dangledIndex);

            assertThat(reason,
                danglingState.findNewDanglingIndices(MetaData.builder().build()).size(),
                equalTo(expected));
        }
    }

    private DanglingIndicesState createDanglingIndicesState(NodeEnvironment env, MetaStateService metaStateService) {
        return new DanglingIndicesState(Settings.EMPTY, env, metaStateService, null, mock(ClusterService.class));
    }

    private DanglingIndicesState createDanglingIndicesState(NodeEnvironment env,
                                                            MetaStateService metaStateService,
                                                            Settings settings) {
        return new DanglingIndicesState(settings, env, metaStateService, null, mock(ClusterService.class));
    }
}
