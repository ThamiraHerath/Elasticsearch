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

package org.elasticsearch.action.admin.cluster.state;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.gateway.GatewayService.STATE_NOT_RECOVERED_BLOCK;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasToString;

public class ClusterStateIT extends ESSingleNodeTestCase {

    public static class CustomPlugin extends Plugin {

        public CustomPlugin() {

        }

        static class CustomPluginCustom implements MetaData.Custom {

            @Override
            public EnumSet<MetaData.XContentContext> context() {
                return MetaData.ALL_CONTEXTS;
            }

            @Override
            public Diff<MetaData.Custom> diff(final MetaData.Custom previousState) {
                return null;
            }

            @Override
            public String getWriteableName() {
                return TYPE;
            }

            @Override
            public void writeTo(final StreamOutput out) throws IOException {

            }

            @Override
            public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
                builder.startObject();
                {

                }
                builder.endObject();
                return builder;
            }
        }

        @Override
        public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
            return super.getNamedWriteables();
        }

        public static final String TYPE = "custom_plugin";

        private final AtomicBoolean installed = new AtomicBoolean();

        @Override
        public Collection<Object> createComponents(
                final Client client,
                final ClusterService clusterService,
                final ThreadPool threadPool,
                final ResourceWatcherService resourceWatcherService,
                final ScriptService scriptService,
                final NamedXContentRegistry xContentRegistry,
                final Environment environment,
                final NodeEnvironment nodeEnvironment,
                final NamedWriteableRegistry namedWriteableRegistry) {
            clusterService.addListener(event -> {
                final ClusterState state = event.state();
                if (state.getBlocks().hasGlobalBlock(STATE_NOT_RECOVERED_BLOCK)) {
                    return;
                }

                final MetaData metaData = state.metaData();
                if (state.nodes().isLocalNodeElectedMaster()) {
                    if (metaData.custom(CustomPlugin.TYPE) == null) {
                        if (installed.compareAndSet(false, true)) {
                            clusterService.submitStateUpdateTask("install-metadata-custom", new ClusterStateUpdateTask(Priority.URGENT) {

                                @Override
                                public ClusterState execute(ClusterState currentState) {
                                    if (currentState.custom(CustomPlugin.TYPE) == null) {
                                        final MetaData.Builder builder = MetaData.builder(currentState.metaData());
                                        builder.putCustom(CustomPlugin.TYPE, new CustomPluginCustom());
                                        return ClusterState.builder(currentState).metaData(builder).build();
                                    } else {
                                        return currentState;
                                    }
                                }

                                @Override
                                public void onFailure(String source, Exception e) {
                                    throw new AssertionError(e);
                                }

                            });
                        }
                    }
                }

            });
            return Collections.emptyList();
        }
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Stream.concat(super.getPlugins().stream(), Stream.of(CustomPlugin.class)).collect(Collectors.toCollection(ArrayList::new));
    }

    public void testRequestCustoms() {
        final ClusterStateResponse state = client().admin().cluster().prepareState().setMetaData(true).setMetaDataCustoms(true).get();
        assertTrue(state.getState().metaData().customs().containsKey(CustomPlugin.TYPE));
    }

    public void testDoNotRequestCustoms() {
        final ClusterStateResponse state = client().admin().cluster().prepareState().setMetaData(true).setMetaDataCustoms(false).get();
        assertFalse(state.getState().metaData().customs().containsKey(CustomPlugin.TYPE));
    }

    public void testRequestCustomsDefault() {
        final ClusterStateResponse state = client().admin().cluster().prepareState().setMetaData(true).get();
        assertFalse(state.getState().metaData().customs().containsKey(CustomPlugin.TYPE));
    }

    public void testValidation() {
        final ClusterStateRequest request = new ClusterStateRequest();
        request.metaData(false);
        request.metaDataCustoms(true);
        final ActionRequestValidationException e = request.validate();
        assertThat(e, hasToString(containsString("metadata customs were requested without requesting metadata")));
    }

}
