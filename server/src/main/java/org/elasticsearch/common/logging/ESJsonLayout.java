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

package org.elasticsearch.common.logging;

import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.core.layout.ByteBufferDestination;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.util.KeyValuePair;
import org.elasticsearch.common.Strings;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formats log events as strings in a json format.
 * <p>
 * The class is wrapping the {@link PatternLayout} with a pattern to format into json. This gives more flexibility and control over how the
 * log messages are formatted in {@link org.apache.logging.log4j.core.layout.JsonLayout}
 * There are fields which are always present in the log line:
 *  <ul>
 *  <li>type - the type of logs. These represent appenders and help docker distinguish log streams.</li>
 *  <li>timestamp - ISO8601 with additional timezone ID</li>
 *  <li>level - INFO, WARN etc</li>
 *  <li>component - logger name, most of the times class name</li>
 *  <li>cluster.name - taken from sys:es.logs.cluster_name system property because it is always set</li>
 *  <li>node.name - taken from NodeNamePatternConverter, as it can be set in runtime as hostname when not set in elasticsearch.yml</li>
 *  <li>node_and_cluster_id - in json as node.id and cluster.uuid - taken from NodeAndClusterIdConverter and present
 *  once clusterStateUpdate is first received</li>
 *  <li>message - a json escaped message. Multiline messages will be converted to single line with new line explicitly
 *  replaced to \n</li>
 *  <li>exceptionAsJson - in json as a stacktrace field. Only present when throwable is passed as a parameter when using a logger.
 *  Taken from JsonThrowablePatternConverter</li>
 *  </ul>
 *
 *  It is possible to add more or override them with <code>esmessagefield</code>
 *  <code>appender.index_search_slowlog_rolling.layout.esmessagefields=message,took,took_millis,total_hits,types,stats,search_type,total_shards,source,id</code>
 *  Each of these will be expanded into a json field with a value taken {@link ESLogMessage} field. In the example above
 *  <code>... "message":  %ESMessageField{message}, "took": %ESMessageField{took} ...</code>
 *  the message passed to a logger will be overriden with a value from %ESMessageField{message}
 *
 *  The value taken from %ESMessageField{message} has to be a correct JSON and is populated in subclasses of <code>ESLogMessage</code>
 *
 *  There is also a way to define custom fields which can be hardcoded, looked up (with log4j MDC) or fetched from <code>ESLogMessage</code>
 *  examples:
 *  appender.custom.layout.additional0.type=KeyValuePair
 *  appender.custom.layout.additional0.key = hardcodedField
 *  appender.custom.layout.additional0.value = "HardcodedValue"
 *  appender.custom.layout.additional1.type=KeyValuePair
 *  appender.custom.layout.additional1.key = contextField
 *  appender.custom.layout.additional1.value = "%X{contextField}"
 *  appender.custom.layout.additional2.type=KeyValuePair
 *  appender.custom.layout.additional2.key = messageField
 *  appender.custom.layout.additional3.value = %ESMessageField{someField}
 *
 */
