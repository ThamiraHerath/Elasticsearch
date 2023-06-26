/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.query;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FilterScorable;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreCachingWrappingScorer;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.elasticsearch.common.lucene.Lucene;

import java.io.IOException;
import java.util.Objects;

/**
 * Top-level collector used in the query phase to perform top hits collection as well as aggs collection.
 * Inspired by {@link org.apache.lucene.search.MultiCollector} but specialized for wrapping two collectors and filtering collected
 * documents as follows:
 * - through an optional <code>post_filter</code> that is applied to the top hits collection
 * - through an optional <code>min_score</code> threshold, which is applied to both the top hits as well as aggs.
 * Supports also terminating the collection after a certain number of documents have been collected (<code>terminate_after</code>).
 */
final class QueryPhaseCollector implements Collector {
    private final Collector aggsCollector;
    private final Collector topDocsCollector;
    private final int terminateAfter;
    private final Weight postFilterWeight;
    private final Float minScore;
    private final boolean cacheScores;

    private int numCollected;
    private boolean terminatedAfter = false;

    QueryPhaseCollector(Collector topDocsCollector, Weight postFilterWeight, int terminateAfter, Collector aggsCollector, Float minScore) {
        this.topDocsCollector = Objects.requireNonNull(topDocsCollector);
        this.postFilterWeight = postFilterWeight;
        this.terminateAfter = terminateAfter;
        this.aggsCollector = aggsCollector;
        this.minScore = minScore;
        this.cacheScores = aggsCollector != null && topDocsCollector.scoreMode().needsScores() && aggsCollector.scoreMode().needsScores();
    }

    @Override
    public void setWeight(Weight weight) {
        if (postFilterWeight == null && minScore == null) {
            // propagate the weight when we do no additional filtering over the docs that are collected
            topDocsCollector.setWeight(weight);
        }
        if (aggsCollector != null && minScore == null) {
            // min_score is applied to aggs collection as well as top docs collection
            aggsCollector.setWeight(weight);
        }
    }

    @Override
    public ScoreMode scoreMode() {
        ScoreMode scoreMode;
        if (aggsCollector == null) {
            scoreMode = topDocsCollector.scoreMode();
        } else {
            assert aggsCollector.scoreMode() != ScoreMode.TOP_SCORES : "aggs never rely on setMinCompetitiveScore";
            if (topDocsCollector.scoreMode() == aggsCollector.scoreMode()) {
                scoreMode = topDocsCollector.scoreMode();
            } else if (topDocsCollector.scoreMode().needsScores() || aggsCollector.scoreMode().needsScores()) {
                scoreMode = ScoreMode.COMPLETE;
            } else {
                scoreMode = ScoreMode.COMPLETE_NO_SCORES;
            }
            //TODO for aggs that return TOP_DOCS, score mode becomes exhaustive unless top score doc agrees on the score mode
        }
        if (minScore != null) {
            scoreMode = scoreMode == ScoreMode.TOP_SCORES ? ScoreMode.TOP_SCORES : ScoreMode.COMPLETE;
        }
        return scoreMode;
    }

    /**
     * @return whether the collection was terminated based on the provided <code>terminate_after</code> value
     */
    boolean isTerminatedAfter() {
        return terminatedAfter;
    }

    private boolean shouldCollectTopDocs(int doc, Scorable scorer, Bits postFilterBits) throws IOException {
        if (minScore == null || scorer.score() >= minScore) {
            if (postFilterBits == null || postFilterBits.get(doc)) {
                // TODO terminate_after is purposely applied after post_filter, yet it is weird as it terminates aggs collection as well
                // based on number of filtered top hits that have been collected. This may be something that we want to address.
                if (terminateAfter > 0 && ++numCollected > terminateAfter) {
                    terminatedAfter = true;
                    throw new CollectionTerminatedException();
                }
                return true;
            }
        }
        return false;
    }

