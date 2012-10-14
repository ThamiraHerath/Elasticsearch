/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.search.group.terms;

import com.google.common.collect.Maps;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilderException;
import org.elasticsearch.search.facet.AbstractFacetBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * Term facets allow to collect frequency of terms within one (or more) field.
 *
 *
 */
public class TermsGroupBuilder extends AbstractFacetBuilder {
    private String fieldName;
    private String[] fieldsNames;
    private int size = 10;
    private Boolean allTerms;
    private Object[] exclude;
    private String regex;
    private int regexFlags = 0;
    private TermsGroup.ComparatorType comparatorType;
    private String script;
    private String lang;
    private Map<String, Object> params;
    String executionHint;

    /**
     * Construct a new term facet with the provided facet name.
     *
     * @param name The facet name.
     */
    public TermsGroupBuilder(String name) {
        super(name);
    }

    /**
     * Should the fact run in global mode (not bounded by the search query) or not. Defaults
     * to <tt>false</tt>.
     */
    public TermsGroupBuilder global(boolean global) {
        super.global(global);
        return this;
    }

    /**
     * Marks the facet to run in a specific scope.
     */
    @Override
    public TermsGroupBuilder scope(String scope) {
        super.scope(scope);
        return this;
    }

    /**
     * An additional facet filter that will further filter the documents the facet will be
     * executed on.
     */
    public TermsGroupBuilder facetFilter(FilterBuilder filter) {
        this.facetFilter = filter;
        return this;
    }

    /**
     * Sets the nested path the facet will execute on. A match (root object) will then cause all the
     * nested objects matching the path to be computed into the facet.
     */
    public TermsGroupBuilder nested(String nested) {
        this.nested = nested;
        return this;
    }

    /**
     * The field the terms will be collected from.
     */
    public TermsGroupBuilder field(String field) {
        this.fieldName = field;
        return this;
    }

    /**
     * The fields the terms will be collected from.
     */
    public TermsGroupBuilder fields(String... fields) {
        this.fieldsNames = fields;
        return this;
    }

    /**
     * Define a script field that will control the terms that will be used (and not filtered, as is the
     * case when the script is provided on top of field / fields).
     */
    public TermsGroupBuilder scriptField(String scriptField) {
        this.script = scriptField;
        return this;
    }

    /**
     * A set of terms that will be excluded.
     */
    public TermsGroupBuilder exclude(Object... exclude) {
        this.exclude = exclude;
        return this;
    }

    /**
     * The number of terms (and frequencies) to return. Defaults to 10.
     */
    public TermsGroupBuilder size(int size) {
        this.size = size;
        return this;
    }

    /**
     * A regular expression to use in order to further filter terms.
     */
    public TermsGroupBuilder regex(String regex) {
        return regex(regex, 0);
    }

    /**
     * A regular expression (with flags) to use in order to further filter terms.
     */
    public TermsGroupBuilder regex(String regex, int flags) {
        this.regex = regex;
        this.regexFlags = flags;
        return this;
    }

    /**
     * The order by which to return the facets by. Defaults to {@link TermsGroup.ComparatorType#COUNT}.
     */
    public TermsGroupBuilder order(TermsGroup.ComparatorType comparatorType) {
        this.comparatorType = comparatorType;
        return this;
    }

    /**
     * A script allowing to either modify or ignore a provided term (can be accessed using <tt>term</tt> var).
     */
    public TermsGroupBuilder script(String script) {
        this.script = script;
        return this;
    }

    /**
     * The language of the script.
     */
    public TermsGroupBuilder lang(String lang) {
        this.lang = lang;
        return this;
    }

    /**
     * An execution hint to how the facet is computed.
     */
    public TermsGroupBuilder executionHint(String executionHint) {
        this.executionHint = executionHint;
        return this;
    }

    /**
     * A parameter that will be passed to the script.
     *
     * @param name  The name of the script parameter.
     * @param value The value of the script parameter.
     */
    public TermsGroupBuilder param(String name, Object value) {
        if (params == null) {
            params = Maps.newHashMap();
        }
        params.put(name, value);
        return this;
    }

    /**
     * Sets all possible terms to be loaded, even ones with 0 count. Note, this *should not* be used
     * with a field that has many possible terms.
     */
    public TermsGroupBuilder allTerms(boolean allTerms) {
        this.allTerms = allTerms;
        return this;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (fieldName == null && fieldsNames == null && script == null) {
            throw new SearchSourceBuilderException("field/fields/script must be set on terms facet for facet [" + name + "]");
        }
        builder.startObject(name);

        builder.startObject(TermsGroup.TYPE);
        if (fieldsNames != null) {
            if (fieldsNames.length == 1) {
                builder.field("field", fieldsNames[0]);
            } else {
                builder.field("fields", fieldsNames);
            }
        } else if (fieldName != null) {
            builder.field("field", fieldName);
        }
        builder.field("size", size);
        if (exclude != null) {
            builder.startArray("exclude");
            for (Object ex : exclude) {
                builder.value(ex);
            }
            builder.endArray();
        }
        if (regex != null) {
            builder.field("regex", regex);
            if (regexFlags != 0) {
                builder.field("regex_flags", Regex.flagsToString(regexFlags));
            }
        }
        if (comparatorType != null) {
            builder.field("order", comparatorType.name().toLowerCase());
        }
        if (allTerms != null) {
            builder.field("all_terms", allTerms);
        }

        if (script != null) {
            builder.field("script", script);
            if (lang != null) {
                builder.field("lang", lang);
            }
            if (this.params != null) {
                builder.field("params", this.params);
            }
        }

        if (executionHint != null) {
            builder.field("execution_hint", executionHint);
        }

        builder.endObject();

        addFilterFacetAndGlobal(builder, params);

        builder.endObject();
        return builder;
    }
}
