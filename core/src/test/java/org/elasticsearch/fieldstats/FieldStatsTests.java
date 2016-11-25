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

package org.elasticsearch.fieldstats;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.action.fieldstats.FieldStats;
import org.elasticsearch.action.fieldstats.FieldStatsResponse;
import org.elasticsearch.action.fieldstats.IndexConstraint;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.elasticsearch.action.fieldstats.IndexConstraint.Comparison.GT;
import static org.elasticsearch.action.fieldstats.IndexConstraint.Comparison.GTE;
import static org.elasticsearch.action.fieldstats.IndexConstraint.Comparison.LT;
import static org.elasticsearch.action.fieldstats.IndexConstraint.Comparison.LTE;
import static org.elasticsearch.action.fieldstats.IndexConstraint.Property.MAX;
import static org.elasticsearch.action.fieldstats.IndexConstraint.Property.MIN;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class FieldStatsTests extends ESSingleNodeTestCase {
    public void testByte() {
        testNumberRange("field1", "byte", 12, 18);
        testNumberRange("field1", "byte", -5, 5);
        testNumberRange("field1", "byte", -18, -12);
    }

    public void testShort() {
        testNumberRange("field1", "short", 256, 266);
        testNumberRange("field1", "short", -5, 5);
        testNumberRange("field1", "short", -266, -256);
    }

    public void testInteger() {
        testNumberRange("field1", "integer", 56880, 56890);
        testNumberRange("field1", "integer", -5, 5);
        testNumberRange("field1", "integer", -56890, -56880);
    }

    public void testLong() {
        testNumberRange("field1", "long", 312321312312412L, 312321312312422L);
        testNumberRange("field1", "long", -5, 5);
        testNumberRange("field1", "long", -312321312312422L, -312321312312412L);
    }

    private static String makeType(String type, boolean indexed, boolean docValues, boolean stored) {
        return new StringBuilder()
            .append("type=").append(type)
            .append(",index=").append(indexed)
            .append(",doc_values=").append(docValues)
            .append(",store=").append(stored).toString();
    }

    public void testString() {
        createIndex("test", Settings.EMPTY, "test",
            "field_index", makeType("keyword", true, false, false),
            "field_dv", makeType("keyword", false, true, false),
            "field_stored", makeType("keyword", false, true, true),
            "field_source", makeType("keyword", false, false, false));
        for (int value = 0; value <= 10; value++) {
            String keyword =  String.format(Locale.ENGLISH, "%03d", value);
            client().prepareIndex("test", "test")
                .setSource("field_index", keyword,
                    "field_dv", keyword,
                    "field_stored", keyword,
                    "field_source", keyword).get();
        }
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse result = client().prepareFieldStats()
            .setFields("field_index", "field_dv", "field_stored", "field_source").get();
        assertThat(result.getAllFieldStats().size(), equalTo(3));
        for (String field : new String[] {"field_index", "field_dv", "field_stored"}) {
            assertThat(result.getAllFieldStats().get(field).getMaxDoc(), equalTo(11L));
            assertThat(result.getAllFieldStats().get(field).getDisplayType(),
                equalTo("string"));
            if ("field_index".equals(field)) {
                assertThat(result.getAllFieldStats().get(field).getMinValue(),
                    equalTo(new BytesRef(String.format(Locale.ENGLISH, "%03d", 0))));
                assertThat(result.getAllFieldStats().get(field).getMaxValue(),
                    equalTo(new BytesRef(String.format(Locale.ENGLISH, "%03d", 10))));
                assertThat(result.getAllFieldStats().get(field).getMinValueAsString(),
                    equalTo(String.format(Locale.ENGLISH, "%03d", 0)));
                assertThat(result.getAllFieldStats().get(field).getMaxValueAsString(),
                    equalTo(String.format(Locale.ENGLISH, "%03d", 10)));
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(11L));
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(100));
            } else {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(0L));
                assertNull(result.getAllFieldStats().get(field).getMinValue());
                assertNull(result.getAllFieldStats().get(field).getMaxValue());
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(0));
            }
        }
    }

    public void testDouble() {
        createIndex("test", Settings.EMPTY, "test",
            "field_index", makeType("double", true, false, false),
            "field_dv", makeType("double", false, true, false),
            "field_stored", makeType("double", false, true, true),
            "field_source", makeType("double", false, false, false));
        for (double value = -1; value <= 9; value++) {
            client().prepareIndex("test", "test")
                .setSource("field_index", value, "field_dv", value, "field_stored", value, "field_source", value).get();
        }
        client().admin().indices().prepareRefresh().get();
        FieldStatsResponse result = client().prepareFieldStats()
            .setFields("field_index", "field_dv", "field_stored", "field_source").get();
        for (String field : new String[] {"field_index", "field_dv", "field_stored"}) {
            assertThat(result.getAllFieldStats().get(field).getMaxDoc(), equalTo(11L));
            assertThat(result.getAllFieldStats().get(field).getDisplayType(), equalTo("float"));
            if ("field_index".equals(field)) {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(11L));
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(100));
                assertThat(result.getAllFieldStats().get(field).getMinValue(), equalTo(-1d));
                assertThat(result.getAllFieldStats().get(field).getMaxValue(), equalTo(9d));
                assertThat(result.getAllFieldStats().get(field).getMinValueAsString(), equalTo(Double.toString(-1)));
            } else {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(0L));
                assertNull(result.getAllFieldStats().get(field).getMinValue());
                assertNull(result.getAllFieldStats().get(field).getMaxValue());
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(0));
            }
        }
    }

    public void testHalfFloat() {
        createIndex("test", Settings.EMPTY, "test",
            "field_index", makeType("half_float", true, false, false),
            "field_dv", makeType("half_float", false, true, false),
            "field_stored", makeType("half_float", false, true, true),
            "field_source", makeType("half_float", false, false, false));
        for (float value = -1; value <= 9; value++) {
            client().prepareIndex("test", "test")
                .setSource("field_index", value, "field_dv", value, "field_stored", value, "field_source", value).get();
        }
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse result = client().prepareFieldStats()
            .setFields("field_index", "field_dv", "field_stored", "field_source").get();
        for (String field : new String[] {"field_index", "field_dv", "field_stored"}) {
            assertThat(result.getAllFieldStats().get(field).getMaxDoc(), equalTo(11L));
            assertThat(result.getAllFieldStats().get(field).getDisplayType(), equalTo("float"));
            if (field.equals("field_index")) {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(11L));
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(100));
                assertThat(result.getAllFieldStats().get(field).getMinValue(), equalTo(-1d));
                assertThat(result.getAllFieldStats().get(field).getMaxValue(), equalTo(9d));
                assertThat(result.getAllFieldStats().get(field).getMinValueAsString(), equalTo(Float.toString(-1)));
                assertThat(result.getAllFieldStats().get(field).getMaxValueAsString(), equalTo(Float.toString(9)));
            } else {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(0L));
                assertNull(result.getAllFieldStats().get(field).getMinValue());
                assertNull(result.getAllFieldStats().get(field).getMaxValue());
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(0));
            }
        }
    }

    public void testFloat() {
        String fieldName = "field";
        createIndex("test", Settings.EMPTY, "test",
            "field_index", makeType("float", true, false, false),
            "field_dv", makeType("float", false, true, false),
            "field_stored", makeType("float", false, true, true),
            "field_source", makeType("float", false, false, false));
        for (float value = -1; value <= 9; value++) {
            client().prepareIndex("test", "test")
                .setSource("field_index", value, "field_dv", value, "field_stored", value, "field_source", value).get();
        }
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse result = client().prepareFieldStats()
            .setFields("field_index", "field_dv", "field_stored", "field_source").get();
        for (String field : new String[]{"field_index", "field_dv", "field_stored"}) {
            assertThat(result.getAllFieldStats().get(field).getMaxDoc(), equalTo(11L));
            assertThat(result.getAllFieldStats().get(field).getDisplayType(), equalTo("float"));
            if (field.equals("field_index")) {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(11L));
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(100));
                assertThat(result.getAllFieldStats().get(field).getMinValue(), equalTo(-1d));
                assertThat(result.getAllFieldStats().get(field).getMaxValue(), equalTo(9d));
                assertThat(result.getAllFieldStats().get(field).getMinValueAsString(), equalTo(Float.toString(-1)));
                assertThat(result.getAllFieldStats().get(field).getMaxValueAsString(), equalTo(Float.toString(9)));
            } else {
                assertThat(result.getAllFieldStats().get(field).getDocCount(), equalTo(0L));
                assertNull(result.getAllFieldStats().get(field).getMinValue());
                assertNull(result.getAllFieldStats().get(field).getMaxValue());
                assertThat(result.getAllFieldStats().get(field).getDensity(), equalTo(0));
            }
        }
    }

    private void testNumberRange(String fieldName, String fieldType, long min, long max) {
        createIndex("test", Settings.EMPTY, "test", fieldName, "type=" + fieldType);
        // index=false
        createIndex("test1", Settings.EMPTY, "test", fieldName, "type=" + fieldType + ",index=false");
        // no value
        createIndex("test2", Settings.EMPTY, "test", fieldName, "type=" + fieldType);

        for (long value = min; value <= max; value++) {
            client().prepareIndex("test", "test").setSource(fieldName, value).get();
        }
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse result = client().prepareFieldStats().setFields(fieldName).get();
        long numDocs = max - min + 1;
        assertThat(result.getAllFieldStats().get(fieldName).getMaxDoc(), equalTo(numDocs));
        assertThat(result.getAllFieldStats().get(fieldName).getDocCount(), equalTo(numDocs));
        assertThat(result.getAllFieldStats().get(fieldName).getDensity(), equalTo(100));
        assertThat(result.getAllFieldStats().get(fieldName).getMinValue(), equalTo(min));
        assertThat(result.getAllFieldStats().get(fieldName).getMaxValue(), equalTo(max));
        assertThat(result.getAllFieldStats().get(fieldName).getMinValueAsString(),
            equalTo(java.lang.Long.toString(min)));
        assertThat(result.getAllFieldStats().get(fieldName).getMaxValueAsString(),
            equalTo(java.lang.Long.toString(max)));
        assertThat(result.getAllFieldStats().get(fieldName).isSearchable(), equalTo(true));
        assertThat(result.getAllFieldStats().get(fieldName).isAggregatable(), equalTo(true));
        if (fieldType.equals("float") || fieldType.equals("double") || fieldType.equals("half-float")) {
            assertThat(result.getAllFieldStats().get(fieldName).getDisplayType(), equalTo("float"));
        } else {
            assertThat(result.getAllFieldStats().get(fieldName).getDisplayType(), equalTo("integer"));
        }

        client().admin().indices().prepareDelete("test").get();
        client().admin().indices().prepareDelete("test1").get();
        client().admin().indices().prepareDelete("test2").get();
    }

    public void testMerge() {
        List<FieldStats> stats = new ArrayList<>();
        stats.add(new FieldStats.Long(1, 1L, 1L, 1L, true, false, 1L, 1L));
        stats.add(new FieldStats.Long(1, 1L, 1L, 1L, true, false, 1L, 1L));
        stats.add(new FieldStats.Long(1, 1L, 1L, 1L, true, false, 1L, 1L));
        stats.add(new FieldStats.Long(0, 0, 0, 0, false, false));

        FieldStats stat = new FieldStats.Long(1, 1L, 1L, 1L, true, false, 1L, 1L);
        for (FieldStats otherStat : stats) {
            stat.accumulate(otherStat);
        }
        assertThat(stat.getMaxDoc(), equalTo(4L));
        assertThat(stat.getDocCount(), equalTo(4L));
        assertThat(stat.getSumDocFreq(), equalTo(4L));
        assertThat(stat.getSumTotalTermFreq(), equalTo(4L));
        assertThat(stat.isSearchable(), equalTo(true));
        assertThat(stat.isAggregatable(), equalTo(false));
        assertThat(stat.getDisplayType(), equalTo("integer"));
    }

    public void testMerge_notAvailable() {
        List<FieldStats> stats = new ArrayList<>();
        stats.add(new FieldStats.Long(1, 1L, 1L, 1L, true, true, 1L, 1L));
        stats.add(new FieldStats.Long(1, 1L, 1L, 1L, true, true, 1L, 1L));
        stats.add(new FieldStats.Long(1, 1L, 1L, 1L, true, false, 1L, 1L));

        FieldStats stat = new FieldStats.Long(1, -1L, -1L, -1L, false, true, 1L, 1L);
        for (FieldStats otherStat : stats) {
            stat.accumulate(otherStat);
        }
        assertThat(stat.getMaxDoc(), equalTo(4L));
        assertThat(stat.getDocCount(), equalTo(-1L));
        assertThat(stat.getSumDocFreq(), equalTo(-1L));
        assertThat(stat.getSumTotalTermFreq(), equalTo(-1L));
        assertThat(stat.isSearchable(), equalTo(true));
        assertThat(stat.isAggregatable(), equalTo(true));
        assertThat(stat.getDisplayType(), equalTo("integer"));

        stats.add(new FieldStats.Long(1, -1L, -1L, -1L, false, true));
        stat = stats.remove(0);
        for (FieldStats otherStat : stats) {
            stat.accumulate(otherStat);
        }
        assertThat(stat.getMaxDoc(), equalTo(4L));
        assertThat(stat.getDocCount(), equalTo(-1L));
        assertThat(stat.getSumDocFreq(), equalTo(-1L));
        assertThat(stat.getSumTotalTermFreq(), equalTo(-1L));
        assertThat(stat.isSearchable(), equalTo(true));
        assertThat(stat.isAggregatable(), equalTo(true));
        assertThat(stat.getDisplayType(), equalTo("integer"));
    }

    public void testNumberFiltering() {
        createIndex("test1", Settings.EMPTY, "type", "value", "type=long");
        client().prepareIndex("test1", "test").setSource("value", 1L).get();
        createIndex("test2", Settings.EMPTY, "type", "value", "type=long");
        client().prepareIndex("test2", "test").setSource("value", 3L).get();
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse response = client().prepareFieldStats()
                .setFields("value")
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(2));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(), equalTo(1L));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(), equalTo(3L));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "-1"),
                    new IndexConstraint("value", MAX, LTE, "0"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "0"),
                    new IndexConstraint("value", MAX, LT, "1"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "0"),
                    new IndexConstraint("value", MAX, LTE, "1"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(), equalTo(1L));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "1"),
                    new IndexConstraint("value", MAX, LTE,  "2"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(), equalTo(1L));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT, "1"),
                    new IndexConstraint("value", MAX, LTE, "2"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT, "2"),
                    new IndexConstraint("value", MAX, LTE, "3"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(), equalTo(3L));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "3"),
                    new IndexConstraint("value", MAX, LTE, "4"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(), equalTo(3L));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT, "3"),
                    new IndexConstraint("value", MAX, LTE, "4"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE,  "1"),
                    new IndexConstraint("value", MAX, LTE, "3"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(2));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(), equalTo(1L));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(), equalTo(3L));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT, "1"),
                    new IndexConstraint("value", MAX, LT, "3"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));
    }

    public void testDateFiltering() {
        DateTime dateTime1 = new DateTime(2014, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC);
        String dateTime1Str = DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.parser().print(dateTime1);
        DateTime dateTime2 = new DateTime(2014, 1, 2, 0, 0, 0, 0, DateTimeZone.UTC);
        String dateTime2Str = DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.parser().print(dateTime2);

        createIndex("test1", Settings.EMPTY, "type", "value", "type=date", "value2", "type=date,index=false");
        client().prepareIndex("test1", "test")
            .setSource("value", dateTime1Str, "value2", dateTime1Str).get();
        createIndex("test2", Settings.EMPTY, "type", "value", "type=date");
        client().prepareIndex("test2", "test").setSource("value", dateTime2Str).get();
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse response = client().prepareFieldStats()
                .setFields("value")
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(2));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(),
            equalTo(dateTime1.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(),
            equalTo(dateTime2.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValueAsString(),
            equalTo(dateTime1Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValueAsString(),
            equalTo(dateTime2Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getDisplayType(),
            equalTo("date"));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "2013-12-30T00:00:00.000Z"),
                    new IndexConstraint("value", MAX, LTE, "2013-12-31T00:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "2013-12-31T00:00:00.000Z"),
                    new IndexConstraint("value", MAX, LTE, "2014-01-01T00:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(),
            equalTo(dateTime1.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValueAsString(),
            equalTo(dateTime1Str));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getDisplayType(),
            equalTo("date"));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT, "2014-01-01T00:00:00.000Z"),
                    new IndexConstraint("value", MAX, LTE, "2014-01-02T00:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(),
            equalTo(dateTime2.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValueAsString(),
            equalTo(dateTime2Str));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT, "2014-01-02T00:00:00.000Z"),
                    new IndexConstraint("value", MAX, LTE, "2014-01-03T00:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "2014-01-01T23:00:00.000Z"),
                    new IndexConstraint("value", MAX, LTE, "2014-01-02T01:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(),
            equalTo(dateTime2.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValueAsString(),
            equalTo(dateTime2Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getDisplayType(),
            equalTo("date"));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GTE, "2014-01-01T00:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(2));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(),
            equalTo(dateTime1.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(),
            equalTo(dateTime2.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValueAsString(),
            equalTo(dateTime1Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValueAsString(),
            equalTo(dateTime2Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getDisplayType(),
            equalTo("date"));

        response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MAX, LTE, "2014-01-02T00:00:00.000Z"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(2));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValue(),
            equalTo(dateTime1.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValue(),
            equalTo(dateTime2.getMillis()));
        assertThat(response.getIndicesMergedFieldStats().get("test1").get("value").getMinValueAsString(),
            equalTo(dateTime1Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValueAsString(),
            equalTo(dateTime2Str));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getDisplayType(),
            equalTo("date"));

        response = client().prepareFieldStats()
            .setFields("value2")
            .setIndexContraints(new IndexConstraint("value2", MAX, LTE, "2014-01-02T00:00:00.000Z"))
            .setLevel("indices")
            .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(0));
    }

    public void testDateFiltering_optionalFormat() {
        createIndex("test1", Settings.EMPTY, "type", "value", "type=date,format=strict_date_optional_time");
        client().prepareIndex("test1", "type").setSource("value", "2014-01-01T00:00:00.000Z").get();
        createIndex("test2", Settings.EMPTY, "type", "value", "type=date,format=strict_date_optional_time");
        client().prepareIndex("test2", "type").setSource("value", "2014-01-02T00:00:00.000Z").get();
        client().admin().indices().prepareRefresh().get();

        DateTime dateTime1 = new DateTime(2014, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC);
        DateTime dateTime2 = new DateTime(2014, 1, 2, 0, 0, 0, 0, DateTimeZone.UTC);
        FieldStatsResponse response = client().prepareFieldStats()
                .setFields("value")
                .setIndexContraints(new IndexConstraint("value", MIN, GT,
                    String.valueOf(dateTime1.getMillis()), "epoch_millis"),
                    new IndexConstraint("value", MAX, LTE, String.valueOf(dateTime2.getMillis()), "epoch_millis"))
                .setLevel("indices")
                .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getMinValueAsString(),
            equalTo("2014-01-02T00:00:00.000Z"));
        assertThat(response.getIndicesMergedFieldStats().get("test2").get("value").getDisplayType(),
            equalTo("date"));

        try {
            client().prepareFieldStats()
                    .setFields("value")
                    .setIndexContraints(new IndexConstraint("value", MIN, GT,
                        String.valueOf(dateTime1.getMillis()), "xyz"))
                    .setLevel("indices")
                    .get();
            fail("IllegalArgumentException should have been thrown");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("Invalid format"));
        }
    }

    public void testEmptyIndex() {
        createIndex("test1", Settings.EMPTY, "type", "value", "type=date");
        FieldStatsResponse response = client().prepareFieldStats()
            .setFields("*")
            .setLevel("indices")
            .get();
        assertThat(response.getIndicesMergedFieldStats().size(), equalTo(1));
        assertThat(response.getIndicesMergedFieldStats().get("test1").size(), equalTo(0));
    }

    public void testMetaFieldsNotIndexed() {
        createIndex("test", Settings.EMPTY);
        client().prepareIndex("test", "type").setSource().get();
        client().admin().indices().prepareRefresh().get();

        FieldStatsResponse response = client().prepareFieldStats()
            .setFields("_id", "_type")
            .get();
        assertThat(response.getAllFieldStats().size(), equalTo(1));
        assertThat(response.getAllFieldStats().get("_type").isSearchable(), equalTo(true));
        assertThat(response.getAllFieldStats().get("_type").isAggregatable(), equalTo(true));
    }

    public void testSerialization() throws IOException {
        Version version = randomBoolean() ? Version.CURRENT : Version.V_5_1_0_UNRELEASED;
        for (int i = 0; i < 20; i++) {
            assertSerialization(randomFieldStats(version.onOrAfter(Version.V_5_2_0_UNRELEASED)),
                version);
        }
    }

    /**
     * creates a random field stats which does not guarantee that {@link FieldStats#maxValue} is greater than {@link FieldStats#minValue}
     **/
    private FieldStats randomFieldStats(boolean withNullMinMax) throws UnknownHostException {
        int type = randomInt(5);
        switch (type) {
            case 0:
                if (withNullMinMax && randomBoolean()) {
                    return new FieldStats.Long(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean());
                } else {
                    return new FieldStats.Long(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean(), randomLong(), randomLong());
                }
            case 1:
                if (withNullMinMax && randomBoolean()) {
                    return new FieldStats.Double(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean());
                } else {
                    return new FieldStats.Double(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean(), randomDouble(), randomDouble());
                }
            case 2:
                if (withNullMinMax && randomBoolean()) {
                    return new FieldStats.Date(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean());
                } else {
                    return new FieldStats.Date(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean(), Joda.forPattern("basicDate"),
                        new Date().getTime(), new Date().getTime());
                }
            case 3:
                if (withNullMinMax && randomBoolean()) {
                    return new FieldStats.Text(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean());
                } else {
                    return new FieldStats.Text(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean(),
                        new BytesRef(randomAsciiOfLength(10)), new BytesRef(randomAsciiOfLength(20)));
                }
            case 4:
                if (withNullMinMax && randomBoolean()) {
                    return new FieldStats.Ip(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean());
                } else {
                    return new FieldStats.Ip(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean(),
                        InetAddress.getByName("::1"), InetAddress.getByName("::1"));
                }
            case 5:
                if (withNullMinMax && randomBoolean()) {
                    return new FieldStats.Ip(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean());
                } else {
                    return new FieldStats.Ip(randomPositiveLong(), randomPositiveLong(), randomPositiveLong(),
                        randomPositiveLong(), randomBoolean(), randomBoolean(),
                        InetAddress.getByName("1.2.3.4"), InetAddress.getByName("1.2.3.4"));
                }
            default:
                throw new IllegalArgumentException("Invalid type");
        }
    }

    private void assertSerialization(FieldStats stats, Version version) throws IOException {
        BytesStreamOutput output = new BytesStreamOutput();
        output.setVersion(version);
        stats.writeTo(output);
        output.flush();
        StreamInput input = output.bytes().streamInput();
        input.setVersion(version);
        FieldStats deserializedStats = FieldStats.readFrom(input);
        assertThat(stats, equalTo(deserializedStats));
        assertThat(stats.hashCode(), equalTo(deserializedStats.hashCode()));
    }
}
