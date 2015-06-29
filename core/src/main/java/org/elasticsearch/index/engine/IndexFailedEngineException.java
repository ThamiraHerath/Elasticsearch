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

package org.elasticsearch.index.engine;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;

/**
 *
 */
public class IndexFailedEngineException extends EngineException {

    private final String type;

    private final String id;

    public IndexFailedEngineException(ShardId shardId, Engine.Index index, Throwable cause) {
        super(shardId, "Index failed for [" + index.type() + "#" + index.id() + "]", cause);
        this.type = index.type();
        this.id = index.id();
    }

    public IndexFailedEngineException(StreamInput in) throws IOException{
        super(in);
        type = in.readString();
        id = in.readString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(type);
        out.writeString(id);
    }

    public String type() {
        return this.type;
    }

    public String id() {
        return this.id;
    }
}