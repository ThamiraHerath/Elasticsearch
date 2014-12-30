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
package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.search.IndexSearcher;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.Aggregator.SubAggCollectionMode;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;

import java.io.IOException;
import java.util.Map;

/**
 *
 */
public class TermsAggregatorFactory extends ValuesSourceAggregatorFactory<ValuesSource>  {

    public enum ExecutionMode {

        MAP(new ParseField("map")) {

            @Override
            Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource,
                              long maxOrd, Terms.Order order, TermsAggregator.BucketCountThresholds bucketCountThresholds, IncludeExclude includeExclude,
                              AggregationContext aggregationContext, Aggregator parent, SubAggCollectionMode subAggCollectMode, boolean showTermDocCountError, Map<String, Object> metaData) throws IOException {
                return new StringTermsAggregator(name, factories, valuesSource, order, bucketCountThresholds, includeExclude, aggregationContext, parent, subAggCollectMode, showTermDocCountError, metaData);
            }

            @Override
            boolean needsGlobalOrdinals() {
                return false;
            }

        },
        GLOBAL_ORDINALS(new ParseField("global_ordinals")) {

            @Override
            Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource,
                              long maxOrd, Terms.Order order, TermsAggregator.BucketCountThresholds bucketCountThresholds, IncludeExclude includeExclude,
                              AggregationContext aggregationContext, Aggregator parent, SubAggCollectionMode subAggCollectMode, boolean showTermDocCountError, Map<String, Object> metaData) throws IOException {
                return new GlobalOrdinalsStringTermsAggregator(name, factories, (ValuesSource.Bytes.WithOrdinals.FieldData) valuesSource, maxOrd, order, bucketCountThresholds, includeExclude, aggregationContext, parent, subAggCollectMode, showTermDocCountError, metaData);
            }

