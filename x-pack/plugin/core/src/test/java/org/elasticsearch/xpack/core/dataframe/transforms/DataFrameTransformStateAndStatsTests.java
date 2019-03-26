/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.dataframe.transforms;

import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;

public class DataFrameTransformStateAndStatsTests extends AbstractSerializingDataFrameTestCase<DataFrameTransformStateAndStats> {

    public static DataFrameTransformStateAndStats randomDataFrameTransformStateAndStats(String id) {
        return new DataFrameTransformStateAndStats(id,
                DataFrameTransformStateTests.randomDataFrameTransformState(),
                DataFrameIndexerTransformStatsTests.randomStats(),
                DataFrameTransformCheckpointStatsTests.randomDataFrameTransformCheckpointStats());
    }

    public static DataFrameTransformStateAndStats randomDataFrameTransformStateAndStats() {
        return new DataFrameTransformStateAndStats(randomAlphaOfLengthBetween(1, 10),
                DataFrameTransformStateTests.randomDataFrameTransformState(),
                DataFrameIndexerTransformStatsTests.randomStats(),
                DataFrameTransformCheckpointStatsTests.randomDataFrameTransformCheckpointStats());
    }

    @Override
    protected DataFrameTransformStateAndStats doParseInstance(XContentParser parser) throws IOException {
        return DataFrameTransformStateAndStats.PARSER.apply(parser, null);
    }

    @Override
    protected DataFrameTransformStateAndStats createTestInstance() {
        return randomDataFrameTransformStateAndStats();
    }

    @Override
    protected Reader<DataFrameTransformStateAndStats> instanceReader() {
        return DataFrameTransformStateAndStats::new;
    }

}
