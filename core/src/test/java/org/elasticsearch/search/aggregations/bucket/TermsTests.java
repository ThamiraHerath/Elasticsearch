/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.search.aggregations.bucket;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.RegExp;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.Script.ScriptInput;
import org.elasticsearch.search.aggregations.Aggregator.SubAggCollectionMode;
import org.elasticsearch.search.aggregations.BaseAggregationTestCase;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregatorFactory.ExecutionMode;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class TermsTests extends BaseAggregationTestCase<TermsAggregationBuilder> {

    private static final String[] executionHints;

    static {
        ExecutionMode[] executionModes = ExecutionMode.values();
        executionHints = new String[executionModes.length];
        for (int i = 0; i < executionModes.length; i++) {
            executionHints[i] = executionModes[i].toString();
        }
    }

    @Override
    protected TermsAggregationBuilder createTestAggregatorBuilder() {
        String name = randomAsciiOfLengthBetween(3, 20);
        TermsAggregationBuilder factory = new TermsAggregationBuilder(name, null);
        String field = randomAsciiOfLengthBetween(3, 20);
        int randomFieldBranch = randomInt(2);
        switch (randomFieldBranch) {
        case 0:
            factory.field(field);
            break;
        case 1:
            factory.field(field);
            factory.script(ScriptInput.inline("_value + 1"));
            break;
        case 2:
            factory.script(ScriptInput.inline("doc[" + field + "] + 1"));
            break;
        default:
            fail();
        }
        if (randomBoolean()) {
            factory.missing("MISSING");
        }
        if (randomBoolean()) {
            factory.bucketCountThresholds().setRequiredSize(randomIntBetween(1, Integer.MAX_VALUE));
        }
        if (randomBoolean()) {
            factory.bucketCountThresholds().setShardSize(randomIntBetween(1, Integer.MAX_VALUE));
        }
        if (randomBoolean()) {
            int minDocCount = randomInt(4);
            switch (minDocCount) {
            case 0:
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                minDocCount = randomInt();
                break;
            default:
                fail();
            }
            factory.bucketCountThresholds().setMinDocCount(minDocCount);
        }
        if (randomBoolean()) {
            int shardMinDocCount = randomInt(4);
            switch (shardMinDocCount) {
            case 0:
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                shardMinDocCount = randomInt();
                break;
            default:
                fail();
            }
            factory.bucketCountThresholds().setShardMinDocCount(shardMinDocCount);
        }
        if (randomBoolean()) {
            factory.collectMode(randomFrom(SubAggCollectionMode.values()));
        }
        if (randomBoolean()) {
            factory.executionHint(randomFrom(executionHints));
        }
        if (randomBoolean()) {
            factory.format("###.##");
        }
        if (randomBoolean()) {
            IncludeExclude incExc = null;
            switch (randomInt(5)) {
            case 0:
                incExc = new IncludeExclude(new RegExp("foobar"), null);
                break;
            case 1:
                incExc = new IncludeExclude(null, new RegExp("foobaz"));
                break;
            case 2:
                incExc = new IncludeExclude(new RegExp("foobar"), new RegExp("foobaz"));
                break;
            case 3:
                SortedSet<BytesRef> includeValues = new TreeSet<>();
                int numIncs = randomIntBetween(1, 20);
                for (int i = 0; i < numIncs; i++) {
                    includeValues.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                SortedSet<BytesRef> excludeValues = null;
                incExc = new IncludeExclude(includeValues, excludeValues);
                break;
            case 4:
                SortedSet<BytesRef> includeValues2 = null;
                SortedSet<BytesRef> excludeValues2 = new TreeSet<>();
                int numExcs2 = randomIntBetween(1, 20);
                for (int i = 0; i < numExcs2; i++) {
                    excludeValues2.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                incExc = new IncludeExclude(includeValues2, excludeValues2);
                break;
            case 5:
                SortedSet<BytesRef> includeValues3 = new TreeSet<>();
                int numIncs3 = randomIntBetween(1, 20);
                for (int i = 0; i < numIncs3; i++) {
                    includeValues3.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                SortedSet<BytesRef> excludeValues3 = new TreeSet<>();
                int numExcs3 = randomIntBetween(1, 20);
                for (int i = 0; i < numExcs3; i++) {
                    excludeValues3.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                incExc = new IncludeExclude(includeValues3, excludeValues3);
                break;
            default:
                fail();
            }
            factory.includeExclude(incExc);
        }
        if (randomBoolean()) {
            List<Terms.Order> order = randomOrder();
            factory.order(order);
        }
        if (randomBoolean()) {
            factory.showTermDocCountError(randomBoolean());
        }
        return factory;
    }

    private List<Terms.Order> randomOrder() {
        List<Terms.Order> orders = new ArrayList<>();
        switch (randomInt(4)) {
        case 0:
            orders.add(Terms.Order.term(randomBoolean()));
            break;
        case 1:
            orders.add(Terms.Order.count(randomBoolean()));
            break;
        case 2:
            orders.add(Terms.Order.aggregation(randomAsciiOfLengthBetween(3, 20), randomBoolean()));
            break;
        case 3:
            orders.add(Terms.Order.aggregation(randomAsciiOfLengthBetween(3, 20), randomAsciiOfLengthBetween(3, 20), randomBoolean()));
            break;
        case 4:
            int numOrders = randomIntBetween(1, 3);
            for (int i = 0; i < numOrders; i++) {
                orders.addAll(randomOrder());
            }
            break;
        default:
            fail();
        }
        return orders;
    }

}
