/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.validate.query;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.test.AbstractBroadcastResponseTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ValidateQueryResponseTests extends AbstractBroadcastResponseTestCase<ValidateQueryResponse> {

    private static ValidateQueryResponse createRandomValidateQueryResponse(
        int totalShards, int successfulShards, int failedShards, List<DefaultShardOperationFailedException> failures) {
        boolean valid = failedShards == 0;
        List<QueryExplanation> queryExplanations = new ArrayList<>(totalShards);
        for(DefaultShardOperationFailedException failure: failures) {
            queryExplanations.add(
                new QueryExplanation(
                    failure.index(), failure.shardId(), false, failure.reason(), null
                )
            );
        }
        return new ValidateQueryResponse(
            valid, queryExplanations, totalShards, successfulShards, failedShards, failures
        );
    }

    private static ValidateQueryResponse createRandomValidateQueryResponse() {
        int totalShards = randomIntBetween(1, 10);
        int successfulShards = randomIntBetween(0, totalShards);
        int failedShards = totalShards - successfulShards;
        boolean valid = failedShards == 0;
        List<QueryExplanation> queryExplanations = new ArrayList<>(totalShards);
        List<DefaultShardOperationFailedException> shardFailures = new ArrayList<>(failedShards);
        for (int i=0; i<successfulShards; i++) {
            QueryExplanation queryExplanation = QueryExplanationTests.createRandomQueryExplanation(true);
            queryExplanations.add(queryExplanation);
        }
        for (int i=0; i<failedShards; i++) {
            QueryExplanation queryExplanation = QueryExplanationTests.createRandomQueryExplanation(false);
            ElasticsearchException exc = new ElasticsearchException("some_error_" + randomInt());
            shardFailures.add(
                new DefaultShardOperationFailedException(
                    queryExplanation.getIndex(), queryExplanation.getShard(),
                    exc
                )
            );
            queryExplanations.add(queryExplanation);
        }
        Collections.shuffle(queryExplanations, random());
        return new ValidateQueryResponse(valid, queryExplanations, totalShards, successfulShards, failedShards, shardFailures);
    }

    @Override
    protected ValidateQueryResponse doParseInstance(XContentParser parser) throws IOException {
        return ValidateQueryResponse.fromXContent(parser);
    }

    @Override
    protected ValidateQueryResponse createTestInstance() {
        return createRandomValidateQueryResponse();
    }

    @Override
    protected void assertEqualInstances(ValidateQueryResponse response, ValidateQueryResponse parsedResponse) {
        super.assertEqualInstances(response, parsedResponse);
        Set<QueryExplanation> queryExplSet = new HashSet<>(response.getQueryExplanation());
        assertEquals(response.isValid(), parsedResponse.isValid());
        assertEquals(response.getQueryExplanation().size(), parsedResponse.getQueryExplanation().size());
        assertTrue(queryExplSet.containsAll(parsedResponse.getQueryExplanation()));
    }

    @Override
    protected ValidateQueryResponse createTestInstance(int totalShards, int successfulShards, int failedShards,
                                                       List<DefaultShardOperationFailedException> failures) {
        return createRandomValidateQueryResponse(totalShards, successfulShards, failedShards, failures);
    }

    @Override
    public void testToXContent() {
        ValidateQueryResponse response = createTestInstance(10, 10, 0, new ArrayList<>());
        String output = Strings.toString(response);
        assertEquals("{\"_shards\":{\"total\":10,\"successful\":10,\"failed\":0},\"valid\":true}", output);
    }
}
