/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.client.watcher;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.xcontent.XContentParser;
import org.joda.time.DateTime;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;
import static org.elasticsearch.client.watcher.WatcherDateTimeUtils.parseDate;
import static org.joda.time.DateTimeZone.UTC;

public class WatchStatus {

    private State state;

    @Nullable private ExecutionState executionState;
    @Nullable private DateTime lastChecked;
    @Nullable private DateTime lastMetCondition;
    @Nullable private long version;
    @Nullable private Map<String, String> headers;
    private Map<String, ActionStatus> actions;

    public WatchStatus(DateTime now, Map<String, ActionStatus> actions) {
        this(-1, new State(true, now), null, null, null, actions, Collections.emptyMap());
    }

    private WatchStatus(long version, State state, ExecutionState executionState, DateTime lastChecked, DateTime lastMetCondition,
                        Map<String, ActionStatus> actions, Map<String, String> headers) {
        this.version = version;
        this.lastChecked = lastChecked;
        this.lastMetCondition = lastMetCondition;
        this.actions = actions;
        this.state = state;
        this.executionState = executionState;
        this.headers = headers;
    }

    public State state() {
        return state;
    }

    public boolean checked() {
        return lastChecked != null;
    }

    public DateTime lastChecked() {
        return lastChecked;
    }

    public ActionStatus actionStatus(String actionId) {
        return actions.get(actionId);
    }

    public long version() {
        return version;
    }

    public void version(long version) {
        this.version = version;
    }

    public void setExecutionState(ExecutionState executionState) {
        this.executionState = executionState;
    }

    public ExecutionState getExecutionState() {
        return executionState;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WatchStatus that = (WatchStatus) o;

        return Objects.equals(lastChecked, that.lastChecked) &&
                Objects.equals(lastMetCondition, that.lastMetCondition) &&
                Objects.equals(version, that.version) &&
                Objects.equals(executionState, that.executionState) &&
                Objects.equals(actions, that.actions) &&
                Objects.equals(headers, that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lastChecked, lastMetCondition, actions, version, executionState, headers);
    }

    public static WatchStatus parse(String watchId, XContentParser parser) throws IOException {
        State state = null;
        ExecutionState executionState = null;
        DateTime lastChecked = null;
        DateTime lastMetCondition = null;
        Map<String, ActionStatus> actions = null;
        long version = -1;
        Map<String, String> headers = Collections.emptyMap();

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (Field.STATE.match(currentFieldName, parser.getDeprecationHandler())) {
                try {
                    state = State.parse(parser);
                } catch (ElasticsearchParseException e) {
                    throw new ElasticsearchParseException("could not parse watch status for [{}]. failed to parse field [{}]",
                            e, watchId, currentFieldName);
                }
            } else if (Field.VERSION.match(currentFieldName, parser.getDeprecationHandler())) {
                if (token.isValue()) {
                    version = parser.longValue();
                } else {
                    throw new ElasticsearchParseException("could not parse watch status for [{}]. expecting field [{}] to hold a long " +
                            "value, found [{}] instead", watchId, currentFieldName, token);
                }
            } else if (Field.LAST_CHECKED.match(currentFieldName, parser.getDeprecationHandler())) {
                if (token.isValue()) {
                    lastChecked = parseDate(currentFieldName, parser, UTC);
                } else {
                    throw new ElasticsearchParseException("could not parse watch status for [{}]. expecting field [{}] to hold a date " +
                            "value, found [{}] instead", watchId, currentFieldName, token);
                }
            } else if (Field.LAST_MET_CONDITION.match(currentFieldName, parser.getDeprecationHandler())) {
                if (token.isValue()) {
                    lastMetCondition = parseDate(currentFieldName, parser, UTC);
                } else {
                    throw new ElasticsearchParseException("could not parse watch status for [{}]. expecting field [{}] to hold a date " +
                            "value, found [{}] instead", watchId, currentFieldName, token);
                }
            } else if (Field.EXECUTION_STATE.match(currentFieldName, parser.getDeprecationHandler())) {
                if (token.isValue()) {
                    executionState = ExecutionState.resolve(parser.text());
                } else {
                    throw new ElasticsearchParseException("could not parse watch status for [{}]. expecting field [{}] to hold a string " +
                            "value, found [{}] instead", watchId, currentFieldName, token);
                }
            } else if (Field.ACTIONS.match(currentFieldName, parser.getDeprecationHandler())) {
                actions = new HashMap<>();
                if (token == XContentParser.Token.START_OBJECT) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            currentFieldName = parser.currentName();
                        } else {
                            ActionStatus actionStatus = ActionStatus.parse(watchId, currentFieldName, parser);
                            actions.put(currentFieldName, actionStatus);
                        }
                    }
                } else {
                    throw new ElasticsearchParseException("could not parse watch status for [{}]. expecting field [{}] to be an object, " +
                            "found [{}] instead", watchId, currentFieldName, token);
                }
            } else if (Field.HEADERS.match(currentFieldName, parser.getDeprecationHandler())) {
                if (token == XContentParser.Token.START_OBJECT) {
                    headers = parser.mapStrings();
                }
            }
        }

        actions = actions == null ? emptyMap() : unmodifiableMap(actions);
        return new WatchStatus(version, state, executionState, lastChecked, lastMetCondition, actions, headers);
    }

    public static class State {

        final boolean active;
        final DateTime timestamp;

        public State(boolean active, DateTime timestamp) {
            this.active = active;
            this.timestamp = timestamp;
        }

        public boolean isActive() {
            return active;
        }

        public DateTime getTimestamp() {
            return timestamp;
        }

        public static State parse(XContentParser parser) throws IOException {
            if (parser.currentToken() != XContentParser.Token.START_OBJECT) {
                throw new ElasticsearchParseException("expected an object but found [{}] instead", parser.currentToken());
            }
            boolean active = true;
            DateTime timestamp = DateTime.now(UTC);
            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (Field.ACTIVE.match(currentFieldName, parser.getDeprecationHandler())) {
                    active = parser.booleanValue();
                } else if (Field.TIMESTAMP.match(currentFieldName, parser.getDeprecationHandler())) {
                    timestamp = parseDate(currentFieldName, parser, UTC);
                }
            }
            return new State(active, timestamp);
        }
    }

    public interface Field {
        ParseField STATE = new ParseField("state");
        ParseField ACTIVE = new ParseField("active");
        ParseField TIMESTAMP = new ParseField("timestamp");
        ParseField LAST_CHECKED = new ParseField("last_checked");
        ParseField LAST_MET_CONDITION = new ParseField("last_met_condition");
        ParseField ACTIONS = new ParseField("actions");
        ParseField VERSION = new ParseField("version");
        ParseField EXECUTION_STATE = new ParseField("execution_state");
        ParseField HEADERS = new ParseField("headers");
    }
}
