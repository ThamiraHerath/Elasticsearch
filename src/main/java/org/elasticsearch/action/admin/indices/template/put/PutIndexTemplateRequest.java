/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.indices.template.put;

import org.elasticsearch.ElasticSearchGenerationException;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.ElasticSearchParseException;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeOperationRequest;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.common.settings.ImmutableSettings.readSettingsFromStream;
import static org.elasticsearch.common.settings.ImmutableSettings.writeSettingsToStream;
import static org.elasticsearch.common.settings.ImmutableSettings.Builder.EMPTY_SETTINGS;
import static org.elasticsearch.common.unit.TimeValue.readTimeValue;

/**
 * A request to create an index template.
 */
public class PutIndexTemplateRequest extends MasterNodeOperationRequest<PutIndexTemplateRequest> {

    private String name;

    private String cause = "";

    private String template;

    private int order;

    private boolean create;

    private Settings settings = EMPTY_SETTINGS;

    private Map<String, String> mappings = newHashMap();

    private List<AliasMetaData> aliases = newArrayList();
    
    private Map<String, IndexMetaData.Custom> customs = newHashMap();

    private TimeValue timeout = new TimeValue(10, TimeUnit.SECONDS);

    PutIndexTemplateRequest() {
       
    }

    /**
     * Constructs a new put index template request with the provided name.
     */
    public PutIndexTemplateRequest(String name) {
        this.name = name;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (name == null) {
            validationException = addValidationError("name is missing", validationException);
        }
        if (template == null) {
            validationException = addValidationError("template is missing", validationException);
        }
        return validationException;
    }

    /**
     * Sets the name of the index template.
     */
    public PutIndexTemplateRequest name(String name) {
        this.name = name;
        return this;
    }

    /**
     * The name of the index template.
     */
    public String name() {
        return this.name;
    }

    public PutIndexTemplateRequest template(String template) {
        this.template = template;
        return this;
    }

    public String template() {
        return this.template;
    }

    public PutIndexTemplateRequest order(int order) {
        this.order = order;
        return this;
    }

    public int order() {
        return this.order;
    }

    /**
     * Set to <tt>true</tt> to force only creation, not an update of an index template. If it already
     * exists, it will fail with an {@link org.elasticsearch.indices.IndexTemplateAlreadyExistsException}.
     */
    public PutIndexTemplateRequest create(boolean create) {
        this.create = create;
        return this;
    }

    public boolean create() {
        return create;
    }

    /**
     * The settings to create the index template with.
     */
    public PutIndexTemplateRequest settings(Settings settings) {
        this.settings = settings;
        return this;
    }

    /**
     * The settings to create the index template with.
     */
    public PutIndexTemplateRequest settings(Settings.Builder settings) {
        this.settings = settings.build();
        return this;
    }

    /**
     * The settings to create the index template with (either json/yaml/properties format).
     */
    public PutIndexTemplateRequest settings(String source) {
        this.settings = ImmutableSettings.settingsBuilder().loadFromSource(source).build();
        return this;
    }

    /**
     * The settings to crete the index template with (either json/yaml/properties format).
     */
    public PutIndexTemplateRequest settings(Map<String, Object> source) {
        try {
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
            builder.map(source);
            settings(builder.string());
        } catch (IOException e) {
            throw new ElasticSearchGenerationException("Failed to generate [" + source + "]", e);
        }
        return this;
    }

