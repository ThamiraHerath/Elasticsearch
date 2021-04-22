/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.snapshots.features;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Response to a feature state reset request. */
public class ResetFeatureStateResponse extends ActionResponse implements ToXContentObject {

    List<ResetFeatureStateStatus> resetFeatureStateStatusList;

    /**
     * Create a response showing which features have had state reset and success
     * or failure status.
     *
     * @param statusList A list of status responses
     */
    public ResetFeatureStateResponse(List<ResetFeatureStateStatus> statusList) {
        resetFeatureStateStatusList = new ArrayList<>();
        resetFeatureStateStatusList.addAll(statusList);
        resetFeatureStateStatusList.sort(Comparator.comparing(ResetFeatureStateStatus::getFeatureName));
    }

    public ResetFeatureStateResponse(StreamInput in) throws IOException {
        super(in);
        this.resetFeatureStateStatusList = in.readList(ResetFeatureStateStatus::new);
    }

    public List<ResetFeatureStateStatus> getFeatureStateResetStatusList() {
        return this.resetFeatureStateStatusList;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        {
            builder.startArray("features");
            for (ResetFeatureStateStatus resetFeatureStateStatus : this.resetFeatureStateStatusList) {
                builder.value(resetFeatureStateStatus);
            }
            builder.endArray();
        }
        builder.endObject();
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeList(this.resetFeatureStateStatusList);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResetFeatureStateResponse that = (ResetFeatureStateResponse) o;
        return Objects.equals(resetFeatureStateStatusList, that.resetFeatureStateStatusList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resetFeatureStateStatusList);
    }

    @Override
    public String toString() {
        return "ResetFeatureStateResponse{" +
            "resetFeatureStateStatusList=" + resetFeatureStateStatusList +
            '}';
    }

    /**
     * An object with the name of a feature and a message indicating success or
     * failure.
     */
    public static class ResetFeatureStateStatus implements Writeable, ToXContentObject {
        private final String featureName;
        private final Status status;
        private final Exception exception;

        public enum Status {
            SUCCESS,
            FAILURE
        }

        public static ResetFeatureStateStatus success(String featureName) {
            return new ResetFeatureStateStatus(featureName, Status.SUCCESS, null);
        }

        public static ResetFeatureStateStatus failure(String featureName, Exception exception) {
            return new ResetFeatureStateStatus(
                featureName,
                Status.FAILURE,
                exception);
        }

        public static ResetFeatureStateStatus failure(String featureName, String exceptionMessage) {
            return new ResetFeatureStateStatus(
                featureName,
                Status.FAILURE,
                new ElasticsearchException(exceptionMessage));
        }

        private ResetFeatureStateStatus(String featureName, Status status, Exception exception) {
            this.featureName = featureName;
            this.status = status;
            this.exception = exception;
        }

        ResetFeatureStateStatus(StreamInput in) throws IOException {
            this.featureName = in.readString();
            this.status = Status.valueOf(in.readString());
            this.exception = in.readBoolean() ? in.readException() : null;
        }

        public String getFeatureName() {
            return this.featureName;
        }

        public Status getStatus() {
            return this.status;
        }

        public Exception getException() {
            return this.exception;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("feature_name", this.featureName);
            builder.field("status", this.status);
            ElasticsearchException.generateFailureXContent(builder, params, exception, true);
            builder.endObject();
            return builder;
        }

        /**
         * Without a convenient way to compare Exception equality, we consider
         * only feature name and success or failure for equality.
         * @param o An object to compare for equality
         * @return True if the feature name and status are equal, false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ResetFeatureStateStatus that = (ResetFeatureStateStatus) o;
            return Objects.equals(featureName, that.featureName) && status == that.status;
        }

        /**
         * @return Hash code based only on feature name and status.
         */
        @Override
        public int hashCode() {
            return Objects.hash(featureName, status);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(this.featureName);
            out.writeString(this.status.toString());
            if (exception != null) {
                out.writeBoolean(true);
                out.writeException(exception);
            } else {
                out.writeBoolean(false);
            }
        }

        @Override
        public String toString() {
            return "ResetFeatureStateStatus{" +
                "featureName='" + featureName + '\'' +
                ", status=" + status +
                ", exception='" + exception + '\'' +
                '}';
        }
    }
}
