/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedTimingStats;
import org.elasticsearch.xpack.core.watcher.watch.ClockMock;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.junit.Before;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

public class DatafeedTimingStatsReporterTests extends ESTestCase {

    private static final String JOB_ID = "my-job-id";

    private ClockMock clock;
    private JobResultsPersister jobResultsPersister;

    @Before
    public void setUpTests() {
        clock = new ClockMock();
        jobResultsPersister = mock(JobResultsPersister.class);
    }

    public void testExecuteWithReporting() {
        DatafeedTimingStats timingStats = new DatafeedTimingStats(JOB_ID, 10000.0);
        DatafeedTimingStatsReporter timingStatsReporter = new DatafeedTimingStatsReporter(timingStats, clock, jobResultsPersister);
        assertThat(timingStatsReporter.getCurrentTimingStats(), equalTo(new DatafeedTimingStats(JOB_ID, 10000.0)));

        timingStatsReporter.executeWithReporting(this::advanceTime, 1);
        assertThat(timingStatsReporter.getCurrentTimingStats(), equalTo(new DatafeedTimingStats(JOB_ID, 11000.0)));
        verifyZeroInteractions(jobResultsPersister);

        timingStatsReporter.executeWithReporting(this::advanceTime, 1);
        assertThat(timingStatsReporter.getCurrentTimingStats(), equalTo(new DatafeedTimingStats(JOB_ID, 12000.0)));
        verify(jobResultsPersister).persistDatafeedTimingStats(new DatafeedTimingStats(JOB_ID, 12000.0));
        verifyNoMoreInteractions(jobResultsPersister);

        timingStatsReporter.executeWithReporting(this::advanceTime, 1);
        assertThat(timingStatsReporter.getCurrentTimingStats(), equalTo(new DatafeedTimingStats(JOB_ID, 13000.0)));
        verifyZeroInteractions(jobResultsPersister);

        timingStatsReporter.executeWithReporting(this::advanceTime, 1);
        assertThat(timingStatsReporter.getCurrentTimingStats(), equalTo(new DatafeedTimingStats(JOB_ID, 14000.0)));
        verify(jobResultsPersister).persistDatafeedTimingStats(new DatafeedTimingStats(JOB_ID, 14000.0));
        verifyNoMoreInteractions(jobResultsPersister);
    }

    private Void advanceTime(int seconds) {
        clock.fastForwardSeconds(seconds);
        return null;
    }
}
