/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.inference.InferenceAction;
import org.elasticsearch.action.support.RefCountingRunnable;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.index.mapper.SemanticTextFieldMapper;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.plugins.internal.DocumentParsingObserver;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

public class FieldInferenceBulkRequestPreprocessor extends AbstractBulkRequestPreprocessor {

    public static final String SEMANTIC_TEXT_ORIGIN = "semantic_text";

    private final OriginSettingClient client;

    public FieldInferenceBulkRequestPreprocessor(Supplier<DocumentParsingObserver> documentParsingObserver, Client client) {
        super(documentParsingObserver);
        this.client = new OriginSettingClient(client, SEMANTIC_TEXT_ORIGIN);
    }

    protected void processIndexRequest(
        IndexRequest indexRequest,
        int slot,
        RefCountingRunnable refs,
        IntConsumer onDropped,
        final BiConsumer<Integer, Exception> onFailure
    ) {
        assert indexRequest.isFieldInferenceDone() == false;

        String index = indexRequest.index();
        Map<String, Object> sourceMap = indexRequest.sourceAsMap();
        sourceMap.entrySet().stream().filter(entry -> fieldNeedsInference(index, entry.getKey(), entry.getValue())).forEach(entry -> {
            runInferenceForField(indexRequest, entry.getKey(), refs, slot, onFailure);
        });
    }

    @Override
    public boolean needsProcessing(DocWriteRequest<?> docWriteRequest, IndexRequest indexRequest, Metadata metadata) {
        return (indexRequest.isFieldInferenceDone() == false)
            && indexRequest.sourceAsMap()
                .entrySet()
                .stream()
                .anyMatch(entry -> fieldNeedsInference(indexRequest.index(), entry.getKey(), entry.getValue()));
    }

    @Override
    public boolean hasBeenProcessed(IndexRequest indexRequest) {
        return indexRequest.isFieldInferenceDone();
    }

    @Override
    public boolean shouldExecuteOnIngestNode() {
        return false;
    }

    private boolean fieldNeedsInference(String index, String fieldName, Object fieldValue) {
        // TODO actual mapping check here
        return fieldName.startsWith("infer_")
            // We want to perform inference when we don't have already calculated it
            && (fieldValue instanceof String);
    }

    private void runInferenceForField(
        IndexRequest indexRequest,
        String fieldName,
        RefCountingRunnable refs,
        int position,
        BiConsumer<Integer, Exception> onFailure
    ) {
        var ingestDocument = newIngestDocument(indexRequest);
        if (ingestDocument.hasField(fieldName) == false) {
            return;
        }

        refs.acquire();

        // TODO Hardcoding model ID and task type
        final String fieldValue = ingestDocument.getFieldValue(fieldName, String.class);
        InferenceAction.Request inferenceRequest = new InferenceAction.Request(
            TaskType.SPARSE_EMBEDDING,
            "my-elser-model",
            fieldValue,
            Map.of()
        );

        final long startTimeInNanos = System.nanoTime();
        ingestMetric.preIngest();
        client.execute(InferenceAction.INSTANCE, inferenceRequest, ActionListener.runAfter(new ActionListener<InferenceAction.Response>() {
            @Override
            public void onResponse(InferenceAction.Response response) {
                // Transform into two subfields, one with the actual text and other with the inference
                Map<String, Object> newFieldValue = new HashMap<>();
                newFieldValue.put(SemanticTextFieldMapper.TEXT_SUBFIELD_NAME, fieldValue);
                newFieldValue.put(
                    SemanticTextFieldMapper.SPARSE_VECTOR_SUBFIELD_NAME,
                    response.getResult().asMap(fieldName).get(fieldName)
                );
                ingestDocument.setFieldValue(fieldName, newFieldValue);

                updateIndexRequestSource(indexRequest, ingestDocument);
            }

            @Override
            public void onFailure(Exception e) {
                // Wrap exception in an illegal argument exception, as there is a problem with the model or model config
                onFailure.accept(
                    position,
                    new IllegalArgumentException("Error performing inference for field [" + fieldName + "]: " + e.getMessage(), e)
                );
                ingestMetric.ingestFailed();
            }
        }, () -> {
            // regardless of success or failure, we always stop the ingest "stopwatch" and release the ref to indicate
            // that we're finished with this document
            indexRequest.isFieldInferenceDone(true);
            final long ingestTimeInNanos = System.nanoTime() - startTimeInNanos;
            ingestMetric.postIngest(ingestTimeInNanos);
            refs.close();
        }));
    }
}
