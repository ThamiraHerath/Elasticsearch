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

package org.elasticsearch.snapshots;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.metadata.SnapshotId;

/**
 * Generic snapshot exception
 */
public class SnapshotException extends ElasticSearchException {
    private final SnapshotId snapshot;

    public SnapshotException(SnapshotId snapshot, String msg) {
        this(snapshot, msg, null);
    }

    public SnapshotException(SnapshotId snapshot, String msg, Throwable cause) {
        super("[" + (snapshot == null ? "_na" : snapshot) + "] " + msg, cause);
        this.snapshot = snapshot;
    }

    public SnapshotId snapshot() {
        return snapshot;
    }
}
