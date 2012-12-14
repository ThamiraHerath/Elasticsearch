/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.field.data.shorts;

import org.elasticsearch.common.util.concurrent.ThreadLocals;
import org.elasticsearch.index.field.data.MultiValueOrdinalArray;
import org.elasticsearch.index.field.data.doubles.DoubleFieldData;

/**
 *
 */
public class MultiValueShortFieldData extends ShortFieldData {

    private static final int VALUE_CACHE_SIZE = 10;

    private ThreadLocal<ThreadLocals.CleanableValue<double[][]>> doublesValuesCache = new ThreadLocal<ThreadLocals.CleanableValue<double[][]>>() {
        @Override
        protected ThreadLocals.CleanableValue<double[][]> initialValue() {
            double[][] value = new double[VALUE_CACHE_SIZE][];
            for (int i = 0; i < value.length; i++) {
                value[i] = new double[i];
            }
            return new ThreadLocals.CleanableValue<double[][]>(value);
        }
    };

    private ThreadLocal<ThreadLocals.CleanableValue<short[][]>> valuesCache = new ThreadLocal<ThreadLocals.CleanableValue<short[][]>>() {
        @Override
        protected ThreadLocals.CleanableValue<short[][]> initialValue() {
            short[][] value = new short[VALUE_CACHE_SIZE][];
            for (int i = 0; i < value.length; i++) {
                value[i] = new short[i];
            }
            return new ThreadLocals.CleanableValue<short[][]>(value);
        }
    };

    // order with value 0 indicates no value
    private final MultiValueOrdinalArray ordinals;

    public MultiValueShortFieldData(String fieldName, int[][] ordinals, short[] values) {
        super(fieldName, values);
        this.ordinals = new MultiValueOrdinalArray(ordinals);
    }

    @Override
    protected long computeSizeInBytes() {
        long size = super.computeSizeInBytes();
        size += ordinals.computeSizeInBytes();
        return size;
    }

    @Override
    public boolean multiValued() {
        return true;
    }

    @Override
    public boolean hasValue(int docId) {
        return ordinals.hasValue(docId);
    }

    @Override
    public void forEachValueInDoc(int docId, StringValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        if (o == 0) {
            proc.onMissing(docId); // first one is special as we need to communicate 0 if nothing is found
            return;
        }

        while (o != 0) {
            proc.onValue(docId, Short.toString(values[o]));
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachValueInDoc(int docId, DoubleValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();

        while (o != 0) {
            proc.onValue(docId, values[o]);
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachValueInDoc(int docId, LongValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();

        while (o != 0) {
            proc.onValue(docId, values[o]);
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachValueInDoc(int docId, MissingDoubleValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        if (o == 0) {
            proc.onMissing(docId); // first one is special as we need to communicate 0 if nothing is found
            return;
        }

        while (o != 0) {
            proc.onValue(docId, values[o]);
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachValueInDoc(int docId, MissingLongValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        if (o == 0) {
            proc.onMissing(docId); // first one is special as we need to communicate 0 if nothing is found
            return;
        }

        while (o != 0) {
            proc.onValue(docId, values[o]);
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachValueInDoc(int docId, ValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        if (o == 0) {
            proc.onMissing(docId); // first one is special as we need to communicate 0 if nothing is found
            return;
        }

        while (o != 0) {
            proc.onValue(docId, values[o]);
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachOrdinalInDoc(int docId, OrdinalInDocProc proc) {
        ordinals.forEachOrdinalInDoc(docId, proc);
    }

    @Override
    public double[] doubleValues(int docId) {
        int length = geValueCount(docId);
        if (length == 0) {
            return DoubleFieldData.EMPTY_DOUBLE_ARRAY;
        }
        double[] doubles;
        if (length < VALUE_CACHE_SIZE) {
            doubles = doublesValuesCache.get().get()[length];
        } else {
            doubles = new double[length];
        }

        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);

        for (int i = 0; i < length; i++) {
            doubles[i] = values[ordinalIter.getNextOrdinal()];
        }
        return doubles;
    }

    protected int geValueCount(int docId) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int count = 0;
        while (ordinalIter.getNextOrdinal() != 0) count++;
        return count;
    }

    @Override
    public short value(int docId) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        return o == 0 ? 0 : values[o];
    }

    @Override
    public short[] values(int docId) {
        int length = geValueCount(docId);
        if (length == 0) {
            return EMPTY_SHORT_ARRAY;
        }
        short[] shorts;
        if (length < VALUE_CACHE_SIZE) {
            shorts = valuesCache.get().get()[length];
        } else {
            shorts = new short[length];
        }

        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);

        for (int i = 0; i < length; i++) {
            shorts[i] = values[ordinalIter.getNextOrdinal()];
        }
        return shorts;
    }
}