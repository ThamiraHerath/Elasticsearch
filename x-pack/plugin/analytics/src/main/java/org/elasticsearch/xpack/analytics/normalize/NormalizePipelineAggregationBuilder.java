/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.analytics.normalize;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.pipeline.AbstractPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.BucketMetricsParser;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;
import static org.elasticsearch.search.aggregations.pipeline.PipelineAggregator.Parser.FORMAT;
import static org.elasticsearch.xpack.analytics.normalize.NormalizePipelineNormalizer.Mean;
import static org.elasticsearch.xpack.analytics.normalize.NormalizePipelineNormalizer.Percent;
import static org.elasticsearch.xpack.analytics.normalize.NormalizePipelineNormalizer.RescaleZeroToOne;
import static org.elasticsearch.xpack.analytics.normalize.NormalizePipelineNormalizer.RescaleZeroToOneHundred;
import static org.elasticsearch.xpack.analytics.normalize.NormalizePipelineNormalizer.Softmax;
import static org.elasticsearch.xpack.analytics.normalize.NormalizePipelineNormalizer.ZScore;

public class NormalizePipelineAggregationBuilder extends AbstractPipelineAggregationBuilder<NormalizePipelineAggregationBuilder> {
    public static final String NAME = "normalize";
    static final ParseField NORMALIZER_FIELD = new ParseField("normalizer");

    @SuppressWarnings("unchecked")
    public static final ConstructingObjectParser<NormalizePipelineAggregationBuilder, String> PARSER = new ConstructingObjectParser<>(
        NAME, false, (args, name) -> new NormalizePipelineAggregationBuilder(name, (String) args[0],
        (String) args[1], (List<String>) args[2]));

    static {
        PARSER.declareString(optionalConstructorArg(), FORMAT);
        PARSER.declareString(constructorArg(), NORMALIZER_FIELD);
        PARSER.declareStringArray(constructorArg(), BUCKETS_PATH_FIELD);
    }

    static final Map<String, Function<List<Double>, NormalizePipelineNormalizer>> NAME_MAP = Map.of(
        RescaleZeroToOne.NAME, RescaleZeroToOne::new,
        RescaleZeroToOneHundred.NAME, RescaleZeroToOneHundred::new,
        Mean.NAME, Mean::new,
        ZScore.NAME, ZScore::new,
        Percent.NAME, Percent::new,
        Softmax.NAME, Softmax::new
    );

    static String validateNormalizerName(String name) {
        if (NAME_MAP.containsKey(name)) {
            return name;
        }

        throw new IllegalArgumentException("invalid normalizer [" + name + "]");
    }

    private final String format;
    private final String normalizer;


    NormalizePipelineAggregationBuilder(String name, String format, String normalizer, List<String> bucketsPath) {
        super(name, NAME, bucketsPath.toArray(new String[0]));
        this.format = format;
        this.normalizer = validateNormalizerName(normalizer);
    }

    NormalizePipelineAggregationBuilder(String name, String format, String normalizer, String bucketsPath) {
        super(name, NAME, new String[] { bucketsPath });
        this.format = format;
        this.normalizer = validateNormalizerName(normalizer);
    }

    /**
     * Read from a stream.
     */
    public NormalizePipelineAggregationBuilder(StreamInput in) throws IOException {
        super(in, NAME);
        format = in.readOptionalString();
        normalizer = in.readString();
    }

    @Override
    protected final void doWriteTo(StreamOutput out) throws IOException {
        out.writeOptionalString(format);
        out.writeString(normalizer);
    }

    /**
     * Gets the format to use on the output of this aggregation.
     */
    public String format() {
        return format;
    }

    protected DocValueFormat formatter() {
        if (format != null) {
            return new DocValueFormat.Decimal(format);
        } else {
            return DocValueFormat.RAW;
        }
    }

    @Override
    protected PipelineAggregator createInternal(Map<String, Object> metadata) {
        return new NormalizePipelineAggregator(name, bucketsPaths, formatter(), NAME_MAP.get(normalizer), metadata);
    }

    @Override
    protected void validate(ValidationContext context) {
        if (bucketsPaths.length != 1) {
            context.addBucketPathValidationError("must contain a single entry for aggregation [" + name + "]");
        }
        context.validateParentAggSequentiallyOrdered(NAME, name);
    }

    @Override
    protected final XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
        if (format != null) {
            builder.field(BucketMetricsParser.FORMAT.getPreferredName(), format);
        }
        builder.field(NORMALIZER_FIELD.getPreferredName(), normalizer);
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), format);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (super.equals(obj) == false) return false;
        NormalizePipelineAggregationBuilder other = (NormalizePipelineAggregationBuilder) obj;
        return Objects.equals(format, other.format);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }
}
