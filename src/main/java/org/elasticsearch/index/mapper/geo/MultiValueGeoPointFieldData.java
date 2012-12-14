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

package org.elasticsearch.index.mapper.geo;

import org.elasticsearch.common.util.concurrent.ThreadLocals;
import org.elasticsearch.index.field.data.MultiValueOrdinalArray;
import org.elasticsearch.index.field.data.doubles.DoubleFieldData;
import org.elasticsearch.index.search.geo.GeoHashUtils;

/**
 *
 */
public class MultiValueGeoPointFieldData extends GeoPointFieldData {

    private static final int VALUE_CACHE_SIZE = 100;

    private static ThreadLocal<ThreadLocals.CleanableValue<GeoPoint[][]>> valuesArrayCache = new ThreadLocal<ThreadLocals.CleanableValue<GeoPoint[][]>>() {
        @Override
        protected ThreadLocals.CleanableValue<GeoPoint[][]> initialValue() {
            GeoPoint[][] value = new GeoPoint[VALUE_CACHE_SIZE][];
            for (int i = 0; i < value.length; i++) {
                value[i] = new GeoPoint[i];
                for (int j = 0; j < value[i].length; j++) {
                    value[i][j] = new GeoPoint();
                }
            }
            return new ThreadLocals.CleanableValue<GeoPoint[][]>(value);
        }
    };

    private ThreadLocal<ThreadLocals.CleanableValue<double[][]>> valuesLatCache = new ThreadLocal<ThreadLocals.CleanableValue<double[][]>>() {
        @Override
        protected ThreadLocals.CleanableValue<double[][]> initialValue() {
            double[][] value = new double[VALUE_CACHE_SIZE][];
            for (int i = 0; i < value.length; i++) {
                value[i] = new double[i];
            }
            return new ThreadLocals.CleanableValue<double[][]>(value);
        }
    };

    private ThreadLocal<ThreadLocals.CleanableValue<double[][]>> valuesLonCache = new ThreadLocal<ThreadLocals.CleanableValue<double[][]>>() {
        @Override
        protected ThreadLocals.CleanableValue<double[][]> initialValue() {
            double[][] value = new double[VALUE_CACHE_SIZE][];
            for (int i = 0; i < value.length; i++) {
                value[i] = new double[i];
            }
            return new ThreadLocals.CleanableValue<double[][]>(value);
        }
    };

    private final MultiValueOrdinalArray ordinals;

    public MultiValueGeoPointFieldData(String fieldName, int[][] ordinals, double[] lat, double[] lon) {
        super(fieldName, lat, lon);
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
            proc.onValue(docId, GeoHashUtils.encode(lat[o], lon[o]));
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachValueInDoc(int docId, ValueInDocProc proc) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();

        while (o != 0) {
            proc.onValue(docId, lat[o], lon[o]);
            o = ordinalIter.getNextOrdinal();
        }
    }

    @Override
    public void forEachOrdinalInDoc(int docId, OrdinalInDocProc proc) {
        ordinals.forEachOrdinalInDoc(docId, proc);
    }

    @Override
    public GeoPoint value(int docId) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        if (o == 0) return null;
        GeoPoint point = valuesCache.get().get();
        point.latlon(lat[o], lon[o]);
        return point;
    }

    protected int geValueCount(int docId) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int count = 0;
        while (ordinalIter.getNextOrdinal() != 0) count++;
        return count;
    }


    @Override
    public GeoPoint[] values(int docId) {
        int length = geValueCount(docId);
        if (length == 0) {
            return EMPTY_ARRAY;
        }
        GeoPoint[] points;
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);

        if (length < VALUE_CACHE_SIZE) {
            points = valuesArrayCache.get().get()[length];
            for (int i = 0; i < length; i++) {
                int loc = ordinalIter.getNextOrdinal();
                points[i].latlon(lat[loc], lon[loc]);
            }
        } else {
            points = new GeoPoint[length];
            for (int i = 0; i < length; i++) {
                int loc = ordinalIter.getNextOrdinal();
                points[i] = new GeoPoint(lat[loc], lon[loc]);
            }
        }
        return points;
    }

    @Override
    public double latValue(int docId) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        return o == 0 ? 0 : lat[o];
    }

    @Override
    public double lonValue(int docId) {
        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);
        int o = ordinalIter.getNextOrdinal();
        return o == 0 ? 0 : lon[o];
    }

    @Override
    public double[] latValues(int docId) {
        int length = geValueCount(docId);
        if (length == 0) {
            return DoubleFieldData.EMPTY_DOUBLE_ARRAY;
        }
        double[] doubles;
        if (length < VALUE_CACHE_SIZE) {
            doubles = valuesLatCache.get().get()[length];
        } else {
            doubles = new double[length];
        }

        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);

        for (int i = 0; i < length; i++) {
            doubles[i] = lat[ordinalIter.getNextOrdinal()];
        }
        return doubles;
    }

    @Override
    public double[] lonValues(int docId) {
        int length = geValueCount(docId);
        if (length == 0) {
            return DoubleFieldData.EMPTY_DOUBLE_ARRAY;
        }
        double[] doubles;
        if (length < VALUE_CACHE_SIZE) {
            doubles = valuesLonCache.get().get()[length];
        } else {
            doubles = new double[length];
        }

        MultiValueOrdinalArray.OrdinalIterator ordinalIter = ordinals.getOrdinalIteratorForDoc(docId);

        for (int i = 0; i < length; i++) {
            doubles[i] = lon[ordinalIter.getNextOrdinal()];
        }
        return doubles;
    }
}