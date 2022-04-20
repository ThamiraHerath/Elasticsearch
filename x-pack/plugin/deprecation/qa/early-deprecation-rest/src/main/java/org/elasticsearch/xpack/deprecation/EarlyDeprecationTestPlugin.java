/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.deprecation;

import org.elasticsearch.logging.DeprecationLogger;
import org.elasticsearch.plugins.ClusterPlugin;
import org.elasticsearch.plugins.Plugin;

/**
 * A plugin to verify that a warning emitted before index template is loaded will be delayed
 */
public class EarlyDeprecationTestPlugin extends Plugin implements ClusterPlugin {
    private DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(EarlyDeprecationTestPlugin.class);

    @Override
    public void onNodeStarted() {
        deprecationLogger.warn(
            DeprecationLogger.DeprecationCategory.API,
            "early_deprecation",
            "Early deprecation emitted after node is started up"
        );
    }
}
