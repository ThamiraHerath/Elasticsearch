/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.rank;

import org.apache.lucene.search.ScoreDoc;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.VersionedNamedWriteable;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code RankDoc} is the base class for all ranked results.
 * Subclasses should extend this with additional information
 * required for their global ranking method.
 */
public abstract class RankDoc extends ScoreDoc implements VersionedNamedWriteable {

    public RankDoc(int doc, float score, int shardIndex) {
        super(doc, score, shardIndex);
    }

    protected RankDoc(StreamInput in) throws IOException {
        super(in.readVInt(), in.readFloat(), in.readVInt());
    }

    @Override
    public final void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(doc);
        out.writeFloat(score);
        out.writeVInt(shardIndex);
        doWriteTo(out);
    }

    public abstract void doWriteTo(StreamOutput out) throws IOException;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankDoc that = (RankDoc) o;
        return doc == that.doc && score == that.score && shardIndex == that.shardIndex && doEquals(o);
    }

    public abstract boolean doEquals(Object o);

    @Override
    public final int hashCode() {
        return Objects.hash(doc, score, shardIndex, doHashCode());
    }

    public abstract int doHashCode();

    @Override
    public String toString() {
        return "RankDoc{" + "score=" + score + ", doc=" + doc + ", shardIndex=" + shardIndex + '}';
    }
}
