/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.util.ArrayUtil;
import org.elasticsearch.common.geo.SpatialPoint;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.PointBlock;

import java.util.Arrays;

/**
 * Removes duplicate values from multivalued positions.
 * This class is generated. Edit {@code X-MultivalueDedupe.java.st} instead.
 */
public class MultivalueDedupePoint {
    /**
     * The number of entries before we switch from and {@code n^2} strategy
     * with low overhead to an {@code n*log(n)} strategy with higher overhead.
     * The choice of number has been experimentally derived.
     */
    private static final int ALWAYS_COPY_MISSING = 110;
    private final PointBlock block;
    // TODO: figure out a way to remove this object array, currently needed for Arrays.sort() below
    private SpatialPoint[] work = new SpatialPoint[ArrayUtil.oversize(2, 16)];
    private int w;

    public MultivalueDedupePoint(PointBlock block) {
        this.block = block;
    }

    /**
     * Remove duplicate values from each position and write the results to a
     * {@link Block} using an adaptive algorithm based on the size of the input list.
     */
    public PointBlock dedupeToBlockAdaptive(BlockFactory blockFactory) {
        if (block.mvDeduplicated()) {
            block.incRef();
            return block;
        }
        try (PointBlock.Builder builder = PointBlock.newBlockBuilder(block.getPositionCount(), blockFactory)) {
            for (int p = 0; p < block.getPositionCount(); p++) {
                int count = block.getValueCount(p);
                int first = block.getFirstValueIndex(p);
                switch (count) {
                    case 0 -> builder.appendNull();
                    case 1 -> builder.appendPoint(block.getX(first), block.getY(first));
                    default -> {
                        /*
                         * It's better to copyMissing when there are few unique values
                         * and better to copy and sort when there are many unique values.
                         * The more duplicate values there are the more comparatively worse
                         * copyAndSort is. But we don't know how many unique values there
                         * because our job is to find them. So we use the count of values
                         * as a proxy that is fast to test. It's not always going to be
                         * optimal but it has the nice property of being quite quick on
                         * short lists and not n^2 levels of terrible on long ones.
                         *
                         * It'd also be possible to make a truly hybrid mechanism that
                         * switches from copyMissing to copyUnique once it collects enough
                         * unique values. The trouble is that the switch is expensive and
                         * makes kind of a "hole" in the performance of that mechanism where
                         * you may as well have just gone with either of the two other
                         * strategies. So we just don't try it for now.
                         */
                        if (count < ALWAYS_COPY_MISSING) {
                            copyMissing(first, count);
                            writeUniquedWork(builder);
                        } else {
                            copyAndSort(first, count);
                            writeSortedWork(builder);
                        }
                    }
                }
            }
            return builder.build();
        }
    }

    /**
     * Remove duplicate values from each position and write the results to a
     * {@link Block} using an algorithm with very low overhead but {@code n^2}
     * case complexity for larger. Prefer {@link #dedupeToBlockAdaptive}
     * which picks based on the number of elements at each position.
     */
    public PointBlock dedupeToBlockUsingCopyAndSort(BlockFactory blockFactory) {
        if (block.mvDeduplicated()) {
            block.incRef();
            return block;
        }
        try (PointBlock.Builder builder = PointBlock.newBlockBuilder(block.getPositionCount(), blockFactory)) {
            for (int p = 0; p < block.getPositionCount(); p++) {
                int count = block.getValueCount(p);
                int first = block.getFirstValueIndex(p);
                switch (count) {
                    case 0 -> builder.appendNull();
                    case 1 -> builder.appendPoint(block.getX(first), block.getY(first));
                    default -> {
                        copyAndSort(first, count);
                        writeSortedWork(builder);
                    }
                }
            }
            return builder.build();
        }
    }