@Plugin(name = "ESJsonLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public class ESJsonLayout extends AbstractStringLayout {

    private final PatternLayout patternLayout;


    protected ESJsonLayout(String typeName, Charset charset, KeyValuePair[] additionalFields, String[] esmessagefields) {
        super(charset);
        this.patternLayout = PatternLayout.newBuilder()
            .withPattern(pattern(typeName, additionalFields, esmessagefields))
            .withAlwaysWriteExceptions(false)
            .build();
    }

    private String pattern(String type, KeyValuePair[] additionalFields, String[] esmessagefields) {
        if (Strings.isEmpty(type)) {
            throw new IllegalArgumentException("layout parameter 'type_name' cannot be empty");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", inQuotes(type));
        map.put("timestamp", inQuotes("%d{yyyy-MM-dd'T'HH:mm:ss,SSSZ}"));
        map.put("level", inQuotes("%p"));
        map.put("component", inQuotes("%c{1.}"));
        map.put("cluster.name", inQuotes("${sys:es.logs.cluster_name}"));
        map.put("node.name", inQuotes("%node_name"));
        map.put("message", inQuotes("%notEmpty{%enc{%marker}{JSON} }%enc{%.-10000m}{JSON}"));

        for (String key : esmessagefields) {
            map.put(key, "%ESMessageField{" + key + "}");
        }
        for (KeyValuePair keyValuePair : additionalFields) {
            map.put(keyValuePair.getKey(), keyValuePair.getValue());
        }


        return createPattern(map);
    }

    private String createPattern(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        String prefix = "";
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append(prefix);
            sb.append(jsonKey(entry.getKey()));
            sb.append(entry.getValue().toString());
            prefix = ", ";
        }
        sb.append(notEmpty(", %node_and_cluster_id "));
        sb.append("%exceptionAsJson ");
        sb.append("}");
        sb.append(System.lineSeparator());

        return sb.toString();
    }

    private String notEmpty(String value) {
        return "%notEmpty{" + value + "}";
    }

    private CharSequence jsonKey(String s) {
        return inQuotes(s) + ": ";
    }


    private String inQuotes(String s) {
        return "\"" + s + "\"";
    }

    private String asJson(String s) {
        return "%enc{" + s + "}{JSON}";
    }

    @PluginFactory
    public static ESJsonLayout createLayout(String type,
                                            Charset charset,
                                            KeyValuePair[] additionalFields,
                                            String[] esmessagefields) {
        return new ESJsonLayout(type, charset, additionalFields, esmessagefields);
    }

    public static class Builder<B extends ESJsonLayout.Builder<B>> extends AbstractStringLayout.Builder<B>
        implements org.apache.logging.log4j.core.util.Builder<ESJsonLayout> {

        @PluginAttribute("type_name")
        String type;

        @PluginAttribute(value = "charset", defaultString = "UTF-8")
        Charset charset;

        @PluginAttribute("esmessagefields")
        private String esmessagefields;

        @PluginElement("AdditionalField")
        private KeyValuePair[] additionalFields;


        public Builder() {
            super();
            setCharset(StandardCharsets.UTF_8);
        }

        @Override
        public ESJsonLayout build() {
            String[] split = Strings.isNullOrEmpty(esmessagefields) ? new String[]{} : esmessagefields.split(",");
            return ESJsonLayout.createLayout(type, charset, additionalFields, split);
        }

        public Charset getCharset() {
            return charset;
        }

        public B setCharset(final Charset type) {
            this.charset = charset;
            return asBuilder();
        }

        public String getType() {
            return type;
        }

        public B setType(final String type) {
            this.type = type;
            return asBuilder();
        }

        public KeyValuePair[] getAdditionalFields() {
            return additionalFields;
        }

        public B setAdditionalFields(KeyValuePair[] additionalFields) {
            this.additionalFields = additionalFields;
            return asBuilder();
        }

        public String getEsmessagefields() {
            return esmessagefields;
        }

        public B setEsmessagefields(String esmessagefields) {
            this.esmessagefields = esmessagefields;
            return asBuilder();
        }
    }

    @PluginBuilderFactory
    public static <B extends ESJsonLayout.Builder<B>> B newBuilder() {
        return new ESJsonLayout.Builder<B>().asBuilder();
    }

    @Override
    public String toSerializable(final LogEvent event) {
        return patternLayout.toSerializable(event);
    }

    @Override
    public Map<String, String> getContentFormat() {
        return patternLayout.getContentFormat();
    }

    @Override
    public void encode(final LogEvent event, final ByteBufferDestination destination) {
        patternLayout.encode(event, destination);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ESJsonLayout{");
        sb.append("patternLayout=").append(patternLayout);
        sb.append('}');
        return sb.toString();
    }
}
