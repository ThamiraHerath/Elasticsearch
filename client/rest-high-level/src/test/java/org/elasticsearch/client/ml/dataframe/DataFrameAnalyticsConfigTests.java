/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.client.ml.dataframe;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.test.AbstractXContentTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.elasticsearch.client.ml.dataframe.DataFrameAnalyticsSourceTests.randomSourceConfig;
import static org.elasticsearch.client.ml.dataframe.DataFrameAnalyticsDestTests.randomDestConfig;
import static org.elasticsearch.client.ml.dataframe.OutlierDetectionTests.randomOutlierDetection;

public class DataFrameAnalyticsConfigTests extends AbstractXContentTestCase<DataFrameAnalyticsConfig> {

    public static DataFrameAnalyticsConfig randomDataFrameAnalyticsConfig() {
        return new DataFrameAnalyticsConfig(
            randomAlphaOfLengthBetween(1, 10),
            randomSourceConfig(),
            randomDestConfig(),
            randomOutlierDetection(),
            null,
            null);
    }

    @Override
    protected DataFrameAnalyticsConfig createTestInstance() {
        return randomDataFrameAnalyticsConfig();
    }

    @Override
    protected DataFrameAnalyticsConfig doParseInstance(XContentParser parser) throws IOException {
        return DataFrameAnalyticsConfig.fromXContent(parser);
    }

    @Override
    protected boolean supportsUnknownFields() {
        return true;
    }

    @Override
    protected Predicate<String> getRandomFieldsExcludeFilter() {
        // allow unknown fields in the root of the object only
        return field -> !field.isEmpty();
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        List<NamedXContentRegistry.Entry> namedXContent = new ArrayList<>();
        namedXContent.addAll(new SearchModule(Settings.EMPTY, false, Collections.emptyList()).getNamedXContents());
        namedXContent.add(
            new NamedXContentRegistry.Entry(DataFrameAnalysis.class, OutlierDetection.NAME, (p, c) -> OutlierDetection.fromXContent(p)));
        return new NamedXContentRegistry(namedXContent);
    }
}
