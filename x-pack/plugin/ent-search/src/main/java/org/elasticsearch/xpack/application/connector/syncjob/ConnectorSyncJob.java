/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.connector.syncjob;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.application.connector.Connector;
import org.elasticsearch.xpack.application.connector.ConnectorSyncStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a sync job in the Elasticsearch ecosystem. Sync jobs refer to a unit of work, which syncs data from a 3rd party
 * data source into an Elasticsearch index using the Connectors service. A ConnectorSyncJob always refers
 * to a corresponding {@link Connector}. Each ConnectorSyncJob instance encapsulates various settings and state information, including:
 * <ul>
 *     <li>A timestamp, when the sync job cancellation was requested.</li>
 *     <li>A timestamp, when the sync job was cancelled.</li>
 *     <li>A timestamp, when the sync job was completed.</li>
 *     <li>A subset of the {@link Connector} fields the sync job is referring to.</li>
 *     <li>A timestamp, when the sync job was created.</li>
 *     <li>The number of documents deleted by the sync job.</li>
 *     <li>An error, which might have appeared during the sync job execution.</li>
 *     <li>A unique identifier for distinguishing different connectors.</li>
 *     <li>The number of documents indexed by the sync job.</li>
 *     <li>The volume of the indexed documents.</li>
 *     <li>The {@link ConnectorSyncJobType} of the sync job.</li>
 *     <li>A timestamp, when the sync job was last seen by the Connectors service.</li>
 *     <li>A {@link Map} containing metadata of the sync job.</li>
 *     <li>A timestamp, when the sync job was started.</li>
 *     <li>The {@link ConnectorSyncStatus} of the connector.</li>
 *     <li>The total number of documents present in the index after the sync job completes.</li>
 *     <li>The {@link ConnectorSyncJobTriggerMethod} of the sync job.</li>
 *     <li>The hostname of the worker to run the sync job.</li>
 * </ul>
 */
public class ConnectorSyncJob implements Writeable, ToXContentObject {

    static final ParseField CANCELATION_REQUESTED_AT_FIELD = new ParseField("cancelation_requested_at");

    static final ParseField CANCELED_AT_FIELD = new ParseField("canceled_at");

    static final ParseField COMPLETED_AT_FIELD = new ParseField("completed_at");

    static final ParseField CONNECTOR_FIELD = new ParseField("connector");

    static final ParseField CREATED_AT_FIELD = new ParseField("created_at");

    static final ParseField DELETED_DOCUMENT_COUNT = new ParseField("deleted_document_count");

    static final ParseField ERROR_FIELD = new ParseField("error");

    public static final ParseField ID_FIELD = new ParseField("id");

    static final ParseField INDEXED_DOCUMENT_COUNT_FIELD = new ParseField("indexed_document_count");

    static final ParseField INDEXED_DOCUMENT_VOLUME_FIELD = new ParseField("indexed_document_volume");

    public static final ParseField JOB_TYPE_FIELD = new ParseField("job_type");

    static final ParseField LAST_SEEN_FIELD = new ParseField("last_seen");

    static final ParseField METADATA_FIELD = new ParseField("metadata");

    static final ParseField STARTED_AT_FIELD = new ParseField("started_at");

    static final ParseField STATUS_FIELD = new ParseField("status");

    static final ParseField TOTAL_DOCUMENT_COUNT_FIELD = new ParseField("total_document_count");

    public static final ParseField TRIGGER_METHOD_FIELD = new ParseField("trigger_method");

    static final ParseField WORKER_HOSTNAME_FIELD = new ParseField("worker_hostname");

    static final ConnectorSyncStatus DEFAULT_INITIAL_STATUS = ConnectorSyncStatus.PENDING;

    static final ConnectorSyncJobType DEFAULT_JOB_TYPE = ConnectorSyncJobType.FULL;

    static final ConnectorSyncJobTriggerMethod DEFAULT_TRIGGER_METHOD = ConnectorSyncJobTriggerMethod.ON_DEMAND;

    private final Instant cancelationRequestedAt;

    @Nullable
    private final Instant canceledAt;

    @Nullable
    private final Instant completedAt;

    private final Connector connector;

    private final Instant createdAt;

