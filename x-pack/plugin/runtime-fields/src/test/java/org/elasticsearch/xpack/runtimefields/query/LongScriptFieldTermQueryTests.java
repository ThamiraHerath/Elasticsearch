/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.runtimefields.query;

import org.elasticsearch.script.Script;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.equalTo;

public class LongScriptFieldTermQueryTests extends AbstractLongScriptFieldQueryTestCase<LongScriptFieldTermQuery> {
    @Override
    protected LongScriptFieldTermQuery createTestInstance() {
        return new LongScriptFieldTermQuery(randomScript(), leafFactory, randomAlphaOfLength(5), randomLong());
    }

    @Override
    protected LongScriptFieldTermQuery copy(LongScriptFieldTermQuery orig) {
        return new LongScriptFieldTermQuery(orig.script(), leafFactory, orig.fieldName(), orig.term());
    }

    @Override
    protected LongScriptFieldTermQuery mutate(LongScriptFieldTermQuery orig) {
        Script script = orig.script();
        String fieldName = orig.fieldName();
        long term = orig.term();
        switch (randomInt(2)) {
            case 0:
                script = randomValueOtherThan(script, this::randomScript);
                break;
            case 1:
                fieldName += "modified";
                break;
            case 2:
                term = randomValueOtherThan(term, ESTestCase::randomLong);
                break;
            default:
                fail();
        }
        return new LongScriptFieldTermQuery(script, leafFactory, fieldName, term);
    }

    @Override
    public void testMatches() {
        LongScriptFieldTermQuery query = new LongScriptFieldTermQuery(randomScript(), leafFactory, "test", 1);
        assertTrue(query.matches(new long[] { 1 }));
        assertFalse(query.matches(new long[] { 2 }));
        assertFalse(query.matches(new long[] {}));
        assertTrue(query.matches(new long[] { 2, 1 }));
    }

    @Override
    protected void assertToString(LongScriptFieldTermQuery query) {
        assertThat(query.toString(query.fieldName()), equalTo(Long.toString(query.term())));
    }
}
