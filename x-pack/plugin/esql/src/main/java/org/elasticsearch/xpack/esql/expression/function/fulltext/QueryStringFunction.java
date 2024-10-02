/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.fulltext;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.querydsl.query.Query;
import org.elasticsearch.xpack.esql.core.querydsl.query.QueryStringQuery;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Full text function that performs a {@link QueryStringQuery} .
 */
public class QueryStringFunction extends FullTextFunction {

    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Expression.class,
        "QStr",
        QueryStringFunction::new
    );

    @FunctionInfo(
        returnType = "boolean",
        preview = true,
        description = "Performs a query string query. Returns true if the provided query string matches the row.",
        examples = { @Example(file = "qstr-function", tag = "qstr-with-field") }
    )
    public QueryStringFunction(
        Source source,
        @Param(
            name = "query",
            type = { "keyword", "text" },
            description = "Query string in Lucene query string format."
        ) Expression queryString
    ) {
        super(source, queryString, List.of(queryString));
    }

    private QueryStringFunction(StreamInput in) throws IOException {
        this(Source.readFrom((PlanStreamInput) in), in.readNamedWriteable(Expression.class));
    }

    @Override
    public String functionName() {
        return "QSTR";
    }

    @Override
    public Query asQuery(String queryText) {
        return new QueryStringQuery(source(), queryText, Map.of(), null);
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new QueryStringFunction(source(), newChildren.getFirst());
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, QueryStringFunction::new, query());
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

}
