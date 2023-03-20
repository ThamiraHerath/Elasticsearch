/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.fetch;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.core.RefCounted;
import org.elasticsearch.search.SearchPhaseResult;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.ShardSearchContextId;
import org.elasticsearch.search.query.QuerySearchResult;

import java.io.IOException;

public final class QueryFetchSearchResult extends SearchPhaseResult {

    private final QuerySearchResult queryResult;
    private final FetchSearchResult fetchResult;
    private final RefCounted refCounted;

    public QueryFetchSearchResult(StreamInput in) throws IOException {
        super(in);
        // TODO: Delegate refcounting to QuerySearchResult (see https://github.com/elastic/elasticsearch/pull/94023)
        queryResult = new QuerySearchResult(in);
        fetchResult = new FetchSearchResult(in);
        refCounted = AbstractRefCounted.of(() -> {
            queryResult.decRef();
            fetchResult.decRef();
        });
    }

    public QueryFetchSearchResult(QuerySearchResult queryResult, FetchSearchResult fetchResult) {
        this.queryResult = queryResult;
        this.fetchResult = fetchResult;
        refCounted = AbstractRefCounted.of(() -> {
            queryResult.decRef();
            fetchResult.decRef();
        });
    }

    @Override
    public ShardSearchContextId getContextId() {
        return queryResult.getContextId();
    }

    @Override
    public SearchShardTarget getSearchShardTarget() {
        return queryResult.getSearchShardTarget();
    }

    @Override
    public void setSearchShardTarget(SearchShardTarget shardTarget) {
        super.setSearchShardTarget(shardTarget);
        queryResult.setSearchShardTarget(shardTarget);
        fetchResult.setSearchShardTarget(shardTarget);
    }

    @Override
    public void setShardIndex(int requestIndex) {
        super.setShardIndex(requestIndex);
        queryResult.setShardIndex(requestIndex);
        fetchResult.setShardIndex(requestIndex);
    }

    @Override
    public QuerySearchResult queryResult() {
        return queryResult;
    }

    @Override
    public FetchSearchResult fetchResult() {
        return fetchResult;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        queryResult.writeTo(out);
        fetchResult.writeTo(out);
    }

    @Override
    public void incRef() {
        refCounted.incRef();
    }

    @Override
    public boolean tryIncRef() {
        return refCounted.tryIncRef();
    }

    @Override
    public boolean decRef() {
        return refCounted.decRef();
    }

    @Override
    public boolean hasReferences() {
        return refCounted.hasReferences();
    }
}
