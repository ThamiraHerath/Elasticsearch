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

package org.elasticsearch.indices.recovery;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.transport.TransportResponse;

import java.io.IOException;

class RecoveryResponse extends TransportResponse {

    private String traceSummary = null;

    RecoveryResponse() {
    }

    public void logTraceSummary(Logger logger, StartRecoveryRequest request,
                                TimeValue recoveryTime) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(request.shardId().getIndex().getName()).append(']').append('[')
            .append(request.shardId().id())
            .append("] ");
        sb.append("recovery completed from ").append(request.sourceNode()).append(", took[")
            .append(recoveryTime);
        if (traceSummary != null) {
            sb.append("]\n");
            sb.append(traceSummary);
        }
        logger.trace("{}", sb);
    }

    public void apprendTraceSummary(String summary) {
        if (traceSummary == null) {
            traceSummary = summary;
        } else {
            traceSummary += "\n" + summary;
        }
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        traceSummary = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(traceSummary);
    }
}
