/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.action.bulk;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;

import java.io.IOException;

/**
 * Represents a single item response for an action executed as part of the bulk API. Holds the index/type/id
 * of the relevant action, and if it has failed or not (with the failure message incase it failed).
 *
 *
 */
public class BulkItemResponse implements Streamable {

    /**
     * Represents a failure.
     */
    public static class Failure {
        private final String index;
        private final String type;
        private final String id;
        private final String message;

        public Failure(String index, String type, String id, String message) {
            this.index = index;
            this.type = type;
            this.id = id;
            this.message = message;
        }

        /**
         * The index name of the action.
         */
        public String getIndex() {
            return this.index;
        }

        /**
         * The type of the action.
         */
        public String getType() {
            return type;
        }

        /**
         * The id of the action.
         */
        public String getId() {
            return id;
        }

        /**
         * The failure message.
         */
        public String getMessage() {
            return this.message;
        }
    }

    private int id;

    private String opType;

    private ActionResponse response;

    private Failure failure;

    BulkItemResponse() {

    }

    public BulkItemResponse(int id, String opType, ActionResponse response) {
        this.id = id;
        this.opType = opType;
        this.response = response;
    }

    public BulkItemResponse(int id, String opType, Failure failure) {
        this.id = id;
        this.opType = opType;
        this.failure = failure;
    }

    /**
     * The numeric order of the item matching the same request order in the bulk request.
     */
    public int getItemId() {
        return id;
    }

    /**
     * The operation type ("index", "create" or "delete").
     */
    public String getOpType() {
        return this.opType;
    }

    /**
     * The index name of the action.
     */
    public String getIndex() {
        if (failure != null) {
            return failure.getIndex();
        }
        if (response instanceof IndexResponse) {
            return ((IndexResponse) response).index();
        } else if (response instanceof DeleteResponse) {
            return ((DeleteResponse) response).getIndex();
        }
        return null;
    }

    /**
     * The type of the action.
     */
    public String getType() {
        if (failure != null) {
            return failure.getType();
        }
        if (response instanceof IndexResponse) {
            return ((IndexResponse) response).type();
        } else if (response instanceof DeleteResponse) {
            return ((DeleteResponse) response).getType();
        }
        return null;
    }

    /**
     * The id of the action.
     */
    public String getId() {
        if (failure != null) {
            return failure.getId();
        }
        if (response instanceof IndexResponse) {
            return ((IndexResponse) response).id();
        } else if (response instanceof DeleteResponse) {
            return ((DeleteResponse) response).getId();
        }
        return null;
    }

    /**
     * The version of the action.
     */
    public long getVersion() {
        if (failure != null) {
            return -1;
        }
        if (response instanceof IndexResponse) {
            return ((IndexResponse) response).version();
        } else if (response instanceof DeleteResponse) {
            return ((DeleteResponse) response).getVersion();
        }
        return -1;
    }

    /**
     * The actual response ({@link IndexResponse} or {@link DeleteResponse}). <tt>null</tt> in
     * case of failure.
     */
    public <T extends ActionResponse> T getResponse() {
        return (T) response;
    }

    /**
     * Is this a failed execution of an operation.
     */
    public boolean isFailed() {
        return failure != null;
    }

    /**
     * The failure message, <tt>null</tt> if it did not fail.
     */
    public String getFailureMessage() {
        if (failure != null) {
            return failure.getMessage();
        }
        return null;
    }

    /**
     * The actual failure object if there was a failure.
     */
    public Failure getFailure() {
        return this.failure;
    }

    public static BulkItemResponse readBulkItem(StreamInput in) throws IOException {
        BulkItemResponse response = new BulkItemResponse();
        response.readFrom(in);
        return response;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        id = in.readVInt();
        opType = in.readString();

        byte type = in.readByte();
        if (type == 0) {
            response = new IndexResponse();
            response.readFrom(in);
        } else if (type == 1) {
            response = new DeleteResponse();
            response.readFrom(in);
        }

        if (in.readBoolean()) {
            failure = new Failure(in.readString(), in.readString(), in.readString(), in.readString());
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(id);
        out.writeString(opType);
        if (response == null) {
            out.writeByte((byte) 2);
        } else {
            if (response instanceof IndexResponse) {
                out.writeByte((byte) 0);
            } else if (response instanceof DeleteResponse) {
                out.writeByte((byte) 1);
            }
            response.writeTo(out);
        }
        if (failure == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeString(failure.getIndex());
            out.writeString(failure.getType());
            out.writeString(failure.getId());
            out.writeString(failure.getMessage());
        }
    }
}
