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

package org.elasticsearch.search.aggregations.metrics.geocentroid;

import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.support.AbstractValuesSourceParser.GeoPointValuesSourceParser;

import java.io.IOException;

/**
 * Parser class for {@link org.elasticsearch.search.aggregations.metrics.geocentroid.GeoCentroidAggregator}
 */
public class GeoCentroidParser extends GeoPointValuesSourceParser {

    private final ObjectParser<GeoCentroidAggregationBuilder, QueryParseContext> parser;

    public GeoCentroidParser() {
        parser = new ObjectParser<>(GeoCentroidAggregationBuilder.NAME);
        addFields(parser, true, false);
    }

    @Override
    public AggregationBuilder parse(String aggregationName, QueryParseContext context) throws IOException {
        return parser.parse(context.parser(), new GeoCentroidAggregationBuilder(aggregationName), context);
    }

}
