/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.core.expression;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xpack.esql.core.util.PlanStreamInput;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unique identifier for a named expression.
 * <p>
 * We use an {@link AtomicLong} to guarantee that they are unique
 * and that create reproducible values when run in subsequent
 * tests. They don't produce reproducible values in production, but
 * you rarely debug with them in production and commonly do so in
 * tests.</p>
 * <p>While this class is {@link Writeable}, to read it you'll need
 * to call {@link PlanStreamInput#readNameId()} to keep a consistent
 * mapping across all plans.</p>
 */
public class NameId implements Writeable {
    private static final AtomicLong COUNTER = new AtomicLong();
    private final long id;

    public NameId() {
        this.id = COUNTER.incrementAndGet();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        NameId other = (NameId) obj;
        return id == other.id;
    }

    @Override
    public String toString() {
        return Long.toString(id);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeLong(id);
    }
}
