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

package org.elasticsearch.client.license;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentParseException;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class PostStartTrialResponse {

    private static final ParseField ACKNOWLEDGED_FIELD = new ParseField("acknowledged");
    private static final ParseField TRIAL_WAS_STARTED_FIELD = new ParseField("trial_was_started");
    private static final ParseField LICENSE_TYPE_FIELD = new ParseField("type");
    private static final ParseField ERROR_MESSAGE_FIELD = new ParseField("error_message");
    private static final ParseField ACKNOWLEDGE_DETAILS_FIELD = new ParseField("acknowledge");
    private static final ParseField ACKNOWLEDGE_HEADER_FIELD = new ParseField("message");

    private static final ConstructingObjectParser<PostStartTrialResponse, Void> PARSER = new ConstructingObjectParser<>(
        "post_start_trial_response",
        true,
        (Object[] arguments, Void aVoid) -> {
            final boolean acknowledged = (boolean) arguments[0];
            final boolean trialWasStarted = (boolean) arguments[1];
            final String licenseType = (String) arguments[2];
            final String errorMessage = (String) arguments[3];

            @SuppressWarnings("unchecked")
            final Tuple<String, Map<String, String[]>> acknowledgeDetails = (Tuple<String, Map<String, String[]>>) arguments[4];
            final String acknowledgeHeader;
            final Map<String, String[]> acknowledgeMessages;

            if (acknowledgeDetails != null) {
                acknowledgeHeader = acknowledgeDetails.v1();
                acknowledgeMessages = acknowledgeDetails.v2();
            } else {
                acknowledgeHeader = null;
                acknowledgeMessages = null;
            }

            return new PostStartTrialResponse(acknowledged, trialWasStarted, licenseType, errorMessage, acknowledgeHeader,
                acknowledgeMessages);
        }
    );

    static {
        PARSER.declareBoolean(constructorArg(), ACKNOWLEDGED_FIELD);
        PARSER.declareBoolean(constructorArg(), TRIAL_WAS_STARTED_FIELD);
        PARSER.declareString(optionalConstructorArg(), LICENSE_TYPE_FIELD);
        PARSER.declareString(optionalConstructorArg(), ERROR_MESSAGE_FIELD);
        // todo consolidate this parsing with the parsing in PutLicenseResponse
        PARSER.declareObject(optionalConstructorArg(), (parser, aVoid) -> {
            Map<String, String[]> acknowledgeMessages = new HashMap<>();
            String message = null;
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else {
                    if (currentFieldName == null) {
                        throw new XContentParseException(parser.getTokenLocation(), "expected message header or acknowledgement");
                    }
                    if (ACKNOWLEDGE_HEADER_FIELD.getPreferredName().equals(currentFieldName)) {
                        if (token != XContentParser.Token.VALUE_STRING) {
                            throw new XContentParseException(parser.getTokenLocation(), "unexpected message header type");
                        }
                        message = parser.text();
                    } else if (token == XContentParser.Token.START_ARRAY){
                        List<String> acknowledgeMessagesList = new ArrayList<>();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (token != XContentParser.Token.VALUE_STRING) {
                                throw new XContentParseException(parser.getTokenLocation(), "unexpected acknowledgement text");
                            }
                            acknowledgeMessagesList.add(parser.text());
                        }
                        acknowledgeMessages.put(currentFieldName, acknowledgeMessagesList.toArray(new String[0]));
                    } else {
                        throw new XContentParseException(parser.getTokenLocation(), "unexpected acknowledgement type");
                    }
                }
            }
            return new Tuple<>(message, acknowledgeMessages);
        }, ACKNOWLEDGE_DETAILS_FIELD);
    }

    public static PostStartTrialResponse fromXContent(XContentParser parser) throws IOException {
        return PARSER.apply(parser, null);
    }

    private final boolean acknowledged;
    private final boolean trialWasStarted;
    private final String licenseType;
    private final String errorMessage;
    private final String acknowledgeHeader;
    private final Map<String, String[]> acknowledgeMessages;

    public PostStartTrialResponse(boolean acknowledged,
                                  boolean trialWasStarted,
                                  String licenseType,
                                  String errorMessage,
                                  String acknowledgeHeader,
                                  Map<String, String[]> acknowledgeMessages) {

        this.acknowledged = acknowledged;
        this.trialWasStarted = trialWasStarted;
        this.licenseType = licenseType;
        this.errorMessage = errorMessage;
        this.acknowledgeHeader = acknowledgeHeader;
        this.acknowledgeMessages = acknowledgeMessages;
    }

    /**
     * Returns true if the request that corresponds to this response acknowledged license changes that would occur as a result of starting
     * a trial license
     */
    public boolean isAcknowledged() {
        return acknowledged;
    }

    /**
     * Returns true if a trial license was started as a result of the request corresponding to this response. Returns false if the cluster
     * did not start a trial, or a trial had already been started before the corresponding request was made
     */
    public boolean isTrialWasStarted() {
        return trialWasStarted;
    }

    /**
     * If a trial license was started as a result of the request corresponding to this response (see {@link #isTrialWasStarted()}) then
     * returns the type of license that was started on the cluster. Returns null otherwise
     */
    public String getLicenseType() {
        return licenseType;
    }

    /**
     * If a trial license was not started as a result of the request corresponding to this response (see {@link #isTrialWasStarted()} then
     * returns a brief message explaining why the trial could not be started. Returns false otherwise
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * If the request corresponding to this response did not acknowledge licensing changes that would result from starting a trial license
     * (see {@link #isAcknowledged()}), returns a message describing how the user must acknowledge licensing changes as a result of
     * such a request. Returns null otherwise
     */
    public String getAcknowledgeHeader() {
        return acknowledgeHeader;
    }

    /**
     * If the request corresponding to this response did not acknowledge licensing changes that would result from starting a trial license
     * (see {@link #isAcknowledged()}, returns a map. The map's keys are names of commercial Elasticsearch features, and their values are
     * messages about how those features will be affected by licensing changes as a result of starting a trial license
     */
    public Map<String, String[]> getAcknowledgeMessages() {
        return acknowledgeMessages;
    }
}
