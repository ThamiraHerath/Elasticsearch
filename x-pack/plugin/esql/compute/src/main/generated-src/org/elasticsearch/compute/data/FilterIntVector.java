/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.data;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.core.Releasables;

/**
 * Filter vector for IntVectors.
 * This class is generated. Do not edit it.
 */
public final class FilterIntVector extends AbstractFilterVector implements IntVector {

    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FilterIntVector.class);

    private final IntVector vector;

    FilterIntVector(IntVector vector, int... positions) {
        super(positions, vector.blockFactory());
        this.vector = vector;
    }

    @Override
    public int getInt(int position) {
        return vector.getInt(mapPosition(position));
    }

    @Override
    public IntBlock asBlock() {
        return new IntVectorBlock(this);
    }

    @Override
    public ElementType elementType() {
        return ElementType.INT;
    }

    @Override
    public boolean isConstant() {
        return vector.isConstant();
    }

    @Override
    public IntVector filter(int... positions) {
        return new FilterIntVector(this, positions);
    }

    @Override
    public long ramBytesUsed() {
        // from a usage and resource point of view filter vectors encapsulate
        // their inner vector, rather than listing it as a child resource
        return BASE_RAM_BYTES_USED + RamUsageEstimator.sizeOf(vector) + RamUsageEstimator.sizeOf(positions);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof IntVector that) {
            return IntVector.equals(this, that);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return IntVector.hash(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName());
        sb.append("[positions=" + getPositionCount() + ", values=[");
        appendValues(sb);
        sb.append("]]");
        return sb.toString();
    }

    private void appendValues(StringBuilder sb) {
        final int positions = getPositionCount();
        for (int i = 0; i < positions; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(getInt(i));
        }
    }

    @Override
    public BlockFactory blockFactory() {
        return vector.blockFactory();
    }

    @Override
    public void close() {
        Releasables.closeExpectNoException(vector);
    }
}
