/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.engine;

import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.mapper.MapperRegistry;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public class TranslogHandler implements Engine.TranslogRecoveryRunner {

    private final MapperService mapperService;

    private final AtomicLong appliedOperations = new AtomicLong();

    long appliedOperations() {
        return appliedOperations.get();
    }

    public TranslogHandler(NamedXContentRegistry xContentRegistry, IndexSettings indexSettings) {
        SimilarityService similarityService = new SimilarityService(indexSettings, null, emptyMap());
        MapperRegistry mapperRegistry = new IndicesModule(emptyList()).getMapperRegistry();
        mapperService = new MapperService(
            indexSettings,
            (type, name) -> Lucene.STANDARD_ANALYZER,
            XContentParserConfiguration.EMPTY.withRegistry(xContentRegistry).withDeprecationHandler(LoggingDeprecationHandler.INSTANCE),
            similarityService,
            mapperRegistry,
            () -> null,
            indexSettings.getMode().idFieldMapperWithoutFieldData(),
            null
        );
    }

    private void applyOperation(Engine engine, Engine.Operation operation) throws IOException {
        switch (operation.operationType()) {
            case INDEX -> engine.index((Engine.Index) operation);
            case DELETE -> engine.delete((Engine.Delete) operation);
            case NO_OP -> engine.noOp((Engine.NoOp) operation);
            default -> throw new IllegalStateException("No operation defined for [" + operation + "]");
        }
    }

    @Override
    public int run(Engine engine, Translog.Snapshot snapshot) throws IOException {
        int opsRecovered = 0;
        Translog.Operation operation;
        while ((operation = snapshot.next()) != null) {
            applyOperation(engine, convertToEngineOp(operation, Engine.Operation.Origin.LOCAL_TRANSLOG_RECOVERY));
            opsRecovered++;
            appliedOperations.incrementAndGet();
        }
        engine.syncTranslog();
        return opsRecovered;
    }

    public Engine.Operation convertToEngineOp(Translog.Operation operation, Engine.Operation.Origin origin) {
        // If a translog op is replayed on the primary (eg. ccr), we need to use external instead of null for its version type.
        final VersionType versionType = (origin == Engine.Operation.Origin.PRIMARY) ? VersionType.EXTERNAL : null;
        switch (operation.opType()) {
            case INDEX -> {
                final Translog.Index index = (Translog.Index) operation;
                final Engine.Index engineIndex = IndexShard.prepareIndex(
                    mapperService,
                    new SourceToParse(index.id(), index.source(), XContentHelper.xContentType(index.source()), index.routing(), Map.of()),
                    index.seqNo(),
                    index.primaryTerm(),
                    index.version(),
                    versionType,
                    origin,
                    index.getAutoGeneratedIdTimestamp(),
                    true,
                    SequenceNumbers.UNASSIGNED_SEQ_NO,
                    SequenceNumbers.UNASSIGNED_PRIMARY_TERM,
                    System.nanoTime()
                );
                return engineIndex;
            }
            case DELETE -> {
                final Translog.Delete delete = (Translog.Delete) operation;
                return IndexShard.prepareDelete(
                    delete.id(),
                    delete.seqNo(),
                    delete.primaryTerm(),
                    delete.version(),
                    versionType,
                    origin,
                    SequenceNumbers.UNASSIGNED_SEQ_NO,
                    SequenceNumbers.UNASSIGNED_PRIMARY_TERM
                );
            }
            case NO_OP -> {
                final Translog.NoOp noOp = (Translog.NoOp) operation;
                final Engine.NoOp engineNoOp = new Engine.NoOp(noOp.seqNo(), noOp.primaryTerm(), origin, System.nanoTime(), noOp.reason());
                return engineNoOp;
            }
            default -> throw new IllegalStateException("No operation defined for [" + operation + "]");
        }
    }

}