    private final long deletedDocumentCount;

    @Nullable
    private final String error;

    private final String id;

    private final long indexedDocumentCount;

    private final long indexedDocumentVolume;

    private final ConnectorSyncJobType jobType;

    @Nullable
    private final Instant lastSeen;

    private final Map<String, Object> metadata;

    @Nullable
    private final Instant startedAt;

    private final ConnectorSyncStatus status;

    @Nullable
    private final long totalDocumentCount;

    private final ConnectorSyncJobTriggerMethod triggerMethod;

    @Nullable
    private final String workerHostname;

    /**
     *
     * @param cancelationRequestedAt    Timestamp when the sync job cancellation was requested.
     * @param canceledAt                Timestamp, when the sync job was cancelled.
     * @param completedAt               Timestamp, when the sync job was completed.
     * @param connector                 Subset of connector fields the sync job is referring to.
     * @param createdAt                 Timestamp, when the sync job was created.
     * @param deletedDocumentCount      Number of documents deleted by the sync job.
     * @param error                     Error, which might have appeared during the sync job execution.
     * @param id                        Unique identifier for distinguishing different connectors.
     * @param indexedDocumentCount      Number of documents indexed by the sync job.
     * @param indexedDocumentVolume     Volume of the indexed documents.
     * @param jobType                   Job type of the sync job.
     * @param lastSeen                  Timestamp, when the sync was last seen by the Connectors service.
     * @param metadata                  Map containing metadata of the sync job.
     * @param startedAt                 Timestamp, when the sync job was started.
     * @param status                    Sync status of the connector.
     * @param totalDocumentCount        Total number of documents present in the index after the sync job completes.
     * @param triggerMethod             Trigger method of the sync job.
     * @param workerHostname            Hostname of the worker to run the sync job.
     */
    private ConnectorSyncJob(
        Instant cancelationRequestedAt,
        Instant canceledAt,
        Instant completedAt,
        Connector connector,
        Instant createdAt,
        long deletedDocumentCount,
        String error,
        String id,
        long indexedDocumentCount,
        long indexedDocumentVolume,
        ConnectorSyncJobType jobType,
        Instant lastSeen,
        Map<String, Object> metadata,
        Instant startedAt,
        ConnectorSyncStatus status,
        long totalDocumentCount,
        ConnectorSyncJobTriggerMethod triggerMethod,
        String workerHostname
    ) {
        this.cancelationRequestedAt = cancelationRequestedAt;
        this.canceledAt = canceledAt;
        this.completedAt = completedAt;
        this.connector = connector;
        this.createdAt = createdAt;
        this.deletedDocumentCount = deletedDocumentCount;
        this.error = error;
        this.id = Objects.requireNonNull(id, "[id] cannot be null");
        this.indexedDocumentCount = indexedDocumentCount;
        this.indexedDocumentVolume = indexedDocumentVolume;
        this.jobType = Objects.requireNonNullElse(jobType, ConnectorSyncJobType.FULL);
        this.lastSeen = lastSeen;
        this.metadata = Objects.requireNonNullElse(metadata, Collections.emptyMap());
        this.startedAt = startedAt;
        this.status = status;
        this.totalDocumentCount = totalDocumentCount;
        this.triggerMethod = Objects.requireNonNullElse(triggerMethod, ConnectorSyncJobTriggerMethod.ON_DEMAND);
        this.workerHostname = workerHostname;
    }

    public ConnectorSyncJob(StreamInput in) throws IOException {
        this.cancelationRequestedAt = in.readOptionalInstant();
        this.canceledAt = in.readOptionalInstant();
        this.completedAt = in.readOptionalInstant();
        this.connector = in.readNamedWriteable(Connector.class);
        this.createdAt = in.readInstant();
        this.deletedDocumentCount = in.readLong();
        this.error = in.readOptionalString();
        this.id = in.readString();
        this.indexedDocumentCount = in.readLong();
        this.indexedDocumentVolume = in.readLong();
        this.jobType = in.readEnum(ConnectorSyncJobType.class);
        this.lastSeen = in.readOptionalInstant();
        this.metadata = in.readMap(StreamInput::readString, StreamInput::readGenericValue);
        this.startedAt = in.readOptionalInstant();
        this.status = in.readEnum(ConnectorSyncStatus.class);
        this.totalDocumentCount = in.readOptionalLong();
        this.triggerMethod = in.readEnum(ConnectorSyncJobTriggerMethod.class);
        this.workerHostname = in.readOptionalString();
    }

