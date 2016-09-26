/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.pipeline.bucketscript;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.Script.ExecutableScriptBinding;
import org.elasticsearch.script.Script.ScriptInput;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.AggregationExecutionException;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregation.ReduceContext;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.InternalMultiBucketAggregation;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation.Bucket;
import org.elasticsearch.search.aggregations.pipeline.BucketHelpers.GapPolicy;
import org.elasticsearch.search.aggregations.pipeline.InternalSimpleValue;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.elasticsearch.search.aggregations.pipeline.BucketHelpers.resolveBucketValue;

public class BucketScriptPipelineAggregator extends PipelineAggregator {
    private final DocValueFormat formatter;
    private final GapPolicy gapPolicy;
    private final ScriptInput script;
    private final Map<String, String> bucketsPathsMap;

    public BucketScriptPipelineAggregator(String name, Map<String, String> bucketsPathsMap, ScriptInput script, DocValueFormat formatter,
            GapPolicy gapPolicy, Map<String, Object> metadata) {
        super(name, bucketsPathsMap.values().toArray(new String[bucketsPathsMap.size()]), metadata);
        this.bucketsPathsMap = bucketsPathsMap;
        this.script = script;
        this.formatter = formatter;
        this.gapPolicy = gapPolicy;
    }

    /**
     * Read from a stream.
     */
    @SuppressWarnings("unchecked")
    public BucketScriptPipelineAggregator(StreamInput in) throws IOException {
        super(in);
        script = ScriptInput.readFrom(in);
        formatter = in.readNamedWriteable(DocValueFormat.class);
        gapPolicy = GapPolicy.readFrom(in);
        bucketsPathsMap = (Map<String, String>) in.readGenericValue();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        script.writeTo(out);
        out.writeNamedWriteable(formatter);
        gapPolicy.writeTo(out);
        out.writeGenericValue(bucketsPathsMap);
    }

    @Override
    public String getWriteableName() {
        return BucketScriptPipelineAggregationBuilder.NAME;
    }

    @Override
    public InternalAggregation reduce(InternalAggregation aggregation, ReduceContext reduceContext) {
        InternalMultiBucketAggregation<InternalMultiBucketAggregation, InternalMultiBucketAggregation.InternalBucket> originalAgg = (InternalMultiBucketAggregation<InternalMultiBucketAggregation, InternalMultiBucketAggregation.InternalBucket>) aggregation;
        List<? extends Bucket> buckets = originalAgg.getBuckets();

        CompiledScript compiledScript =
            script.lookup.getCompiled(reduceContext.scriptService(), ScriptContext.Standard.AGGS, ExecutableScriptBinding.BINDING);
        List newBuckets = new ArrayList<>();
        for (Bucket bucket : buckets) {
            Map<String, Object> vars = new HashMap<>();
            if (script.params != null) {
                vars.putAll(script.params);
            }
            boolean skipBucket = false;
            for (Map.Entry<String, String> entry : bucketsPathsMap.entrySet()) {
                String varName = entry.getKey();
                String bucketsPath = entry.getValue();
                Double value = resolveBucketValue(originalAgg, bucket, bucketsPath, gapPolicy);
                if (GapPolicy.SKIP == gapPolicy && (value == null || Double.isNaN(value))) {
                    skipBucket = true;
                    break;
                }
                vars.put(varName, value);
            }
            if (skipBucket) {
                newBuckets.add(bucket);
            } else {
                ExecutableScript executableScript = ExecutableScriptBinding.bind(compiledScript, vars);
                Object returned = executableScript.run();
                if (returned == null) {
                    newBuckets.add(bucket);
                } else {
                    if (!(returned instanceof Number)) {
                        throw new AggregationExecutionException("series_arithmetic script for reducer [" + name()
                                + "] must return a Number");
                    }
                    final List<InternalAggregation> aggs = StreamSupport.stream(bucket.getAggregations().spliterator(), false).map((p) -> {
                        return (InternalAggregation) p;
                    }).collect(Collectors.toList());
                    aggs.add(new InternalSimpleValue(name(), ((Number) returned).doubleValue(), formatter,
                            new ArrayList<>(), metaData()));
                    InternalMultiBucketAggregation.InternalBucket newBucket = originalAgg.createBucket(new InternalAggregations(aggs),
                            (InternalMultiBucketAggregation.InternalBucket) bucket);
                    newBuckets.add(newBucket);
                }
            }
        }
        return originalAgg.create(newBuckets);
    }
}
