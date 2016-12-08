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

package org.elasticsearch.search.aggregations.metrics.cardinality;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.support.AbstractValuesSourceParser.AnyValuesSourceParser;

import java.io.IOException;


public class CardinalityParser extends AnyValuesSourceParser {

    private static final ParseField REHASH = new ParseField("rehash").withAllDeprecated("no replacement - values will always be rehashed");

    private final ObjectParser<CardinalityAggregationBuilder, QueryParseContext> parser;

    public CardinalityParser() {
        parser = new ObjectParser<>(CardinalityAggregationBuilder.NAME);
        addFields(parser, true, false);
        parser.declareLong(CardinalityAggregationBuilder::precisionThreshold, CardinalityAggregationBuilder.PRECISION_THRESHOLD_FIELD);
        parser.declareLong((b, v) -> {/*ignore*/}, REHASH);
    }

    @Override
    public AggregationBuilder parse(String aggregationName, QueryParseContext context) throws IOException {
        return parser.parse(context.parser(), new CardinalityAggregationBuilder(aggregationName, null), context);
    }

}
