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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

public class NioShutdown {

    private final Logger logger;

    public NioShutdown(Logger logger) {
        this.logger = logger;
    }

    public void orderlyShutdown(OpenChannels openChannels, NioClient client, ArrayList<AcceptingSelector> acceptors,
                         ArrayList<SocketSelector> socketSelectors) {
        // Close the client. This ensures that no new send connections will be opened. Client could be null if exception was
        // throw on start up. Additionally, the http transport does not have a client
        if (client != null) {
            client.close();
        }

        // Start by closing the server channels. Once these are closed, we are guaranteed to no accept new connections
        openChannels.closeServerChannels();

        for (AcceptingSelector acceptor : acceptors) {
            shutdownSelector(acceptor);
        }

        openChannels.close();

        for (SocketSelector selector : socketSelectors) {
            shutdownSelector(selector);
        }
    }

    private void shutdownSelector(ESSelector selector) {
        try {
            selector.close();
        } catch (IOException | ElasticsearchException e) {
            logger.warn("unexpected exception while stopping selector", e);
        }
    }
}
