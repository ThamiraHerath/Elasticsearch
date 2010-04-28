/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.mapper.json;

import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.elasticsearch.ElasticSearchIllegalStateException;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.util.Strings;
import org.elasticsearch.util.concurrent.ThreadSafe;
import org.elasticsearch.util.gcommon.collect.ImmutableMap;
import org.elasticsearch.util.joda.FormatDateTimeFormatter;
import org.elasticsearch.util.json.JsonBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.mapper.json.JsonMapperBuilders.*;
import static org.elasticsearch.index.mapper.json.JsonTypeParsers.*;
import static org.elasticsearch.util.MapBuilder.*;
import static org.elasticsearch.util.gcommon.collect.ImmutableMap.*;
import static org.elasticsearch.util.gcommon.collect.Lists.*;
import static org.elasticsearch.util.json.JacksonNodes.*;

/**
 * @author kimchy (shay.banon)
 */
@ThreadSafe
public class JsonObjectMapper implements JsonMapper, JsonIncludeInAllMapper {

    public static final String JSON_TYPE = "object";

    public static class Defaults {
        public static final boolean ENABLED = true;
        public static final boolean DYNAMIC = true;
        public static final JsonPath.Type PATH_TYPE = JsonPath.Type.FULL;
        public static final FormatDateTimeFormatter[] DATE_TIME_FORMATTERS = new FormatDateTimeFormatter[]{JsonDateFieldMapper.Defaults.DATE_TIME_FORMATTER};
    }

    public static class Builder extends JsonMapper.Builder<Builder, JsonObjectMapper> {

        private boolean enabled = Defaults.ENABLED;

        private boolean dynamic = Defaults.DYNAMIC;

        private JsonPath.Type pathType = Defaults.PATH_TYPE;

        private List<FormatDateTimeFormatter> dateTimeFormatters = newArrayList();

        private Boolean includeInAll;

        private final List<JsonMapper.Builder> mappersBuilders = newArrayList();

        public Builder(String name) {
            super(name);
            this.builder = this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder dynamic(boolean dynamic) {
            this.dynamic = dynamic;
            return this;
        }

        public Builder pathType(JsonPath.Type pathType) {
            this.pathType = pathType;
            return this;
        }

        public Builder noDateTimeFormatter() {
            this.dateTimeFormatters = null;
            return this;
        }

        public Builder includeInAll(boolean includeInAll) {
            this.includeInAll = includeInAll;
            return this;
        }

        public Builder dateTimeFormatter(Iterable<FormatDateTimeFormatter> dateTimeFormatters) {
            for (FormatDateTimeFormatter dateTimeFormatter : dateTimeFormatters) {
                this.dateTimeFormatters.add(dateTimeFormatter);
            }
            return this;
        }

        public Builder dateTimeFormatter(FormatDateTimeFormatter[] dateTimeFormatters) {
            this.dateTimeFormatters.addAll(newArrayList(dateTimeFormatters));
            return this;
        }

        public Builder dateTimeFormatter(FormatDateTimeFormatter dateTimeFormatter) {
            this.dateTimeFormatters.add(dateTimeFormatter);
            return this;
        }

        public Builder add(JsonMapper.Builder builder) {
            mappersBuilders.add(builder);
            return this;
        }

        @Override public JsonObjectMapper build(BuilderContext context) {
            if (dateTimeFormatters == null) {
                dateTimeFormatters = newArrayList();
            } else if (dateTimeFormatters.isEmpty()) {
                // add the default one
                dateTimeFormatters.addAll(newArrayList(Defaults.DATE_TIME_FORMATTERS));
            }
            JsonPath.Type origPathType = context.path().pathType();
            context.path().pathType(pathType);
            context.path().add(name);

            Map<String, JsonMapper> mappers = new HashMap<String, JsonMapper>();
            for (JsonMapper.Builder builder : mappersBuilders) {
                JsonMapper mapper = builder.build(context);
                mappers.put(mapper.name(), mapper);
            }
            JsonObjectMapper objectMapper = new JsonObjectMapper(name, enabled, dynamic, pathType,
                    dateTimeFormatters.toArray(new FormatDateTimeFormatter[dateTimeFormatters.size()]),
                    mappers);

            context.path().pathType(origPathType);
            context.path().remove();

            objectMapper.includeInAll(includeInAll);

            return objectMapper;
        }
    }

    public static class TypeParser implements JsonTypeParser {
        @Override public JsonMapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Map<String, Object> objectNode = node;
            JsonObjectMapper.Builder builder = object(name);