    /**
     * Remove duplicate values from each position and write the results to a
     * {@link Block} using an algorithm that sorts all values. It has a higher
     * overhead for small numbers of values at each position than
     * {@link #dedupeToBlockUsingCopyMissing} for large numbers of values the
     * performance is dominated by the {@code n*log n} sort. Prefer
     * {@link #dedupeToBlockAdaptive} unless you need the results sorted.
     */
    public PointBlock dedupeToBlockUsingCopyMissing(BlockFactory blockFactory) {
        if (block.mvDeduplicated()) {
            block.incRef();
            return block;
        }
        try (PointBlock.Builder builder = PointBlock.newBlockBuilder(block.getPositionCount(), blockFactory)) {
            for (int p = 0; p < block.getPositionCount(); p++) {
                int count = block.getValueCount(p);
                int first = block.getFirstValueIndex(p);
                switch (count) {
                    case 0 -> builder.appendNull();
                    case 1 -> builder.appendPoint(block.getX(first), block.getY(first));
                    default -> {
                        copyMissing(first, count);
                        writeUniquedWork(builder);
                    }
                }
            }
            return builder.build();
        }
    }

    /**
     * Dedupe values and build a {@link IntBlock} suitable for passing
     * as the grouping block to a {@link GroupingAggregatorFunction}.
     */
    public MultivalueDedupe.HashResult hash(BlockFactory blockFactory, LongHash hash) {
        try (IntBlock.Builder builder = blockFactory.newIntBlockBuilder(block.getPositionCount())) {
            boolean sawNull = false;
            for (int p = 0; p < block.getPositionCount(); p++) {
                int count = block.getValueCount(p);
                int first = block.getFirstValueIndex(p);
                switch (count) {
                    case 0 -> {
                        sawNull = true;
                        builder.appendInt(0);
                    }
                    case 1 -> {
                        double x = block.getX(first);
                        double y = block.getY(first);
                        hash(builder, hash, x, y);
                    }
                    default -> {
                        if (count < ALWAYS_COPY_MISSING) {
                            copyMissing(first, count);
                            hashUniquedWork(hash, builder);
                        } else {
                            copyAndSort(first, count);
                            hashSortedWork(hash, builder);
                        }
                    }
                }
            }
            return new MultivalueDedupe.HashResult(builder.build(), sawNull);
        }
    }

    /**
     * Build a {@link BatchEncoder} which deduplicates values at each position
     * and then encodes the results into a {@link byte[]} which can be used for
     * things like hashing many fields together.
     */
    public BatchEncoder batchEncoder(int batchSize) {
        return new BatchEncoder.Points(batchSize) {
            @Override
            protected void readNextBatch() {
                int position = firstPosition();
                if (w > 0) {
                    // The last block didn't fit so we have to *make* it fit
                    ensureCapacity(w);
                    startPosition();
                    encodeUniquedWork(this);
                    endPosition();
                    position++;
                }
                for (; position < block.getPositionCount(); position++) {
                    int count = block.getValueCount(position);
                    int first = block.getFirstValueIndex(position);
                    switch (count) {
                        case 0 -> encodeNull();
                        case 1 -> {
                            double x = block.getX(first);
                            double y = block.getY(first);
                            if (hasCapacity(1)) {
                                startPosition();
                                encode(x, y);
                                endPosition();
                            } else {
                                // We should be able to replace this with two double[] if we can figure out the sorting below
                                work[0] = new SpatialPoint(x, y);
                                w = 1;
                                return;
                            }
                        }
                        default -> {
                            if (count < ALWAYS_COPY_MISSING) {
                                copyMissing(first, count);
                            } else {
                                copyAndSort(first, count);
                                convertSortedWorkToUnique();
                            }
                            if (hasCapacity(w)) {
                                startPosition();
                                encodeUniquedWork(this);
                                endPosition();
                            } else {
                                return;
                            }
                        }
                    }
                }
            }

        };
    }

    /**
     * Copy all value from the position into {@link #work} and then
     * sorts it {@code n * log(n)}.
     */
    private void copyAndSort(int first, int count) {
        grow(count);
        int end = first + count;

        w = 0;
        for (int i = first; i < end; i++) {
            work[w++] = new SpatialPoint(block.getX(i), block.getY(i));
        }

        Arrays.sort(work, 0, w);
    }

