/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plan.logical.command;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.xpack.sql.expression.Attribute;
import org.elasticsearch.xpack.sql.expression.FieldAttribute;
import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.sql.rule.RuleExecutor.Batch;
import org.elasticsearch.xpack.sql.rule.RuleExecutor.ExecutionInfo;
import org.elasticsearch.xpack.sql.rule.RuleExecutor.Transformation;
import org.elasticsearch.xpack.sql.session.Rows;
import org.elasticsearch.xpack.sql.session.SchemaRowSet;
import org.elasticsearch.xpack.sql.session.SqlSession;
import org.elasticsearch.xpack.sql.tree.Source;
import org.elasticsearch.xpack.sql.tree.Node;
import org.elasticsearch.xpack.sql.tree.NodeInfo;
import org.elasticsearch.xpack.sql.tree.NodeUtils;
import org.elasticsearch.xpack.sql.type.KeywordEsField;
import org.elasticsearch.xpack.sql.util.Graphviz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static java.util.Collections.singletonList;
import static org.elasticsearch.action.ActionListener.wrap;

public class Debug extends Command {

    public enum Type {
        ANALYZED, OPTIMIZED;
    }

    public enum Format {
        TEXT, GRAPHVIZ
    }

    private final LogicalPlan plan;
    private final Format format;
    private final Type type;

    public Debug(Source location, LogicalPlan plan, Type type, Format format) {
        super(location);
        this.plan = plan;
        this.format = format == null ? Format.TEXT : format;
        this.type = type == null ? Type.OPTIMIZED : type;
    }

    @Override
    protected NodeInfo<Debug> info() {
        return NodeInfo.create(this, Debug::new, plan, type, format);
    }

    public LogicalPlan plan() {
        return plan;
    }

    public Format format() {
        return format;
    }

    public Type type() {
        return type;
    }

    @Override
    public List<Attribute> output() {
        return singletonList(new FieldAttribute(source(), "plan", new KeywordEsField("plan")));
    }

    @Override
    public void execute(SqlSession session, ActionListener<SchemaRowSet> listener) {
        switch (type) {
            case ANALYZED:
                session.debugAnalyzedPlan(plan, wrap(i -> handleInfo(i, listener), listener::onFailure));
                break;
            case OPTIMIZED:
                session.analyzedPlan(plan, true,
                        wrap(analyzedPlan -> handleInfo(session.optimizer().debugOptimize(analyzedPlan), listener), listener::onFailure));
                break;
            default:
                break;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void handleInfo(ExecutionInfo info, ActionListener<SchemaRowSet> listener) {
        String planString = null;

        if (format == Format.TEXT) {
            StringBuilder sb = new StringBuilder();
            if (info == null) {
                sb.append(plan.toString());
            } else {
                Map<Batch, List<Transformation>> map = info.transformations();

                for (Entry<Batch, List<Transformation>> entry : map.entrySet()) {
                    // for each batch
                    sb.append("***");
                    sb.append(entry.getKey().name());
                    sb.append("***");
                    for (Transformation tf : entry.getValue()) {
                        sb.append(tf.ruleName());
                        sb.append("\n");
                        sb.append(NodeUtils.diffString(tf.before(), tf.after()));
                        sb.append("\n");
                    }
                }
            }
            planString = sb.toString();
        } else {
            if (info == null) {
                planString = Graphviz.dot("Planned", plan);
            } else {
                Map<String, Node<?>> plans = new LinkedHashMap<>();
                Map<Batch, List<Transformation>> map = info.transformations();
                plans.put("start", info.before());

                for (Entry<Batch, List<Transformation>> entry : map.entrySet()) {
                    // for each batch
                    int counter = 0;
                    for (Transformation tf : entry.getValue()) {
                        if (tf.hasChanged()) {
                            plans.put(tf.ruleName() + "#" + ++counter, tf.after());
                        }
                    }
                }
                planString = Graphviz.dot(plans, true);
            }
        }

        listener.onResponse(Rows.singleton(output(), planString));
    }

    @Override
    public int hashCode() {
        return Objects.hash(plan, type, format);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        Debug o = (Debug) obj;
        return Objects.equals(format, o.format) && Objects.equals(type, o.type) && Objects.equals(plan, o.plan);
    }
}