            for (Map.Entry<String, Object> entry : objectNode.entrySet()) {
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();

                if (fieldName.equals("dynamic")) {
                    builder.dynamic(nodeBooleanValue(fieldNode));
                } else if (fieldName.equals("type")) {
                    String type = fieldNode.toString();
                    if (!type.equals("object")) {
                        throw new MapperParsingException("Trying to parse an object but has a different type [" + type + "] for [" + name + "]");
                    }
                } else if (fieldName.equals("date_formats")) {
                    List<FormatDateTimeFormatter> dateTimeFormatters = newArrayList();
                    if (fieldNode instanceof List) {
                        for (Object node1 : (List) fieldNode) {
                            dateTimeFormatters.add(parseDateTimeFormatter(fieldName, node1));
                        }
                    } else if ("none".equals(fieldNode.toString())) {
                        dateTimeFormatters = null;
                    } else {
                        dateTimeFormatters.add(parseDateTimeFormatter(fieldName, fieldNode));
                    }
                    if (dateTimeFormatters == null) {
                        builder.noDateTimeFormatter();
                    } else {
                        builder.dateTimeFormatter(dateTimeFormatters);
                    }
                } else if (fieldName.equals("enabled")) {
                    builder.enabled(nodeBooleanValue(fieldNode));
                } else if (fieldName.equals("path")) {
                    builder.pathType(parsePathType(name, fieldNode.toString()));
                } else if (fieldName.equals("properties")) {
                    parseProperties(builder, (Map<String, Object>) fieldNode, parserContext);
                } else if (fieldName.equals("include_in_all")) {
                    builder.includeInAll(nodeBooleanValue(fieldNode));
                }
            }
            return builder;
        }

        private void parseProperties(JsonObjectMapper.Builder objBuilder, Map<String, Object> propsNode, JsonTypeParser.ParserContext parserContext) {
            for (Map.Entry<String, Object> entry : propsNode.entrySet()) {
                String propName = entry.getKey();
                Map<String, Object> propNode = (Map<String, Object>) entry.getValue();

                String type;
                Object typeNode = propNode.get("type");
                if (typeNode != null) {
                    type = typeNode.toString();
                } else {
                    // lets see if we can derive this...
                    if (propNode.get("properties") != null) {
                        type = JsonObjectMapper.JSON_TYPE;
                    } else if (propNode.get("fields") != null) {
                        type = JsonMultiFieldMapper.JSON_TYPE;
                    } else {
                        throw new MapperParsingException("No type specified for property [" + propName + "]");
                    }
                }

                JsonTypeParser typeParser = parserContext.typeParser(type);
                if (typeParser == null) {
                    throw new MapperParsingException("No handler for type [" + type + "] declared on field [" + propName + "]");
                }
                objBuilder.add(typeParser.parse(propName, propNode, parserContext));
            }
        }
    }

    private final String name;

    private final boolean enabled;

    private final boolean dynamic;

    private final JsonPath.Type pathType;

    private final FormatDateTimeFormatter[] dateTimeFormatters;

    private Boolean includeInAll;

    private volatile ImmutableMap<String, JsonMapper> mappers = ImmutableMap.of();

    private final Object mutex = new Object();

    protected JsonObjectMapper(String name) {
        this(name, Defaults.ENABLED, Defaults.DYNAMIC, Defaults.PATH_TYPE);
    }

    protected JsonObjectMapper(String name, boolean enabled, boolean dynamic, JsonPath.Type pathType) {
        this(name, enabled, dynamic, pathType, Defaults.DATE_TIME_FORMATTERS);
    }

    protected JsonObjectMapper(String name, boolean enabled, boolean dynamic, JsonPath.Type pathType,
                               FormatDateTimeFormatter[] dateTimeFormatters) {
        this(name, enabled, dynamic, pathType, dateTimeFormatters, null);
    }

    JsonObjectMapper(String name, boolean enabled, boolean dynamic, JsonPath.Type pathType,
                     FormatDateTimeFormatter[] dateTimeFormatters, Map<String, JsonMapper> mappers) {
        this.name = name;
        this.enabled = enabled;
        this.dynamic = dynamic;
        this.pathType = pathType;
        this.dateTimeFormatters = dateTimeFormatters;
        if (mappers != null) {
            this.mappers = copyOf(mappers);
        }
    }

    @Override public String name() {
        return this.name;
    }

    @Override public void includeInAll(Boolean includeInAll) {
        if (includeInAll == null) {
            return;
        }
        this.includeInAll = includeInAll;
        // when called from outside, apply this on all the inner mappers
        for (JsonMapper mapper : mappers.values()) {
            if (mapper instanceof JsonIncludeInAllMapper) {
                ((JsonIncludeInAllMapper) mapper).includeInAll(includeInAll);
            }
        }
    }

