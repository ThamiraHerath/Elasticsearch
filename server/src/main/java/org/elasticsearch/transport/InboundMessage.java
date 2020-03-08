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

import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.internal.io.IOUtils;

import java.io.Closeable;
import java.io.IOException;

public abstract class InboundMessage extends NetworkMessage implements Closeable {

    private final StreamInput streamInput;

    InboundMessage(ThreadContext threadContext, Version version, byte status, long requestId, StreamInput streamInput) {
        super(threadContext, version, status, requestId);
        this.streamInput = streamInput;
    }

    StreamInput getStreamInput() {
        return streamInput;
    }

    static class Reader {

        private final Version version;
        private final NamedWriteableRegistry namedWriteableRegistry;
        private final ThreadContext threadContext;

        Reader(Version version, NamedWriteableRegistry namedWriteableRegistry, ThreadContext threadContext) {
            this.version = version;
            this.namedWriteableRegistry = namedWriteableRegistry;
            this.threadContext = threadContext;
        }

        InboundMessage deserialize(BytesReference reference) throws IOException {
            StreamInput streamInput = reference.streamInput();
            boolean success = false;
            try (ThreadContext.StoredContext existing = threadContext.stashContext()) {
                long requestId = streamInput.readLong();
                byte status = streamInput.readByte();
                final Version remoteVersion = Version.fromId(streamInput.readInt());
                streamInput.setVersion(remoteVersion);
                final boolean isHandshake = TransportStatus.isHandshake(status);
                ensureVersionCompatibility(remoteVersion, version, isHandshake);

                if (remoteVersion.onOrAfter(TcpHeader.VERSION_WITH_HEADER_SIZE)) {
                    // Consume the variable header size
                    streamInput.readInt();
                } else {
                    streamInput = decompressingStream(status, streamInput);
                    assertRemoteVersion(streamInput, remoteVersion);
                }

                threadContext.readFrom(streamInput);

                InboundMessage message;
                if (TransportStatus.isRequest(status)) {
                    if (remoteVersion.before(Version.V_8_0_0)) {
                        // discard features
                        streamInput.readStringArray();
                    }
                    final String action = streamInput.readString();

                    if (remoteVersion.onOrAfter(TcpHeader.VERSION_WITH_HEADER_SIZE)) {
                        streamInput = decompressingStream(status, streamInput);
                        assertRemoteVersion(streamInput, remoteVersion);
                    }
                    streamInput = namedWriteableStream(streamInput);
                    message = new Request(threadContext, remoteVersion, status, requestId, action, streamInput);
                } else {
                    if (remoteVersion.onOrAfter(TcpHeader.VERSION_WITH_HEADER_SIZE)) {
                        streamInput = decompressingStream(status, streamInput);
                        assertRemoteVersion(streamInput, remoteVersion);
                    }
                    streamInput = namedWriteableStream(streamInput);
                    assertRemoteVersion(streamInput, remoteVersion);
                    message = new Response(threadContext, remoteVersion, status, requestId, streamInput);
                }
                success = true;
                return message;
            } finally {
                if (success == false) {
                    IOUtils.closeWhileHandlingException(streamInput);
                }
            }
        }

        static StreamInput decompressingStream(byte status, StreamInput streamInput) throws IOException {
            if (TransportStatus.isCompress(status) && streamInput.available() > 0) {
                try {
                    return CompressorFactory.COMPRESSOR.streamInput(streamInput);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("stream marked as compressed, but is missing deflate header");
                }
            } else {
                return streamInput;
            }
        }

        private StreamInput namedWriteableStream(StreamInput delegate) {
            return new NamedWriteableAwareStreamInput(delegate, namedWriteableRegistry);
        }

        static void assertRemoteVersion(StreamInput in, Version version) {
            assert version.equals(in.getVersion()) : "Stream version [" + in.getVersion() + "] does not match version [" + version + "]";
        }
    }

    @Override
    public void close() throws IOException {
        streamInput.close();
    }

    private static void ensureVersionCompatibility(Version version, Version currentVersion, boolean isHandshake) {
        // for handshakes we are compatible with N-2 since otherwise we can't figure out our initial version
        // since we are compatible with N-1 and N+1 so we always send our minCompatVersion as the initial version in the
        // handshake. This looks odd but it's required to establish the connection correctly we check for real compatibility
        // once the connection is established
        final Version compatibilityVersion = isHandshake ? currentVersion.minimumCompatibilityVersion() : currentVersion;
        if (version.isCompatible(compatibilityVersion) == false) {
            final Version minCompatibilityVersion = isHandshake ? compatibilityVersion : compatibilityVersion.minimumCompatibilityVersion();
            String msg = "Received " + (isHandshake ? "handshake " : "") + "message from unsupported version: [";
            throw new IllegalStateException(msg + version + "] minimal compatible version is: [" + minCompatibilityVersion + "]");
        }
    }

    public static class Request extends InboundMessage {

        private final String actionName;

        Request(ThreadContext threadContext, Version version, byte status, long requestId, String actionName,
                StreamInput streamInput) {
            super(threadContext, version, status, requestId, streamInput);
            this.actionName = actionName;
        }

        String getActionName() {
            return actionName;
        }

    }

    public static class Response extends InboundMessage {

        Response(ThreadContext threadContext, Version version, byte status, long requestId, StreamInput streamInput) {
            super(threadContext, version, status, requestId, streamInput);
        }
    }
}
