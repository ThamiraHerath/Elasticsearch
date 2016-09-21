/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.script.mustache;

import org.elasticsearch.action.admin.cluster.storedscripts.PutStoredScriptRequest;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.AcknowledgedRestListener;
import org.elasticsearch.script.ScriptMetaData.StoredScriptSource;

import java.io.IOException;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestRequest.Method.PUT;

public class RestPutSearchTemplateAction extends BaseRestHandler {
    private static final ParseField parseTemplate = new ParseField("template");

    @Inject
    public RestPutSearchTemplateAction(Settings settings, RestController controller) {
        super(settings);

        controller.registerHandler(POST, "/_search/template/{id}", this);
        controller.registerHandler(PUT, "/_search/template/{id}", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, NodeClient client) {
        StoredScriptSource source = parseStoredScript(request.content());
        PutStoredScriptRequest putRequest = new PutStoredScriptRequest(request.param("id"), source);
        client.admin().cluster().putStoredScript(putRequest, new AcknowledgedRestListener<>(channel));
    }

    private static StoredScriptSource parseStoredScript(BytesReference content) {
        try (XContentParser parser = XContentHelper.createParser(content)) {
            if (parser.nextToken() != Token.START_OBJECT) {
                throw new ParsingException(parser.getTokenLocation(),
                    "unexpected token [" + parser.currentToken() + "], expected start object [{]");
            }

            if (parser.nextToken() == Token.END_OBJECT) {
                throw new ParsingException(parser.getTokenLocation(),
                    "unexpected token [" + parser.currentToken() + "], expected [<template>]");
            }

            if (parser.currentToken() != Token.FIELD_NAME) {
                throw new ParsingException(parser.getTokenLocation(),
                    "unexpected token [" + parser.currentName() + "], expected [template]");
            }

            if (parseTemplate.getPreferredName().equals(parser.currentName())) {
                if (parser.nextToken() == Token.VALUE_STRING) {
                    return new StoredScriptSource(null, "mustache", parser.text());
                } else if (parser.currentToken() == Token.START_OBJECT) {
                    XContentBuilder builder = XContentFactory.contentBuilder(parser.contentType());
                    builder.copyCurrentStructure(parser);

                    return new StoredScriptSource(null, "mustache", builder.string());
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                        "unexpected token [" + parser.currentToken() + "], expected [<template>]");
                }
            } else {
                XContentBuilder builder = XContentFactory.contentBuilder(parser.contentType());
                builder.startObject();
                builder.copyCurrentStructure(parser);
                builder.endObject();

                return new StoredScriptSource(null, "mustache", builder.string());
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }
}
