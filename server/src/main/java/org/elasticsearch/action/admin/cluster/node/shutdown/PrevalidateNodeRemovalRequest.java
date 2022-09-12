/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.node.shutdown;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeReadRequest;
import org.elasticsearch.common.io.stream.StreamInput;

import java.io.IOException;
import java.util.List;

public class PrevalidateNodeRemovalRequest extends MasterNodeReadRequest<PrevalidateNodeRemovalRequest> {
    private final String[] nodeIds;

    public PrevalidateNodeRemovalRequest(String... nodeIds) {
        this.nodeIds = nodeIds;
    }

    public PrevalidateNodeRemovalRequest(final StreamInput in) throws IOException {
        super(in);
        nodeIds = in.readStringArray();
    }

    @Override
    public ActionRequestValidationException validate() {
        // TODO: make sure all provided node IDs are in the cluster
        return null;
    }

    public List<String> getNodeIds() {
        return List.of(nodeIds);
    }
}
