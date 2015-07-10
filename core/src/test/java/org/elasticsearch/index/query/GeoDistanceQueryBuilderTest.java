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

package org.elasticsearch.index.query;

import org.apache.lucene.search.Query;
import org.elasticsearch.common.geo.GeoDistance;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.fielddata.IndexGeoPointFieldData;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.geo.GeoPointFieldMapper;
import org.elasticsearch.index.search.geo.GeoDistanceRangeQuery;
import org.junit.Test;

import java.io.IOException;

public class GeoDistanceQueryBuilderTest extends BaseQueryTestCase<GeoDistanceQueryBuilder> {

    @Override
    protected GeoDistanceQueryBuilder doCreateTestQueryBuilder() {
        GeoDistanceQueryBuilder qb = new GeoDistanceQueryBuilder("mapped_geo");
        String distance = "" + randomDouble();
        if (randomBoolean()) {
            DistanceUnit unit = randomFrom(DistanceUnit.values());
            distance = distance + unit.toString();
        }
        int selector = randomIntBetween(0, 2);
        switch (selector) {
            case 0:
                qb.distance(randomDouble(), randomFrom(DistanceUnit.values()));
                break;
            case 1:
                qb.distance(distance, randomFrom(DistanceUnit.values()));
                break;
            case 2:
                qb.distance(distance);
                break;
        }

        qb.point(GeoDataGenerator.randomGeoPoint());

        if (randomBoolean()) {
            qb.normalizeLat(randomBoolean());
        }

        if (randomBoolean()) {
            qb.normalizeLon(randomBoolean());
        }

        // TODO not testing memory here as it would need an entire test node pulled up
        qb.optimizeBbox(randomFrom("indexed"));

        if (randomBoolean()) {
            qb.geoDistance(randomFrom(GeoDistance.values()));
        }
        return qb;
    }

    @Override
    protected Query doCreateExpectedQuery(GeoDistanceQueryBuilder qb, QueryParseContext parseContext) throws IOException {
        double normDistance = qb.geoDistance().normalize(qb.distance(), DistanceUnit.DEFAULT);

        if (qb.normalizeLat() || qb.normalizeLon()) {
            GeoUtils.normalizePoint(qb.point(), qb.normalizeLat(), qb.normalizeLon());
        }

        MappedFieldType fieldType = parseContext.fieldMapper(qb.fieldName());
        if (fieldType == null) {
            throw new QueryParsingException(parseContext, "failed to find geo_point field [" + qb.fieldName() + "]");
        }
        if (!(fieldType instanceof GeoPointFieldMapper.GeoPointFieldType)) {
            throw new QueryParsingException(parseContext, "field [" + qb.fieldName() + "] is not a geo_point field");
        }
        GeoPointFieldMapper.GeoPointFieldType geoFieldType = ((GeoPointFieldMapper.GeoPointFieldType) fieldType);

        IndexGeoPointFieldData indexFieldData = parseContext.getForField(fieldType);
        Query query = new GeoDistanceRangeQuery(qb.point(), null, normDistance, true, false, qb.geoDistance(), geoFieldType, indexFieldData, qb.optimizeBbox());
        return query;
    }

    @Test
    public void testValidate() {
        
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSettingFieldToNullDisallowed() {
        new GeoDistanceQueryBuilder(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSettingFieldToEmptyDisallowed() {
        new GeoDistanceQueryBuilder("");
    }

}
