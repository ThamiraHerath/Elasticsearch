/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.synonyms;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Objects;

public class SynonymRule implements Writeable, ToXContentObject {

    public static final ParseField SYNONYM_FIELD = new ParseField("synonym");
    public static final ParseField ID_FIELD = new ParseField("id");
    public static final String SYNONYM_SET_FIELD = "synonym_set";
    private static final ConstructingObjectParser<SynonymRule, Void> PARSER = new ConstructingObjectParser<>("synonym_rule", args -> {
        @SuppressWarnings("unchecked")
        final String id = (String) args[0];
        final String synonym = (String) args[1];
        return new SynonymRule(id, synonym);
    });

    static {
        PARSER.declareStringOrNull(ConstructingObjectParser.optionalConstructorArg(), ID_FIELD);
        PARSER.declareString(ConstructingObjectParser.constructorArg(), SYNONYM_FIELD);
    }

    private final String synonym;
    private final String id;

    public SynonymRule(@Nullable String id, String synonym) {
        this.id = id;
        if (Strings.isEmpty(synonym)) {
            throw new IllegalStateException("synonym cannot be null");
        }
        ;
        this.synonym = synonym;
    }

    public SynonymRule(StreamInput in) throws IOException {
        this.id = in.readOptionalString();
        this.synonym = in.readString();
    }

    public static SynonymRule fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        {
            if (id != null) {
                builder.field(ID_FIELD.getPreferredName(), id);
            }
            builder.field(SYNONYM_FIELD.getPreferredName(), synonym);
        }
        builder.endObject();

        return builder;
    }

    public XContentBuilder toXContentForIndex(XContentBuilder builder, String synonymSetName) throws IOException {
        builder.startObject();
        {
            builder.field(SYNONYM_SET_FIELD, synonymSetName);
            builder.field(SYNONYM_FIELD.getPreferredName(), synonym);
        }

        return builder;
    }

    public String synonym() {
        return synonym;
    }

    public String id() {
        return id;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalString(id);
        out.writeString(synonym);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SynonymRule that = (SynonymRule) o;
        return Objects.equals(synonym, that.synonym) && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(synonym, id);
    }
}
