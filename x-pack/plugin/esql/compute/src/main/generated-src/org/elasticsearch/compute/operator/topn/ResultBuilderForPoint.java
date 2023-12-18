/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.topn;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.geo.SpatialPoint;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.PointBlock;

class ResultBuilderForPoint implements ResultBuilder {
    private final PointBlock.Builder builder;

    private final boolean inKey;

    /**
     * The value previously set by {@link #decodeKey}.
     */
    // TODO: we should be able to get rid of this object
    private SpatialPoint key;

    ResultBuilderForPoint(BlockFactory blockFactory, TopNEncoder encoder, boolean inKey, int initialSize) {
        assert encoder == TopNEncoder.DEFAULT_UNSORTABLE : encoder.toString();
        this.inKey = inKey;
        this.builder = PointBlock.newBlockBuilder(initialSize, blockFactory);
    }

    @Override
    public void decodeKey(BytesRef keys) {
        assert inKey;
        key = TopNEncoder.DEFAULT_SORTABLE.decodePoint(keys);
    }

    @Override
    public void decodeValue(BytesRef values) {
        int count = TopNEncoder.DEFAULT_UNSORTABLE.decodeVInt(values);
        switch (count) {
            case 0 -> {
                builder.appendNull();
            }
            case 1 -> {
                SpatialPoint value = inKey ? key : readValueFromValues(values);
                builder.appendPoint(value.getX(), value.getY());
            }
            default -> {
                builder.beginPositionEntry();
                for (int i = 0; i < count; i++) {
                    SpatialPoint point = readValueFromValues(values);
                    builder.appendPoint(point.getX(), point.getY());
                }
                builder.endPositionEntry();
            }
        }
    }

    private SpatialPoint readValueFromValues(BytesRef values) {
        return TopNEncoder.DEFAULT_UNSORTABLE.decodePoint(values);
    }

    @Override
    public PointBlock build() {
        return builder.build();
    }

    @Override
    public String toString() {
        return "ResultBuilderForPoint[inKey=" + inKey + "]";
    }

    @Override
    public void close() {
        builder.close();
    }
}
