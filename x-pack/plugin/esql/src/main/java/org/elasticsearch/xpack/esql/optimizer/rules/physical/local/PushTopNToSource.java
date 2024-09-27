/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.physical.local;

import org.elasticsearch.geometry.Geometry;
import org.elasticsearch.geometry.Point;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.NameId;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.scalar.spatial.BinarySpatialFunction;
import org.elasticsearch.xpack.esql.expression.function.scalar.spatial.SpatialRelatesUtils;
import org.elasticsearch.xpack.esql.expression.function.scalar.spatial.StDistance;
import org.elasticsearch.xpack.esql.optimizer.LocalPhysicalOptimizerContext;
import org.elasticsearch.xpack.esql.optimizer.PhysicalOptimizerRules;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EvalExec;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.plan.physical.TopNExec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class PushTopNToSource extends PhysicalOptimizerRules.ParameterizedOptimizerRule<TopNExec, LocalPhysicalOptimizerContext> {
    @Override
    protected PhysicalPlan rule(TopNExec topNExec, LocalPhysicalOptimizerContext ctx) {
        Pushable pushable = evaluatePushable(topNExec, x -> LucenePushDownUtils.hasIdenticalDelegate(x, ctx.searchStats()));
        return pushable.rewrite(topNExec);
    }

    /**
     * Multiple scenarios for pushing down TopN to Lucene source. Each involve checking a combination of conditions and then
     * performing an associated rewrite specific to that scenario. This interface should be extended by each scenario, and
     * include the appropriate rewrite logic.
     */
    interface Pushable {
        PhysicalPlan rewrite(TopNExec topNExec);
    }

    private static Pushable NO_OP = new NoOpPushable();

    record NoOpPushable() implements Pushable {
        public PhysicalPlan rewrite(TopNExec topNExec) {
            return topNExec;
        }
    }

    record PushableExchangeExec(ExchangeExec exchangeExec, EsQueryExec queryExec) implements Pushable {
        public PhysicalPlan rewrite(TopNExec topNExec) {
            var sorts = buildFieldSorts(topNExec.order());
            var limit = topNExec.limit();
            return exchangeExec.replaceChild(queryExec.withSorts(sorts).withLimit(limit));
        }
    }

    record PushableQueryExec(EsQueryExec queryExec) implements Pushable {
        public PhysicalPlan rewrite(TopNExec topNExec) {
            var sorts = buildFieldSorts(topNExec.order());
            var limit = topNExec.limit();
            return queryExec.withSorts(sorts).withLimit(limit);
        }
    }

    record PushableGeoDistance(FieldAttribute fieldAttribute, Order order, Point point) {
        private EsQueryExec.Sort sort() {
            return new EsQueryExec.GeoDistanceSort(fieldAttribute.exactAttribute(), order.direction(), point.getLat(), point.getLon());
        }

        private static PushableGeoDistance from(StDistance distance, Order order, Map<NameId, FieldAttribute> refAttributes) {
            if (distance.left() instanceof Attribute attr && distance.right().foldable()) {
                return from(attr, distance.right(), order, refAttributes);
            } else if (distance.right() instanceof Attribute attr && distance.left().foldable()) {
                return from(attr, distance.left(), order, refAttributes);
            }
            return null;
        }

        private static PushableGeoDistance from(
            Attribute attr,
            Expression literal,
            Order order,
            Map<NameId, FieldAttribute> refAttributes
        ) {
            FieldAttribute fieldAttribute = fieldAttribute(attr, refAttributes);
            if (fieldAttribute != null) {
                Geometry geometry = SpatialRelatesUtils.makeGeometryFromLiteral(literal);
                if (geometry instanceof Point point) {
                    return new PushableGeoDistance(fieldAttribute, order, point);
                }
            }
            return null;
        }

        private static FieldAttribute fieldAttribute(Attribute attr, Map<NameId, FieldAttribute> refAttributes) {
            if (attr instanceof FieldAttribute fieldAttribute) {
                return fieldAttribute;
            } else if (attr instanceof ReferenceAttribute ref && refAttributes.containsKey(ref.id())) {
                return refAttributes.get(ref.id());
            }
            return null;
        }
    }

    record PushableCompoundExec(EvalExec evalExec, EsQueryExec queryExec, List<EsQueryExec.Sort> pushableSorts) implements Pushable {
        public PhysicalPlan rewrite(TopNExec topNExec) {
            // We need to keep the EVAL in place because the coordinator will have its own TopNExec so we need to keep the distance
            return evalExec.replaceChild(queryExec.withSorts(pushableSorts).withLimit(topNExec.limit()));
        }
    }

    private static Pushable evaluatePushable(TopNExec topNExec, Predicate<FieldAttribute> hasIdenticalDelegate) {
        PhysicalPlan child = topNExec.child();
        if (child instanceof EsQueryExec queryExec
            && queryExec.canPushSorts()
            && canPushDownOrders(topNExec.order(), hasIdenticalDelegate)) {
            // With the simplest case of `FROM index | SORT ...` we only allow pushing down if the sort is on a field
            return new PushableQueryExec(queryExec);
        }
        if (child instanceof ExchangeExec exchangeExec
            && exchangeExec.child() instanceof EsQueryExec queryExec
            && queryExec.canPushSorts()
            && canPushDownOrders(topNExec.order(), hasIdenticalDelegate)) {
            // When we have an exchange between the FROM and the SORT, we also only allow pushing down if the sort is on a field
            return new PushableExchangeExec(exchangeExec, queryExec);
        }
        if (child instanceof EvalExec evalExec && evalExec.child() instanceof EsQueryExec queryExec && queryExec.canPushSorts()) {
            // When we have an EVAL between the FROM and the SORT, we consider pushing down if the sort is on a field and/or
            // a distance function defined in the EVAL. We also move the EVAL to after the SORT.
            List<Order> orders = topNExec.order();
            List<Alias> fields = evalExec.fields();
            LinkedHashMap<NameId, StDistance> refs = new LinkedHashMap<>();
            LinkedHashMap<NameId, FieldAttribute> refAttributes = new LinkedHashMap<>();
            fields.forEach(alias -> {
                // TODO: can we support CARTESIAN also?
                if (alias.child() instanceof StDistance distance && distance.crsType() == BinarySpatialFunction.SpatialCrsType.GEO) {
                    refs.put(alias.id(), distance);
                } else if (alias.child() instanceof FieldAttribute fieldAttribute) {
                    refAttributes.put(alias.id(), fieldAttribute);
                } else if (alias.child() instanceof ReferenceAttribute ref && refAttributes.containsKey(ref.id())) {
                    FieldAttribute fieldAttribute = refAttributes.get(ref.id());
                    refAttributes.put(alias.id(), fieldAttribute);
                }
            });
            List<EsQueryExec.Sort> pushableSorts = new ArrayList<>();
            for (Order order : orders) {
                if (LucenePushDownUtils.isPushableFieldAttribute(order.child(), hasIdenticalDelegate)) {
                    pushableSorts.add(
                        new EsQueryExec.FieldSort(
                            ((FieldAttribute) order.child()).exactAttribute(),
                            order.direction(),
                            order.nullsPosition()
                        )
                    );
                } else if (order.child() instanceof ReferenceAttribute referenceAttribute) {
                    if (refs.containsKey(referenceAttribute.id())) {
                        StDistance distance = refs.get(referenceAttribute.id());
                        PushableGeoDistance pushableGeoDistance = PushableGeoDistance.from(distance, order, refAttributes);
                        if (pushableGeoDistance != null) {
                            pushableSorts.add(pushableGeoDistance.sort());
                        } else {
                            // As soon as we see a non-pushable sort, we know we need a final SORT command
                            break;
                        }
                    } else if (refAttributes.containsKey(referenceAttribute.id())) {
                        // If the SORT refers to a reference to a pushable field, we can push it down
                        FieldAttribute fieldAttribute = refAttributes.get(referenceAttribute.id());
                        if (LucenePushDownUtils.isPushableFieldAttribute(fieldAttribute, hasIdenticalDelegate)) {
                            pushableSorts.add(
                                new EsQueryExec.FieldSort(fieldAttribute.exactAttribute(), order.direction(), order.nullsPosition())
                            );
                        }
                    } else {
                        // If the SORT refers to a non-pushable reference function, the EVAL must remain before the SORT,
                        // and we can no longer push down anything
                        break;
                    }
                } else {
                    // As soon as we see a non-pushable sort, we know we need a final SORT command
                    break;
                }
            }
            if (pushableSorts.size() > 0) {
                return new PushableCompoundExec(evalExec, queryExec, pushableSorts);
            }
        }
        return NO_OP;
    }

    private static boolean canPushDownOrders(List<Order> orders, Predicate<FieldAttribute> hasIdenticalDelegate) {
        // allow only exact FieldAttributes (no expressions) for sorting
        return orders.stream().allMatch(o -> LucenePushDownUtils.isPushableFieldAttribute(o.child(), hasIdenticalDelegate));
    }

    private static List<EsQueryExec.Sort> buildFieldSorts(List<Order> orders) {
        List<EsQueryExec.Sort> sorts = new ArrayList<>(orders.size());
        for (Order o : orders) {
            sorts.add(new EsQueryExec.FieldSort(((FieldAttribute) o.child()).exactAttribute(), o.direction(), o.nullsPosition()));
        }
        return sorts;
    }
}
