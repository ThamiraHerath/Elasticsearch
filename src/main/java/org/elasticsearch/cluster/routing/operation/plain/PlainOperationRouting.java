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

package org.elasticsearch.cluster.routing.operation.plain;

import com.google.common.collect.Lists;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.allocation.decider.AwarenessAllocationDecider;
import org.elasticsearch.cluster.routing.operation.OperationRouting;
import org.elasticsearch.cluster.routing.operation.hash.HashFunction;
import org.elasticsearch.cluster.routing.operation.hash.djb.DjbHashFunction;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.hash.MurmurHash3;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.math.MathUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexShardMissingException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndexMissingException;

import java.util.*;

/**
 *
 */
public class PlainOperationRouting extends AbstractComponent implements OperationRouting {

    // unused: as of elasticsearch 1.5, the hash function is hardwired to murmur3 and the type is never used to compute the
    // shard to route a document to
    @Deprecated
    private final HashFunction hashFunction;
    @Deprecated
    private final boolean useType;

    private final AwarenessAllocationDecider awarenessAllocationDecider;

    @Inject
    public PlainOperationRouting(Settings indexSettings, HashFunction hashFunction, AwarenessAllocationDecider awarenessAllocationDecider) {
        super(indexSettings);
        this.hashFunction = hashFunction;
        this.useType = indexSettings.getAsBoolean("cluster.routing.operation.use_type", false);
        this.awarenessAllocationDecider = awarenessAllocationDecider;
    }

    @Override
    public ShardIterator indexShards(ClusterState clusterState, String index, String type, String id, @Nullable String routing) throws IndexMissingException, IndexShardMissingException {
        return shards(clusterState, index, type, id, routing).shardsIt();
    }

    @Override
    public ShardIterator deleteShards(ClusterState clusterState, String index, String type, String id, @Nullable String routing) throws IndexMissingException, IndexShardMissingException {
        return shards(clusterState, index, type, id, routing).shardsIt();
    }

    @Override
    public ShardIterator getShards(ClusterState clusterState, String index, String type, String id, @Nullable String routing, @Nullable String preference) throws IndexMissingException, IndexShardMissingException {
        return preferenceActiveShardIterator(shards(clusterState, index, type, id, routing), clusterState.nodes().localNodeId(), clusterState.nodes(), preference);
    }

    @Override
    public ShardIterator getShards(ClusterState clusterState, String index, int shardId, @Nullable String preference) throws IndexMissingException, IndexShardMissingException {
        return preferenceActiveShardIterator(shards(clusterState, index, shardId), clusterState.nodes().localNodeId(), clusterState.nodes(), preference);
    }

    @Override
    public GroupShardsIterator broadcastDeleteShards(ClusterState clusterState, String index) throws IndexMissingException {
        return indexRoutingTable(clusterState, index).groupByShardsIt();
    }

    @Override
    public GroupShardsIterator deleteByQueryShards(ClusterState clusterState, String index, @Nullable Set<String> routing) throws IndexMissingException {
        if (routing == null || routing.isEmpty()) {
            return indexRoutingTable(clusterState, index).groupByShardsIt();
        }

        // we use set here and not identity set since we might get duplicates
        HashSet<ShardIterator> set = new HashSet<>();
        IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
        for (String r : routing) {
            int shardId = shardId(clusterState, index, null, null, r);
            IndexShardRoutingTable indexShard = indexRouting.shard(shardId);
            if (indexShard == null) {
                throw new IndexShardMissingException(new ShardId(index, shardId));
            }
            set.add(indexShard.shardsRandomIt());
        }
        return new GroupShardsIterator(Lists.newArrayList(set));
    }

    @Override
    public int searchShardsCount(ClusterState clusterState, String[] indices, String[] concreteIndices, @Nullable Map<String, Set<String>> routing, @Nullable String preference) throws IndexMissingException {
        final Set<IndexShardRoutingTable> shards = computeTargetedShards(clusterState, concreteIndices, routing);
        return shards.size();
    }

    @Override
    public GroupShardsIterator searchShards(ClusterState clusterState, String[] indices, String[] concreteIndices, @Nullable Map<String, Set<String>> routing, @Nullable String preference) throws IndexMissingException {
        final Set<IndexShardRoutingTable> shards = computeTargetedShards(clusterState, concreteIndices, routing);
        final Set<ShardIterator> set = new HashSet<>(shards.size());
        for (IndexShardRoutingTable shard : shards) {
            ShardIterator iterator = preferenceActiveShardIterator(shard, clusterState.nodes().localNodeId(), clusterState.nodes(), preference);
            if (iterator != null) {
                set.add(iterator);
            }
        }
        return new GroupShardsIterator(Lists.newArrayList(set));
    }

