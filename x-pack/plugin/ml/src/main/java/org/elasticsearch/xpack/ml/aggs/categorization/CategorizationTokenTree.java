/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.aggs.categorization;

import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.search.aggregations.InternalAggregations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * Categorized semi-structured text utilizing the drain algorithm: https://arxiv.org/pdf/1806.04356.pdf
 * With the following key differences
 *  - This structure keeps track of the "smallest" sub-tree. So, instead of naively adding a new "*" node, the smallest sub-tree
 *    is transformed if the incoming token has a higher doc_count.
 *  - Additionally, similarities are weighted, which allows for nicer merging of existing log categories
 *  - An optional tree reduction step is available to collapse together tiny sub-trees
 *
 *
 * The main implementation is a fixed-sized prefix tree.
 * Consequently, this assumes that splits that give us more information come earlier in the text.
 *
 * Examples:
 *
 * Given log values:
 *
 * Node is online
 * Node is offline
 *
 * With a fixed tree depth of 2 we would get the following splits
 *                  3 // initial root is the number of tokens
 *                  |
 *               "Node" // first prefix node of value "Node"
 *                 |
 *               "is"
 *              /    \
 * [Node is online] [Node is offline] //the individual categories for this simple case
 *
 * If the similarityThreshold was less than 0.6, the result would be a single category [Node is *]
 *
 */
public class CategorizationTokenTree implements Accountable {

    private static final long SHALLOW_SIZE = RamUsageEstimator.shallowSizeOfInstance(CategorizationTokenTree.class);
    private final int maxMatchTokens;
    private final int maxUniqueTokens;
    private final int similarityThreshold;
    private long idGenerator;
    private final Map<Integer, TreeNode> root = new HashMap<>();
    private long sizeInBytes;

    public CategorizationTokenTree(int maxUniqueTokens, int maxMatchTokens, int similarityThreshold) {
        assert maxUniqueTokens > 0 && maxMatchTokens >= 0;
        this.maxUniqueTokens = maxUniqueTokens;
        this.maxMatchTokens = maxMatchTokens;
        this.similarityThreshold = similarityThreshold;
        this.sizeInBytes = SHALLOW_SIZE;
    }

    public List<InternalCategorizationAggregation.Bucket> toIntermediateBuckets(CategorizationBytesRefHash hash) {
        return root.values().stream().flatMap(c -> c.getAllChildrenLogGroups().stream()).map(lg -> {
            int[] categoryTokenIds = lg.getCategorization();
            BytesRef[] bytesRefs = new BytesRef[categoryTokenIds.length];
            for (int i = 0; i < categoryTokenIds.length; i++) {
                bytesRefs[i] = hash.getDeep(categoryTokenIds[i]);
            }
            InternalCategorizationAggregation.Bucket bucket = new InternalCategorizationAggregation.Bucket(
                new InternalCategorizationAggregation.BucketKey(bytesRefs),
                lg.getCount(),
                InternalAggregations.EMPTY
            );
            bucket.bucketOrd = lg.bucketOrd;
            return bucket;
        }).collect(Collectors.toList());
    }

    void mergeSmallestChildren() {
        root.values().forEach(TreeNode::collapseTinyChildren);
    }

    /**
     * This method does not mutate the underlying structure. Meaning, if a matching categories isn't found, it may return empty.
     *
     * @param logTokenIds The tokens to categorize
     * @return The log category or `Optional.empty()` if one doesn't exist
     */
    public Optional<TextCategorization> parseLogLineConst(final int[] logTokenIds) {
        TreeNode currentNode = this.root.get(logTokenIds.length);
        if (currentNode == null) { // we are missing an entire sub tree. New log length found
            return Optional.empty();
        }
        return Optional.ofNullable(currentNode.getLogGroup(logTokenIds));
    }

    /**
     * This categorizes the passed tokens, potentially mutating the structure by expanding an existing category or adding a new one.
     * @param logTokenIds The log tokens to categorize
     * @param docCount The count of docs for the given tokens
     * @return An existing categorization or a newly created one
     */
    public TextCategorization parseLogLine(final int[] logTokenIds, long docCount) {
        TreeNode currentNode = this.root.get(logTokenIds.length);
        if (currentNode == null) { // we are missing an entire sub tree. New log length found
            currentNode = newNode(docCount, 0, logTokenIds);
            this.root.put(logTokenIds.length, currentNode);
        } else {
            currentNode.incCount(docCount);
        }
        return currentNode.addLog(logTokenIds, docCount, this);
    }

    TreeNode newNode(long docCount, int tokenPos, int[] logTokenIds) {
        TreeNode node = tokenPos < maxMatchTokens - 1 && tokenPos < logTokenIds.length
            ? new TreeNode.InnerTreeNode(docCount, tokenPos, maxUniqueTokens)
            : new TreeNode.LeafTreeNode(docCount, similarityThreshold);
        // The size of the node + entry (since it is a map entry) + extra reference for priority queue
        sizeInBytes += node.ramBytesUsed() + RamUsageEstimator.HASHTABLE_RAM_BYTES_PER_ENTRY + RamUsageEstimator.NUM_BYTES_OBJECT_REF;
        return node;
    }

    TextCategorization newGroup(long docCount, int[] logTokenIds) {
        TextCategorization group = new TextCategorization(logTokenIds, docCount, idGenerator++);
        // Get the regular size bytes from the LogGroup and how much it costs to reference it
        sizeInBytes += group.ramBytesUsed() + RamUsageEstimator.NUM_BYTES_OBJECT_REF;
        return group;
    }

    @Override
    public long ramBytesUsed() {
        return sizeInBytes;
    }

}
