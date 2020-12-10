/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DataStreamTestHelper;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperServiceTestCase;
import org.elasticsearch.plugins.Plugin;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class MetadataMigrateToDataStreamServiceTests extends MapperServiceTestCase {

    public void testValidateRequestWithNonexistentAlias() {
        ClusterState cs = ClusterState.EMPTY_STATE;
        String nonExistentAlias = "nonexistent_alias";
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> MetadataMigrateToDataStreamService
            .validateRequest(cs, new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(
                nonExistentAlias, TimeValue.ZERO, TimeValue.ZERO)));
        assertThat(e.getMessage(), containsString("alias [" + nonExistentAlias + "] does not exist"));
    }

    public void testValidateRequestWithFilteredAlias() {
        String filteredAliasName = "filtered_alias";
        AliasMetadata filteredAlias = AliasMetadata.builder(filteredAliasName).filter("{\"term\":{\"user.id\":\"kimchy\"}}").build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy")).metadata(
            Metadata.builder().put(IndexMetadata.builder("foo")
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .putAlias(filteredAlias)
                .numberOfShards(1)
                .numberOfReplicas(0))
        ).build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> MetadataMigrateToDataStreamService
            .validateRequest(cs, new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(
                filteredAliasName, TimeValue.ZERO, TimeValue.ZERO)));
        assertThat(e.getMessage(), containsString("alias [" + filteredAliasName + "] may not have custom filtering or routing"));
    }

    public void testValidateRequestWithAliasWithRouting() {
        String routedAliasName = "routed_alias";
        AliasMetadata aliasWithRouting = AliasMetadata.builder(routedAliasName).routing("foo").build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy")).metadata(
            Metadata.builder().put(IndexMetadata.builder("foo")
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .putAlias(aliasWithRouting)
                .numberOfShards(1)
                .numberOfReplicas(0))
        ).build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> MetadataMigrateToDataStreamService
            .validateRequest(cs, new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(
                routedAliasName, TimeValue.ZERO, TimeValue.ZERO)));
        assertThat(e.getMessage(), containsString("alias [" + routedAliasName + "] may not have custom filtering or routing"));
    }

    public void testValidateRequestWithAliasWithoutWriteIndex() {
        String aliasWithoutWriteIndex = "alias";
        AliasMetadata alias1 = AliasMetadata.builder(aliasWithoutWriteIndex).build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy")).metadata(
            Metadata.builder()
                .put(IndexMetadata.builder("foo1")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo2")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo3")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo4")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
        ).build();

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> MetadataMigrateToDataStreamService
            .validateRequest(cs, new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(
                aliasWithoutWriteIndex, TimeValue.ZERO, TimeValue.ZERO)));
        assertThat(e.getMessage(), containsString("alias [" + aliasWithoutWriteIndex + "] must specify a write index"));
    }

    public void testValidateRequest() {
        String aliasName = "alias";
        AliasMetadata alias1 = AliasMetadata.builder(aliasName).build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy")).metadata(
            Metadata.builder()
                .put(IndexMetadata.builder("foo1")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo2")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo3")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo4")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(AliasMetadata.builder(aliasName).writeIndex(true))
                    .numberOfShards(1)
                    .numberOfReplicas(0))
        ).build();
        MetadataMigrateToDataStreamService.validateRequest(cs,
            new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(aliasName, TimeValue.ZERO, TimeValue.ZERO));
    }

    public void testValidateRequestWithIndicesWithMultipleAliasReferences() {
        String aliasName = "alias";
        AliasMetadata alias1 = AliasMetadata.builder(aliasName).build();
        AliasMetadata alias2 = AliasMetadata.builder(aliasName + "2").build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy")).metadata(
            Metadata.builder()
                .put(IndexMetadata.builder("foo1")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo2")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .putAlias(alias2)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo3")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .putAlias(alias2)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
                .put(IndexMetadata.builder("foo4")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putAlias(alias1)
                    .numberOfShards(1)
                    .numberOfReplicas(0))
        ).build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
            () -> MetadataMigrateToDataStreamService.validateBackingIndices(cs, aliasName));
        String emsg = e.getMessage();
        assertThat(emsg, containsString("other aliases referencing indices ["));
        assertThat(emsg, containsString("] must be removed before migrating to a data stream"));
        String referencedIndices = emsg.substring(emsg.indexOf('[') + 1, emsg.indexOf(']'));
        Set<String> indices = Strings.commaDelimitedListToSet(referencedIndices);
        assertThat(indices, containsInAnyOrder("foo2", "foo3"));
    }

    public void testCreateDataStreamWithSuppliedWriteIndex() throws Exception {
        String dataStreamName = "foo";
        AliasMetadata alias = AliasMetadata.builder(dataStreamName).build();
        IndexMetadata
            foo1 =
            IndexMetadata.builder("foo1")
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .putAlias(AliasMetadata.builder(dataStreamName).writeIndex(true).build())
                .numberOfShards(1)
                .numberOfReplicas(0)
                .putMapping(generateMapping("@timestamp", "date"))
                .build();
        IndexMetadata
            foo2 =
            IndexMetadata.builder("foo2")
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .putAlias(alias)
                .numberOfShards(1)
                .numberOfReplicas(0)
                .putMapping(generateMapping("@timestamp", "date"))
                .build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy"))
            .metadata(Metadata.builder()
                .put(foo1, false)
                .put(foo2, false)
                .put("template", new ComposableIndexTemplate(org.elasticsearch.common.collect.List.of(dataStreamName + "*"), null, null,
                    null, null, null, new ComposableIndexTemplate.DataStreamTemplate())))
            .build();

        ClusterState newState = MetadataMigrateToDataStreamService.migrateToDataStream(cs, this::getMapperService,
            new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(dataStreamName,
                TimeValue.ZERO,
                TimeValue.ZERO));
        IndexAbstraction ds = newState.metadata().getIndicesLookup().get(dataStreamName);
        assertThat(ds, notNullValue());
        assertThat(ds.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
        assertThat(ds.getIndices().size(), equalTo(2));
        List<String> backingIndexNames = ds.getIndices().stream().map(x -> x.getIndex().getName()).collect(Collectors.toList());
        assertThat(backingIndexNames, containsInAnyOrder("foo1", "foo2"));
        assertThat(ds.getWriteIndex().getIndex().getName(), equalTo("foo1"));
        for (IndexMetadata im : ds.getIndices()) {
            assertThat(im.getSettings().get("index.hidden"), equalTo("true"));
            assertThat(im.getAliases().size(), equalTo(0));
        }
    }

    public void testCreateDataStreamHidesBackingIndicesAndRemovesAlias() throws Exception {
        String dataStreamName = "foo";
        AliasMetadata alias = AliasMetadata.builder(dataStreamName).build();
        IndexMetadata foo1 = IndexMetadata.builder("foo1")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .putAlias(AliasMetadata.builder(dataStreamName).writeIndex(true).build())
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putMapping(generateMapping("@timestamp", "date"))
            .build();
        IndexMetadata foo2 = IndexMetadata.builder("foo2")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .putAlias(alias)
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putMapping(generateMapping("@timestamp", "date"))
            .build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy")).metadata(
            Metadata.builder()
                .put(foo1, false)
                .put(foo2, false)
                .put("template", new ComposableIndexTemplate(org.elasticsearch.common.collect.List.of(dataStreamName + "*"), null, null,
                    null, null, null, new ComposableIndexTemplate.DataStreamTemplate())))
            .build();

        ClusterState newState = MetadataMigrateToDataStreamService.migrateToDataStream(cs, this::getMapperService,
            new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(dataStreamName,
                TimeValue.ZERO,
                TimeValue.ZERO));
        IndexAbstraction ds = newState.metadata().getIndicesLookup().get(dataStreamName);
        assertThat(ds, notNullValue());
        assertThat(ds.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
        assertThat(ds.getIndices().size(), equalTo(2));
        List<String> backingIndexNames = ds.getIndices().stream().map(x -> x.getIndex().getName()).collect(Collectors.toList());
        assertThat(backingIndexNames, containsInAnyOrder("foo1", "foo2"));
        assertThat(ds.getWriteIndex().getIndex().getName(), equalTo("foo1"));
        for (IndexMetadata im : ds.getIndices()) {
            assertThat(im.getSettings().get("index.hidden"), equalTo("true"));
            assertThat(im.getAliases().size(), equalTo(0));
        }
    }

    public void testCreateDataStreamWithoutSuppliedWriteIndex() throws Exception {
        String dataStreamName = "foo";
        AliasMetadata alias = AliasMetadata.builder(dataStreamName).build();
        IndexMetadata
            foo1 =
            IndexMetadata.builder("foo1")
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .putAlias(alias)
                .numberOfShards(1)
                .numberOfReplicas(0)
                .putMapping(generateMapping("@timestamp", "date"))
                .build();
        IndexMetadata
            foo2 =
            IndexMetadata.builder("foo2")
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .putAlias(alias)
                .numberOfShards(1)
                .numberOfReplicas(0)
                .putMapping(generateMapping("@timestamp", "date"))
                .build();
        ClusterState cs = ClusterState.builder(new ClusterName("dummy"))
            .metadata(Metadata.builder()
                .put(foo1, false)
                .put(foo2, false)
                .put("template", new ComposableIndexTemplate(org.elasticsearch.common.collect.List.of(dataStreamName + "*"), null, null,
                    null, null, null, new ComposableIndexTemplate.DataStreamTemplate())))
            .build();

        IllegalArgumentException e =
            expectThrows(IllegalArgumentException.class,
                () -> MetadataMigrateToDataStreamService.migrateToDataStream(cs,
                    this::getMapperService,
                    new MetadataMigrateToDataStreamService.MigrateToDataStreamClusterStateUpdateRequest(dataStreamName,
                        TimeValue.ZERO,
                        TimeValue.ZERO)));
        assertThat(e.getMessage(), containsString("alias [" + dataStreamName + "] must specify a write index"));
    }

    private static MappingMetadata generateMapping(String timestampFieldName, String type) throws IOException {
        String source = DataStreamTestHelper.generateMapping(timestampFieldName, type);
        return new MappingMetadata(MapperService.SINGLE_MAPPING_NAME,
            XContentHelper.convertToMap(XContentFactory.xContent(source), source, true));
    }

    private MapperService getMapperService(IndexMetadata im) {
        try {
            return createMapperService("_doc", "{\"_doc\": " + im.mapping().source().toString() + "}");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected Collection<? extends Plugin> getPlugins() {
        return org.elasticsearch.common.collect.List.of(new MetadataIndexTemplateServiceTests.DummyPlugin());
    }
}
