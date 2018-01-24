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

package org.elasticsearch.nio;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.function.BiConsumer;

/**
 * Event handler designed to handle events from non-server sockets
 */
public class SocketEventHandler extends EventHandler {

    private final Logger logger;

    public SocketEventHandler(Logger logger) {
        super(logger);
        this.logger = logger;
    }

    /**
     * This method is called when a NioSocketChannel is successfully registered. It should only be called
     * once per channel.
     *
     * @param channel that was registered
     */
    protected void handleRegistration(NioSocketChannel channel) throws IOException {
        SocketChannelContext context = channel.getContext();
        context.register();
        // TODO: Test attachment
        SelectionKey selectionKey = context.getSelectionKey();
        selectionKey.attach(channel);
        if (context.hasQueuedWriteOps()) {
            SelectionKeyUtils.setConnectReadAndWriteInterested(selectionKey);
        } else {
            SelectionKeyUtils.setConnectAndReadInterested(selectionKey);
        }
    }

    /**
     * This method is called when an attempt to register a channel throws an exception.
     *
     * @param channel that was registered
     * @param exception that occurred
     */
    protected void registrationException(NioSocketChannel channel, Exception exception) {
        logger.debug(() -> new ParameterizedMessage("failed to register socket channel: {}", channel), exception);
        channel.getContext().handleException(exception);
    }

    /**
     * This method is called when a NioSocketChannel has just been accepted or if it has receive an
     * OP_CONNECT event.
     *
     * @param channel that was registered
     */
    protected void handleConnect(NioSocketChannel channel) throws IOException {
        SocketChannelContext context = channel.getContext();
        if (context.connect()) {
            SelectionKeyUtils.removeConnectInterested(context.getSelectionKey());
        }

    }

    /**
     * This method is called when an attempt to connect a channel throws an exception.
     *
     * @param channel that was connecting
     * @param exception that occurred
     */
    protected void connectException(NioSocketChannel channel, Exception exception) {
        logger.debug(() -> new ParameterizedMessage("failed to connect to socket channel: {}", channel), exception);
        channel.getContext().handleException(exception);
    }

    /**
     * This method is called when a channel signals it is ready for be read. All of the read logic should
     * occur in this call.
     *
     * @param channel that can be read
     */
    protected void handleRead(NioSocketChannel channel) throws IOException {
        channel.getContext().read();
    }

    /**
     * This method is called when an attempt to read from a channel throws an exception.
     *
     * @param channel that was being read
     * @param exception that occurred
     */
    protected void readException(NioSocketChannel channel, Exception exception) {
        logger.debug(() -> new ParameterizedMessage("exception while reading from socket channel: {}", channel), exception);
        channel.getContext().handleException(exception);
    }

    /**
     * This method is called when a channel signals it is ready to receive writes. All of the write logic
     * should occur in this call.
     *
     * @param channel that can be written to
     */
    protected void handleWrite(NioSocketChannel channel) throws IOException {
        SocketChannelContext channelContext = channel.getContext();
        channelContext.flushChannel();
    }

    /**
     * This method is called when an attempt to write to a channel throws an exception.
     *
     * @param channel that was being written to
     * @param exception that occurred
     */
    protected void writeException(NioSocketChannel channel, Exception exception) {
        logger.debug(() -> new ParameterizedMessage("exception while writing to socket channel: {}", channel), exception);
        channel.getContext().handleException(exception);
    }

    /**
     * This method is called when a listener attached to a channel operation throws an exception.
     *
     * @param listener that was called
     * @param exception that occurred
     */
    protected <V> void listenerException(BiConsumer<V, Throwable> listener, Exception exception) {
        logger.warn(new ParameterizedMessage("exception while executing listener: {}", listener), exception);
    }

    /**
     * @param channel that was handled
     */
    protected void postHandling(NioSocketChannel channel) {
        SocketChannelContext context = channel.getContext();
        if (context.selectorShouldClose()) {
            handleClose(channel);
        } else {
            SelectionKey selectionKey = context.getSelectionKey();
            boolean currentlyWriteInterested = SelectionKeyUtils.isWriteInterested(selectionKey);
            boolean pendingWrites = context.hasQueuedWriteOps();
            if (currentlyWriteInterested == false && pendingWrites) {
                SelectionKeyUtils.setWriteInterested(selectionKey);
            } else if (currentlyWriteInterested && pendingWrites == false) {
                SelectionKeyUtils.removeWriteInterested(selectionKey);
            }
        }
    }
}
