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

package org.elasticsearch.index.query;

import org.elasticsearch.common.Nullable;

/**
 * A static factory for simple "import static" usage.
 */
public abstract class QueryBuilders {

    /**
     * A query that match on all documents.
     */
    public static MatchAllQueryBuilder matchAllQuery() {
        return new MatchAllQueryBuilder();
    }

    /**
     * Creates a text query with type "BOOLEAN" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     * @deprecated use {@link #textQuery(String, Object)} instead
     */
    public static MatchQueryBuilder text(String name, Object text) {
        return textQuery(name, text);
    }

    /**
     * Creates a text query with type "BOOLEAN" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     * @deprecated Use {@link #matchQuery(String, Object)}
     */
    public static MatchQueryBuilder textQuery(String name, Object text) {
        return new MatchQueryBuilder(name, text).type(MatchQueryBuilder.Type.BOOLEAN);
    }

    /**
     * Creates a match query with type "BOOLEAN" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     */
    public static MatchQueryBuilder matchQuery(String name, Object text) {
        return new MatchQueryBuilder(name, text).type(MatchQueryBuilder.Type.BOOLEAN);
    }

    /**
     * Creates a match query with type "BOOLEAN" for the provided field name and text.
     *
     * @param fieldNames The field names.
     * @param text The query text (to be analyzed).
     */
    public static MultiMatchQueryBuilder multiMatchQuery(Object text, String... fieldNames) {
        return new MultiMatchQueryBuilder(text, fieldNames); // BOOLEAN is the default
    }

    /**
     * Creates a text query with type "PHRASE" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     * @deprecated use {@link #textPhraseQuery(String, Object)} instead
     */
    public static MatchQueryBuilder textPhrase(String name, Object text) {
        return textPhraseQuery(name, text);
    }

    /**
     * Creates a text query with type "PHRASE" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     * @deprecated Use {@link #matchPhraseQuery(String, Object)}
     */
    public static MatchQueryBuilder textPhraseQuery(String name, Object text) {
        return new MatchQueryBuilder(name, text).type(MatchQueryBuilder.Type.PHRASE);
    }

    /**
     * Creates a text query with type "PHRASE" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     */
    public static MatchQueryBuilder matchPhraseQuery(String name, Object text) {
        return new MatchQueryBuilder(name, text).type(MatchQueryBuilder.Type.PHRASE);
    }

    /**
     * Creates a text query with type "PHRASE_PREFIX" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     * @deprecated use {@link #textPhrasePrefixQuery(String, Object)} instead
     */
    public static MatchQueryBuilder textPhrasePrefix(String name, Object text) {
        return textPhrasePrefixQuery(name, text);
    }

    /**
     * Creates a text query with type "PHRASE_PREFIX" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     * @deprecated Use {@link #matchPhrasePrefixQuery(String, Object)}
     */
    public static MatchQueryBuilder textPhrasePrefixQuery(String name, Object text) {
        return new MatchQueryBuilder(name, text).type(MatchQueryBuilder.Type.PHRASE_PREFIX);
    }

    /**
     * Creates a match query with type "PHRASE_PREFIX" for the provided field name and text.
     *
     * @param name The field name.
     * @param text The query text (to be analyzed).
     */
    public static MatchQueryBuilder matchPhrasePrefixQuery(String name, Object text) {
        return new MatchQueryBuilder(name, text).type(MatchQueryBuilder.Type.PHRASE_PREFIX);
    }

    /**
     * A query that generates the union of documents produced by its sub-queries, and that scores each document
     * with the maximum score for that document as produced by any sub-query, plus a tie breaking increment for any
     * additional matching sub-queries.
     */
    public static DisMaxQueryBuilder disMaxQuery() {
        return new DisMaxQueryBuilder();
    }

