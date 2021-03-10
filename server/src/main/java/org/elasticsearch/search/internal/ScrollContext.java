/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.internal;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.search.Scroll;

/** Wrapper around information that needs to stay around when scrolling. */
public final class ScrollContext {
    public TotalHits totalHits = null;
    public float maxScore = Float.NaN;
    public ScoreDoc lastEmittedDoc;
    public Scroll scroll;
}
