/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.connector.action;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.application.connector.Connector;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public class GetConnectorAction extends ActionType<GetConnectorAction.Response> {

    public static final GetConnectorAction INSTANCE = new GetConnectorAction();
    public static final String NAME = "cluster:admin/xpack/connector/get";

    private GetConnectorAction() {
        super(NAME);
    }

    public static class Request extends ActionRequest implements ToXContentObject {

        private final String connectorId;

        private static final ParseField CONNECTOR_ID_FIELD = new ParseField("connector_id");

        public Request(String connectorId) {
            this.connectorId = connectorId;
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            this.connectorId = in.readString();
        }

        public String getConnectorId() {
            return connectorId;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (Strings.isNullOrEmpty(connectorId)) {
                validationException = addValidationError("connector_id missing", validationException);
            }

            return validationException;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(connectorId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return Objects.equals(connectorId, request.connectorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connectorId);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            {
                builder.field(CONNECTOR_ID_FIELD.getPreferredName(), connectorId);
            }
            builder.endObject();
            return builder;
        }

        private static final ConstructingObjectParser<Request, Void> PARSER = new ConstructingObjectParser<>(
            "get_connector_request",
            false,
            (p) -> new Request((String) p[0])

        );
        static {
            PARSER.declareString(constructorArg(), CONNECTOR_ID_FIELD);
        }

        public static Request parse(XContentParser parser) {
            return PARSER.apply(parser, null);
        }
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        private final Connector connector;

        public Response(Connector connector) {
            this.connector = connector;
        }

        public Response(StreamInput in) throws IOException {
            super(in);
            this.connector = new Connector(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            connector.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return connector.toXContent(builder, params);
        }

        public static GetConnectorAction.Response fromXContent(XContentParser parser, String docId) throws IOException {
            return new GetConnectorAction.Response(Connector.fromXContent(parser, docId));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Response response = (Response) o;
            return Objects.equals(connector, response.connector);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connector);
        }
    }
}