    /**
     * Constructs a query that will match only specific ids within types.
     *
     * @param types The mapping/doc type
     */
    public static IdsQueryBuilder idsQuery(@Nullable String... types) {
        return new IdsQueryBuilder(types);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, String value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, int value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, long value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, float value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, double value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, boolean value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents containing a term.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static TermQueryBuilder termQuery(String name, Object value) {
        return new TermQueryBuilder(name, value);
    }

    /**
     * A Query that matches documents using fuzzy query.
     *
     * @param name  The name of the field
     * @param value The value of the term
     */
    public static FuzzyQueryBuilder fuzzyQuery(String name, String value) {
        return new FuzzyQueryBuilder(name, value);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name The name of the field
     */
    public static FieldQueryBuilder fieldQuery(String name, String query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name  The name of the field
     * @param query The query string
     */
    public static FieldQueryBuilder fieldQuery(String name, int query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name  The name of the field
     * @param query The query string
     */
    public static FieldQueryBuilder fieldQuery(String name, long query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name  The name of the field
     * @param query The query string
     */
    public static FieldQueryBuilder fieldQuery(String name, float query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name  The name of the field
     * @param query The query string
     */
    public static FieldQueryBuilder fieldQuery(String name, double query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name  The name of the field
     * @param query The query string
     */
    public static FieldQueryBuilder fieldQuery(String name, boolean query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A query that executes the query string against a field. It is a simplified
     * version of {@link QueryStringQueryBuilder} that simply runs against
     * a single field.
     *
     * @param name  The name of the field
     * @param query The query string
     */
    public static FieldQueryBuilder fieldQuery(String name, Object query) {
        return new FieldQueryBuilder(name, query);
    }

    /**
     * A Query that matches documents containing terms with a specified prefix.
     *
     * @param name   The name of the field
     * @param prefix The prefix query
     */
    public static PrefixQueryBuilder prefixQuery(String name, String prefix) {
        return new PrefixQueryBuilder(name, prefix);
    }

    /**
     * A Query that matches documents within an range of terms.
     *
     * @param name The field name
     */
    public static RangeQueryBuilder rangeQuery(String name) {
        return new RangeQueryBuilder(name);
    }

    /**
     * Implements the wildcard search query. Supported wildcards are <tt>*</tt>, which
     * matches any character sequence (including the empty one), and <tt>?</tt>,
     * which matches any single character. Note this query can be slow, as it
     * needs to iterate over many terms. In order to prevent extremely slow WildcardQueries,
     * a Wildcard term should not start with one of the wildcards <tt>*</tt> or
     * <tt>?</tt>.
     *
     * @param name  The field name
     * @param query The wildcard query string
     */
    public static WildcardQueryBuilder wildcardQuery(String name, String query) {
        return new WildcardQueryBuilder(name, query);
    }

    /**
     * A query that parses a query string and runs it. There are two modes that this operates. The first,
     * when no field is added (using {@link QueryStringQueryBuilder#field(String)}, will run the query once and non prefixed fields
     * will use the {@link QueryStringQueryBuilder#defaultField(String)} set. The second, when one or more fields are added
     * (using {@link QueryStringQueryBuilder#field(String)}), will run the parsed query against the provided fields, and combine
     * them either using DisMax or a plain boolean query (see {@link QueryStringQueryBuilder#useDisMax(boolean)}).
     *
     * @param queryString The query string to run
     */
    public static QueryStringQueryBuilder queryString(String queryString) {
        return new QueryStringQueryBuilder(queryString);
    }

    /**
     * The BoostingQuery class can be used to effectively demote results that match a given query.
     * Unlike the "NOT" clause, this still selects documents that contain undesirable terms,
     * but reduces their overall score:
     */
    public static BoostingQueryBuilder boostingQuery() {
        return new BoostingQueryBuilder();
    }

    /**
     * A Query that matches documents matching boolean combinations of other queries.
     */
    public static BoolQueryBuilder boolQuery() {
        return new BoolQueryBuilder();
    }

    public static SpanTermQueryBuilder spanTermQuery(String name, String value) {
        return new SpanTermQueryBuilder(name, value);
    }

    public static SpanTermQueryBuilder spanTermQuery(String name, int value) {
        return new SpanTermQueryBuilder(name, value);
    }

    public static SpanTermQueryBuilder spanTermQuery(String name, long value) {
        return new SpanTermQueryBuilder(name, value);
    }

    public static SpanTermQueryBuilder spanTermQuery(String name, float value) {
        return new SpanTermQueryBuilder(name, value);
    }

    public static SpanTermQueryBuilder spanTermQuery(String name, double value) {
        return new SpanTermQueryBuilder(name, value);
    }

    public static SpanFirstQueryBuilder spanFirstQuery(SpanQueryBuilder match, int end) {
        return new SpanFirstQueryBuilder(match, end);
    }

    public static SpanNearQueryBuilder spanNearQuery() {
        return new SpanNearQueryBuilder();
    }

    public static SpanNotQueryBuilder spanNotQuery() {
        return new SpanNotQueryBuilder();
    }

    public static SpanOrQueryBuilder spanOrQuery() {
        return new SpanOrQueryBuilder();
    }

    public static FieldMaskingSpanQueryBuilder fieldMaskingSpanQuery(SpanQueryBuilder query, String field) {
        return new FieldMaskingSpanQueryBuilder(query, field);
    }

    /**
     * A query that applies a filter to the results of another query.
     *
     * @param queryBuilder  The query to apply the filter to
     * @param filterBuilder The filter to apply on the query
     * @deprecated Use filteredQuery instead (rename)
     */
    public static FilteredQueryBuilder filtered(QueryBuilder queryBuilder, @Nullable FilterBuilder filterBuilder) {
        return new FilteredQueryBuilder(queryBuilder, filterBuilder);
    }

    /**
     * A query that applies a filter to the results of another query.
     *
     * @param queryBuilder  The query to apply the filter to
     * @param filterBuilder The filter to apply on the query
     */
    public static FilteredQueryBuilder filteredQuery(QueryBuilder queryBuilder, @Nullable FilterBuilder filterBuilder) {
        return new FilteredQueryBuilder(queryBuilder, filterBuilder);
    }

    /**
     * A query that wraps a filter and simply returns a constant score equal to the
     * query boost for every document in the filter.
     *
     * @param filterBuilder The filter to wrap in a constant score query
     */
    public static ConstantScoreQueryBuilder constantScoreQuery(FilterBuilder filterBuilder) {
        return new ConstantScoreQueryBuilder(filterBuilder);
    }

    /**
     * A query that simply applies the boost fact to the wrapped query (multiplies it).
     *
     * @param queryBuilder The query to apply the boost factor to.
     */
    public static CustomBoostFactorQueryBuilder customBoostFactorQuery(QueryBuilder queryBuilder) {
        return new CustomBoostFactorQueryBuilder(queryBuilder);
    }

    /**
     * A query that allows to define a custom scoring script.
     *
     * @param queryBuilder The query to custom score
     */
    public static CustomScoreQueryBuilder customScoreQuery(QueryBuilder queryBuilder) {
        return new CustomScoreQueryBuilder(queryBuilder);
    }

    public static CustomFiltersScoreQueryBuilder customFiltersScoreQuery(QueryBuilder queryBuilder) {
        return new CustomFiltersScoreQueryBuilder(queryBuilder);
    }

    /**
     * A more like this query that finds documents that are "like" the provided {@link MoreLikeThisQueryBuilder#likeText(String)}
     * which is checked against the fields the query is constructed with.
     *
     * @param fields The fields to run the query against
     */
    public static MoreLikeThisQueryBuilder moreLikeThisQuery(String... fields) {
        return new MoreLikeThisQueryBuilder(fields);
    }

    /**
     * A more like this query that finds documents that are "like" the provided {@link MoreLikeThisQueryBuilder#likeText(String)}
     * which is checked against the "_all" field.
     */
    public static MoreLikeThisQueryBuilder moreLikeThisQuery() {
        return new MoreLikeThisQueryBuilder();
    }

    /**
     * A fuzzy like this query that finds documents that are "like" the provided {@link FuzzyLikeThisQueryBuilder#likeText(String)}
     * which is checked against the fields the query is constructed with.
     *
     * @param fields The fields to run the query against
     */
    public static FuzzyLikeThisQueryBuilder fuzzyLikeThisQuery(String... fields) {
        return new FuzzyLikeThisQueryBuilder(fields);
    }

    /**
     * A fuzzy like this query that finds documents that are "like" the provided {@link FuzzyLikeThisQueryBuilder#likeText(String)}
     * which is checked against the "_all" field.
     */
    public static FuzzyLikeThisQueryBuilder fuzzyLikeThisQuery() {
        return new FuzzyLikeThisQueryBuilder();
    }

    /**
     * A fuzzy like this query that finds documents that are "like" the provided {@link FuzzyLikeThisFieldQueryBuilder#likeText(String)}.
     */
    public static FuzzyLikeThisFieldQueryBuilder fuzzyLikeThisFieldQuery(String name) {
        return new FuzzyLikeThisFieldQueryBuilder(name);
    }

    /**
     * A more like this query that runs against a specific field.
     *
     * @param name The field name
     */
    public static MoreLikeThisFieldQueryBuilder moreLikeThisFieldQuery(String name) {
        return new MoreLikeThisFieldQueryBuilder(name);
    }

    /**
     * Constructs a new scoring child query, with the child type and the query to run on the child documents. The
     * results of this query are the parent docs that those child docs matched.
     *
     * @param type  The child type.
     * @param query The query.
     */
    public static TopChildrenQueryBuilder topChildrenQuery(String type, QueryBuilder query) {
        return new TopChildrenQueryBuilder(type, query);
    }

    /**
     * Constructs a new NON scoring child query, with the child type and the query to run on the child documents. The
     * results of this query are the parent docs that those child docs matched.
     *
     * @param type  The child type.
     * @param query The query.
     */
    public static HasChildQueryBuilder hasChildQuery(String type, QueryBuilder query) {
        return new HasChildQueryBuilder(type, query);
    }

    public static NestedQueryBuilder nestedQuery(String path, QueryBuilder query) {
        return new NestedQueryBuilder(path, query);
    }

    public static NestedQueryBuilder nestedQuery(String path, FilterBuilder filter) {
        return new NestedQueryBuilder(path, filter);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder termsQuery(String name, String... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder termsQuery(String name, int... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder termsQuery(String name, long... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder termsQuery(String name, float... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder termsQuery(String name, double... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder termsQuery(String name, Object... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder inQuery(String name, String... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder inQuery(String name, int... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder inQuery(String name, long... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder inQuery(String name, float... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder inQuery(String name, double... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A filer for a field based on several terms matching on any of them.
     *
     * @param name   The field name
     * @param values The terms
     */
    public static TermsQueryBuilder inQuery(String name, Object... values) {
        return new TermsQueryBuilder(name, values);
    }

    /**
     * A query that will execute the wrapped query only for the specified indices, and "match_all" when
     * it does not match those indices.
     */
    public static IndicesQueryBuilder indicesQuery(QueryBuilder queryBuilder, String... indices) {
        return new IndicesQueryBuilder(queryBuilder, indices);
    }

    /**
     * A Query builder which allows building a query thanks to a JSON string or binary data.
     */
    public static WrapperQueryBuilder wrapperQuery(String source) {
        return new WrapperQueryBuilder(source);
    }

    /**
     * A Query builder which allows building a query thanks to a JSON string or binary data.
     */
    public static WrapperQueryBuilder wrapperQuery(byte[] source, int offset, int length) {
        return new WrapperQueryBuilder(source, offset, length);
    }

    private QueryBuilders() {

    }
}