    public String getId() {
        return id;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        {
            builder.field(CANCELATION_REQUESTED_AT_FIELD.getPreferredName(), cancelationRequestedAt);
            builder.field(CANCELED_AT_FIELD.getPreferredName(), canceledAt);
            builder.field(COMPLETED_AT_FIELD.getPreferredName(), completedAt);

            builder.startObject(CONNECTOR_FIELD.getPreferredName());
            {
                builder.field(Connector.ID_FIELD.getPreferredName(), connector.getConnectorId());
                builder.field(Connector.FILTERING_FIELD.getPreferredName(), connector.getFiltering());
                builder.field(Connector.INDEX_NAME_FIELD.getPreferredName(), connector.getIndexName());
                builder.field(Connector.LANGUAGE_FIELD.getPreferredName(), connector.getLanguage());
                builder.field(Connector.PIPELINE_FIELD.getPreferredName(), connector.getPipeline());
                builder.field(Connector.SERVICE_TYPE_FIELD.getPreferredName(), connector.getServiceType());
                builder.field(Connector.CONFIGURATION_FIELD.getPreferredName(), connector.getConfiguration());
            }
            builder.endObject();

            builder.field(CREATED_AT_FIELD.getPreferredName(), createdAt);
            builder.field(DELETED_DOCUMENT_COUNT.getPreferredName(), deletedDocumentCount);
            builder.field(ERROR_FIELD.getPreferredName(), error);
            builder.field(ID_FIELD.getPreferredName(), id);
            builder.field(INDEXED_DOCUMENT_COUNT_FIELD.getPreferredName(), indexedDocumentCount);
            builder.field(INDEXED_DOCUMENT_VOLUME_FIELD.getPreferredName(), indexedDocumentVolume);
            builder.field(JOB_TYPE_FIELD.getPreferredName(), jobType);
            builder.field(LAST_SEEN_FIELD.getPreferredName(), lastSeen);
            builder.field(METADATA_FIELD.getPreferredName(), metadata);
            builder.field(STARTED_AT_FIELD.getPreferredName(), startedAt);
            builder.field(STATUS_FIELD.getPreferredName(), status);
            builder.field(TOTAL_DOCUMENT_COUNT_FIELD.getPreferredName(), totalDocumentCount);
            builder.field(TRIGGER_METHOD_FIELD.getPreferredName(), triggerMethod);
            builder.field(WORKER_HOSTNAME_FIELD.getPreferredName(), workerHostname);
        }
        builder.endObject();
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalInstant(cancelationRequestedAt);
        out.writeOptionalInstant(canceledAt);
        out.writeOptionalInstant(completedAt);
        out.writeNamedWriteable(connector);
        out.writeInstant(createdAt);
        out.writeLong(deletedDocumentCount);
        out.writeOptionalString(error);
        out.writeString(id);
        out.writeLong(indexedDocumentCount);
        out.writeLong(indexedDocumentVolume);
        out.writeEnum(jobType);
        out.writeOptionalInstant(lastSeen);
        out.writeMap(metadata, StreamOutput::writeString, StreamOutput::writeGenericValue);
        out.writeOptionalInstant(startedAt);
        out.writeEnum(status);
        out.writeOptionalLong(totalDocumentCount);
        out.writeEnum(triggerMethod);
        out.writeOptionalString(workerHostname);
    }

    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;

        ConnectorSyncJob connectorSyncJob = (ConnectorSyncJob) other;

