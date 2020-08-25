/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.runtimefields.query;

import org.elasticsearch.script.Script;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.equalTo;

public class LongScriptFieldRangeQueryTests extends AbstractLongScriptFieldQueryTestCase<LongScriptFieldRangeQuery> {
    @Override
    protected LongScriptFieldRangeQuery createTestInstance() {
        long lower = randomLong();
        long upper = randomValueOtherThan(lower, ESTestCase::randomLong);
        if (lower > upper) {
            long tmp = lower;
            lower = upper;
            upper = tmp;
        }
        return new LongScriptFieldRangeQuery(randomScript(), leafFactory, randomAlphaOfLength(5), lower, upper);
    }

    @Override
    protected LongScriptFieldRangeQuery copy(LongScriptFieldRangeQuery orig) {
        return new LongScriptFieldRangeQuery(orig.script(), leafFactory, orig.fieldName(), orig.lowerValue(), orig.upperValue());
    }

    @Override
    protected LongScriptFieldRangeQuery mutate(LongScriptFieldRangeQuery orig) {
        Script script = orig.script();
        String fieldName = orig.fieldName();
        long lower = orig.lowerValue();
        long upper = orig.upperValue();
        switch (randomInt(3)) {
            case 0:
                script = randomValueOtherThan(script, this::randomScript);
                break;
            case 1:
                fieldName += "modified";
                break;
            case 2:
                if (lower == Long.MIN_VALUE) {
                    fieldName += "modified_instead_of_lower";
                } else {
                    lower -= 1;
                }
                break;
            case 3:
                if (upper == Long.MAX_VALUE) {
                    fieldName += "modified_instead_of_upper";
                } else {
                    upper += 1;
                }
                break;
            default:
                fail();
        }
        return new LongScriptFieldRangeQuery(script, leafFactory, fieldName, lower, upper);
    }

    @Override
    public void testMatches() {
        LongScriptFieldRangeQuery query = new LongScriptFieldRangeQuery(randomScript(), leafFactory, "test", 1, 3);
        assertTrue(query.matches(new long[] { 1 }));
        assertTrue(query.matches(new long[] { 2 }));
        assertTrue(query.matches(new long[] { 3 }));
        assertFalse(query.matches(new long[] {}));
        assertFalse(query.matches(new long[] { 5 }));
        assertTrue(query.matches(new long[] { 1, 5 }));
        assertTrue(query.matches(new long[] { 5, 1 }));
    }

    @Override
    protected void assertToString(LongScriptFieldRangeQuery query) {
        assertThat(query.toString(query.fieldName()), equalTo("[" + query.lowerValue() + " TO " + query.upperValue() + "]"));
    }
}