            @Override
            boolean needsGlobalOrdinals() {
                return true;
            }

        },
        GLOBAL_ORDINALS_HASH(new ParseField("global_ordinals_hash")) {

            @Override
            Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource,
                              long maxOrd, Terms.Order order, TermsAggregator.BucketCountThresholds bucketCountThresholds, IncludeExclude includeExclude,
                              AggregationContext aggregationContext, Aggregator parent, SubAggCollectionMode subAggCollectMode, boolean showTermDocCountError, Map<String, Object> metaData) throws IOException {
                return new GlobalOrdinalsStringTermsAggregator.WithHash(name, factories, (ValuesSource.Bytes.WithOrdinals.FieldData) valuesSource, maxOrd, order, bucketCountThresholds, includeExclude, aggregationContext, parent, subAggCollectMode, showTermDocCountError, metaData);
            }

            @Override
            boolean needsGlobalOrdinals() {
                return true;
            }
        },
        GLOBAL_ORDINALS_LOW_CARDINALITY(new ParseField("global_ordinals_low_cardinality")) {

            @Override
            Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource,
                              long maxOrd, Terms.Order order, TermsAggregator.BucketCountThresholds bucketCountThresholds, IncludeExclude includeExclude,
                              AggregationContext aggregationContext, Aggregator parent, SubAggCollectionMode subAggCollectMode, boolean showTermDocCountError, Map<String, Object> metaData) throws IOException {
                if (includeExclude != null || factories.count() > 0) {
                    return GLOBAL_ORDINALS.create(name, factories, valuesSource, maxOrd, order, bucketCountThresholds, includeExclude, aggregationContext, parent, subAggCollectMode, showTermDocCountError, metaData);
                }
                return new GlobalOrdinalsStringTermsAggregator.LowCardinality(name, factories, (ValuesSource.Bytes.WithOrdinals.FieldData) valuesSource, maxOrd, order, bucketCountThresholds, aggregationContext, parent, subAggCollectMode, showTermDocCountError, metaData);
            }

            @Override
            boolean needsGlobalOrdinals() {
                return true;
            }
        };

        public static ExecutionMode fromString(String value) {
            for (ExecutionMode mode : values()) {
                if (mode.parseField.match(value)) {
                    return mode;
                }
            }
            throw new ElasticsearchIllegalArgumentException("Unknown `execution_hint`: [" + value + "], expected any of " + values());
        }

        private final ParseField parseField;

        ExecutionMode(ParseField parseField) {
            this.parseField = parseField;
        }

        abstract Aggregator create(String name, AggregatorFactories factories, ValuesSource valuesSource,
                                   long maxOrd, Terms.Order order, TermsAggregator.BucketCountThresholds bucketCountThresholds,
                                   IncludeExclude includeExclude, AggregationContext aggregationContext, Aggregator parent, SubAggCollectionMode subAggCollectMode, boolean showTermDocCountError, Map<String, Object> metaData) throws IOException;

        abstract boolean needsGlobalOrdinals();

        @Override
        public String toString() {
            return parseField.getPreferredName();
        }
    }

    private final Terms.Order order;
    private final IncludeExclude includeExclude;
    private final String executionHint;
    private SubAggCollectionMode subAggCollectMode;
    private final TermsAggregator.BucketCountThresholds bucketCountThresholds;
    private boolean showTermDocCountError;

    public TermsAggregatorFactory(String name, ValuesSourceConfig config, Terms.Order order, TermsAggregator.BucketCountThresholds bucketCountThresholds, IncludeExclude includeExclude, String executionHint,SubAggCollectionMode executionMode, boolean showTermDocCountError) {
        super(name, StringTerms.TYPE.name(), config);
        this.order = order;
        this.includeExclude = includeExclude;
        this.executionHint = executionHint;
        this.bucketCountThresholds = bucketCountThresholds;
        this.subAggCollectMode = executionMode;
        this.showTermDocCountError = showTermDocCountError;
    }

    @Override
    protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent, Map<String, Object> metaData) throws IOException {
        final InternalAggregation aggregation = new UnmappedTerms(name, order, bucketCountThresholds.getRequiredSize(), bucketCountThresholds.getShardSize(), bucketCountThresholds.getMinDocCount(), metaData);
        return new NonCollectingAggregator(name, aggregationContext, parent, factories, metaData) {
            {
                // even in the case of an unmapped aggregator, validate the order
                InternalOrder.validate(order, this);
            }
            @Override
            public InternalAggregation buildEmptyAggregation() {
                return aggregation;
            }
        };
    }

    @Override
    protected Aggregator create(ValuesSource valuesSource, AggregationContext aggregationContext, Aggregator parent, boolean collectsOnly0, Map<String, Object> metaData) throws IOException {
        if (collectsOnly0 == false) {
            return asMultiBucketAggregator(this, aggregationContext, parent);
        }
        if (valuesSource instanceof ValuesSource.Bytes) {
            ExecutionMode execution = null;
            if (executionHint != null) {
                execution = ExecutionMode.fromString(executionHint);
            }

            // In some cases, using ordinals is just not supported: override it
            if (!(valuesSource instanceof ValuesSource.Bytes.WithOrdinals)) {
                execution = ExecutionMode.MAP;
            }

            final long maxOrd;
            final double ratio;
            if (execution == null || execution.needsGlobalOrdinals()) {
                ValuesSource.Bytes.WithOrdinals valueSourceWithOrdinals = (ValuesSource.Bytes.WithOrdinals) valuesSource;
                IndexSearcher indexSearcher = aggregationContext.searchContext().searcher();
                maxOrd = valueSourceWithOrdinals.globalMaxOrd(indexSearcher);
                ratio = maxOrd / ((double) indexSearcher.getIndexReader().numDocs());
            } else {
                maxOrd = -1;
                ratio = -1;
            }

            // Let's try to use a good default
            if (execution == null) {
                // if there is a parent bucket aggregator the number of instances of this aggregator is going
                // to be unbounded and most instances may only aggregate few documents, so use hashed based
                // global ordinals to keep the bucket ords dense.
                if (Aggregator.hasParentBucketsAggregator(parent)) {
                    execution = ExecutionMode.GLOBAL_ORDINALS_HASH;
                } else {
                    if (factories == AggregatorFactories.EMPTY) {
                        if (ratio <= 0.5 && maxOrd <= 2048) {
                            // 0.5: At least we need reduce the number of global ordinals look-ups by half
                            // 2048: GLOBAL_ORDINALS_LOW_CARDINALITY has additional memory usage, which directly linked to maxOrd, so we need to limit.
                            execution = ExecutionMode.GLOBAL_ORDINALS_LOW_CARDINALITY;
                        } else {
                            execution = ExecutionMode.GLOBAL_ORDINALS;
                        }
                    } else {
                        execution = ExecutionMode.GLOBAL_ORDINALS;
                    }
                }
            }

            assert execution != null;
            valuesSource.setNeedsGlobalOrdinals(execution.needsGlobalOrdinals());
            return execution.create(name, factories, valuesSource, maxOrd, order, bucketCountThresholds, includeExclude, aggregationContext, parent, subAggCollectMode, showTermDocCountError, metaData);
        }

        if ((includeExclude != null) && (includeExclude.isRegexBased())) {
            throw new AggregationExecutionException("Aggregation [" + name + "] cannot support regular expression style include/exclude " +
                    "settings as they can only be applied to string fields. Use an array of numeric values for include/exclude clauses used to filter numeric fields");
        }

        if (valuesSource instanceof ValuesSource.Numeric) {
            IncludeExclude.LongFilter longFilter = null;
            if (((ValuesSource.Numeric) valuesSource).isFloatingPoint()) {
                if (includeExclude != null) {
                    longFilter = includeExclude.convertToDoubleFilter();
                }
                return new DoubleTermsAggregator(name, factories, (ValuesSource.Numeric) valuesSource, config.format(),
                        order, bucketCountThresholds, aggregationContext, parent, subAggCollectMode,
                        showTermDocCountError, longFilter, metaData);
            }
            if (includeExclude != null) {
                longFilter = includeExclude.convertToLongFilter();
            }
            return new LongTermsAggregator(name, factories, (ValuesSource.Numeric) valuesSource, config.format(),
                    order, bucketCountThresholds, aggregationContext, parent, subAggCollectMode, showTermDocCountError, longFilter, metaData);
        }

        throw new AggregationExecutionException("terms aggregation cannot be applied to field [" + config.fieldContext().field() +
                "]. It can only be applied to numeric or string fields.");
    }

}
