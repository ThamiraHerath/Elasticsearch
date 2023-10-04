/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ml.inference.ingest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.ConfigurationUtils;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.core.ml.MlConfigVersion;
import org.elasticsearch.xpack.core.ml.action.InferModelAction;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ClassificationConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ClassificationConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.EmptyConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.FillMaskConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.FillMaskConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.NerConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.NerConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.NlpConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.PassThroughConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.PassThroughConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.QuestionAnsweringConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.QuestionAnsweringConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.RegressionConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.RegressionConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextClassificationConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextClassificationConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextEmbeddingConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextEmbeddingConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextExpansionConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextExpansionConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextSimilarityConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextSimilarityConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ZeroShotClassificationConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ZeroShotClassificationConfigUpdate;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.inference.loadingservice.LocalModel;
import org.elasticsearch.xpack.ml.notifications.InferenceAuditor;
import org.elasticsearch.xpack.ml.utils.InferenceProcessorInfoExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.elasticsearch.ingest.ConfigurationUtils.newConfigurationException;
import static org.elasticsearch.ingest.IngestDocument.INGEST_KEY;
import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

public class InferenceProcessor extends AbstractProcessor {

    // How many total inference processors are allowed to be used in the cluster.
    public static final Setting<Integer> MAX_INFERENCE_PROCESSORS = Setting.intSetting(
        "xpack.ml.max_inference_processors",
        50,
        1,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final String TYPE = "inference";
    public static final String MODEL_ID = "model_id";
    public static final String INFERENCE_CONFIG = "inference_config";

    // target field style mappings
    public static final String TARGET_FIELD = "target_field";
    public static final String FIELD_MAPPINGS = "field_mappings";
    public static final String FIELD_MAP = "field_map";
    private static final String DEFAULT_TARGET_FIELD = "ml.inference";

    // input field config
    public static final String INPUT_OUTPUT = "input_output";
    public static final String INPUT_FIELD = "input_field";
    public static final String OUTPUT_FIELD = "output_field";

    public static InferenceProcessor fromInputFieldConfiguration(
        Client client,
        InferenceAuditor auditor,
        String tag,
        String description,
        String modelId,
        InferenceConfigUpdate inferenceConfig,
        List<Factory.InputConfig> inputs
    ) {
        return new InferenceProcessor(client, auditor, tag, description, null, modelId, inferenceConfig, null, inputs, true);
    }

    public static InferenceProcessor fromTargetFieldConfiguration(
        Client client,
        InferenceAuditor auditor,
        String tag,
        String description,
        String targetField,
        String modelId,
        InferenceConfigUpdate inferenceConfig,
        Map<String, String> fieldMap
    ) {
        return new InferenceProcessor(client, auditor, tag, description, targetField, modelId, inferenceConfig, fieldMap, null, false);
    }

    private final Client client;
    private final String modelId;
    private final String targetField;
    private final InferenceConfigUpdate inferenceConfig;
    private final Map<String, String> fieldMap;
    private final InferenceAuditor auditor;
    private volatile boolean previouslyLicensed;
    private final AtomicBoolean shouldAudit = new AtomicBoolean(true);
    private final List<Factory.InputConfig> inputs;
    private final boolean configuredWithInputsFields;

    private InferenceProcessor(
        Client client,
        InferenceAuditor auditor,
        String tag,
        String description,
        String targetField,
        String modelId,
        InferenceConfigUpdate inferenceConfig,
        Map<String, String> fieldMap,
        List<Factory.InputConfig> inputs,
        boolean configuredWithInputsFields
    ) {
        super(tag, description);
        this.configuredWithInputsFields = configuredWithInputsFields;
        this.client = ExceptionsHelper.requireNonNull(client, "client");
        this.auditor = ExceptionsHelper.requireNonNull(auditor, "auditor");
        this.modelId = ExceptionsHelper.requireNonNull(modelId, MODEL_ID);
        this.inferenceConfig = ExceptionsHelper.requireNonNull(inferenceConfig, INFERENCE_CONFIG);

        if (configuredWithInputsFields) {
            this.inputs = ExceptionsHelper.requireNonNull(inputs, INPUT_OUTPUT);
            this.targetField = null;
            this.fieldMap = null;
        } else {
            this.inputs = null;
            this.targetField = ExceptionsHelper.requireNonNull(targetField, TARGET_FIELD);
            this.fieldMap = ExceptionsHelper.requireNonNull(fieldMap, FIELD_MAP);
        }
    }

    public String getModelId() {
        return modelId;
    }

    @Override
    public void execute(IngestDocument ingestDocument, BiConsumer<IngestDocument, Exception> handler) {

        InferModelAction.Request request;
        try {
            request = buildRequest(ingestDocument);
        } catch (ElasticsearchStatusException e) {
            handler.accept(ingestDocument, e);
            return;
        }

        executeAsyncWithOrigin(
            client,
            ML_ORIGIN,
            InferModelAction.INSTANCE,
            request,
            ActionListener.wrap(r -> handleResponse(r, ingestDocument, handler), e -> handler.accept(ingestDocument, e))
        );
    }

    void handleResponse(InferModelAction.Response response, IngestDocument ingestDocument, BiConsumer<IngestDocument, Exception> handler) {
        if (previouslyLicensed == false) {
            previouslyLicensed = true;
        }
        if (response.isLicensed() == false) {
            auditWarningAboutLicenseIfNecessary();
        }
        try {
            mutateDocument(response, ingestDocument);
            handler.accept(ingestDocument, null);
        } catch (ElasticsearchException ex) {
            handler.accept(ingestDocument, ex);
        }
    }

    InferModelAction.Request buildRequest(IngestDocument ingestDocument) {
        Map<String, Object> fields = new HashMap<>(ingestDocument.getSourceAndMetadata());
        // Add ingestMetadata as previous processors might have added metadata from which we are predicting (see: foreach processor)
        if (ingestDocument.getIngestMetadata().isEmpty() == false) {
            fields.put(INGEST_KEY, ingestDocument.getIngestMetadata());
        }

        if (configuredWithInputsFields) {
            List<String> requestInputs = new ArrayList<>();
            for (var inputFields : inputs) {
                var lookup = (String) fields.get(inputFields.inputField);
                if (lookup == null) {
                    lookup = ""; // need to send a non-null request to the same number of results back
                }
                requestInputs.add(lookup);
            }
            return InferModelAction.Request.forTextInput(modelId, inferenceConfig, requestInputs);
        } else {
            LocalModel.mapFieldsIfNecessary(fields, fieldMap);
            return InferModelAction.Request.forIngestDocs(modelId, List.of(fields), inferenceConfig, previouslyLicensed);
        }
    }

    void auditWarningAboutLicenseIfNecessary() {
        if (shouldAudit.compareAndSet(true, false)) {
            auditor.warning(
                modelId,
                "This cluster is no longer licensed to use this model in the inference ingest processor. "
                    + "Please update your license information."
            );
        }
    }

    void mutateDocument(InferModelAction.Response response, IngestDocument ingestDocument) {
        if (response.getInferenceResults().isEmpty()) {
            throw new ElasticsearchStatusException("Unexpected empty inference response", RestStatus.INTERNAL_SERVER_ERROR);
        }

        // TODO
        // The field where the model Id is written to.
        // If multiple inference processors are in the same pipeline, it is wise to tag them
        // The tag will keep default value entries from stepping on each other
        // String modelIdField = tag == null ? MODEL_ID_RESULTS_FIELD : MODEL_ID_RESULTS_FIELD + "." + tag;

        if (configuredWithInputsFields) {
            if (response.getInferenceResults().size() != inputs.size()) {
                throw new ElasticsearchStatusException(
                    "number of results [{}] does not match the number of inputs [{}]",
                    RestStatus.INTERNAL_SERVER_ERROR,
                    response.getInferenceResults().size(),
                    inputs.size()
                );
            }

            for (int i = 0; i < inputs.size(); i++) {
                InferenceResults.writeResultToField(
                    response.getInferenceResults().get(i),
                    ingestDocument,
                    inputs.get(i).outputBasePath(),
                    inputs.get(i).outputField,
                    response.getId() != null ? response.getId() : modelId,
                    i == 0
                );
            }
        } else {
            assert response.getInferenceResults().size() == 1;
            InferenceResults.writeResult(
                response.getInferenceResults().get(0),
                ingestDocument,
                targetField,
                response.getId() != null ? response.getId() : modelId
            );
        }
    }

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    boolean isConfiguredWithInputsFields() {
        return configuredWithInputsFields;
    }

    public List<Factory.InputConfig> getInputs() {
        return inputs;
    }

    Map<String, String> getFieldMap() {
        return fieldMap;
    }

    String getTargetField() {
        return targetField;
    }

    InferenceConfigUpdate getInferenceConfig() {
        return inferenceConfig;
    }

    InferenceAuditor getAuditor() {
        return auditor;
    }

    public static final class Factory implements Processor.Factory, Consumer<ClusterState> {

        private static final Logger logger = LogManager.getLogger(Factory.class);

        private final Client client;
        private final InferenceAuditor auditor;
        private volatile int currentInferenceProcessors;
        private volatile int maxIngestProcessors;
        private volatile MlConfigVersion minNodeVersion = MlConfigVersion.CURRENT;

        public Factory(Client client, ClusterService clusterService, Settings settings, boolean includeNodeInfo) {
            this.client = client;
            this.maxIngestProcessors = MAX_INFERENCE_PROCESSORS.get(settings);
            this.auditor = new InferenceAuditor(client, clusterService, includeNodeInfo);
            clusterService.getClusterSettings().addSettingsUpdateConsumer(MAX_INFERENCE_PROCESSORS, this::setMaxIngestProcessors);
        }

        @Override
        public void accept(ClusterState state) {
            minNodeVersion = MlConfigVersion.getMinMlConfigVersion(state.nodes());
            try {
                currentInferenceProcessors = InferenceProcessorInfoExtractor.countInferenceProcessors(state);
            } catch (Exception ex) {
                // We cannot throw any exception here. It might break other pipelines.
                logger.debug("failed gathering processors for pipelines", ex);
            }
        }

        @Override
        public InferenceProcessor create(
            Map<String, Processor.Factory> processorFactories,
            String tag,
            String description,
            Map<String, Object> config
        ) {
            if (this.maxIngestProcessors <= currentInferenceProcessors) {
                throw new ElasticsearchStatusException(
                    "Max number of inference processors reached, total inference processors [{}]. "
                        + "Adjust the setting [{}]: [{}] if a greater number is desired.",
                    RestStatus.CONFLICT,
                    currentInferenceProcessors,
                    MAX_INFERENCE_PROCESSORS.getKey(),
                    maxIngestProcessors
                );
            }

            String modelId = ConfigurationUtils.readStringProperty(TYPE, tag, config, MODEL_ID);

            InferenceConfigUpdate inferenceConfigUpdate;
            Map<String, Object> inferenceConfigMap = ConfigurationUtils.readOptionalMap(TYPE, tag, config, INFERENCE_CONFIG);
            if (inferenceConfigMap == null) {
                if (minNodeVersion.before(EmptyConfigUpdate.minimumSupportedVersion())) {
                    // an inference config is required when the empty update is not supported
                    throw newConfigurationException(TYPE, tag, INFERENCE_CONFIG, "required property is missing");
                }
                inferenceConfigUpdate = new EmptyConfigUpdate();
            } else {
                inferenceConfigUpdate = inferenceConfigUpdateFromMap(inferenceConfigMap);
            }

            List<Map<String, Object>> inputs = ConfigurationUtils.readOptionalList(TYPE, tag, config, INPUT_OUTPUT);
            boolean configuredWithInputFields = inputs != null;
            if (configuredWithInputFields) {
                // new style input/output configuration
                var parsedInputs = parseInputFields(tag, inputs);

                // validate incompatible settings are not present
                String targetField = ConfigurationUtils.readOptionalStringProperty(TYPE, tag, config, TARGET_FIELD);
                if (targetField != null) {
                    throw newConfigurationException(
                        TYPE,
                        tag,
                        TARGET_FIELD,
                        "option is incompatible with ["
                            + INPUT_OUTPUT
                            + "]."
                            + " Use the ["
                            + OUTPUT_FIELD
                            + "] option to specify where to write the inference results to."
                    );
                }

                if (inferenceConfigUpdate.getResultsField() != null) {
                    throw newConfigurationException(
                        TYPE,
                        tag,
                        null,
                        "The ["
                            + INFERENCE_CONFIG
                            + "."
                            + InferenceConfig.RESULTS_FIELD.getPreferredName()
                            + "] setting is incompatible with using ["
                            + INPUT_OUTPUT
                            + "]. Prefer to use the ["
                            + INPUT_OUTPUT
                            + "."
                            + OUTPUT_FIELD
                            + "] option to specify where to write the inference results to."
                    );
                }

                return fromInputFieldConfiguration(client, auditor, tag, description, modelId, inferenceConfigUpdate, parsedInputs);
            } else {
                // old style configuration with target field
                String defaultTargetField = tag == null ? DEFAULT_TARGET_FIELD : DEFAULT_TARGET_FIELD + "." + tag;
                // If multiple inference processors are in the same pipeline, it is wise to tag them
                // The tag will keep default value entries from stepping on each other
                String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, TARGET_FIELD, defaultTargetField);
                Map<String, String> fieldMap = ConfigurationUtils.readOptionalMap(TYPE, tag, config, FIELD_MAP);
                if (fieldMap == null) {
                    fieldMap = ConfigurationUtils.readOptionalMap(TYPE, tag, config, FIELD_MAPPINGS);
                    // TODO Remove in 9?.x
                    if (fieldMap != null) {
                        LoggingDeprecationHandler.INSTANCE.logRenamedField(null, () -> null, FIELD_MAPPINGS, FIELD_MAP);
                    }
                }

                if (fieldMap == null) {
                    fieldMap = Collections.emptyMap();
                }
                return fromTargetFieldConfiguration(
                    client,
                    auditor,
                    tag,
                    description,
                    targetField,
                    modelId,
                    inferenceConfigUpdate,
                    fieldMap
                );
            }
        }

        // Package private for testing
        void setMaxIngestProcessors(int maxIngestProcessors) {
            logger.trace("updating setting maxIngestProcessors from [{}] to [{}]", this.maxIngestProcessors, maxIngestProcessors);
            this.maxIngestProcessors = maxIngestProcessors;
        }

        InferenceConfigUpdate inferenceConfigUpdateFromMap(Map<String, Object> configMap) {
            ExceptionsHelper.requireNonNull(configMap, INFERENCE_CONFIG);
            if (configMap.size() != 1) {
                throw ExceptionsHelper.badRequestException(
                    "{} must be an object with one inference type mapped to an object.",
                    INFERENCE_CONFIG
                );
            }
            Object value = configMap.values().iterator().next();

            if ((value instanceof Map<?, ?>) == false) {
                throw ExceptionsHelper.badRequestException(
                    "{} must be an object with one inference type mapped to an object.",
                    INFERENCE_CONFIG
                );
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> valueMap = (Map<String, Object>) value;

            if (configMap.containsKey(ClassificationConfig.NAME.getPreferredName())) {
                checkSupportedVersion(ClassificationConfig.EMPTY_PARAMS);
                return ClassificationConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(FillMaskConfig.NAME)) {
                checkNlpSupported(FillMaskConfig.NAME);
                return FillMaskConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(NerConfig.NAME)) {
                checkNlpSupported(NerConfig.NAME);
                return NerConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(PassThroughConfig.NAME)) {
                checkNlpSupported(PassThroughConfig.NAME);
                return PassThroughConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(RegressionConfig.NAME.getPreferredName())) {
                checkSupportedVersion(RegressionConfig.EMPTY_PARAMS);
                return RegressionConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(TextClassificationConfig.NAME)) {
                checkNlpSupported(TextClassificationConfig.NAME);
                return TextClassificationConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(TextEmbeddingConfig.NAME)) {
                checkNlpSupported(TextEmbeddingConfig.NAME);
                return TextEmbeddingConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(TextExpansionConfig.NAME)) {
                checkNlpSupported(TextExpansionConfig.NAME);
                return TextExpansionConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(TextSimilarityConfig.NAME)) {
                checkNlpSupported(TextSimilarityConfig.NAME);
                return TextSimilarityConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(ZeroShotClassificationConfig.NAME)) {
                checkNlpSupported(ZeroShotClassificationConfig.NAME);
                return ZeroShotClassificationConfigUpdate.fromMap(valueMap);
            } else if (configMap.containsKey(QuestionAnsweringConfig.NAME)) {
                checkNlpSupported(QuestionAnsweringConfig.NAME);
                return QuestionAnsweringConfigUpdate.fromMap(valueMap);
            } else {
                throw ExceptionsHelper.badRequestException(
                    "unrecognized inference configuration type {}. Supported types {}",
                    configMap.keySet(),
                    List.of(
                        ClassificationConfig.NAME.getPreferredName(),
                        RegressionConfig.NAME.getPreferredName(),
                        FillMaskConfig.NAME,
                        NerConfig.NAME,
                        PassThroughConfig.NAME,
                        QuestionAnsweringConfig.NAME,
                        TextClassificationConfig.NAME,
                        TextEmbeddingConfig.NAME,
                        TextExpansionConfigUpdate.NAME,
                        TextSimilarityConfig.NAME,
                        ZeroShotClassificationConfig.NAME
                    )
                );
            }
        }

        void checkNlpSupported(String taskType) {
            if (NlpConfig.MINIMUM_NLP_SUPPORTED_VERSION.after(minNodeVersion)) {
                throw ExceptionsHelper.badRequestException(
                    Messages.getMessage(
                        Messages.INFERENCE_CONFIG_NOT_SUPPORTED_ON_VERSION,
                        taskType,
                        NlpConfig.MINIMUM_NLP_SUPPORTED_VERSION,
                        minNodeVersion
                    )
                );
            }
        }

        void checkSupportedVersion(InferenceConfig config) {
            if (config.getMinimalSupportedMlConfigVersion().after(minNodeVersion)) {
                throw ExceptionsHelper.badRequestException(
                    Messages.getMessage(
                        Messages.INFERENCE_CONFIG_NOT_SUPPORTED_ON_VERSION,
                        config.getName(),
                        config.getMinimalSupportedMlConfigVersion(),
                        minNodeVersion
                    )
                );
            }
        }

        List<InputConfig> parseInputFields(String tag, List<Map<String, Object>> inputs) {
            if (inputs.isEmpty()) {
                throw newConfigurationException(TYPE, tag, INPUT_OUTPUT, "cannot be empty at least one is required");
            }
            var inputNames = new HashSet<String>();
            var outputNames = new HashSet<String>();
            var parsedInputs = new ArrayList<InputConfig>();

            for (var input : inputs) {
                String inputField = ConfigurationUtils.readStringProperty(TYPE, tag, input, INPUT_FIELD);
                String outputField = ConfigurationUtils.readStringProperty(TYPE, tag, input, OUTPUT_FIELD);

                if (inputNames.add(inputField) == false) {
                    throw duplicatedFieldNameError(INPUT_FIELD, inputField, tag);
                }
                if (outputNames.add(outputField) == false) {
                    throw duplicatedFieldNameError(OUTPUT_FIELD, outputField, tag);
                }

                var outputPaths = extractBasePathAndFinalElement(outputField);

                if (input.isEmpty()) {
                    parsedInputs.add(new InputConfig(inputField, outputPaths.v1(), outputPaths.v2(), Map.of()));
                } else {
                    parsedInputs.add(new InputConfig(inputField, outputPaths.v1(), outputPaths.v2(), new HashMap<>(input)));
                }
            }

            return parsedInputs;
        }

        private ElasticsearchException duplicatedFieldNameError(String property, String fieldName, String tag) {
            return newConfigurationException(TYPE, tag, property, "names must be unique but [" + fieldName + "] is repeated");
        }

        /**
         * {@code outputField} can be a dot '.' seperated path of elements.
         * Extract the base path (everything before the last '.') and the final
         * element.
         * If {@code outputField} does not contain any dotted elements the base
         * path is null.
         *
         * @param outputField The path to split
         * @return Tuple of {@code <basePath, finalElement>}
         */
        static Tuple<String, String> extractBasePathAndFinalElement(String outputField) {
            int lastIndex = outputField.lastIndexOf('.');
            if (lastIndex < 0) {
                return new Tuple<>(null, outputField);
            } else {
                return new Tuple<>(outputField.substring(0, lastIndex), outputField.substring(lastIndex + 1));
            }
        }

        public record InputConfig(String inputField, String outputBasePath, String outputField, Map<String, Object> extras) {}
    }
}