    private Bits getPostFilterBits(LeafReaderContext context) throws IOException {
        if (postFilterWeight == null) {
            return null;
        }
        final ScorerSupplier filterScorerSupplier = postFilterWeight.scorerSupplier(context);
        return Lucene.asSequentialAccessBits(context.reader().maxDoc(), filterScorerSupplier);
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
        if (terminateAfter > 0 && numCollected >= terminateAfter) {
            terminatedAfter = true;
            throw new CollectionTerminatedException();
        }
        Bits postFilterBits = getPostFilterBits(context);

        if (aggsCollector == null) {
            LeafCollector topDocsLeafCollector;
            try {
                topDocsLeafCollector = topDocsCollector.getLeafCollector(context);
            } catch (@SuppressWarnings("unused") CollectionTerminatedException e) {
                // TODO we keep on collecting although we have nothing to collect (there is no top docs nor aggs leaf collector).
                // The reason is only to set the early terminated flag to the QueryResult like some tests expect. This needs fixing.
                if (terminateAfter == 0) {
                    throw e;
                }
                topDocsLeafCollector = null;
            }
            return new TopDocsLeafCollector(postFilterBits, topDocsLeafCollector);
        }

        LeafCollector topDocsLeafCollector;
        try {
            topDocsLeafCollector = topDocsCollector.getLeafCollector(context);
        } catch (@SuppressWarnings("unused") CollectionTerminatedException e) {
            // top docs collector does not need this segment, but the aggs collector does.
            topDocsLeafCollector = null;
        }

        LeafCollector aggsLeafCollector;
        try {
            aggsLeafCollector = aggsCollector.getLeafCollector(context);
        } catch (@SuppressWarnings("unused") CollectionTerminatedException e) {
            // aggs collector does not need this segment, but the top docs collector may.
            if (topDocsLeafCollector == null) {
                // TODO we keep on collecting although we have nothing to collect (there is no top docs nor aggs leaf collector).
                // The reason is only to set the early terminated flag to the QueryResult. We should fix this.
                if (terminateAfter == 0) {
                    throw e;
                }
            }
            aggsLeafCollector = null;
        }
        //say that the aggs collector early terminates while the top docs collector does not, we still want to wrap in the same way
        //to enforce that setMinCompetitiveScore is a no-op. Otherwise we may allow the top docs collector to skip non competitive
        //hits despite the score mode of the Collector did not allow it.
        return new CompositeLeafCollector(postFilterBits, topDocsLeafCollector, aggsLeafCollector);
    }

    private class TopDocsLeafCollector implements LeafCollector {
        private final Bits postFilterBits;
        private LeafCollector topDocsLeafCollector;
        private Scorable scorer;

        TopDocsLeafCollector(Bits postFilterBits, LeafCollector topDocsLeafCollector) {
            this.postFilterBits = postFilterBits;
            this.topDocsLeafCollector = topDocsLeafCollector;
        }

        @Override
        public void setScorer(Scorable scorer) throws IOException {
            if (cacheScores) {
                scorer = ScoreCachingWrappingScorer.wrap(scorer);
            }
            scorer = new FilterScorable(scorer) {
                @Override
                public void setMinCompetitiveScore(float minScore) throws IOException {
                    if (terminateAfter == 0) {
                        // Ignore calls to setMinCompetitiveScore when terminate_after is used, otherwise early termination
                        // of total hits tracking makes it impossible to terminate after.
                        // TODO the reason is only to set the early terminated flag to the QueryResult. We should fix this.
                        in.setMinCompetitiveScore(minScore);
                    }
                }
            };
            if (topDocsLeafCollector != null) {
                topDocsLeafCollector.setScorer(scorer);
            }
            this.scorer = scorer;
        }

        @Override
        public DocIdSetIterator competitiveIterator() throws IOException {
            if (topDocsLeafCollector != null) {
                return topDocsLeafCollector.competitiveIterator();
            }
            return null;
        }