    Settings settings() {
        return this.settings;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     */
    public PutIndexTemplateRequest mapping(String type, String source) {
        mappings.put(type, source);
        return this;
    }
    
    /**
     * The cause for this index template creation.
     */
    public PutIndexTemplateRequest cause(String cause) {
        this.cause = cause;
        return this;
    }

    public String cause() {
        return this.cause;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     */
    public PutIndexTemplateRequest mapping(String type, XContentBuilder source) {
        try {
            mappings.put(type, source.string());
        } catch (IOException e) {
            throw new ElasticSearchIllegalArgumentException("Failed to build json for mapping request", e);
        }
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     */
    public PutIndexTemplateRequest mapping(String type, Map<String, Object> source) {
        // wrap it in a type map if its not
        if (source.size() != 1 || !source.containsKey(type)) {
            source = MapBuilder.<String, Object>newMapBuilder().put(type, source).map();
        }
        try {
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
            builder.map(source);
            return mapping(type, builder.string());
        } catch (IOException e) {
            throw new ElasticSearchGenerationException("Failed to generate [" + source + "]", e);
        }
    }

    Map<String, String> mappings() {
        return this.mappings;
    }

    /**
     * The template source definition.
     */
    public PutIndexTemplateRequest source(XContentBuilder templateBuilder) {
        try {
            return source(templateBuilder.bytes());
        } catch (Exception e) {
            throw new ElasticSearchIllegalArgumentException("Failed to build json for template request", e);
        }
    }

    /**
     * The template source definition.
     */
    public PutIndexTemplateRequest source(Map templateSource) {
        Map<String, Object> source = templateSource;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name.equals("template")) {
                template(entry.getValue().toString());
            } else if (name.equals("order")) {
                order(XContentMapValues.nodeIntegerValue(entry.getValue(), order()));
            } else if (name.equals("settings")) {
                if (!(entry.getValue() instanceof Map)) {
                    throw new ElasticSearchIllegalArgumentException("Malformed settings section, should include an inner object");
                }
                settings((Map<String, Object>) entry.getValue());
            } else if (name.equals("mappings")) {
                Map<String, Object> mappings = (Map<String, Object>) entry.getValue();
                for (Map.Entry<String, Object> entry1 : mappings.entrySet()) {
                    if (!(entry1.getValue() instanceof Map)) {
                        throw new ElasticSearchIllegalArgumentException("Malformed mappings section for type [" + entry1.getKey() + "], should include an inner object describing the mapping");
                    }
                    mapping(entry1.getKey(), (Map<String, Object>) entry1.getValue());
                }
            } else if (name.equals("aliases")) {
                Map<String, Object> aliases = (Map<String, Object>) entry.getValue();
                for (Map.Entry<String, Object> entry1 : aliases.entrySet()) {
                    if (!(entry1.getValue() instanceof Map)) {
                        throw new ElasticSearchIllegalArgumentException("Malformed alias [" + entry1.getKey() + "], should include an inner object describing the alias");
                    }
                    try {
                        // FIXME: jbrook - This is a mess. Find a better way to build the alias metadata
                        Map<String, Object> aliasMap = (Map<String, Object>)entry1.getValue();
                        HashMap<String, Object> aliasWrapperMap = new HashMap<String, Object>();
                        aliasWrapperMap.put(entry1.getKey(), aliasMap);
                        
                        AliasMetaData metadata = AliasMetaData.Builder.fromMap(aliasWrapperMap);
                        
                        // FromXContent does not handle alias names, so rebuild it with a name
                        metadata = AliasMetaData.builder(metadata).alias(entry1.getKey()).build();
                        alias(metadata);
                    } catch (IOException e) {
                        throw new ElasticSearchParseException("failed to parse custom metadata for [" + name + "]");
                    }
                }
            } else {
                // maybe custom?
                IndexMetaData.Custom.Factory factory = IndexMetaData.lookupFactory(name);
                if (factory != null) {
                    try {
                        customs.put(name, factory.fromMap((Map<String, Object>) entry.getValue()));
                    } catch (IOException e) {
                        throw new ElasticSearchParseException("failed to parse custom metadata for [" + name + "]");
                    }
                }
            }
        }
        return this;
    }

    /**
     * The template source definition.
     */
    public PutIndexTemplateRequest source(String templateSource) {
        try {
            return source(XContentFactory.xContent(templateSource).createParser(templateSource).mapOrderedAndClose());
        } catch (Exception e) {
            throw new ElasticSearchIllegalArgumentException("failed to parse template source [" + templateSource + "]", e);
        }
    }

    /**
     * The template source definition.
     */
    public PutIndexTemplateRequest source(byte[] source) {
        return source(source, 0, source.length);
    }

    /**
     * The template source definition.
     */
    public PutIndexTemplateRequest source(byte[] source, int offset, int length) {
        try {
            return source(XContentFactory.xContent(source, offset, length).createParser(source, offset, length).mapOrderedAndClose());
        } catch (IOException e) {
            throw new ElasticSearchIllegalArgumentException("failed to parse template source", e);
        }
    }

    /**
     * The template source definition.
     */
    public PutIndexTemplateRequest source(BytesReference source) {
        try {
            return source(XContentFactory.xContent(source).createParser(source).mapOrderedAndClose());
        } catch (IOException e) {
            throw new ElasticSearchIllegalArgumentException("failed to parse template source", e);
        }
    }

    public PutIndexTemplateRequest custom(IndexMetaData.Custom custom) {
        customs.put(custom.type(), custom);
        return this;
    }

    Map<String, IndexMetaData.Custom> customs() {
        return this.customs;
    }
       
    List<AliasMetaData> aliases() {
        return this.aliases;
    }
    
    /**
     * Adds an alias that will be added when the index gets created.
     *
     * @param alias   The metadata for the new alias
     * @return  the index template creation request
     */
    public PutIndexTemplateRequest alias(AliasMetaData alias) {
        aliases.add(alias);
        return this;
    }

    /**
     * Timeout to wait till the put mapping gets acknowledged of all current cluster nodes. Defaults to
     * <tt>10s</tt>.
     */
    TimeValue timeout() {
        return timeout;
    }

    /**
     * Timeout to wait till the put mapping gets acknowledged of all current cluster nodes. Defaults to
     * <tt>10s</tt>.
     */
    public PutIndexTemplateRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Timeout to wait till the put mapping gets acknowledged of all current cluster nodes. Defaults to
     * <tt>10s</tt>.
     */
    public PutIndexTemplateRequest timeout(String timeout) {
        return timeout(TimeValue.parseTimeValue(timeout, null));
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);

        cause = in.readString();
        name = in.readString();
        template = in.readString();
        order = in.readInt();
        create = in.readBoolean();
        settings = readSettingsFromStream(in);
        timeout = readTimeValue(in);
        int size = in.readVInt();
        for (int i = 0; i < size; i++) {
            mappings.put(in.readString(), in.readString());
        }        
        // FIXME: jbrook - Version check required
        int aliasesSize = in.readVInt();
        for (int i = 0; i < aliasesSize; i++) {
            AliasMetaData aliasMd = AliasMetaData.Builder.readFrom(in);
            aliases.add(aliasMd);
        }
        int customSize = in.readVInt();
        for (int i = 0; i < customSize; i++) {
            String type = in.readString();
            IndexMetaData.Custom customIndexMetaData = IndexMetaData.lookupFactorySafe(type).readFrom(in);
            customs.put(type, customIndexMetaData);
        }
    }
    
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(cause);
        out.writeString(name);
        out.writeString(template);
        out.writeInt(order);
        out.writeBoolean(create);
        writeSettingsToStream(settings, out);
        timeout.writeTo(out);
        out.writeVInt(mappings.size());
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            out.writeString(entry.getKey());
            out.writeString(entry.getValue());
        }
        // FIXME: jbrook - Version check required
        out.writeVInt(aliases().size());
        for (AliasMetaData aliasMd : aliases()) {
            AliasMetaData.Builder.writeTo(aliasMd, out);
        }
        out.writeVInt(customs.size());
        for (Map.Entry<String, IndexMetaData.Custom> entry : customs.entrySet()) {
            out.writeString(entry.getKey());
            IndexMetaData.lookupFactorySafe(entry.getKey()).writeTo(entry.getValue(), out);
        }
    }
}
