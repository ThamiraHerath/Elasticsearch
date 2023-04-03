/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.rank.rrf;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.rank.RankBuilder;
import org.elasticsearch.search.rank.RankDoc;
import org.elasticsearch.search.rank.RankShardResult;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;

import java.util.List;

public class RankRRFPlugin extends Plugin {

    public static final String NAME = "rrf";

    @Override
    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return List.of(
            new NamedWriteableRegistry.Entry(RankBuilder.class, NAME, RRFRankBuilder::new),
            new NamedWriteableRegistry.Entry(RankShardResult.class, NAME, RRFRankShardResult::new),
            new NamedWriteableRegistry.Entry(RankDoc.class, NAME, RRFRankDoc::new)
        );
    }

    @Override
    public List<NamedXContentRegistry.Entry> getNamedXContent() {
        return List.of(
            new NamedXContentRegistry.Entry(
                RankBuilder.class,
                new ParseField(NAME),
                RRFRankBuilder::fromXContent
            )
        );
    }
}