        @Override
        public void collect(int doc) throws IOException {
            if (shouldCollectTopDocs(doc, scorer, postFilterBits)) {
                if (topDocsLeafCollector != null) {
                    try {
                        topDocsLeafCollector.collect(doc);
                    } catch (@SuppressWarnings("unused") CollectionTerminatedException e) {
                        // TODO we keep on collecting although we have nothing to collect (there is no top docs nor aggs leaf
                        // collector).
                        // The reason is only to set the early terminated flag to the QueryResult. We should fix this.
                        if (terminateAfter == 0) {
                            throw e;
                        }
                        topDocsLeafCollector = null;
                    }
                }
            }
        }
    }

    private class CompositeLeafCollector implements LeafCollector {
        private final Bits postFilterBits;
        private LeafCollector topDocsLeafCollector;
        private LeafCollector aggsLeafCollector;
        private Scorable scorer;

        CompositeLeafCollector(Bits postFilterBits, LeafCollector topDocsLeafCollector, LeafCollector aggsLeafCollector) {
            this.postFilterBits = postFilterBits;
            this.topDocsLeafCollector = topDocsLeafCollector;
            this.aggsLeafCollector = aggsLeafCollector;
        }

        @Override
        public void setScorer(Scorable scorer) throws IOException {
            if (cacheScores) {
                scorer = ScoreCachingWrappingScorer.wrap(scorer);
            }
            scorer = new FilterScorable(scorer) {
                // TODO aggs can also skip non competitive hits
                @Override
                public void setMinCompetitiveScore(float minScore) {
                    // Ignore calls to setMinCompetitiveScore so that if the top docs collector
                    // wants to skip low-scoring hits, the aggs collector still sees all hits.
                    // this is important also for terminate_after in case used when total hits tracking is early terminated.
                }
            };
            if (topDocsLeafCollector != null) {
                topDocsLeafCollector.setScorer(scorer);
            }
            if (aggsLeafCollector != null) {
                aggsLeafCollector.setScorer(scorer);
            }
            this.scorer = scorer;
        }

        @Override
        public void collect(int doc) throws IOException {
            if (shouldCollectTopDocs(doc, scorer, postFilterBits)) {
                if (topDocsLeafCollector != null) {
                    try {
                        topDocsLeafCollector.collect(doc);
                    } catch (@SuppressWarnings("unused") CollectionTerminatedException e) {
                        // top docs collector does not need this segment, but the aggs collector may.
                        if (aggsLeafCollector == null) {
                            // TODO we keep on collecting although we have nothing to collect (there is no top docs nor aggs leaf
                            // collector).
                            // The reason is only to set the early terminated flag to the QueryResult. We should fix this.
                            if (terminateAfter == 0) {
                                throw e;
                            }
                        }
                        topDocsLeafCollector = null;
                    }
                }
            }
            // min_score is applied to aggs as well as top hits
            if (minScore == null || scorer.score() >= minScore) {
                if (aggsLeafCollector != null) {
                    try {
                        aggsLeafCollector.collect(doc);
                    } catch (@SuppressWarnings("unused") CollectionTerminatedException e) {
                        // aggs collector does not need this segment, but the top docs collector may.
                        if (topDocsLeafCollector == null) {
                            // TODO we keep on collecting although we have nothing to collect (there is no top docs nor aggs leaf
                            // collector).
                            // The reason is only to set the early terminated flag to the QueryResult. We should fix this.
                            if (terminateAfter == 0) {
                                throw e;
                            }
                        }
                        aggsLeafCollector = null;
                    }
                }
            }
        }

        @Override
        public DocIdSetIterator competitiveIterator() throws IOException {
            if (topDocsLeafCollector == null) {
                //we expose the aggs competitive iterator only when the top docs collector early terminated its collection
                return aggsLeafCollector.competitiveIterator();
            }
            return null;
        }
    }
}
