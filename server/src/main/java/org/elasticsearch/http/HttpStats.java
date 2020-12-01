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

package org.elasticsearch.http;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContentFragment;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class HttpStats implements Writeable, ToXContentFragment {

    private final long serverOpen;
    private final long totalOpen;
    private final List<ClientStats> clientStats;

    public HttpStats(List<ClientStats> clientStats, long serverOpen, long totalOpened) {
        this.clientStats = clientStats;
        this.serverOpen = serverOpen;
        this.totalOpen = totalOpened;
    }

    public HttpStats(long serverOpen, long totalOpened) {
        this(List.of(), serverOpen, totalOpened);
    }

    public HttpStats(StreamInput in) throws IOException {
        serverOpen = in.readVLong();
        totalOpen = in.readVLong();
        if (in.getVersion().onOrAfter(Version.V_8_0_0)) {
            clientStats = in.readList(ClientStats::new);
        } else {
            clientStats = List.of();
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(serverOpen);
        out.writeVLong(totalOpen);
        if (out.getVersion().onOrAfter(Version.V_8_0_0)) {
            out.writeList(clientStats);
        }
    }

    public long getServerOpen() {
        return this.serverOpen;
    }

    public long getTotalOpen() {
        return this.totalOpen;
    }

    static final class Fields {
        static final String HTTP = "http";
        static final String CURRENT_OPEN = "current_open";
        static final String TOTAL_OPENED = "total_opened";
        static final String CLIENTS = "clients";
        static final String CLIENT_ID = "id";
        static final String CLIENT_AGENT = "agent";
        static final String CLIENT_LOCAL_ADDRESS = "local_address";
        static final String CLIENT_REMOTE_ADDRESS = "remote_address";
        static final String CLIENT_LAST_URI = "last_uri";
        static final String CLIENT_OPENED_TIME_MILLIS = "opened_time_millis";
        static final String CLIENT_CLOSED_TIME_MILLIS = "closed_time_millis";
        static final String CLIENT_LAST_REQUEST_TIME_MILLIS = "last_request_time_millis";
        static final String CLIENT_REQUEST_COUNT = "request_count";
        static final String CLIENT_REQUEST_SIZE_BYTES = "request_size_bytes";
        static final String CLIENT_FORWARDED_FOR = "x_forwarded_for";
        static final String CLIENT_OPAQUE_ID = "x_opaque_id";
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(Fields.HTTP);
        builder.field(Fields.CURRENT_OPEN, serverOpen);
        builder.field(Fields.TOTAL_OPENED, totalOpen);
        builder.startArray(Fields.CLIENTS);
        for (ClientStats clientStats : this.clientStats) {
            builder.startObject();
            builder.field(Fields.CLIENT_ID, clientStats.id);
            builder.field(Fields.CLIENT_AGENT, clientStats.agent);
            builder.field(Fields.CLIENT_LOCAL_ADDRESS, clientStats.localAddress);
            builder.field(Fields.CLIENT_REMOTE_ADDRESS, clientStats.remoteAddress);
            builder.field(Fields.CLIENT_LAST_URI, clientStats.lastUri);
            builder.field(Fields.CLIENT_FORWARDED_FOR, clientStats.forwardedFor);
            builder.field(Fields.CLIENT_OPAQUE_ID, clientStats.opaqueId);
            builder.field(Fields.CLIENT_OPENED_TIME_MILLIS, clientStats.openedTimeMillis);
            builder.field(Fields.CLIENT_CLOSED_TIME_MILLIS, clientStats.closedTimeMillis);
            builder.field(Fields.CLIENT_LAST_REQUEST_TIME_MILLIS, clientStats.lastRequestTimeMillis);
            builder.field(Fields.CLIENT_REQUEST_COUNT, clientStats.requestCount);
            builder.field(Fields.CLIENT_REQUEST_SIZE_BYTES, clientStats.requestSizeBytes);
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }

    public static class ClientStats implements Writeable {
        int id;
        String agent;
        String localAddress;
        String remoteAddress;
        String lastUri;
        String forwardedFor;
        String opaqueId;
        long openedTimeMillis;
        long closedTimeMillis = -1;
        volatile long lastRequestTimeMillis = -1;
        volatile long requestCount;
        volatile long requestSizeBytes;

        ClientStats(long openedTimeMillis) {
            this.id = System.identityHashCode(this);
            this.openedTimeMillis = openedTimeMillis;
        }

        // visible for testing
        public ClientStats(String agent, String localAddress, String remoteAddress, String lastUri, String forwardedFor, String opaqueId,
            long openedTimeMillis, long closedTimeMillis, long lastRequestTimeMillis, long requestCount, long requestSizeBytes) {
            this.id = System.identityHashCode(this);
            this.agent = agent;
            this.localAddress = localAddress;
            this.remoteAddress = remoteAddress;
            this.lastUri = lastUri;
            this.forwardedFor = forwardedFor;
            this.opaqueId = opaqueId;
            this.openedTimeMillis = openedTimeMillis;
            this.closedTimeMillis = closedTimeMillis;
            this.lastRequestTimeMillis = lastRequestTimeMillis;
            this.requestCount = requestCount;
            this.requestSizeBytes = requestSizeBytes;
        }

        ClientStats(StreamInput in) throws IOException {
            this.id = in.readInt();
            this.agent = in.readString();
            this.localAddress = in.readString();
            this.remoteAddress = in.readString();
            this.lastUri = in.readString();
            this.forwardedFor = in.readString();
            this.opaqueId = in.readString();
            this.openedTimeMillis = in.readLong();
            this.closedTimeMillis = in.readLong();
            this.lastRequestTimeMillis = in.readLong();
            this.requestCount = in.readLong();
            this.requestSizeBytes = in.readLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeInt(id);
            out.writeString(agent);
            out.writeString(localAddress);
            out.writeString(remoteAddress);
            out.writeString(lastUri);
            out.writeString(forwardedFor);
            out.writeString(opaqueId);
            out.writeLong(openedTimeMillis);
            out.writeLong(closedTimeMillis);
            out.writeLong(lastRequestTimeMillis);
            out.writeLong(requestCount);
            out.writeLong(requestSizeBytes);
        }
    }
}
