/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.spatial.index.fielddata.plain;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.util.Accountable;
import org.elasticsearch.script.field.ToScriptFieldFactory;
import org.elasticsearch.xpack.spatial.index.fielddata.GeoShapeValues;
import org.elasticsearch.xpack.spatial.index.fielddata.LeafShapeFieldData;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

final class LatLonShapeDVAtomicShapeFieldData extends LeafShapeFieldData<GeoShapeValues> {
    private final LeafReader reader;
    private final String fieldName;

    LatLonShapeDVAtomicShapeFieldData(LeafReader reader, String fieldName, ToScriptFieldFactory<GeoShapeValues> toScriptFieldFactory) {
        super(toScriptFieldFactory);
        this.reader = reader;
        this.fieldName = fieldName;
    }

    @Override
    public long ramBytesUsed() {
        return 0; // not exposed by lucene
    }

    @Override
    public Collection<Accountable> getChildResources() {
        return Collections.emptyList();
    }

    @Override
    public void close() {
        // noop
    }

    @Override
    public GeoShapeValues getShapeValues() {
        try {
            return new GeoShapeValues.BinaryDocData(DocValues.getBinary(reader, fieldName));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load doc values", e);
        }
    }
}
