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
package org.elasticsearch.test.discovery;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.discovery.zen.ping.PingContextProvider;
import org.elasticsearch.discovery.zen.ping.ZenPing;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.plugins.Plugin;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class MockZenPing extends AbstractLifecycleComponent implements ZenPing {

    static final Set<MockZenPing> activeNodes = ConcurrentCollections.newConcurrentSet();

    private volatile PingContextProvider contextProvider;

    @Inject
    public MockZenPing(Settings settings) {
        super(settings);
    }

    @Override
    public void setPingContextProvider(PingContextProvider contextProvider) {
        this.contextProvider = contextProvider;
    }

    @Override
    public void ping(PingListener listener, TimeValue timeout) {
        List<PingResponse> responseList = activeNodes.stream()
            .filter(p -> p != this) // remove this as pings are not expected to return the local node
            .map(MockZenPing::getPingResponse)
            .collect(Collectors.toList());
        listener.onPing(responseList);
    }

    private PingResponse getPingResponse() {
        final ClusterState clusterState = contextProvider.clusterState();
        return new PingResponse(clusterState.nodes().getLocalNode(), clusterState.nodes().getMasterNode(), clusterState);
    }

    @Override
    protected void doStart() {
        assert contextProvider != null;
        boolean added = activeNodes.add(this);
        assert added;
    }

    @Override
    protected void doStop() {
        boolean found = activeNodes.remove(this);
        assert found;
    }

    @Override
    protected void doClose() {

    }

    public static class TestPlugin extends Plugin implements DiscoveryPlugin {

        @Override
        public Set<Class<? extends ZenPing>> getZenPings(Settings settings) {
            return Collections.singleton(MockZenPing.class);
        }
    }
}
