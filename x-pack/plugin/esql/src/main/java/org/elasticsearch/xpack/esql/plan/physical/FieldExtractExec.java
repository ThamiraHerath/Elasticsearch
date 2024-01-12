/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.NodeUtils;
import org.elasticsearch.xpack.ql.tree.Source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public class FieldExtractExec extends UnaryExec implements EstimatesRowSize {
    private final List<Attribute> attributesToExtract;
    private final Attribute sourceAttribute;
    private final HashSet<Attribute> forStats;

    private FieldExtractExec(Source source, PhysicalPlan child, List<Attribute> attributesToExtract, HashSet<Attribute> forStats) {
        super(source, child);
        this.attributesToExtract = attributesToExtract;
        this.sourceAttribute = extractSourceAttributesFrom(child);
        this.forStats = forStats;
    }

    public FieldExtractExec(Source source, PhysicalPlan child, List<Attribute> attributesToExtract) {
        this(source, child, attributesToExtract, new HashSet<>());
    }

    public static Attribute extractSourceAttributesFrom(PhysicalPlan plan) {
        return plan.outputSet().stream().filter(EsQueryExec::isSourceAttribute).findFirst().orElse(null);
    }

    @Override
    protected NodeInfo<FieldExtractExec> info() {
        return NodeInfo.create(this, FieldExtractExec::new, child(), attributesToExtract, forStats);
    }

    @Override
    public UnaryExec replaceChild(PhysicalPlan newChild) {
        return new FieldExtractExec(source(), newChild, attributesToExtract, forStats);
    }

    public List<Attribute> attributesToExtract() {
        return attributesToExtract;
    }

    public Attribute sourceAttribute() {
        return sourceAttribute;
    }

    @Override
    public List<Attribute> output() {
        List<Attribute> output = new ArrayList<>(child().output());
        output.addAll(attributesToExtract);
        return output;
    }

    @Override
    public PhysicalPlan estimateRowSize(State state) {
        state.add(true, attributesToExtract);
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributesToExtract, forStats, child());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        FieldExtractExec other = (FieldExtractExec) obj;
        return Objects.equals(attributesToExtract, other.attributesToExtract)
            && Objects.equals(forStats, other.forStats)
            && Objects.equals(child(), other.child());
    }

    @Override
    public String nodeString() {
        return nodeName() + NodeUtils.limitedToString(attributesToExtract) + NodeUtils.limitedToString(forStats);
    }

    public FieldExtractExec withForStats(Attribute attr) {
        HashSet<Attribute> newForStats = new HashSet<>(forStats);
        newForStats.add(attr);
        return new FieldExtractExec(source(), child(), attributesToExtract, newForStats);
    }

    /**
     * Returns true if the given attribute is extracted for usage in STATS.
     */
    public boolean forStats(Attribute attr) {
        return forStats.contains(attr);
    }
}
