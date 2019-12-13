/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plan.logical.command.sys;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.DataTypes;
import org.elasticsearch.xpack.sql.plan.logical.command.Command;
import org.elasticsearch.xpack.sql.session.Cursor.Page;
import org.elasticsearch.xpack.sql.session.SqlSession;

import java.sql.DatabaseMetaData;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.xpack.ql.type.DataType.BOOLEAN;
import static org.elasticsearch.xpack.ql.type.DataType.INTEGER;
import static org.elasticsearch.xpack.ql.type.DataType.SHORT;

/**
 * System command designed to be used by JDBC / ODBC for column metadata.
 * In JDBC it used for {@link DatabaseMetaData#getTypeInfo()},
 * in ODBC for <a href="https://docs.microsoft.com/en-us/sql/odbc/reference/syntax/sqlgettypeinfo-function">SQLGetTypeInfo</a>
 */
public class SysTypes extends Command {

    private final Integer type;

    public SysTypes(Source source, int type) {
        super(source);
        this.type = Integer.valueOf(type);
    }

    @Override
    protected NodeInfo<SysTypes> info() {
        return NodeInfo.create(this, SysTypes::new, type);
    }

    @Override
    public List<Attribute> output() {
        return asList(keyword("TYPE_NAME"),
                      field("DATA_TYPE", INTEGER),
                      field("PRECISION",INTEGER),
                      keyword("LITERAL_PREFIX"),
                      keyword("LITERAL_SUFFIX"),
                      keyword("CREATE_PARAMS"),
                      field("NULLABLE", SHORT),
                      field("CASE_SENSITIVE", BOOLEAN),
                      field("SEARCHABLE", SHORT),
                      field("UNSIGNED_ATTRIBUTE", BOOLEAN),
                      field("FIXED_PREC_SCALE", BOOLEAN),
                      field("AUTO_INCREMENT", BOOLEAN),
                      keyword("LOCAL_TYPE_NAME"),
                      field("MINIMUM_SCALE", SHORT),
                      field("MAXIMUM_SCALE", SHORT),
                      field("SQL_DATA_TYPE", INTEGER),
                      field("SQL_DATETIME_SUB", INTEGER),
                      field("NUM_PREC_RADIX", INTEGER),
                      // ODBC
                      field("INTERVAL_PRECISION", INTEGER)
                      );
    }

    @Override
    public final void execute(SqlSession session, ActionListener<Page> listener) {
        Stream<DataType> values = Stream.of(DataType.values());
        if (type.intValue() != 0) {
            values = values.filter(t -> type.equals(t.sqlType().getVendorTypeNumber()));
        }
        List<List<?>> rows = values
                // sort by SQL int type (that's what the JDBC/ODBC specs want) followed by name
                .sorted(Comparator.comparing((DataType t) -> t.sqlType().getVendorTypeNumber()).thenComparing(DataType::sqlName))
                .map(t -> asList(t.toString(),
                        t.sqlType().getVendorTypeNumber(),
                        DataTypes.precision(t),
                        "'",
                        "'",
                        null,
                        // don't be specific on nullable
                        DatabaseMetaData.typeNullableUnknown,
                        // all strings are case-sensitive
                        t.isString(),
                        // everything is searchable,
                        DatabaseMetaData.typeSearchable,
                        // only numerics are signed
                        !t.isSigned(),
                        //no fixed precision scale SQL_FALSE
                        Boolean.FALSE,
                        // not auto-incremented
                        Boolean.FALSE,
                        null,
                        DataTypes.metaSqlMinimumScale(t),
                        DataTypes.metaSqlMaximumScale(t),
                        // SQL_DATA_TYPE - ODBC wants this to be not null
                        DataTypes.metaSqlDataType(t),
                        DataTypes.metaSqlDateTimeSub(t),
                        // Radix
                        DataTypes.metaSqlRadix(t),
                        null
                        ))
                .collect(toList());
        
        listener.onResponse(of(session, rows));
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        return type.equals(((SysTypes) obj).type);
    }
}
