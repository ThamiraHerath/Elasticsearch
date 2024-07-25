/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.geo.GeometryTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.AbstractScalarFunctionTestCase;
import org.elasticsearch.xpack.esql.expression.function.FunctionName;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.esql.core.util.SpatialCoordinateTypes.GEO;

@FunctionName("to_geopoint")
public class ToGeoPointTests extends AbstractScalarFunctionTestCase {
    public ToGeoPointTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        // TODO multivalue fields
        final String attribute = "Attribute[channel=0]";
        final Function<String, String> evaluatorName = s -> "ToGeoPoint" + s + "Evaluator[field=" + attribute + "]";
        final List<TestCaseSupplier> suppliers = new ArrayList<>();

        TestCaseSupplier.forUnaryGeoPoint(suppliers, attribute, DataType.GEO_POINT, v -> v, List.of());
        // random strings that don't look like a geo point
        TestCaseSupplier.forUnaryStrings(suppliers, evaluatorName.apply("FromString"), DataType.GEO_POINT, bytesRef -> null, bytesRef -> {
            var exception = expectThrows(Exception.class, () -> GEO.wktToWkb(bytesRef.utf8ToString()));
            return List.of(
                "Line -1:-1: evaluation of [] failed, treating result as null. Only first 20 failures recorded.",
                "Line -1:-1: " + exception
            );
        });
        // strings that are geo point representations
        for (DataType dt : List.of(DataType.KEYWORD, DataType.TEXT)) {
            TestCaseSupplier.unary(
                suppliers,
                evaluatorName.apply("FromString"),
                List.of(
                    new TestCaseSupplier.TypedDataSupplier(
                        "<geo point as string>",
                        () -> new BytesRef(GEO.asWkt(GeometryTestUtils.randomPoint())),
                        dt
                    )
                ),
                DataType.GEO_POINT,
                bytesRef -> GEO.wktToWkb(((BytesRef) bytesRef).utf8ToString()),
                List.of()
            );
        }

        return parameterSuppliersFromTypedDataWithDefaultChecks(true, suppliers, (v, p) -> "geo_point or string");
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new ToGeoPoint(source, args.get(0));
    }
}
