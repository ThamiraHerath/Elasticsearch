/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.operator.ColumnExtractOperator;
import org.elasticsearch.grok.Grok;
import org.elasticsearch.grok.GrokCaptureConfig;
import org.elasticsearch.grok.GrokCaptureExtracter;
import org.joni.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class GrokEvaluatorExtracter implements ColumnExtractOperator.Evaluator, GrokCaptureExtracter {

    private final Grok parser;
    private final String pattern;

    private final List<GrokCaptureExtracter> fieldExtracters;

    private final boolean[] valuesSet;
    private final Object[] firstValues;
    private final ElementType[] positionToType;
    private Block.Builder[] blocks;

    public GrokEvaluatorExtracter(
        final Grok parser,
        final String pattern,
        final Map<String, Integer> keyToBlock,
        final Map<String, ElementType> types
    ) {
        this.parser = parser;
        this.pattern = pattern;
        this.valuesSet = new boolean[types.size()];
        this.firstValues = new Object[types.size()];
        this.positionToType = new ElementType[types.size()];

        fieldExtracters = new ArrayList<>(parser.captureConfig().size());
        for (GrokCaptureConfig config : parser.captureConfig()) {
            var key = config.name();
            ElementType type = types.get(key);
            Integer blockIdx = keyToBlock.get(key);
            positionToType[blockIdx] = type;

            fieldExtracters.add(config.objectExtracter(value -> {
                if (firstValues[blockIdx] == null) {
                    firstValues[blockIdx] = value;
                } else {
                    Block.Builder block = blocks()[blockIdx];
                    if (valuesSet[blockIdx] == false) {
                        block.beginPositionEntry();
                        append(firstValues[blockIdx], block, type);
                        valuesSet[blockIdx] = true;
                    }
                    append(value, block, type);
                }
            }));
        }

    }

    private static void append(Object value, Block.Builder block, ElementType type) {
        if (value instanceof Float f) {
            // Grok patterns can produce float values (Eg. %{WORD:x:float})
            // Since ESQL does not support floats natively, but promotes them to Double, we are doing promotion here
            // TODO remove when floats are supported
            ((DoubleBlock.Builder) block).appendDouble(f.doubleValue());
        } else {
            BlockUtils.appendValue(block, value, type);
        }
    }

    public Block.Builder[] blocks() {
        return blocks;
    }

    @Override
    public void computeRow(BytesRefBlock inputBlock, int row, Block.Builder[] blocks, BytesRef spare) {
        this.blocks = blocks;
        int position = inputBlock.getFirstValueIndex(row);
        int valueCount = inputBlock.getValueCount(row);
        Arrays.fill(valuesSet, false);
        Arrays.fill(firstValues, null);
        for (int c = 0; c < valueCount; c++) {
            BytesRef input = inputBlock.getBytesRef(position + c, spare);
            parser.match(input.bytes, input.offset, input.length, this);
        }
        for (int i = 0; i < firstValues.length; i++) {
            if (firstValues[i] == null) {
                this.blocks[i].appendNull();
            } else if (valuesSet[i]) {
                this.blocks[i].endPositionEntry();
            } else {
                append(firstValues[i], blocks[i], positionToType[i]);
            }
        }
    }

    @Override
    public void extract(byte[] utf8Bytes, int offset, Region region) {
        fieldExtracters.forEach(extracter -> extracter.extract(utf8Bytes, offset, region));
    }

    @Override
    public String toString() {
        return "GrokEvaluatorExtracter[pattern=" + pattern + "]";
    }
}
