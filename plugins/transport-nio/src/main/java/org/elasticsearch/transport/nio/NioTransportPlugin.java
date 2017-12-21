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

package org.elasticsearch.transport.nio;

import org.elasticsearch.bootstrap.BootstrapCheck;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class NioTransportPlugin extends Plugin implements NetworkPlugin {

    public static final String NIO_TRANSPORT_NAME = "nio-transport";

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(
            NioTransport.NIO_WORKER_COUNT,
            NioTransport.NIO_ACCEPTOR_COUNT
        );
    }

    @Override
    public Settings additionalSettings() {
        final Settings.Builder settingsBuilder = Settings.builder();
        settingsBuilder.put(NetworkModule.TRANSPORT_TYPE_KEY, NIO_TRANSPORT_NAME);
        return settingsBuilder.build();
    }

    @Override
    public Map<String, Supplier<Transport>> getTransports(Settings settings, ThreadPool threadPool, BigArrays bigArrays,
                                                          PageCacheRecycler pageCacheRecycler,
                                                          CircuitBreakerService circuitBreakerService,
                                                          NamedWriteableRegistry namedWriteableRegistry,
                                                          NetworkService networkService) {
        return Collections.singletonMap(NIO_TRANSPORT_NAME,
            () -> new NioTransport(settings, threadPool, networkService, bigArrays, pageCacheRecycler, namedWriteableRegistry,
                circuitBreakerService));
    }

    @Override
    public List<BootstrapCheck> getBootstrapChecks() {
        return Collections.singletonList(new NioNotEnabledBootstrapCheck());
    }
}
