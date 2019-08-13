/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.xpack.core.action.util.QueryPage;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedJobValidator;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedTimingStats;
import org.elasticsearch.xpack.core.ml.job.config.DataDescription;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.DataCounts;
import org.elasticsearch.xpack.core.ml.job.results.Bucket;
import org.elasticsearch.xpack.core.ml.job.results.Result;
import org.elasticsearch.xpack.ml.datafeed.delayeddatacheck.DelayedDataDetector;
import org.elasticsearch.xpack.ml.datafeed.delayeddatacheck.DelayedDataDetectorFactory;
import org.elasticsearch.xpack.ml.datafeed.extractor.DataExtractorFactory;
import org.elasticsearch.xpack.ml.datafeed.persistence.DatafeedConfigProvider;
import org.elasticsearch.xpack.ml.job.persistence.BucketsQueryBuilder;
import org.elasticsearch.xpack.ml.job.persistence.JobConfigProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsProvider;
import org.elasticsearch.xpack.ml.notifications.Auditor;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DatafeedJobBuilder {

    private final Client client;
    private final NamedXContentRegistry xContentRegistry;
    private final Auditor auditor;
    private final Supplier<Long> currentTimeSupplier;
    private final JobConfigProvider jobConfigProvider;
    private final JobResultsProvider jobResultsProvider;
    private final DatafeedConfigProvider datafeedConfigProvider;
    private final JobResultsPersister jobResultsPersister;

    public DatafeedJobBuilder(Client client, NamedXContentRegistry xContentRegistry, Auditor auditor, Supplier<Long> currentTimeSupplier,
                              JobConfigProvider jobConfigProvider, JobResultsProvider jobResultsProvider,
                              DatafeedConfigProvider datafeedConfigProvider, JobResultsPersister jobResultsPersister) {
        this.client = client;
        this.xContentRegistry = Objects.requireNonNull(xContentRegistry);
        this.auditor = Objects.requireNonNull(auditor);
        this.currentTimeSupplier = Objects.requireNonNull(currentTimeSupplier);
        this.jobConfigProvider = Objects.requireNonNull(jobConfigProvider);
        this.jobResultsProvider = Objects.requireNonNull(jobResultsProvider);
        this.datafeedConfigProvider = Objects.requireNonNull(datafeedConfigProvider);
        this.jobResultsPersister = Objects.requireNonNull(jobResultsPersister);
    }

    void build(String datafeedId, ActionListener<DatafeedJob> listener) {
        AtomicReference<Job> jobHolder = new AtomicReference<>();
        AtomicReference<DatafeedConfig> datafeedConfigHolder = new AtomicReference<>();

        // Step 5. Build datafeed job object
        Consumer<Context> contextHanlder = context -> {
            TimeValue frequency = getFrequencyOrDefault(datafeedConfigHolder.get(), jobHolder.get(), xContentRegistry);
            TimeValue queryDelay = datafeedConfigHolder.get().getQueryDelay();
            DelayedDataDetector delayedDataDetector =
                    DelayedDataDetectorFactory.buildDetector(jobHolder.get(), datafeedConfigHolder.get(), client, xContentRegistry);
            DatafeedJob datafeedJob =
                new DatafeedJob(
                    jobHolder.get().getId(),
                    buildDataDescription(jobHolder.get()),
                    frequency.millis(),
                    queryDelay.millis(),
                    context.dataExtractorFactory,
                    context.timingStatsReporter,
                    client,
                    auditor,
                    currentTimeSupplier,
                    delayedDataDetector,
                    context.latestFinalBucketEndMs,
                    context.latestRecordTimeMs);

            listener.onResponse(datafeedJob);
        };

        final Context context = new Context();

        // Context building complete - invoke final listener
        ActionListener<DataExtractorFactory> dataExtractorFactoryHandler = ActionListener.wrap(
                dataExtractorFactory -> {
                    context.dataExtractorFactory = dataExtractorFactory;
                    contextHanlder.accept(context);
                }, e -> {
                    auditor.error(jobHolder.get().getId(), e.getMessage());
                    listener.onFailure(e);
                }
        );

        // Create data extractor factory
        Consumer<DatafeedTimingStats> datafeedTimingStatsHandler = initialTimingStats -> {
            context.timingStatsReporter =
                new DatafeedTimingStatsReporter(initialTimingStats, jobResultsPersister::persistDatafeedTimingStats);
            DataExtractorFactory.create(
                client,
                datafeedConfigHolder.get(),
                jobHolder.get(),
                xContentRegistry,
                context.timingStatsReporter,
                dataExtractorFactoryHandler);
        };

        Consumer<DataCounts> dataCountsHandler = dataCounts -> {
            if (dataCounts.getLatestRecordTimeStamp() != null) {
                context.latestRecordTimeMs = dataCounts.getLatestRecordTimeStamp().getTime();
            }
            jobResultsProvider.datafeedTimingStats(jobHolder.get().getId(), datafeedTimingStatsHandler, listener::onFailure);
        };

        // Collect data counts
        Consumer<QueryPage<Bucket>> bucketsHandler = buckets -> {
            if (buckets.results().size() == 1) {
                TimeValue bucketSpan = jobHolder.get().getAnalysisConfig().getBucketSpan();
                context.latestFinalBucketEndMs = buckets.results().get(0).getTimestamp().getTime() + bucketSpan.millis() - 1;
            }
            jobResultsProvider.dataCounts(jobHolder.get().getId(), dataCountsHandler, listener::onFailure);
        };

        // Collect latest bucket
        Consumer<String> jobIdConsumer = jobId -> {
            BucketsQueryBuilder latestBucketQuery = new BucketsQueryBuilder()
                    .sortField(Result.TIMESTAMP.getPreferredName())
                    .sortDescending(true)
                    .size(1)
                    .includeInterim(false);
            jobResultsProvider.bucketsViaInternalClient(jobId, latestBucketQuery, bucketsHandler, e -> {
                if (e instanceof ResourceNotFoundException) {
                    QueryPage<Bucket> empty = new QueryPage<>(Collections.emptyList(), 0, Bucket.RESULT_TYPE_FIELD);
                    bucketsHandler.accept(empty);
                } else {
                    listener.onFailure(e);
                }
            });
        };

        // Get the job config and re-validate
        // Re-validation is required as the config has been re-read since
        // the previous validation
        ActionListener<Job.Builder> jobConfigListener = ActionListener.wrap(
                jobBuilder -> {
                    try {
                        jobHolder.set(jobBuilder.build());
                        DatafeedJobValidator.validate(datafeedConfigHolder.get(), jobHolder.get(), xContentRegistry);
                        jobIdConsumer.accept(jobHolder.get().getId());
                    } catch (Exception e) {
                        listener.onFailure(e);
                    }
                },
                listener::onFailure
        );

        // Get the datafeed config
        ActionListener<DatafeedConfig.Builder> datafeedConfigListener = ActionListener.wrap(
                configBuilder -> {
                    try {
                        datafeedConfigHolder.set(configBuilder.build());
                        jobConfigProvider.getJob(datafeedConfigHolder.get().getJobId(), jobConfigListener);
                    } catch (Exception e) {
                        listener.onFailure(e);
                    }
                },
                listener::onFailure
        );

        datafeedConfigProvider.getDatafeedConfig(datafeedId, datafeedConfigListener);
    }

    private static TimeValue getFrequencyOrDefault(DatafeedConfig datafeed, Job job, NamedXContentRegistry xContentRegistry) {
        TimeValue frequency = datafeed.getFrequency();
        if (frequency == null) {
            TimeValue bucketSpan = job.getAnalysisConfig().getBucketSpan();
            return datafeed.defaultFrequency(bucketSpan, xContentRegistry);
        }
        return frequency;
    }

    private static DataDescription buildDataDescription(Job job) {
        DataDescription.Builder dataDescription = new DataDescription.Builder();
        dataDescription.setFormat(DataDescription.DataFormat.XCONTENT);
        if (job.getDataDescription() != null) {
            dataDescription.setTimeField(job.getDataDescription().getTimeField());
        }
        dataDescription.setTimeFormat(DataDescription.EPOCH_MS);
        return dataDescription.build();
    }

    private static class Context {
        volatile long latestFinalBucketEndMs = -1L;
        volatile long latestRecordTimeMs = -1L;
        volatile DataExtractorFactory dataExtractorFactory;
        volatile DatafeedTimingStatsReporter timingStatsReporter;
    }
}
