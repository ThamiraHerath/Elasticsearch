/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.legacygeo.search;

import org.elasticsearch.Version;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.legacygeo.test.TestLegacyGeoShapeFieldMapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.geo.GeoBoundingBoxQueryIntegTestCase;
import org.elasticsearch.test.VersionUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public class GeoBoundingBoxQueryLegacyGeoShapeIT extends GeoBoundingBoxQueryIntegTestCase {

    @Override
    protected boolean addMockGeoShapeFieldMapper() {
        return false;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(TestLegacyGeoShapeFieldMapperPlugin.class);
    }

    @Override
    public XContentBuilder getMapping() throws IOException {
        return XContentFactory.jsonBuilder()
            .startObject()
            .startObject("_doc")
            .startObject("properties")
            .startObject("location")
            .field("type", "geo_shape")
            .field("strategy", "recursive")
            .endObject()
            .endObject()
            .endObject()
            .endObject();
    }

    @Override
    public Version randomSupportedVersion() {
        return VersionUtils.randomIndexCompatibleVersion(random());
    }
}
