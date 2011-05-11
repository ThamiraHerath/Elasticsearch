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

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProcessedClusterStateUpdateTask;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.indices.InvalidAliasNameException;

import java.util.Map;

import static org.elasticsearch.cluster.ClusterState.*;
import static org.elasticsearch.cluster.metadata.IndexMetaData.*;
import static org.elasticsearch.cluster.metadata.MetaData.*;
import static org.elasticsearch.common.collect.Maps.*;
import static org.elasticsearch.common.settings.ImmutableSettings.*;

/**
 * @author kimchy (shay.banon)
 */
public class MetaDataIndexAliasesService extends AbstractComponent {

    private final ClusterService clusterService;

    @Inject public MetaDataIndexAliasesService(Settings settings, ClusterService clusterService) {
        super(settings);
        this.clusterService = clusterService;
    }

    public void indicesAliases(final Request request, final Listener listener) {
        clusterService.submitStateUpdateTask("index-aliases", new ProcessedClusterStateUpdateTask() {
            @Override public ClusterState execute(ClusterState currentState) {

                for (AliasAction aliasAction : request.actions) {
                    if (!currentState.metaData().hasIndex(aliasAction.index())) {
                        listener.onFailure(new IndexMissingException(new Index(aliasAction.index())));
                        return currentState;
                    }
                    if (currentState.metaData().hasIndex(aliasAction.alias())) {
                        listener.onFailure(new InvalidAliasNameException(new Index(aliasAction.index()), aliasAction.alias(), "an index exists with the same name as the alias"));
                        return currentState;
                    }
                }

                MetaData.Builder builder = newMetaDataBuilder().metaData(currentState.metaData());
                for (AliasAction aliasAction : request.actions) {
                    IndexMetaData indexMetaData = builder.get(aliasAction.index());
                    if (indexMetaData == null) {
                        throw new IndexMissingException(new Index(aliasAction.index()));
                    }
                    // TODO: Not sure this is the best way to store it. But at least it's backward compatible.
                    String[] aliases = indexMetaData.settings().getAsArray("index.aliases");
                    String[] filters = indexMetaData.settings().getAsArray("index.alias_filters");
                    Map<String, String> indexAliases = newHashMap();
                    for (int i = 0; i < aliases.length; i++) {
                        if (filters != null && i < filters.length) {
                            indexAliases.put(aliases[i], filters[i]);
                        } else {
                            indexAliases.put(aliases[i], "");
                        }
                    }
                    if (aliasAction.actionType() == AliasAction.Type.ADD) {
                        if (aliasAction.filter() != null) {
                            indexAliases.put(aliasAction.alias(), Base64.encodeBytes(aliasAction.filter()));
                        } else {
                            indexAliases.put(aliasAction.alias(), "");
                        }
                    } else if (aliasAction.actionType() == AliasAction.Type.REMOVE) {
                        indexAliases.remove(aliasAction.alias());
                    }

                    aliases = new String[indexAliases.size()];
                    filters = new String[indexAliases.size()];
                    int cur = 0;
                    for (Map.Entry<String, String> alias : indexAliases.entrySet()) {
                        aliases[cur] = alias.getKey();
                        filters[cur] = alias.getValue();
                        cur++;
                    }

                    Settings settings = settingsBuilder().put(indexMetaData.settings())
                            .putArray("index.aliases", aliases)
                            .putArray("index.alias_filters", filters)
                            .build();

                    builder.put(newIndexMetaDataBuilder(indexMetaData).settings(settings));
                }
                return newClusterStateBuilder().state(currentState).metaData(builder).build();
            }

            @Override public void clusterStateProcessed(ClusterState clusterState) {
                listener.onResponse(new Response());
            }
        });
    }

    public static interface Listener {

        void onResponse(Response response);

        void onFailure(Throwable t);
    }

    public static class Request {

        final AliasAction[] actions;

        public Request(AliasAction[] actions) {
            this.actions = actions;
        }
    }

    public static class Response {

    }
}
