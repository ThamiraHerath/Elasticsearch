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

package org.elasticsearch.search.aggregations.bucket;

import org.elasticsearch.script.MockScriptPlugin;
import org.elasticsearch.search.lookup.LeafDocLookup;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Mock scripts shared by DateRangeIT and DateHistogramIT.
 *
 * Provides {@link DateScriptMocksPlugin#EXTRACT_FIELD} and {@link DateScriptMocksPlugin#PLUS_ONE_MONTH} scripts.
 */
public class DateScriptMocksPlugin extends MockScriptPlugin {
    static final String EXTRACT_FIELD = "extract_field";
    static final String PLUS_ONE_MONTH = "date_plus_1_month";

    @Override
    public Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
        Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();
        scripts.put(EXTRACT_FIELD, params -> {
            LeafDocLookup docLookup = (LeafDocLookup) params.get("doc");
            String fieldname = (String) params.get("fieldname");
            return docLookup.get(fieldname);
        });
        scripts.put(PLUS_ONE_MONTH, params ->
            new DateTime(Double.valueOf((double) params.get("_value")).longValue(), DateTimeZone.UTC).plusMonths(1).getMillis());
        return scripts;
    }
}
