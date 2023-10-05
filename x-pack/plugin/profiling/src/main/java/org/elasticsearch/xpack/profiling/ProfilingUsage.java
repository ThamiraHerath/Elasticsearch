/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.profiling;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.xpack.core.XPackFeatureSet;
import org.elasticsearch.xpack.core.XPackField;

import java.io.IOException;

public class ProfilingUsage extends XPackFeatureSet.Usage {
    public ProfilingUsage(StreamInput input) throws IOException {
        super(input);
    }

    public ProfilingUsage(boolean available, boolean enabled) {
        super(XPackField.UNIVERSAL_PROFILING, available, enabled);
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        // TODO: This should probably say 8.11.0 but I'm not certain. Check with somebody what's appropriate.
        return TransportVersions.V_8_8_1;
    }
}