    /**
     * Fill {@link #work} with the unique values in the position by scanning
     * all fields already copied {@code n^2}.
     */
    private void copyMissing(int first, int count) {
        grow(count);
        int end = first + count;

        work[0] = new SpatialPoint(block.getX(first), block.getY(first));
        w = 1;
        i: for (int i = first + 1; i < end; i++) {
            SpatialPoint v = new SpatialPoint(block.getX(i), block.getY(i));
            for (int j = 0; j < w; j++) {
                if (v.equals(work[j])) {
                    continue i;
                }
            }
            work[w++] = v;
        }
    }

    /**
     * Writes an already deduplicated {@link #work} to a {@link PointBlock.Builder}.
     */
    private void writeUniquedWork(PointBlock.Builder builder) {
        if (w == 1) {
            builder.appendPoint(work[0].getX(), work[0].getY());
            return;
        }
        builder.beginPositionEntry();
        for (int i = 0; i < w; i++) {
            builder.appendPoint(work[i].getX(), work[i].getY());
        }
        builder.endPositionEntry();
    }

    /**
     * Writes a sorted {@link #work} to a {@link PointBlock.Builder}, skipping duplicates.
     */
    private void writeSortedWork(PointBlock.Builder builder) {
        if (w == 1) {
            builder.appendPoint(work[0].getX(), work[0].getY());
            return;
        }
        builder.beginPositionEntry();
        SpatialPoint prev = work[0];
        builder.appendPoint(prev.getX(), prev.getY());
        for (int i = 1; i < w; i++) {
            if (false == prev.equals(work[i])) {
                prev = work[i];
                builder.appendPoint(prev.getX(), prev.getY());
            }
        }
        builder.endPositionEntry();
    }

    /**
     * Writes an already deduplicated {@link #work} to a hash.
     */
    private void hashUniquedWork(LongHash hash, IntBlock.Builder builder) {
        if (w == 1) {
            hash(builder, hash, work[0].getX(), work[0].getY());
            return;
        }
        builder.beginPositionEntry();
        for (int i = 0; i < w; i++) {
            hash(builder, hash, work[i].getX(), work[i].getY());
        }
        builder.endPositionEntry();
    }

    /**
     * Writes a sorted {@link #work} to a hash, skipping duplicates.
     */
    private void hashSortedWork(LongHash hash, IntBlock.Builder builder) {
        if (w == 1) {
            hash(builder, hash, work[0].getX(), work[0].getY());
            return;
        }
        builder.beginPositionEntry();
        SpatialPoint prev = work[0];
        hash(builder, hash, prev.getX(), prev.getY());
        for (int i = 1; i < w; i++) {
            if (false == prev.equals(work[i])) {
                prev = work[i];
                hash(builder, hash, prev.getX(), prev.getY());
            }
        }
        builder.endPositionEntry();
    }

    /**
     * Writes a deduplicated {@link #work} to a {@link BatchEncoder.Points}.
     */
    private void encodeUniquedWork(BatchEncoder.Points encoder) {
        for (int i = 0; i < w; i++) {
            encoder.encode(work[i].getX(), work[i].getY());
        }
    }

    /**
     * Converts {@link #work} from sorted array to a deduplicated array.
     */
    private void convertSortedWorkToUnique() {
        SpatialPoint prev = work[0];
        int end = w;
        w = 1;
        for (int i = 1; i < end; i++) {
            if (false == prev.equals(work[i])) {
                prev = work[i];
                work[w++] = prev;
            }
        }
    }

    private void grow(int size) {
        work = ArrayUtil.grow(work, size);
    }

    private void hash(IntBlock.Builder builder, LongHash hash, double x, double y) {
        builder.appendInt(Math.toIntExact(BlockHash.hashOrdToGroupNullReserved(hash.add(31L * Double.hashCode(x) + Double.hashCode(y)))));
    }
}
