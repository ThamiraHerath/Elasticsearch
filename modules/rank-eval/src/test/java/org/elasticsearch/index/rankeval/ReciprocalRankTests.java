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

package org.elasticsearch.index.rankeval;

import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.rankeval.PrecisionAtN.Rating;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import static java.util.Collections.emptyList;

public class ReciprocalRankTests extends ESTestCase {

    public void testMaxAcceptableRank() {
        ReciprocalRank reciprocalRank = new ReciprocalRank();
        assertEquals(ReciprocalRank.DEFAULT_MAX_ACCEPTABLE_RANK, reciprocalRank.getMaxAcceptableRank());

        int maxRank = randomIntBetween(1, 100);
        reciprocalRank.setMaxAcceptableRank(maxRank);
        assertEquals(maxRank, reciprocalRank.getMaxAcceptableRank());

        InternalSearchHit[] hits = new InternalSearchHit[10];
        for (int i = 0; i < 10; i++) {
            hits[i] = new InternalSearchHit(i, Integer.toString(i), new Text("type"), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new Index("test", "uuid"), 0));
        }
        List<RatedDocument> ratedDocs = new ArrayList<>();
        int relevantAt = 5;
        for (int i = 0; i < 10; i++) {
            if (i == relevantAt) {
                ratedDocs.add(new RatedDocument(
                        new RatedDocumentKey("test", "type", Integer.toString(i)),
                        Rating.RELEVANT.ordinal()));
            } else {
                ratedDocs.add(new RatedDocument(
                        new RatedDocumentKey("test", "type", Integer.toString(i)),
                        Rating.IRRELEVANT.ordinal()));
            }
        }

        int rankAtFirstRelevant = relevantAt + 1;
        EvalQueryQuality evaluation = reciprocalRank.evaluate(hits, ratedDocs, "_id");
        if (rankAtFirstRelevant <= maxRank) {
            assertEquals(1.0 / rankAtFirstRelevant, evaluation.getQualityLevel(), Double.MIN_VALUE);

            // check that if we lower maxRank by one, we don't find any result and get 0.0 quality level
            reciprocalRank = new ReciprocalRank(rankAtFirstRelevant - 1);
            evaluation = reciprocalRank.evaluate(hits, ratedDocs, "_id");
            assertEquals(0.0, evaluation.getQualityLevel(), Double.MIN_VALUE);

        } else {
            assertEquals(0.0, evaluation.getQualityLevel(), Double.MIN_VALUE);
        }
    }

    public void testEvaluationOneRelevantInResults() {
        ReciprocalRank reciprocalRank = new ReciprocalRank();
        InternalSearchHit[] hits = new InternalSearchHit[10];
        for (int i = 0; i < 10; i++) {
            hits[i] = new InternalSearchHit(i, Integer.toString(i), new Text("type"), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new Index("test", "uuid"), 0));
        }
        List<RatedDocument> ratedDocs = new ArrayList<>();
        // mark one of the ten docs relevant
        int relevantAt = randomIntBetween(0, 9);
        for (int i = 0; i <= 20; i++) {
            if (i == relevantAt) {
                ratedDocs.add(new RatedDocument(
                        new RatedDocumentKey("test", "type", Integer.toString(i)),
                        Rating.RELEVANT.ordinal()));
            } else {
                ratedDocs.add(new RatedDocument(
                        new RatedDocumentKey("test", "type", Integer.toString(i)),
                        Rating.IRRELEVANT.ordinal()));
            }
        }

        EvalQueryQuality evaluation = reciprocalRank.evaluate(hits, ratedDocs, "_id");
        assertEquals(1.0 / (relevantAt + 1), evaluation.getQualityLevel(), Double.MIN_VALUE);
    }

    public void testCombine() {
        ReciprocalRank reciprocalRank = new ReciprocalRank();
        Vector<EvalQueryQuality> partialResults = new Vector<>(3);
        partialResults.add(new EvalQueryQuality(0.5, emptyList()));
        partialResults.add(new EvalQueryQuality(1.0, emptyList()));
        partialResults.add(new EvalQueryQuality(0.75, emptyList()));
        assertEquals(0.75, reciprocalRank.combine(partialResults), Double.MIN_VALUE);
    }

    public void testEvaluationNoRelevantInResults() {
        ReciprocalRank reciprocalRank = new ReciprocalRank();
        InternalSearchHit[] hits = new InternalSearchHit[10];
        for (int i = 0; i < 10; i++) {
            hits[i] = new InternalSearchHit(i, Integer.toString(i), new Text("type"), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new Index("test", "uuid"), 0));
        }
        List<RatedDocument> ratedDocs = new ArrayList<>();
        EvalQueryQuality evaluation = reciprocalRank.evaluate(hits, ratedDocs, "_id");
        assertEquals(0.0, evaluation.getQualityLevel(), Double.MIN_VALUE);
    }
}
