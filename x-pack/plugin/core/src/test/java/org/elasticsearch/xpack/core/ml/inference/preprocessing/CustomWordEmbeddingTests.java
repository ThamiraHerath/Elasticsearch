/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.inference.preprocessing;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.core.ml.inference.ModelFieldType;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.equalTo;


public class CustomWordEmbeddingTests extends PreProcessingTests<CustomWordEmbedding> {

    @Override
    protected CustomWordEmbedding doParseInstance(XContentParser parser) throws IOException {
        return lenient ? CustomWordEmbedding.fromXContentLenient(parser) : CustomWordEmbedding.fromXContentStrict(parser);
    }

    @Override
    protected CustomWordEmbedding createTestInstance() {
        return createRandom();
    }

    public static CustomWordEmbedding createRandom() {
        int quantileSize = randomIntBetween(1, 10);
        int internalQuantSize = randomIntBetween(1, 10);
        short[][] quantiles = new short[quantileSize][internalQuantSize];
        for (int i = 0; i < quantileSize; i++) {
            for (int j = 0; j < internalQuantSize; j++) {
                quantiles[i][j] = randomShort();
            }
        }
        int weightsSize = randomIntBetween(1, 10);
        int internalWeightsSize = randomIntBetween(1, 10);
        byte[][] weights = new byte[weightsSize][internalWeightsSize];
        for (int i = 0; i < weightsSize; i++) {
            for (int j = 0; j < internalWeightsSize; j++) {
                weights[i][j] = randomByte();
            }
        }
        return new CustomWordEmbedding(quantiles, weights, randomAlphaOfLength(10), randomAlphaOfLength(10));
    }

    public void testInputFieldValue() {
        CustomWordEmbedding processor = createRandom();
        assertThat(processor.inputType(processor.getFieldName()), equalTo(ModelFieldType.TEXT));
    }

    @Override
    protected Writeable.Reader<CustomWordEmbedding> instanceReader() {
        return CustomWordEmbedding::new;
    }

}
