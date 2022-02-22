/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.cluster.Diff;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.SimpleDiffableSerializationTestCase;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import static org.elasticsearch.cluster.metadata.DesiredNodesTestCase.randomDesiredNodes;

public class DesiredNodesMetadataSerializationTests extends SimpleDiffableSerializationTestCase<Metadata.Custom> {
    @Override
    protected Metadata.Custom makeTestChanges(Metadata.Custom testInstance) {
        if (randomBoolean()) {
            return testInstance;
        }
        return mutate((DesiredNodesMetadata) testInstance);
    }

    @Override
    protected Writeable.Reader<Diff<Metadata.Custom>> diffReader() {
        return DesiredNodesMetadata::readDiffFrom;
    }

    @Override
    protected Metadata.Custom doParseInstance(XContentParser parser) throws IOException {
        return DesiredNodesMetadata.fromXContent(parser);
    }

    @Override
    protected Writeable.Reader<Metadata.Custom> instanceReader() {
        return DesiredNodesMetadata::readFrom;
    }

    @Override
    protected NamedWriteableRegistry getNamedWriteableRegistry() {
        return new NamedWriteableRegistry(
            Collections.singletonList(
                new NamedWriteableRegistry.Entry(Metadata.Custom.class, DesiredNodesMetadata.TYPE, DesiredNodesMetadata::readFrom)
            )
        );
    }

    @Override
    protected Metadata.Custom createTestInstance() {
        return randomDesiredNodesMetadata();
    }

    public static DesiredNodesMetadata randomDesiredNodesMetadata() {
        final var desiredNodes = randomDesiredNodes();

        return DesiredNodesMetadata.create(desiredNodes, Set.copyOf(randomSubsetOf(desiredNodes.nodes())));
    }

    private DesiredNodesMetadata mutate(DesiredNodesMetadata base) {
        // new historyID
        if (randomBoolean()) {
            return randomDesiredNodesMetadata();
        }
        final var latestDesiredNodes = base.getLatestDesiredNodes();
        final var mutatedDesiredNodes = new DesiredNodes(
            latestDesiredNodes.historyID(),
            latestDesiredNodes.version() + 1,
            randomList(1, 10, DesiredNodesTestCase::randomDesiredNodeWithRandomSettings)
        );
        return DesiredNodesMetadata.create(mutatedDesiredNodes, Set.copyOf(randomSubsetOf(mutatedDesiredNodes.nodes())));
    }
}
