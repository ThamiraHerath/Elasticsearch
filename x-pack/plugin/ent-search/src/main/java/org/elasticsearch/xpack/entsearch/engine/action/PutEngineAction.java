/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.entsearch.engine.action;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.entsearch.engine.Engine;

import java.io.IOException;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class PutEngineAction extends ActionType<PutEngineAction.Response> {

    public static final PutEngineAction INSTANCE = new PutEngineAction();
    public static final String NAME = "cluster:admin/engine/put";

    public PutEngineAction() {
        super(NAME, PutEngineAction.Response::new);
    }

    public static class Request extends ActionRequest {

        private final Engine engine;
        private XContentType xContentType = XContentType.JSON;

        public Request(StreamInput in) throws IOException {
            super(in);
            String engineId = in.readString();
            final BytesReference bytesReference = in.readBytesReference();
            xContentType = in.readEnum(XContentType.class);

            this.engine = Engine.fromXContentBytes(engineId, bytesReference, xContentType);
        }

        public Request(String engineId, BytesReference content, XContentType contentType) {
            this.engine = Engine.fromXContentBytes(engineId, content, contentType);
            xContentType = contentType;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (engine.indices().length == 0) {
                validationException = addValidationError("indices are missing", validationException);
            }

            return validationException;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            engine.writeTo(out);
            XContentHelper.writeTo(out, xContentType);
        }

        public Engine getEngine() {
            return engine;
        }
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        final DocWriteResponse.Result result;

        public Response(StreamInput in) throws IOException {
            super(in);
            result = DocWriteResponse.Result.readFrom(in);
        }

        public Response(DocWriteResponse.Result result) {
            this.result = result;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            this.result.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("result", this.result.getLowercase());
            builder.endObject();
            return builder;
        }
    }

}
