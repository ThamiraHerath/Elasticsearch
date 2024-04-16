/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.segments;

import org.apache.lucene.tests.util.LuceneTestCase;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.index.engine.Segment;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESTestCase;

import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.hasItem;

@ClusterScope(scope = ESIntegTestCase.Scope.TEST)
@LuceneTestCase.SuppressCodecs("*")
public class IndicesSegmentsWithVectorsIT extends ESIntegTestCase {

    public void testIndicesSegmentsWithVectors() {
        String indexName = "test-vectors";
        createIndex(indexName);
        ensureGreen(indexName);

        String vectorField = "embedding";
        PutMappingRequest request = new PutMappingRequest().indices(indexName)
            .origin(randomFrom("1", "2"))
            .source(vectorField, "type=dense_vector");
        assertAcked(indicesAdmin().putMapping(request).actionGet());

        int docs = between(10, 100);
        int dims = randomInt(100);
        for (int i = 0; i < docs; i++) {
            List<Float> floats = randomList(dims, dims, ESTestCase::randomFloat);
            prepareIndex(indexName).setId("" + i).setSource(vectorField, floats).get();
        }
        indicesAdmin().prepareFlush(indexName).get();
        IndicesSegmentResponse response = indicesAdmin().prepareSegments(indexName).get();
        assertNoFailures(response);

        IndexSegments indexSegments = response.getIndices().get(indexName);
        assertNotNull(indexSegments);
        IndexShardSegments shardSegments = indexSegments.getShards().get(0);
        assertNotNull(shardSegments);
        ShardSegments shard = shardSegments.shards()[0];
        for (Segment segment : shard.getSegments()) {
            assertThat(segment.getAttributes().keySet(), hasItem(endsWith("VectorsFormat")));
            assertThat(segment.getAttributes().values(), hasItem("[" + vectorField + "]"));
        }
    }
}
