/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.util.ArrayUtil;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.IntBlock;

import java.util.Arrays;

/**
 * Removes duplicate values from multivalued positions.
 * This class is generated. Edit {@code X-MultivalueDedupe.java.st} instead.
 */
public class MultivalueDedupeDouble {
    /**
     * The number of entries before we switch from and {@code n^2} strategy
     * with low overhead to an {@code n*log(n)} strategy with higher overhead.
     * The choice of number has been experimentally derived.
     */
    private static final int ALWAYS_COPY_MISSING = 110;
    private final DoubleBlock block;
    private double[] work = new double[ArrayUtil.oversize(2, Double.BYTES)];
    private int w;

    public MultivalueDedupeDouble(DoubleBlock block) {
        this.block = block;
    }

    /**
     * Remove duplicate values from each position and write the results to a
     * {@link Block} using an adaptive algorithm based on the size of the input list.
     */
    public DoubleBlock dedupeToBlockAdaptive() {
        if (block.mvDeduplicated()) {
            return block;
        }
        DoubleBlock.Builder builder = DoubleBlock.newBlockBuilder(block.getPositionCount());
        for (int p = 0; p < block.getPositionCount(); p++) {
            int count = block.getValueCount(p);
            int first = block.getFirstValueIndex(p);
            switch (count) {
                case 0 -> builder.appendNull();
                case 1 -> builder.appendDouble(block.getDouble(first));
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

    /**
     * Remove duplicate values from each position and write the results to a
     * {@link Block} using an algorithm with very low overhead but {@code n^2}
     * case complexity for larger. Prefer {@link #dedupeToBlockAdaptive}
     * which picks based on the number of elements at each position.
     */
    public DoubleBlock dedupeToBlockUsingCopyAndSort() {
        if (block.mvDeduplicated()) {
            return block;
        }
        DoubleBlock.Builder builder = DoubleBlock.newBlockBuilder(block.getPositionCount());
        for (int p = 0; p < block.getPositionCount(); p++) {
            int count = block.getValueCount(p);
            int first = block.getFirstValueIndex(p);
            switch (count) {
                case 0 -> builder.appendNull();
                case 1 -> builder.appendDouble(block.getDouble(first));
                default -> {
                    copyAndSort(first, count);
                    writeSortedWork(builder);
                }
            }
        }
        return builder.build();
    }

    /**
     * Remove duplicate values from each position and write the results to a
     * {@link Block} using an algorithm that sorts all values. It has a higher
     * overhead for small numbers of values at each position than
     * {@link #dedupeToBlockUsingCopyMissing} for large numbers of values the
     * performance is dominated by the {@code n*log n} sort. Prefer
     * {@link #dedupeToBlockAdaptive} unless you need the results sorted.
     */
    public DoubleBlock dedupeToBlockUsingCopyMissing() {
        if (block.mvDeduplicated()) {
            return block;
        }
        DoubleBlock.Builder builder = DoubleBlock.newBlockBuilder(block.getPositionCount());
        for (int p = 0; p < block.getPositionCount(); p++) {
            int count = block.getValueCount(p);
            int first = block.getFirstValueIndex(p);
            switch (count) {
                case 0 -> builder.appendNull();
                case 1 -> builder.appendDouble(block.getDouble(first));
                default -> {
                    copyMissing(first, count);
                    writeUniquedWork(builder);
                }
            }
        }
        return builder.build();
    }

    /**
     * Dedupe values and build a {@link IntBlock} suitable for passing
     * as the grouping block to a {@link GroupingAggregatorFunction}.
     */
    public MultivalueDedupe.HashResult hash(LongHash hash) {
        IntBlock.Builder builder = IntBlock.newBlockBuilder(block.getPositionCount());
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
                    double v = block.getDouble(first);
                    hash(builder, hash, v);
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

    /**
     * Build a {@link BatchEncoder} which deduplicates values at each position
     * and then encodes the results into a {@link byte[]} which can be used for
     * things like hashing many fields together.
     */
    public BatchEncoder batchEncoder(int batchSize) {
        return new BatchEncoder.Doubles(batchSize) {
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
                            double v = block.getDouble(first);
                            if (hasCapacity(1)) {
                                startPosition();
                                encode(v);
                                endPosition();
                            } else {
                                work[0] = v;
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
            work[w++] = block.getDouble(i);
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

        work[0] = block.getDouble(first);
        w = 1;
        i: for (int i = first + 1; i < end; i++) {
            double v = block.getDouble(i);
            for (int j = 0; j < w; j++) {
                if (v == work[j]) {
                    continue i;
                }
            }
            work[w++] = v;
        }
    }

    /**
     * Writes an already deduplicated {@link #work} to a {@link DoubleBlock.Builder}.
     */
    private void writeUniquedWork(DoubleBlock.Builder builder) {
        if (w == 1) {
            builder.appendDouble(work[0]);
            return;
        }
        builder.beginPositionEntry();
        for (int i = 0; i < w; i++) {
            builder.appendDouble(work[i]);
        }
        builder.endPositionEntry();
    }

    /**
     * Writes a sorted {@link #work} to a {@link DoubleBlock.Builder}, skipping duplicates.
     */
    private void writeSortedWork(DoubleBlock.Builder builder) {
        if (w == 1) {
            builder.appendDouble(work[0]);
            return;
        }
        builder.beginPositionEntry();
        double prev = work[0];
        builder.appendDouble(prev);
        for (int i = 1; i < w; i++) {
            if (prev != work[i]) {
                prev = work[i];
                builder.appendDouble(prev);
            }
        }
        builder.endPositionEntry();
    }

    /**
     * Writes an already deduplicated {@link #work} to a hash.
     */
    private void hashUniquedWork(LongHash hash, IntBlock.Builder builder) {
        if (w == 1) {
            hash(builder, hash, work[0]);
            return;
        }
        builder.beginPositionEntry();
        for (int i = 0; i < w; i++) {
            hash(builder, hash, work[i]);
        }
        builder.endPositionEntry();
    }

    /**
     * Writes a sorted {@link #work} to a hash, skipping duplicates.
     */
    private void hashSortedWork(LongHash hash, IntBlock.Builder builder) {
        if (w == 1) {
            hash(builder, hash, work[0]);
            return;
        }
        builder.beginPositionEntry();
        double prev = work[0];
        hash(builder, hash, prev);
        for (int i = 1; i < w; i++) {
            if (prev != work[i]) {
                prev = work[i];
                hash(builder, hash, prev);
            }
        }
        builder.endPositionEntry();
    }

    /**
     * Writes a deduplicated {@link #work} to a {@link BatchEncoder.Doubles}.
     */
    private void encodeUniquedWork(BatchEncoder.Doubles encoder) {
        for (int i = 0; i < w; i++) {
            encoder.encode(work[i]);
        }
    }

    /**
     * Converts {@link #work} from sorted array to a deduplicated array.
     */
    private void convertSortedWorkToUnique() {
        double prev = work[0];
        int end = w;
        w = 1;
        for (int i = 1; i < end; i++) {
            if (prev != work[i]) {
                prev = work[i];
                work[w++] = prev;
            }
        }
    }

    private void grow(int size) {
        work = ArrayUtil.grow(work, size);
    }

    private void hash(IntBlock.Builder builder, LongHash hash, double v) {
        builder.appendInt(Math.toIntExact(BlockHash.hashOrdToGroupNullReserved(hash.add(Double.doubleToLongBits(v)))));
    }
}
