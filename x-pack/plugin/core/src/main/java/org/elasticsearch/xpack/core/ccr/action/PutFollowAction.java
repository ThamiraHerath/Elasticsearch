/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.ccr.action;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.AcknowledgedRequest;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.xpack.core.ccr.action.ResumeFollowAction.Request.FOLLOWER_INDEX_FIELD;

public final class PutFollowAction extends Action<PutFollowAction.Response> {

    public static final PutFollowAction INSTANCE = new PutFollowAction();
    public static final String NAME = "indices:admin/xpack/ccr/put_follow";

    private PutFollowAction() {
        super(NAME);
    }

    @Override
    public Response newResponse() {
        throw new UnsupportedOperationException("usage of Streamable is to be replaced by Writeable");
    }

    @Override
    public Writeable.Reader<Response> getResponseReader() {
        return Response::new;
    }

    public static class Request extends AcknowledgedRequest<Request> implements IndicesRequest, ToXContentObject {

        public static Request fromXContent(final XContentParser parser, final String followerIndex) throws IOException {
            Body body = Body.PARSER.parse(parser, null);
            if (followerIndex != null) {
                if (body.getFollowerIndex() == null) {
                    body.setFollowerIndex(followerIndex);
                } else {
                    if (body.getFollowerIndex().equals(followerIndex) == false) {
                        throw new IllegalArgumentException("provided follower_index is not equal");
                    }
                }
            }
            Request request = new Request();
            request.setBody(body);
            return request;
        }

        private Body body = new Body();

        public Request() {
        }

        public Body getBody() {
            return body;
        }

        public void setBody(Body body) {
            this.body = body;
        }

        @Override
        public ActionRequestValidationException validate() {
            return body.validate();
        }

        @Override
        public String[] indices() {
            return new String[]{body.getFollowerIndex()};
        }

        @Override
        public IndicesOptions indicesOptions() {
            return IndicesOptions.strictSingleIndexNoExpandForbidClosed();
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            body = new Body(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            body.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return body.toXContent(builder, params);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return Objects.equals(body, request.body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(body);
        }

        public static class Body extends FollowParameters implements ToXContentObject {

            private static final ParseField REMOTE_CLUSTER_FIELD = new ParseField("remote_cluster");
            private static final ParseField LEADER_INDEX_FIELD = new ParseField("leader_index");

            private static final ObjectParser<Body, Void> PARSER = new ObjectParser<>(NAME, Body::new);

            static {
                PARSER.declareString(Body::setRemoteCluster, REMOTE_CLUSTER_FIELD);
                PARSER.declareString(Body::setLeaderIndex, LEADER_INDEX_FIELD);
                PARSER.declareString(Body::setFollowerIndex, FOLLOWER_INDEX_FIELD);
                initParser(PARSER);
            }

            private String remoteCluster;
            private String leaderIndex;
            private String followerIndex;

            public Body() {
            }

            public String getRemoteCluster() {
                return remoteCluster;
            }

            public void setRemoteCluster(String remoteCluster) {
                this.remoteCluster = remoteCluster;
            }

            public String getLeaderIndex() {
                return leaderIndex;
            }

            public void setLeaderIndex(String leaderIndex) {
                this.leaderIndex = leaderIndex;
            }

            public String getFollowerIndex() {
                return followerIndex;
            }

            public void setFollowerIndex(String followerIndex) {
                this.followerIndex = followerIndex;
            }

            @Override
            public ActionRequestValidationException validate() {
                ActionRequestValidationException e = super.validate();
                if (remoteCluster == null) {
                    e = addValidationError(REMOTE_CLUSTER_FIELD.getPreferredName() + " is missing", e);
                }
                if (leaderIndex == null) {
                    e = addValidationError(LEADER_INDEX_FIELD.getPreferredName() + " is missing", e);
                }
                if (followerIndex == null) {
                    e = addValidationError(FOLLOWER_INDEX_FIELD.getPreferredName() + " is missing", e);
                }
                return e;
            }

            public Body(StreamInput in) throws IOException {
                this.remoteCluster = in.readString();
                this.leaderIndex = in.readString();
                this.followerIndex = in.readString();
                fromStreamInput(in);
            }

            @Override
            public void writeTo(StreamOutput out) throws IOException {
                out.writeString(remoteCluster);
                out.writeString(leaderIndex);
                out.writeString(followerIndex);
                super.writeTo(out);
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                builder.startObject();
                {
                    builder.field(REMOTE_CLUSTER_FIELD.getPreferredName(), remoteCluster);
                    builder.field(LEADER_INDEX_FIELD.getPreferredName(), leaderIndex);
                    builder.field(FOLLOWER_INDEX_FIELD.getPreferredName(), followerIndex);
                    toXContentFragment(builder);
                }
                builder.endObject();
                return builder;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                if (!super.equals(o)) return false;
                Body body = (Body) o;
                return Objects.equals(remoteCluster, body.remoteCluster) &&
                    Objects.equals(leaderIndex, body.leaderIndex) &&
                    Objects.equals(followerIndex, body.followerIndex);
            }

            @Override
            public int hashCode() {
                return Objects.hash(super.hashCode(), remoteCluster, leaderIndex, followerIndex);
            }
        }

    }

    public static class Response extends ActionResponse implements ToXContentObject {

        private final boolean followIndexCreated;
        private final boolean followIndexShardsAcked;
        private final boolean indexFollowingStarted;

        public Response(boolean followIndexCreated, boolean followIndexShardsAcked, boolean indexFollowingStarted) {
            this.followIndexCreated = followIndexCreated;
            this.followIndexShardsAcked = followIndexShardsAcked;
            this.indexFollowingStarted = indexFollowingStarted;
        }

        public boolean isFollowIndexCreated() {
            return followIndexCreated;
        }

        public boolean isFollowIndexShardsAcked() {
            return followIndexShardsAcked;
        }

        public boolean isIndexFollowingStarted() {
            return indexFollowingStarted;
        }

        public Response(StreamInput in) throws IOException {
            super(in);
            followIndexCreated = in.readBoolean();
            followIndexShardsAcked = in.readBoolean();
            indexFollowingStarted = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeBoolean(followIndexCreated);
            out.writeBoolean(followIndexShardsAcked);
            out.writeBoolean(indexFollowingStarted);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            {
                builder.field("follow_index_created", followIndexCreated);
                builder.field("follow_index_shards_acked", followIndexShardsAcked);
                builder.field("index_following_started", indexFollowingStarted);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Response response = (Response) o;
            return followIndexCreated == response.followIndexCreated &&
                followIndexShardsAcked == response.followIndexShardsAcked &&
                indexFollowingStarted == response.indexFollowingStarted;
        }

        @Override
        public int hashCode() {
            return Objects.hash(followIndexCreated, followIndexShardsAcked, indexFollowingStarted);
        }
    }

}