    public JsonObjectMapper putMapper(JsonMapper mapper) {
        if (mapper instanceof JsonIncludeInAllMapper) {
            ((JsonIncludeInAllMapper) mapper).includeInAll(includeInAll);
        }
        synchronized (mutex) {
            mappers = newMapBuilder(mappers).put(mapper.name(), mapper).immutableMap();
        }
        return this;
    }

    @Override public void traverse(FieldMapperListener fieldMapperListener) {
        for (JsonMapper mapper : mappers.values()) {
            mapper.traverse(fieldMapperListener);
        }
    }

    public void parse(JsonParseContext jsonContext) throws IOException {
        if (!enabled) {
            jsonContext.jp().skipChildren();
            return;
        }
        JsonParser jp = jsonContext.jp();

        JsonPath.Type origPathType = jsonContext.path().pathType();
        jsonContext.path().pathType(pathType);

        String currentFieldName = jp.getCurrentName();
        JsonToken token;
        while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
            if (token == JsonToken.START_OBJECT) {
                serializeObject(jsonContext, currentFieldName);
            } else if (token == JsonToken.START_ARRAY) {
                serializeArray(jsonContext, currentFieldName);
            } else if (token == JsonToken.FIELD_NAME) {
                currentFieldName = jp.getCurrentName();
            } else if (token == JsonToken.VALUE_NULL) {
                serializeNullValue(jsonContext, currentFieldName);
            } else {
                serializeValue(jsonContext, currentFieldName, token);
            }
        }
        // restore the enable path flag
        jsonContext.path().pathType(origPathType);
    }

    private void serializeNullValue(JsonParseContext jsonContext, String lastFieldName) throws IOException {
        // we can only handle null values if we have mappings for them
        JsonMapper mapper = mappers.get(lastFieldName);
        if (mapper != null) {
            mapper.parse(jsonContext);
        }
    }

    private void serializeObject(JsonParseContext jsonContext, String currentFieldName) throws IOException {
        jsonContext.path().add(currentFieldName);

        JsonMapper objectMapper = mappers.get(currentFieldName);
        if (objectMapper != null) {
            objectMapper.parse(jsonContext);
        } else {
            if (dynamic) {
                // we sync here just so we won't add it twice. Its not the end of the world
                // to sync here since next operations will get it before
                synchronized (mutex) {
                    objectMapper = mappers.get(currentFieldName);
                    if (objectMapper != null) {
                        objectMapper.parse(jsonContext);
                    }

                    BuilderContext builderContext = new BuilderContext(jsonContext.path());
                    objectMapper = JsonMapperBuilders.object(currentFieldName).enabled(true)
                            .dynamic(dynamic).pathType(pathType).dateTimeFormatter(dateTimeFormatters).build(builderContext);
                    putMapper(objectMapper);
                    objectMapper.parse(jsonContext);
                    jsonContext.addedMapper();
                }
            } else {
                // not dynamic, read everything up to end object
                jsonContext.jp().skipChildren();
            }
        }

        jsonContext.path().remove();
    }

    private void serializeArray(JsonParseContext jsonContext, String lastFieldName) throws IOException {
        JsonParser jp = jsonContext.jp();
        JsonToken token;
        while ((token = jp.nextToken()) != JsonToken.END_ARRAY) {
            if (token == JsonToken.START_OBJECT) {
                serializeObject(jsonContext, lastFieldName);
            } else if (token == JsonToken.START_ARRAY) {
                serializeArray(jsonContext, lastFieldName);
            } else if (token == JsonToken.FIELD_NAME) {
                lastFieldName = jp.getCurrentName();
            } else if (token == JsonToken.VALUE_NULL) {
                serializeNullValue(jsonContext, lastFieldName);
            } else {
                serializeValue(jsonContext, lastFieldName, token);
            }
        }
    }

    private void serializeValue(JsonParseContext jsonContext, String currentFieldName, JsonToken token) throws IOException {
        JsonMapper mapper = mappers.get(currentFieldName);
        if (mapper != null) {
            mapper.parse(jsonContext);
            return;
        }
        if (!dynamic) {
            return;
        }
        // we sync here since we don't want to add this field twice to the document mapper
        // its not the end of the world, since we add it to the mappers once we create it
        // so next time we won't even get here for this field
        synchronized (mutex) {
            mapper = mappers.get(currentFieldName);
            if (mapper != null) {
                mapper.parse(jsonContext);
                return;
            }

            BuilderContext builderContext = new BuilderContext(jsonContext.path());
            if (token == JsonToken.VALUE_STRING) {
                String text = jsonContext.jp().getText();
                // check if it fits one of the date formats
                boolean isDate = false;
                // a safe check since "1" gets parsed as well
                if (text.contains(":") || text.contains("-")) {
                    for (FormatDateTimeFormatter dateTimeFormatter : dateTimeFormatters) {
                        try {
                            dateTimeFormatter.parser().parseMillis(text);
                            mapper = dateField(currentFieldName).dateTimeFormatter(dateTimeFormatter).build(builderContext);
                            isDate = true;
                            break;
                        } catch (Exception e) {
                            // failure to parse this, continue
                        }
                    }
                }
                if (!isDate) {
                    mapper = stringField(currentFieldName).build(builderContext);
                }
            } else if (token == JsonToken.VALUE_NUMBER_INT) {
                mapper = longField(currentFieldName).build(builderContext);
            } else if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                mapper = doubleField(currentFieldName).build(builderContext);
            } else if (token == JsonToken.VALUE_TRUE) {
                mapper = booleanField(currentFieldName).build(builderContext);
            } else if (token == JsonToken.VALUE_FALSE) {
                mapper = booleanField(currentFieldName).build(builderContext);
            } else {
                // TODO how do we identify dynamically that its a binary value?
                throw new ElasticSearchIllegalStateException("Can't handle serializing a dynamic type with json token [" + token + "] and field name [" + currentFieldName + "]");
            }
            putMapper(mapper);
            jsonContext.docMapper().addFieldMapper((FieldMapper) mapper);

            mapper.parse(jsonContext);
            jsonContext.addedMapper();
        }
    }

    @Override public void merge(JsonMapper mergeWith, JsonMergeContext mergeContext) throws MergeMappingException {
        if (!(mergeWith instanceof JsonObjectMapper)) {
            mergeContext.addConflict("Can't merge a non object mapping [" + mergeWith.name() + "] with an object mapping [" + name() + "]");
            return;
        }
        JsonObjectMapper mergeWithObject = (JsonObjectMapper) mergeWith;
        synchronized (mutex) {
            for (JsonMapper mergeWithMapper : mergeWithObject.mappers.values()) {
                JsonMapper mergeIntoMapper = mappers.get(mergeWithMapper.name());
                if (mergeIntoMapper == null) {
                    // no mapping, simply add it if not simulating
                    if (!mergeContext.mergeFlags().simulate()) {
                        putMapper(mergeWithMapper);
                        if (mergeWithMapper instanceof JsonFieldMapper) {
                            mergeContext.docMapper().addFieldMapper((FieldMapper) mergeWithMapper);
                        }
                    }
                } else {
                    if ((mergeWithMapper instanceof JsonMultiFieldMapper) && !(mergeIntoMapper instanceof JsonMultiFieldMapper)) {
                        JsonMultiFieldMapper mergeWithMultiField = (JsonMultiFieldMapper) mergeWithMapper;
                        mergeWithMultiField.merge(mergeIntoMapper, mergeContext);
                        if (!mergeContext.mergeFlags().simulate()) {
                            putMapper(mergeWithMultiField);
                            // now, raise events for all mappers
                            for (JsonMapper mapper : mergeWithMultiField.mappers().values()) {
                                if (mapper instanceof JsonFieldMapper) {
                                    mergeContext.docMapper().addFieldMapper((FieldMapper) mapper);
                                }
                            }
                        }
                    } else {
                        mergeIntoMapper.merge(mergeWithMapper, mergeContext);
                    }
                }
            }
        }
    }

    @Override public void toJson(JsonBuilder builder, Params params) throws IOException {
        toJson(builder, params, JsonMapper.EMPTY_ARRAY);
    }

    public void toJson(JsonBuilder builder, Params params, JsonMapper... additionalMappers) throws IOException {
        builder.startObject(name);
        builder.field("type", JSON_TYPE);
        builder.field("dynamic", dynamic);
        builder.field("enabled", enabled);
        builder.field("path", pathType.name().toLowerCase());
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        }

        if (dateTimeFormatters.length > 0) {
            builder.startArray("date_formats");
            for (FormatDateTimeFormatter dateTimeFormatter : dateTimeFormatters) {
                builder.value(dateTimeFormatter.format());
            }
            builder.endArray();
        }

        // check internal mappers first (this is only relevant for root object)
        for (JsonMapper mapper : mappers.values()) {
            if (mapper instanceof InternalMapper) {
                mapper.toJson(builder, params);
            }
        }
        if (additionalMappers != null) {
            for (JsonMapper mapper : additionalMappers) {
                mapper.toJson(builder, params);
            }
        }

        if (!mappers.isEmpty()) {
            builder.startObject("properties");
            for (JsonMapper mapper : mappers.values()) {
                if (!(mapper instanceof InternalMapper)) {
                    mapper.toJson(builder, params);
                }
            }
            builder.endObject();
        }
        builder.endObject();
    }
}