    private static final Map<String, Set<String>> EMPTY_ROUTING = Collections.emptyMap();

    private Set<IndexShardRoutingTable> computeTargetedShards(ClusterState clusterState, String[] concreteIndices, @Nullable Map<String, Set<String>> routing) throws IndexMissingException {
        routing = routing == null ? EMPTY_ROUTING : routing; // just use an empty map
        final Set<IndexShardRoutingTable> set = new HashSet<>();
        // we use set here and not list since we might get duplicates
        for (String index : concreteIndices) {
            final IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
            final Set<String> effectiveRouting = routing.get(index);
            if (effectiveRouting != null) {
                for (String r : effectiveRouting) {
                    int shardId = shardId(clusterState, index, null, null, r);
                    IndexShardRoutingTable indexShard = indexRouting.shard(shardId);
                    if (indexShard == null) {
                        throw new IndexShardMissingException(new ShardId(index, shardId));
                    }
                    // we might get duplicates, but that's ok, they will override one another
                    set.add(indexShard);
                }
            } else {
                for (IndexShardRoutingTable indexShard : indexRouting) {
                    set.add(indexShard);
                }
            }
        }
        return set;
    }

    private ShardIterator preferenceActiveShardIterator(IndexShardRoutingTable indexShard, String localNodeId, DiscoveryNodes nodes, @Nullable String preference) {
        if (preference == null || preference.isEmpty()) {
            String[] awarenessAttributes = awarenessAllocationDecider.awarenessAttributes();
            if (awarenessAttributes.length == 0) {
                return indexShard.activeInitializingShardsRandomIt();
            } else {
                return indexShard.preferAttributesActiveInitializingShardsIt(awarenessAttributes, nodes);
            }
        }
        if (preference.charAt(0) == '_') {
            Preference preferenceType = Preference.parse(preference);
            if (preferenceType == Preference.SHARDS) {
                // starts with _shards, so execute on specific ones
                int index = preference.indexOf(';');

                String shards;
                if (index == -1) {
                    shards = preference.substring(Preference.SHARDS.type().length() + 1);
                } else {
                    shards = preference.substring(Preference.SHARDS.type().length() + 1, index);
                }
                String[] ids = Strings.splitStringByCommaToArray(shards);
                boolean found = false;
                for (String id : ids) {
                    if (Integer.parseInt(id) == indexShard.shardId().id()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return null;
                }
                // no more preference
                if (index == -1 || index == preference.length() - 1) {
                    String[] awarenessAttributes = awarenessAllocationDecider.awarenessAttributes();
                    if (awarenessAttributes.length == 0) {
                        return indexShard.activeInitializingShardsRandomIt();
                    } else {
                        return indexShard.preferAttributesActiveInitializingShardsIt(awarenessAttributes, nodes);
                    }
                } else {
                    // update the preference and continue
                    preference = preference.substring(index + 1);
                }
            }
            preferenceType = Preference.parse(preference);
            switch (preferenceType) {
                case PREFER_NODE:
                    return indexShard.preferNodeActiveInitializingShardsIt(preference.substring(Preference.PREFER_NODE.type().length() + 1));
                case LOCAL:
                    return indexShard.preferNodeActiveInitializingShardsIt(localNodeId);
                case PRIMARY:
                    return indexShard.primaryActiveInitializingShardIt();
                case PRIMARY_FIRST:
                    return indexShard.primaryFirstActiveInitializingShardsIt();
                case ONLY_LOCAL:
                    return indexShard.onlyNodeActiveInitializingShardsIt(localNodeId);
                case ONLY_NODE:
                    String nodeId = preference.substring(Preference.ONLY_NODE.type().length() + 1);
                    ensureNodeIdExists(nodes, nodeId);
                    return indexShard.onlyNodeActiveInitializingShardsIt(nodeId);
                default:
                    throw new ElasticsearchIllegalArgumentException("unknown preference [" + preferenceType + "]");
            }
        }
        // if not, then use it as the index
        String[] awarenessAttributes = awarenessAllocationDecider.awarenessAttributes();
        if (awarenessAttributes.length == 0) {
            return indexShard.activeInitializingShardsIt(DjbHashFunction.DJB_HASH(preference));
        } else {
            return indexShard.preferAttributesActiveInitializingShardsIt(awarenessAttributes, nodes, DjbHashFunction.DJB_HASH(preference));
        }
    }

    public IndexMetaData indexMetaData(ClusterState clusterState, String index) {
        IndexMetaData indexMetaData = clusterState.metaData().index(index);
        if (indexMetaData == null) {
            throw new IndexMissingException(new Index(index));
        }
        return indexMetaData;
    }

    protected IndexRoutingTable indexRoutingTable(ClusterState clusterState, String index) {
        IndexRoutingTable indexRouting = clusterState.routingTable().index(index);
        if (indexRouting == null) {
            throw new IndexMissingException(new Index(index));
        }
        return indexRouting;
    }


    // either routing is set, or type/id are set

    protected IndexShardRoutingTable shards(ClusterState clusterState, String index, String type, String id, String routing) {
        int shardId = shardId(clusterState, index, type, id, routing);
        return shards(clusterState, index, shardId);
    }

    protected IndexShardRoutingTable shards(ClusterState clusterState, String index, int shardId) {
        IndexShardRoutingTable indexShard = indexRoutingTable(clusterState, index).shard(shardId);
        if (indexShard == null) {
            throw new IndexShardMissingException(new ShardId(index, shardId));
        }
        return indexShard;
    }

    private int shardId(ClusterState clusterState, String index, String type, String id, @Nullable String routing) {
        final IndexMetaData indexMetaData = indexMetaData(clusterState, index);
        final Version createdVersion = Version.indexCreated(indexMetaData.getSettings());
        if (createdVersion.onOrAfter(Version.V_1_5_0)) {
            // on and after 1.5, we force usage of murmur3 for hashing: it has a better distribution that makes sure
            // that there are no adversarial numbers of shards or patterns of document ids that generate unbalanced shards
            // (as there could be with DJB hash with 33 shards and incremental numeric ids)

            // nocommit: should we force the use of the type? it is tempting to avoid some worst-cases in the lots-of-types
            // case but it currently breaks parent/child given that in that case only the parent id is passed as a routing key

            if (routing == null) {
                if (id == null) {
                    throw new ElasticsearchIllegalArgumentException("_id cannot be null, got _id=[" + id + "]");
                }
                routing = id;
            }
            final int shard = shardId(indexMetaData.numberOfShards(), routing);
            assert shard >= 0 && shard < indexMetaData.numberOfShards();
            return shard;
        } else {
            // This is the pre-1.5.0 routing logic
            if (routing == null) {
                if (!useType) {
                    return Math.abs(hash(id) % indexMetaData.numberOfShards());
                } else {
                    return Math.abs(hash(type, id) % indexMetaData.numberOfShards());
                }
            }
            return Math.abs(hash(routing) % indexMetaData.numberOfShards());
        }
    }

    // public for testing
    public static int shardId(int numShards, String routing) {
        return MathUtils.mod(murmur3Hash(routing), numShards);
    }

    private static int murmur3Hash(CharSequence key) {
        final byte[] bytesToHash = new byte[key.length() * 2];
        for (int i = 0; i < key.length(); ++i) {
            final char c = key.charAt(i);
            final byte b1 = (byte) (c >>> 8), b2 = (byte) c;
            assert ((b1 & 0xFF) << 8 | (b2 & 0xFF)) == c;
            bytesToHash[i * 2] = b1;
            bytesToHash[i * 2 + 1] = b2;
        }
        final MurmurHash3.Hash128 hash = MurmurHash3.hash128(bytesToHash, 0, bytesToHash.length, 0, new MurmurHash3.Hash128());
        return (int) hash.h1;
    }

    @Deprecated
    protected int hash(String routing) {
        return hashFunction.hash(routing);
    }

    @Deprecated
    protected int hash(String type, String id) {
        if (type == null || "_all".equals(type)) {
            throw new ElasticsearchIllegalArgumentException("Can't route an operation with no type and having type part of the routing (for backward comp)");
        }
        return hashFunction.hash(type, id);
    }

    private void ensureNodeIdExists(DiscoveryNodes nodes, String nodeId) {
        if (!nodes.dataNodes().keys().contains(nodeId)) {
            throw new ElasticsearchIllegalArgumentException("No data node with id[" + nodeId + "] found");
        }
    }

    // nocommit: kept here for testing purposes, will be removed when pushing
    public static void main(String[] args) {
        final int numShards = 33;
        final int[] counts = new int[numShards];
        final int[] counts2 = new int[numShards];
        for (int i = 0; i < 100000; ++i) {
            final String id = Integer.toString(i);
            counts[shardId(numShards, id)]++;
            counts2[Math.abs(DjbHashFunction.DJB_HASH(id) % numShards)]++;
        }
        System.out.println(Arrays.toString(counts));
        System.out.println(Arrays.toString(counts2));
    }

}
