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

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.commongrams.CommonGramsFilter;
import org.apache.lucene.analysis.core.StopAnalyzer;
import org.apache.lucene.analysis.core.UpperCaseFilter;
import org.apache.lucene.analysis.en.KStemFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.LengthFilter;
import org.apache.lucene.analysis.miscellaneous.TrimFilter;
import org.apache.lucene.analysis.miscellaneous.TruncateTokenFilter;
import org.apache.lucene.analysis.miscellaneous.UniqueTokenFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.reverse.ReverseStringFilter;
import org.apache.lucene.analysis.standard.ClassicFilter;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.indices.analysis.PreBuiltCacheFactory.CachingStrategy;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

import java.util.Map;
import java.util.TreeMap;

public class CommonAnalysisPlugin extends Plugin implements AnalysisPlugin {
    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        Map<String, AnalysisProvider<TokenFilterFactory>> filters = new TreeMap<>();
        filters.put("asciifolding", ASCIIFoldingTokenFilterFactory::new);
        filters.put("word_delimiter", WordDelimiterTokenFilterFactory::new);
        filters.put("word_delimiter_graph", WordDelimiterGraphTokenFilterFactory::new);
        return filters;
    }

    @Override
    public Map<String, PreBuiltTokenFilterSpec> getPreBuiltTokenFilters() {
        // TODO we should revisit the caching strategies.
        Map<String, PreBuiltTokenFilterSpec> filters = new TreeMap<>();
        filters.put("asciifolding", new PreBuiltTokenFilterSpec(true, CachingStrategy.ONE, (input, version) ->
                new ASCIIFoldingFilter(input)));
        filters.put("classic", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new ClassicFilter(input)));
        filters.put("common_grams", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new CommonGramsFilter(input, CharArraySet.EMPTY_SET)));
        filters.put("edge_ngram", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new EdgeNGramTokenFilter(input, EdgeNGramTokenFilter.DEFAULT_MIN_GRAM_SIZE, EdgeNGramTokenFilter.DEFAULT_MAX_GRAM_SIZE)));
        // TODO deprecate edgeNGram
        filters.put("edgeNGram", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new EdgeNGramTokenFilter(input, EdgeNGramTokenFilter.DEFAULT_MIN_GRAM_SIZE, EdgeNGramTokenFilter.DEFAULT_MAX_GRAM_SIZE)));
        filters.put("kstem", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new KStemFilter(input)));
        filters.put("length", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new LengthFilter(input, 0, Integer.MAX_VALUE)));
        filters.put("ngram", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new NGramTokenFilter(input)));
        // TODO deprecate nGram
        filters.put("nGram", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new NGramTokenFilter(input)));
        filters.put("porter_stem", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new PorterStemFilter(input)));
        filters.put("reverse", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new ReverseStringFilter(input)));
        // The stop filter is in lucene-core but the English stop words set is in lucene-analyzers-common
        filters.put("stop", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new StopFilter(input, StopAnalyzer.ENGLISH_STOP_WORDS_SET)));
        filters.put("trim", new PreBuiltTokenFilterSpec(false, CachingStrategy.LUCENE, (input, version) ->
                new TrimFilter(input)));
        filters.put("truncate", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new TruncateTokenFilter(input, 10)));
        filters.put("unique", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new UniqueTokenFilter(input)));
        filters.put("uppercase", new PreBuiltTokenFilterSpec(true, CachingStrategy.LUCENE, (input, version) ->
                new UpperCaseFilter(input)));
        filters.put("word_delimiter", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new WordDelimiterFilter(input,
                        WordDelimiterFilter.GENERATE_WORD_PARTS
                      | WordDelimiterFilter.GENERATE_NUMBER_PARTS
                      | WordDelimiterFilter.SPLIT_ON_CASE_CHANGE
                      | WordDelimiterFilter.SPLIT_ON_NUMERICS
                      | WordDelimiterFilter.STEM_ENGLISH_POSSESSIVE, null)));
        filters.put("word_delimiter_graph", new PreBuiltTokenFilterSpec(false, CachingStrategy.ONE, (input, version) ->
                new WordDelimiterGraphFilter(input,
                          WordDelimiterGraphFilter.GENERATE_WORD_PARTS
                        | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
                        | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
                        | WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
                        | WordDelimiterGraphFilter.STEM_ENGLISH_POSSESSIVE, null)));

        return filters;
    }
}
