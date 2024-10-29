/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.fulltext;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.capabilities.Validatable;
import org.elasticsearch.xpack.esql.common.Failure;
import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.TypeResolutions;
import org.elasticsearch.xpack.esql.core.querydsl.query.QueryStringQuery;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.expression.function.TwoOptionalArguments;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FIRST;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.SECOND;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isNotNull;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isString;

/**
 * Full text function that performs a {@link QueryStringQuery} .
 */
public class Match extends FullTextFunction implements Validatable, TwoOptionalArguments {

    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(Expression.class, "Match", Match::readFrom);

    private final Expression field;
    private final boolean isOperator;

    @FunctionInfo(
        returnType = "boolean",
        preview = true,
        description = "Performs a match query on the specified field. Returns true if the provided query matches the row.",
        examples = { @Example(file = "match-function", tag = "match-with-field") }
    )
    public Match(
        Source source,
        @Param(name = "field", type = { "keyword", "text" }, description = "Field that the query will target.") Expression field,
        @Param(
            name = "query",
            type = { "keyword", "text" },
            description = "Text you wish to find in the provided field."
        ) Expression matchQuery
    ) {
        this(source, field, matchQuery, false);
    }

    private Match(Source source, Expression field, Expression matchQuery, boolean isOperator) {
        super(source, matchQuery, List.of(field, matchQuery));
        this.field = field;
        this.isOperator = isOperator;
    }

    public static Match operator(Source source, Expression field, Expression matchQuery) {
        return new Match(source, field, matchQuery, true);
    }

    private static Match readFrom(StreamInput in) throws IOException {
        Source source = Source.readFrom((PlanStreamInput) in);
        Expression field = in.readNamedWriteable(Expression.class);
        Expression query = in.readNamedWriteable(Expression.class);
        boolean isOperator = false;
        Expression boost = null;
        Expression fuzziness = null;
        if (in.getTransportVersion().onOrAfter(TransportVersions.MATCH_OPERATOR_COLON)) {
            isOperator = in.readBoolean();
        }
        return new Match(source, field, query, isOperator);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        source().writeTo(out);
        out.writeNamedWriteable(field());
        out.writeNamedWriteable(query());
        if (out.getTransportVersion().onOrAfter(TransportVersions.MATCH_OPERATOR_COLON)) {
            out.writeBoolean(isOperator);
        }
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    protected TypeResolution resolveNonQueryParamTypes() {
        return isNotNull(field, sourceText(), FIRST).and(isString(field, sourceText(), FIRST)).and(super.resolveNonQueryParamTypes());
    }

    @Override
    public void validate(Failures failures) {
        if (field instanceof FieldAttribute == false) {
            failures.add(
                Failure.fail(
                    field,
                    "[{}] {} cannot operate on [{}], which is not a field from an index mapping",
                    functionName(),
                    functionType(),
                    field.sourceText()
                )
            );
        }
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new Match(source(), newChildren.get(0), newChildren.get(1), isOperator);
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, Match::new, field, query());
    }

    protected TypeResolutions.ParamOrdinal queryParamOrdinal() {
        return SECOND;
    }

    public Expression field() {
        return field;
    }

    @Override
    public String functionType() {
        return isOperator ? "operator" : super.functionType();
    }

    @Override
    public String functionName() {
        return isOperator ? ":" : super.functionName();
    }
}
