/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.search.aggregations.bucket.composite;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.search.aggregations.LeafBucketCollector;

import java.io.IOException;

import static org.apache.lucene.index.SortedSetDocValues.NO_MORE_ORDS;

/**
 * A {@link SingleDimensionValuesSource} for global ordinals
 */
class GlobalOrdinalValuesSource extends SingleDimensionValuesSource<BytesRef> {
    private final CheckedFunction<LeafReaderContext, SortedSetDocValues, IOException> docValuesFunc;
    private final long[] values;
    private SortedSetDocValues lookup;
    private long currentValue;
    private Long afterValueGlobalOrd;
    private boolean isTopValueInsertionPoint;

    GlobalOrdinalValuesSource(MappedFieldType type, CheckedFunction<LeafReaderContext, SortedSetDocValues, IOException> docValuesFunc,
                              int size, int reverseMul) {
        super(type, size, reverseMul);
        this.docValuesFunc = docValuesFunc;
        this.values = new long[size];
    }

    @Override
    String type() {
        return "global_ordinals";
    }

    @Override
    void copyCurrent(int slot) {
        values[slot] = currentValue;
    }

    @Override
    int compare(int from, int to) {
        return Long.compare(values[from], values[to]) * reverseMul;
    }

    @Override
    int compareCurrent(int slot) {
        return Long.compare(currentValue, values[slot]) * reverseMul;
    }

    @Override
    int compareCurrentWithAfter() {
        int cmp = Long.compare(currentValue, afterValueGlobalOrd);
        if (cmp == 0 && isTopValueInsertionPoint) {
            // the top value is missing in this shard, the comparison is against
            // the insertion point of the top value so equality means that the value
            // is "after" the insertion point.
            return reverseMul;
        }
        return cmp * reverseMul;
    }

    @Override
    void setAfter(Comparable<?> value) {
        if (value instanceof BytesRef) {
            afterValue = (BytesRef) value;
        } else if (value instanceof String) {
            afterValue = new BytesRef(value.toString());
        } else {
            throw new IllegalArgumentException("invalid value, expected string, got " + value.getClass().getSimpleName());
        }
    }

    @Override
    BytesRef toComparable(int slot) throws IOException {
        return BytesRef.deepCopyOf(lookup.lookupOrd(values[slot]));
    }

    @Override
    LeafBucketCollector getLeafCollector(LeafReaderContext context, LeafBucketCollector next) throws IOException {
        final SortedSetDocValues dvs = docValuesFunc.apply(context);
        if (lookup == null) {
            initLookup(dvs);
        }
        return new LeafBucketCollector() {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (dvs.advanceExact(doc)) {
                    long ord;
                    while ((ord = dvs.nextOrd()) != NO_MORE_ORDS) {
                        currentValue = ord;
                        next.collect(doc, bucket);
                    }
                }
            }
        };
    }

    @Override
    LeafBucketCollector getLeafCollector(Comparable<?> value, LeafReaderContext context, LeafBucketCollector next) throws IOException {
        if (value.getClass() != BytesRef.class) {
            throw new IllegalArgumentException("Expected BytesRef, got " + value.getClass());
        }
        BytesRef term = (BytesRef) value;
        final SortedSetDocValues dvs = docValuesFunc.apply(context);
        if (lookup == null) {
            initLookup(dvs);
        }
        return new LeafBucketCollector() {
            boolean currentValueIsSet = false;

            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (!currentValueIsSet) {
                    if (dvs.advanceExact(doc)) {
                        long ord;
                        while ((ord = dvs.nextOrd()) != NO_MORE_ORDS) {
                            if (term.equals(lookup.lookupOrd(ord))) {
                                currentValueIsSet = true;
                                currentValue = ord;
                                break;
                            }
                        }
                    }
                }
                assert currentValueIsSet;
                next.collect(doc, bucket);
            }
        };
    }

    @Override
    SortedDocsProducer createSortedDocsProducerOrNull(IndexReader reader, Query query) {
        if (checkIfSortedDocsIsApplicable(reader, fieldType) == false ||
                (query != null && query.getClass() != MatchAllDocsQuery.class)) {
            return null;
        }
        return new TermsSortedDocsProducer(fieldType.name());
    }

    private void initLookup(SortedSetDocValues dvs) throws IOException {
        lookup = dvs;
        if (afterValue != null && afterValueGlobalOrd == null) {
            afterValueGlobalOrd = lookup.lookupTerm(afterValue);
            if (afterValueGlobalOrd < 0) {
                // convert negative insert position
                afterValueGlobalOrd = -afterValueGlobalOrd - 1;
                isTopValueInsertionPoint = true;
            }
        }
    }
}
