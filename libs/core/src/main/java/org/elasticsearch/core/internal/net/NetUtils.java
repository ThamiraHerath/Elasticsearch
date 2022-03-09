/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.core.internal.net;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.NetworkChannel;
import java.util.Arrays;

/**
 * Utilities for network-related methods.
 */
public class NetUtils {

    private NetUtils() {}

    /**
     * Returns the extended TCP_KEEPIDLE socket option, if available on this JDK
     */
    public static SocketOption<Integer> getTcpKeepIdleSocketOptionOrNull() {
        return getExtendedSocketOptionOrNull("TCP_KEEPIDLE");
    }

    /**
     * Returns the extended TCP_KEEPINTERVAL socket option, if available on this JDK
     */
    public static SocketOption<Integer> getTcpKeepIntervalSocketOptionOrNull() {
        return getExtendedSocketOptionOrNull("TCP_KEEPINTERVAL");
    }

    /**
     * Returns the extended TCP_KEEPCOUNT socket option, if available on this JDK
     */
    public static SocketOption<Integer> getTcpKeepCountSocketOptionOrNull() {
        return getExtendedSocketOptionOrNull("TCP_KEEPCOUNT");
    }

    @SuppressWarnings("unchecked")
    private static <T> SocketOption<T> getExtendedSocketOptionOrNull(String fieldName) {
        try {
            final Class<?> extendedSocketOptionsClass = Class.forName("jdk.net.ExtendedSocketOptions");
            final Field field = extendedSocketOptionsClass.getField(fieldName);
            return (SocketOption<T>) field.get(null);
        } catch (Exception t) {
            // ignore
            return null;
        }
    }

    /**
     * If SO_KEEPALIVE is enabled (default), this method ensures sane default values for the extended socket options
     * TCP_KEEPIDLE and TCP_KEEPINTERVAL. The default value for TCP_KEEPIDLE is system dependent, but is typically 2 hours.
     * Such a high value can result in firewalls eagerly closing these connections. To tell any intermediate devices that
     * the connection remains alive, we explicitly set these options to 5 minutes if the defaults are higher than that.
     */
    public static void tryEnsureReasonableKeepAliveConfig(NetworkChannel socketChannel) {
        assert socketChannel != null;
        try {
            if (socketChannel.supportedOptions().contains(StandardSocketOptions.SO_KEEPALIVE)) {
                final Boolean keepalive = socketChannel.getOption(StandardSocketOptions.SO_KEEPALIVE);
                assert keepalive != null;
                if (keepalive.booleanValue()) {
                    for (SocketOption<Integer> option : Arrays.asList(
                        NetUtils.getTcpKeepIdleSocketOptionOrNull(),
                        NetUtils.getTcpKeepIntervalSocketOptionOrNull()
                    )) {
                        setMinValueForSocketOption(socketChannel, option, 300);
                    }
                }
            }
        } catch (Exception e) {
            // Getting an exception here should be ok when concurrently closing the channel
            // An UnsupportedOperationException or IllegalArgumentException, however, should not happen
            assert e instanceof IOException : e;
        }
    }

    private static void setMinValueForSocketOption(NetworkChannel socketChannel, SocketOption<Integer> option, int minValue) {
        if (option != null && socketChannel.supportedOptions().contains(option)) {
            try {
                final Integer currentIdleVal = socketChannel.getOption(option);
                assert currentIdleVal != null;
                if (currentIdleVal.intValue() > minValue) {
                    socketChannel.setOption(option, minValue);
                }
            } catch (Exception e) {
                // Getting an exception here should be ok when concurrently closing the channel
                // An UnsupportedOperationException or IllegalArgumentException, however, should not happen
                assert e instanceof IOException : e;
            }
        }
    }
}
