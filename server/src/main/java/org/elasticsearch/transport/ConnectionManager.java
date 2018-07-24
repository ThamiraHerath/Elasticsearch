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
package org.elasticsearch.transport;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.CheckedBiConsumer;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractLifecycleRunnable;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.util.concurrent.KeyedLock;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.elasticsearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;

public class ConnectionManager implements Closeable {

    private final ConcurrentMap<DiscoveryNode, Transport.Connection> connectedNodes = newConcurrentMap();
    private final KeyedLock<String> connectionLock = new KeyedLock<>();
    private final Logger logger;
    private final Transport transport;
    private final ThreadPool threadPool;
    private final TimeValue pingSchedule;
    private final Lifecycle lifecycle = new Lifecycle();
    private final DelegatingNodeConnectionListener connectionListener = new DelegatingNodeConnectionListener();

    public ConnectionManager(Settings settings, Transport transport, ThreadPool threadPool) {
        this.logger = Loggers.getLogger(getClass(), settings);
        this.transport = transport;
        this.threadPool = threadPool;
        this.pingSchedule = TcpTransport.PING_SCHEDULE.get(settings);
        this.lifecycle.moveToStarted();

        if (pingSchedule.millis() > 0) {
            threadPool.schedule(pingSchedule, ThreadPool.Names.GENERIC, new ScheduledPing());
        }
    }

    public void addListener(TransportConnectionListener listener) {
        this.connectionListener.listeners.add(listener);
    }

    public void removeListener(TransportConnectionListener listener) {
        this.connectionListener.listeners.remove(listener);
    }

    public void connectToNode(DiscoveryNode node, ConnectionProfile connectionProfile,
                              CheckedBiConsumer<Transport.Connection, ConnectionProfile, IOException> connectionValidator)
        throws ConnectTransportException {
        if (node == null) {
            throw new ConnectTransportException(null, "can't connect to a null node");
        }
        ensureOpen();
        try (Releasable ignored = connectionLock.acquire(node.getId())) {
            Transport.Connection connection = connectedNodes.get(node);
            if (connection != null) {
                return;
            }
            boolean success = false;
            try {
                connection = transport.openConnection(node, connectionProfile);
                connectionValidator.accept(connection, connectionProfile);
                // we acquire a connection lock, so no way there is an existing connection
                connectedNodes.put(node, connection);
                if (logger.isDebugEnabled()) {
                    logger.debug("connected to node [{}]", node);
                }
                try {
                    connectionListener.onNodeConnected(node);
                } finally {
                    final Transport.Connection finalConnection = connection;
                    connection.addCloseListener(ActionListener.wrap(() -> {
                        if (connectedNodes.remove(node, finalConnection)) {
                            connectionListener.onNodeDisconnected(node);
                        }
                    }));
                }
                if (connection.isClosed()) {
                    throw new NodeNotConnectedException(node, "connection concurrently closed");
                }
                success = true;
            } catch (ConnectTransportException e) {
                throw e;
            } catch (Exception e) {
                throw new ConnectTransportException(node, "general node connection failure", e);
            } finally {
                if (success == false) { // close the connection if there is a failure
                    logger.trace(() -> new ParameterizedMessage("failed to connect to [{}], cleaning dangling connections", node));
                    IOUtils.closeWhileHandlingException(connection);
                }
            }
        }
    }

    public Transport.Connection getConnection(DiscoveryNode node) {
        Transport.Connection connection = connectedNodes.get(node);
        if (connection == null) {
            throw new NodeNotConnectedException(node, "Node not connected");
        }
        return connection;
    }

    public boolean nodeConnected(DiscoveryNode node) {
        return connectedNodes.containsKey(node);
    }

    public void disconnectFromNode(DiscoveryNode node) {
        // TODO: Do we need to lock here?
        Transport.Connection nodeChannels = connectedNodes.remove(node);
        if (nodeChannels != null) { // if we found it and removed it we close and notify
            IOUtils.closeWhileHandlingException(nodeChannels, () -> connectionListener.onNodeDisconnected(node));
        }
    }

    public int connectedNodeCount() {
        return connectedNodes.size();
    }

    @Override
    public void close() {
        lifecycle.moveToStopped();
        // TODO: Either add locking externally or in here.
        // we are holding a write lock so nobody modifies the connectedNodes / openConnections map - it's safe to first close
        // all instances and then clear them maps
        Iterator<Map.Entry<DiscoveryNode, Transport.Connection>> iterator = connectedNodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DiscoveryNode, Transport.Connection> next = iterator.next();
            try {
                IOUtils.closeWhileHandlingException(next.getValue());
                connectionListener.onNodeDisconnected(next.getKey());
            } finally {
                iterator.remove();
            }
        }

        lifecycle.moveToClosed();
    }

    private void ensureOpen() {
        if (lifecycle.started() == false) {
            throw new IllegalStateException("connection manager is closed");
        }
    }

    private class ScheduledPing extends AbstractLifecycleRunnable {

        private ScheduledPing() {
            super(lifecycle, logger);
        }

        @Override
        protected void doRunInLifecycle() {
            for (Map.Entry<DiscoveryNode, Transport.Connection> entry : connectedNodes.entrySet()) {
                Transport.Connection connection = entry.getValue();
                if (connection.sendPing() == false) {
                    logger.warn("attempted to send ping to connection without support for pings [{}]", connection);
                }
            }
        }

        @Override
        protected void onAfterInLifecycle() {
            try {
                threadPool.schedule(pingSchedule, ThreadPool.Names.GENERIC, this);
            } catch (EsRejectedExecutionException ex) {
                if (ex.isExecutorShutdown()) {
                    logger.debug("couldn't schedule new ping execution, executor is shutting down", ex);
                } else {
                    throw ex;
                }
            }
        }

        @Override
        public void onFailure(Exception e) {
            if (lifecycle.stoppedOrClosed()) {
                logger.trace("failed to send ping transport message", e);
            } else {
                logger.warn("failed to send ping transport message", e);
            }
        }
    }

    private static final class DelegatingNodeConnectionListener implements TransportConnectionListener {

        private final List<TransportConnectionListener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void onNodeDisconnected(DiscoveryNode key) {
            for (TransportConnectionListener listener : listeners) {
                listener.onNodeDisconnected(key);
            }
        }

        @Override
        public void onNodeConnected(DiscoveryNode node) {
            for (TransportConnectionListener listener : listeners) {
                listener.onNodeConnected(node);
            }
        }
    }
}