        return Objects.equals(cancelationRequestedAt, connectorSyncJob.cancelationRequestedAt)
            && Objects.equals(canceledAt, connectorSyncJob.canceledAt)
            && Objects.equals(completedAt, connectorSyncJob.completedAt)
            && Objects.equals(connector, connectorSyncJob.connector)
            && Objects.equals(createdAt, connectorSyncJob.createdAt)
            && Objects.equals(deletedDocumentCount, connectorSyncJob.deletedDocumentCount)
            && Objects.equals(error, connectorSyncJob.error)
            && Objects.equals(id, connectorSyncJob.id)
            && Objects.equals(indexedDocumentCount, connectorSyncJob.indexedDocumentCount)
            && Objects.equals(indexedDocumentVolume, connectorSyncJob.indexedDocumentVolume)
            && Objects.equals(jobType, connectorSyncJob.jobType)
            && Objects.equals(lastSeen, connectorSyncJob.lastSeen)
            && Objects.equals(metadata, connectorSyncJob.metadata)
            && Objects.equals(startedAt, connectorSyncJob.startedAt)
            && Objects.equals(status, connectorSyncJob.status)
            && Objects.equals(totalDocumentCount, connectorSyncJob.totalDocumentCount)
            && Objects.equals(triggerMethod, connectorSyncJob.triggerMethod)
            && Objects.equals(workerHostname, connectorSyncJob.workerHostname);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            cancelationRequestedAt,
            canceledAt,
            completedAt,
            connector,
            createdAt,
            deletedDocumentCount,
            error,
            id,
            indexedDocumentCount,
            indexedDocumentVolume,
            jobType,
            lastSeen,
            metadata,
            startedAt,
            status,
            totalDocumentCount,
            triggerMethod,
            workerHostname
        );
    }

    public static class Builder {
        private Instant cancellationRequestedAt;

        private Instant canceledAt;

        private Instant completedAt;

        private Connector connector;

        private Instant createdAt;

        private long deletedDocumentCount;

        private String error;

        private String id;

        private long indexedDocumentCount;

        private long indexedDocumentVolume;

        private ConnectorSyncJobType jobType;

        private Instant lastSeen;

        private Map<String, Object> metadata;

        private Instant startedAt;

        private ConnectorSyncStatus status;

        private long totalDocumentCount;

        private ConnectorSyncJobTriggerMethod triggerMethod;

        private String workerHostname;

        public Builder setCancellationRequestedAt(Instant cancellationRequestedAt) {
            this.cancellationRequestedAt = cancellationRequestedAt;
            return this;
        }

        public Builder setCanceledAt(Instant canceledAt) {
            this.canceledAt = canceledAt;
            return this;
        }

        public Builder setCompletedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder setConnector(Connector connector) {
            this.connector = connector;
            return this;
        }

        public Builder setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder setDeletedDocumentCount(long deletedDocumentCount) {
            this.deletedDocumentCount = deletedDocumentCount;
            return this;
        }

        public Builder setError(String error) {
            this.error = error;
            return this;
        }

        public Builder setId(String id) {
            this.id = id;
            return this;
        }

        public Builder setIndexedDocumentCount(long indexedDocumentCount) {
            this.indexedDocumentCount = indexedDocumentCount;
            return this;
        }

        public Builder setIndexedDocumentVolume(long indexedDocumentVolume) {
            this.indexedDocumentVolume = indexedDocumentVolume;
            return this;
        }

        public Builder setJobType(ConnectorSyncJobType jobType) {
            this.jobType = jobType;
            return this;
        }

        public Builder setLastSeen(Instant lastSeen) {
            this.lastSeen = lastSeen;
            return this;
        }

        public Builder setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder setStatus(ConnectorSyncStatus status) {
            this.status = status;
            return this;
        }

        public Builder setTotalDocumentCount(long totalDocumentCount) {
            this.totalDocumentCount = totalDocumentCount;
            return this;
        }

        public Builder setTriggerMethod(ConnectorSyncJobTriggerMethod triggerMethod) {
            this.triggerMethod = triggerMethod;
            return this;
        }

        public Builder setWorkerHostname(String workerHostname) {
            this.workerHostname = workerHostname;
            return this;
        }

        public ConnectorSyncJob build() {
            return new ConnectorSyncJob(
                cancellationRequestedAt,
                canceledAt,
                completedAt,
                connector,
                createdAt,
                deletedDocumentCount,
                error,
                id,
                indexedDocumentCount,
                indexedDocumentVolume,
                jobType,
                lastSeen,
                metadata,
                startedAt,
                status,
                totalDocumentCount,
                triggerMethod,
                workerHostname
            );
        }
    }
}
