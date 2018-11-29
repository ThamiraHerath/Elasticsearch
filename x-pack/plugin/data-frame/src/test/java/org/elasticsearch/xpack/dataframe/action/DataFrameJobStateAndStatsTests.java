/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.dataframe.action;

import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.dataframe.action.DataFrameJobStateAndStats;
import org.elasticsearch.xpack.dataframe.job.AbstractSerializingDataFrameTestCase;
import org.elasticsearch.xpack.dataframe.job.DataFrameIndexerJobStatsTests;
import org.elasticsearch.xpack.dataframe.job.DataFrameJobStateTests;

import java.io.IOException;

public class DataFrameJobStateAndStatsTests
        extends AbstractSerializingDataFrameTestCase<DataFrameJobStateAndStats> {

    @Override
    protected DataFrameJobStateAndStats doParseInstance(XContentParser parser) throws IOException {
        return DataFrameJobStateAndStats.PARSER.apply(parser, null);
    }

    @Override
    protected DataFrameJobStateAndStats createTestInstance() {
        return new DataFrameJobStateAndStats(randomAlphaOfLengthBetween(1,10),
                DataFrameJobStateTests.randomDataFrameJobState(),
                DataFrameIndexerJobStatsTests.randomStats());
    }

    @Override
    protected Reader<DataFrameJobStateAndStats> instanceReader() {
        return DataFrameJobStateAndStats::new;
    }

}
