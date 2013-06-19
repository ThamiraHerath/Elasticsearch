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

package org.elasticsearch.index.fielddata.plain;

import gnu.trove.list.array.TDoubleArrayList;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.packed.PackedInts;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.RamUsage;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.fielddata.*;
import org.elasticsearch.index.fielddata.fieldcomparator.DoubleValuesComparatorSource;
import org.elasticsearch.index.fielddata.fieldcomparator.SortMode;
import org.elasticsearch.index.fielddata.ordinals.Ordinals;
import org.elasticsearch.index.fielddata.ordinals.Ordinals.Docs;
import org.elasticsearch.index.fielddata.ordinals.OrdinalsBuilder;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.settings.IndexSettings;

/**
 */
public class DoubleArrayIndexFieldData extends AbstractIndexFieldData<DoubleArrayAtomicFieldData> implements IndexNumericFieldData<DoubleArrayAtomicFieldData> {

    public static class Builder implements IndexFieldData.Builder {

        @Override
        public IndexFieldData build(Index index, @IndexSettings Settings indexSettings, FieldMapper.Names fieldNames, FieldDataType type, IndexFieldDataCache cache) {
            return new DoubleArrayIndexFieldData(index, indexSettings, fieldNames, type, cache);
        }
    }

    public DoubleArrayIndexFieldData(Index index, @IndexSettings Settings indexSettings, FieldMapper.Names fieldNames, FieldDataType fieldDataType, IndexFieldDataCache cache) {
        super(index, indexSettings, fieldNames, fieldDataType, cache);
    }

    @Override
    public NumericType getNumericType() {
        return NumericType.DOUBLE;
    }

    @Override
    public boolean valuesOrdered() {
        // because we might have single values? we can dynamically update a flag to reflect that
        // based on the atomic field data loaded
        return false;
    }

    @Override
    public DoubleArrayAtomicFieldData load(AtomicReaderContext context) {
        try {
            return cache.load(context, this);
        } catch (Throwable e) {
            if (e instanceof ElasticSearchException) {
                throw (ElasticSearchException) e;
            } else {
                throw new ElasticSearchException(e.getMessage(), e);
            }
        }
    }

    @Override
    public DoubleArrayAtomicFieldData loadDirect(AtomicReaderContext context) throws Exception {

        AtomicReader reader = context.reader();
        Terms terms = reader.terms(getFieldNames().indexName());
        if (terms == null) {
            return DoubleArrayAtomicFieldData.EMPTY;
        }
        // TODO: how can we guess the number of terms? numerics end up creating more terms per value...
        final TDoubleArrayList values = new TDoubleArrayList();

        values.add(0); // first "t" indicates null value
        final float acceptableOverheadRatio = fieldDataType.getSettings().getAsFloat("acceptable_overhead_ratio", PackedInts.DEFAULT);
        OrdinalsBuilder builder = new OrdinalsBuilder(terms, reader.maxDoc(), acceptableOverheadRatio);
        try {
            final BytesRefIterator iter = builder.buildFromTerms(getNumericType().wrapTermsEnum(terms.iterator(null)), reader.getLiveDocs());
            BytesRef term;
            while ((term = iter.next()) != null) {
                values.add(NumericUtils.sortableLongToDouble(NumericUtils.prefixCodedToLong(term)));
            }
            Ordinals build = builder.build(fieldDataType.getSettings());
            if (!build.isMultiValued() && CommonSettings.removeOrdsOnSingleValue(fieldDataType)) {
                Docs ordinals = build.ordinals();
                final FixedBitSet set = builder.buildDocsWithValuesSet();

                // there's sweatspot where due to low unique value count, using ordinals will consume less memory
                long singleValuesArraySize = reader.maxDoc() * RamUsage.NUM_BYTES_DOUBLE + (set == null ? 0 : set.getBits().length * RamUsage.NUM_BYTES_LONG + RamUsage.NUM_BYTES_INT);
                long uniqueValuesArraySize = values.size() * RamUsage.NUM_BYTES_DOUBLE;
                long ordinalsSize = build.getMemorySizeInBytes();
                if (uniqueValuesArraySize + ordinalsSize < singleValuesArraySize) {
                    return new DoubleArrayAtomicFieldData.WithOrdinals(values.toArray(new double[values.size()]), reader.maxDoc(), build);
                }

                double[] sValues = new double[reader.maxDoc()];
                int maxDoc = reader.maxDoc();
                for (int i = 0; i < maxDoc; i++) {
                    sValues[i] = values.get(ordinals.getOrd(i));
                }

                if (set == null) {
                    return new DoubleArrayAtomicFieldData.Single(sValues, reader.maxDoc());
                } else {
                    return new DoubleArrayAtomicFieldData.SingleFixedSet(sValues, reader.maxDoc(), set);
                }
            } else {
                return new DoubleArrayAtomicFieldData.WithOrdinals(
                        values.toArray(new double[values.size()]),
                        reader.maxDoc(),
                        build);
            }
        } finally {
            builder.close();
        }

    }

    @Override
    public XFieldComparatorSource comparatorSource(@Nullable Object missingValue, SortMode sortMode) {
        return new DoubleValuesComparatorSource(this, missingValue, sortMode);
    }
}
