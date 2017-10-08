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

package org.elasticsearch.indices.analysis;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.apache.lucene.analysis.BaseTokenStreamTestCase.assertTokenStreamContents;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.Analysis;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.CustomAnalyzer;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.analysis.MyFilterTokenFilterFactory;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.analysis.PreConfiguredCharFilter;
import org.elasticsearch.index.analysis.PreConfiguredTokenFilter;
import org.elasticsearch.index.analysis.PreConfiguredTokenizer;
import org.elasticsearch.index.analysis.StandardTokenizerFactory;
import org.elasticsearch.index.analysis.StopTokenFilterFactory;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.test.VersionUtils;
import org.hamcrest.MatcherAssert;

public class AnalysisModuleTests extends ESTestCase {
    private final Settings emptyNodeSettings = Settings.builder()
            .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
            .build();

    public IndexAnalyzers getIndexAnalyzers(Settings settings) throws IOException {
        return getIndexAnalyzers(getNewRegistry(settings), settings);
    }

    public IndexAnalyzers getIndexAnalyzers(AnalysisRegistry registry, Settings settings) throws IOException {
        IndexSettings idxSettings = IndexSettingsModule.newIndexSettings("test", settings);
        return registry.build(idxSettings);
    }

    public AnalysisRegistry getNewRegistry(Settings settings) {
        try {
            return new AnalysisModule(new Environment(settings), singletonList(new AnalysisPlugin() {
                @Override
                public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
                    return singletonMap("myfilter", MyFilterTokenFilterFactory::new);
                }

                @Override
                public Map<String, AnalysisProvider<CharFilterFactory>> getCharFilters() {
                    return AnalysisPlugin.super.getCharFilters();
                }
            })).getAnalysisRegistry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Settings loadFromClasspath(String path) throws IOException {
        return Settings.builder().loadFromStream(path, getClass().getResourceAsStream(path), false)
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .build();

    }

    public void testSimpleConfigurationJson() throws IOException {
        Settings settings = loadFromClasspath("/org/elasticsearch/index/analysis/test1.json");
        testSimpleConfiguration(settings);
    }

    public void testSimpleConfigurationYaml() throws IOException {
        Settings settings = loadFromClasspath("/org/elasticsearch/index/analysis/test1.yml");
        testSimpleConfiguration(settings);
    }

    public void testAnalyzerAliasNotAllowedPost5x() throws IOException {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.foobar.type", "standard")
            .put("index.analysis.analyzer.foobar.alias","foobaz")
            // analyzer aliases were removed in v5.0.0 alpha6
            .put(IndexMetaData.SETTING_VERSION_CREATED, VersionUtils.randomVersionBetween(random(), Version.V_5_0_0_beta1, null))
            .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
            .build();
        AnalysisRegistry registry = getNewRegistry(settings);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> getIndexAnalyzers(registry, settings));
        assertEquals("setting [index.analysis.analyzer.foobar.alias] is not supported", e.getMessage());
    }

    public void testVersionedAnalyzers() throws Exception {
        String yaml = "/org/elasticsearch/index/analysis/test1.yml";
        Settings settings2 = Settings.builder()
                .loadFromStream(yaml, getClass().getResourceAsStream(yaml), false)
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.V_5_0_0)
                .build();
        AnalysisRegistry newRegistry = getNewRegistry(settings2);
        IndexAnalyzers indexAnalyzers = getIndexAnalyzers(newRegistry, settings2);

        // registry always has the current version
        assertThat(newRegistry.getAnalyzer("default"), is(instanceOf(NamedAnalyzer.class)));
        NamedAnalyzer defaultNamedAnalyzer = (NamedAnalyzer) newRegistry.getAnalyzer("default");
        assertThat(defaultNamedAnalyzer.analyzer(), is(instanceOf(StandardAnalyzer.class)));
        assertEquals(Version.CURRENT.luceneVersion, defaultNamedAnalyzer.analyzer().getVersion());

        // analysis service has the expected version
        assertThat(indexAnalyzers.get("standard").analyzer(), is(instanceOf(StandardAnalyzer.class)));
        assertEquals(Version.V_5_0_0.luceneVersion,
                indexAnalyzers.get("standard").analyzer().getVersion());
        assertEquals(Version.V_5_0_0.luceneVersion,
                indexAnalyzers.get("thai").analyzer().getVersion());

        assertThat(indexAnalyzers.get("custom7").analyzer(), is(instanceOf(StandardAnalyzer.class)));
        assertEquals(org.apache.lucene.util.Version.fromBits(3,6,0), indexAnalyzers.get("custom7").analyzer().getVersion());
    }

    private void testSimpleConfiguration(Settings settings) throws IOException {
        IndexAnalyzers indexAnalyzers = getIndexAnalyzers(settings);
        Analyzer analyzer = indexAnalyzers.get("custom1").analyzer();

        assertThat(analyzer, instanceOf(CustomAnalyzer.class));
        CustomAnalyzer custom1 = (CustomAnalyzer) analyzer;
        assertThat(custom1.tokenizerFactory(), instanceOf(StandardTokenizerFactory.class));
        assertThat(custom1.tokenFilters().length, equalTo(2));

        StopTokenFilterFactory stop1 = (StopTokenFilterFactory) custom1.tokenFilters()[0];
        assertThat(stop1.stopWords().size(), equalTo(1));

        // verify position increment gap
        analyzer = indexAnalyzers.get("custom6").analyzer();
        assertThat(analyzer, instanceOf(CustomAnalyzer.class));
        CustomAnalyzer custom6 = (CustomAnalyzer) analyzer;
        assertThat(custom6.getPositionIncrementGap("any_string"), equalTo(256));

        // check custom class name (my)
        analyzer = indexAnalyzers.get("custom4").analyzer();
        assertThat(analyzer, instanceOf(CustomAnalyzer.class));
        CustomAnalyzer custom4 = (CustomAnalyzer) analyzer;
        assertThat(custom4.tokenFilters()[0], instanceOf(MyFilterTokenFilterFactory.class));
    }

    public void testWordListPath() throws Exception {
        Settings settings = Settings.builder()
                               .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                               .build();
        Environment env = new Environment(settings);
        String[] words = new String[]{"donau", "dampf", "schiff", "spargel", "creme", "suppe"};

        Path wordListFile = generateWordList(words);
        settings = Settings.builder().loadFromSource("index: \n  word_list_path: " + wordListFile.toAbsolutePath(), XContentType.YAML)
            .build();

        Set<?> wordList = Analysis.getWordSet(env, settings, "index.word_list");
        MatcherAssert.assertThat(wordList.size(), equalTo(6));
//        MatcherAssert.assertThat(wordList, hasItems(words));
        Files.delete(wordListFile);
    }

    private Path generateWordList(String... words) throws Exception {
        Path wordListFile = createTempDir().resolve("wordlist.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(wordListFile, StandardCharsets.UTF_8)) {
            for (String word : words) {
                writer.write(word);
                writer.write('\n');
            }
        }
        return wordListFile;
    }

    public void testUnderscoreInAnalyzerName() throws IOException {
        Settings settings = Settings.builder()
                .put("index.analysis.analyzer._invalid_name.tokenizer", "keyword")
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put(IndexMetaData.SETTING_VERSION_CREATED, "1")
                .build();
        try {
            getIndexAnalyzers(settings);
            fail("This should fail with IllegalArgumentException because the analyzers name starts with _");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), either(equalTo("analyzer name must not start with '_'. got \"_invalid_name\""))
                    .or(equalTo("analyzer name must not start with '_'. got \"_invalidName\"")));
        }
    }

    /**
     * Tests that plugins can register pre-configured char filters that vary in behavior based on Elasticsearch version, Lucene version,
     * and that do not vary based on version at all.
     */
    public void testPluginPreConfiguredCharFilters() throws IOException {
        boolean noVersionSupportsMultiTerm = randomBoolean();
        boolean luceneVersionSupportsMultiTerm = randomBoolean();
        boolean elasticsearchVersionSupportsMultiTerm = randomBoolean();
        AnalysisRegistry registry = new AnalysisModule(new Environment(emptyNodeSettings), singletonList(new AnalysisPlugin() {
            @Override
            public List<PreConfiguredCharFilter> getPreConfiguredCharFilters() {
                return Arrays.asList(
                        PreConfiguredCharFilter.singleton("no_version", noVersionSupportsMultiTerm,
                                tokenStream -> new AppendCharFilter(tokenStream, "no_version")),
                        PreConfiguredCharFilter.luceneVersion("lucene_version", luceneVersionSupportsMultiTerm,
                                (tokenStream, luceneVersion) -> new AppendCharFilter(tokenStream, luceneVersion.toString())),
                        PreConfiguredCharFilter.elasticsearchVersion("elasticsearch_version", elasticsearchVersionSupportsMultiTerm,
                                (tokenStream, esVersion) -> new AppendCharFilter(tokenStream, esVersion.toString()))
                        );
            }
        })).getAnalysisRegistry();

        Version version = VersionUtils.randomVersion(random());
        IndexAnalyzers analyzers = getIndexAnalyzers(registry, Settings.builder()
                .put("index.analysis.analyzer.no_version.tokenizer", "keyword")
                .put("index.analysis.analyzer.no_version.char_filter", "no_version")
                .put("index.analysis.analyzer.lucene_version.tokenizer", "keyword")
                .put("index.analysis.analyzer.lucene_version.char_filter", "lucene_version")
                .put("index.analysis.analyzer.elasticsearch_version.tokenizer", "keyword")
                .put("index.analysis.analyzer.elasticsearch_version.char_filter", "elasticsearch_version")
                .put(IndexMetaData.SETTING_VERSION_CREATED, version)
                .build());
        assertTokenStreamContents(analyzers.get("no_version").tokenStream("", "test"), new String[] {"testno_version"});
        assertTokenStreamContents(analyzers.get("lucene_version").tokenStream("", "test"), new String[] {"test" + version.luceneVersion});
        assertTokenStreamContents(analyzers.get("elasticsearch_version").tokenStream("", "test"), new String[] {"test" + version});

        assertEquals("test" + (noVersionSupportsMultiTerm ? "no_version" : ""),
                analyzers.get("no_version").normalize("", "test").utf8ToString());
        assertEquals("test" + (luceneVersionSupportsMultiTerm ? version.luceneVersion.toString() : ""),
                analyzers.get("lucene_version").normalize("", "test").utf8ToString());
        assertEquals("test" + (elasticsearchVersionSupportsMultiTerm ? version.toString() : ""),
                analyzers.get("elasticsearch_version").normalize("", "test").utf8ToString());
    }

    /**
     * Tests that plugins can register pre-configured token filters that vary in behavior based on Elasticsearch version, Lucene version,
     * and that do not vary based on version at all.
     */
    public void testPluginPreConfiguredTokenFilters() throws IOException {
        boolean noVersionSupportsMultiTerm = randomBoolean();
        boolean luceneVersionSupportsMultiTerm = randomBoolean();
        boolean elasticsearchVersionSupportsMultiTerm = randomBoolean();
        AnalysisRegistry registry = new AnalysisModule(new Environment(emptyNodeSettings), singletonList(new AnalysisPlugin() {
            @Override
            public List<PreConfiguredTokenFilter> getPreConfiguredTokenFilters() {
                return Arrays.asList(
                        PreConfiguredTokenFilter.singleton("no_version", noVersionSupportsMultiTerm,
                                tokenStream -> new AppendTokenFilter(tokenStream, "no_version")),
                        PreConfiguredTokenFilter.luceneVersion("lucene_version", luceneVersionSupportsMultiTerm,
                                (tokenStream, luceneVersion) -> new AppendTokenFilter(tokenStream, luceneVersion.toString())),
                        PreConfiguredTokenFilter.elasticsearchVersion("elasticsearch_version", elasticsearchVersionSupportsMultiTerm,
                                (tokenStream, esVersion) -> new AppendTokenFilter(tokenStream, esVersion.toString()))
                        );
            }
        })).getAnalysisRegistry();

        Version version = VersionUtils.randomVersion(random());
        IndexAnalyzers analyzers = getIndexAnalyzers(registry, Settings.builder()
                .put("index.analysis.analyzer.no_version.tokenizer", "keyword")
                .put("index.analysis.analyzer.no_version.filter", "no_version")
                .put("index.analysis.analyzer.lucene_version.tokenizer", "keyword")
                .put("index.analysis.analyzer.lucene_version.filter", "lucene_version")
                .put("index.analysis.analyzer.elasticsearch_version.tokenizer", "keyword")
                .put("index.analysis.analyzer.elasticsearch_version.filter", "elasticsearch_version")
                .put(IndexMetaData.SETTING_VERSION_CREATED, version)
                .build());
        assertTokenStreamContents(analyzers.get("no_version").tokenStream("", "test"), new String[] {"testno_version"});
        assertTokenStreamContents(analyzers.get("lucene_version").tokenStream("", "test"), new String[] {"test" + version.luceneVersion});
        assertTokenStreamContents(analyzers.get("elasticsearch_version").tokenStream("", "test"), new String[] {"test" + version});

        assertEquals("test" + (noVersionSupportsMultiTerm ? "no_version" : ""),
                analyzers.get("no_version").normalize("", "test").utf8ToString());
        assertEquals("test" + (luceneVersionSupportsMultiTerm ? version.luceneVersion.toString() : ""),
                analyzers.get("lucene_version").normalize("", "test").utf8ToString());
        assertEquals("test" + (elasticsearchVersionSupportsMultiTerm ? version.toString() : ""),
                analyzers.get("elasticsearch_version").normalize("", "test").utf8ToString());
    }

    /**
     * Tests that plugins can register pre-configured token filters that vary in behavior based on Elasticsearch version, Lucene version,
     * and that do not vary based on version at all.
     */
    public void testPluginPreConfiguredTokenizers() throws IOException {
        boolean noVersionSupportsMultiTerm = randomBoolean();
        boolean luceneVersionSupportsMultiTerm = randomBoolean();
        boolean elasticsearchVersionSupportsMultiTerm = randomBoolean();

        // Simple tokenizer that always spits out a single token with some preconfigured characters
        final class FixedTokenizer extends Tokenizer {
            private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
            private final char[] chars;
            private boolean read = false;

            protected FixedTokenizer(String chars) {
                this.chars = chars.toCharArray();
            }

            @Override
            public boolean incrementToken() throws IOException {
                if (read) {
                    return false;
                }
                clearAttributes();
                read = true;
                term.resizeBuffer(chars.length);
                System.arraycopy(chars, 0, term.buffer(), 0, chars.length);
                term.setLength(chars.length);
                return true;
            }

            @Override
            public void reset() throws IOException {
                super.reset();
                read = false;
            }
        }
        AnalysisRegistry registry = new AnalysisModule(new Environment(emptyNodeSettings), singletonList(new AnalysisPlugin() {
            @Override
            public List<PreConfiguredTokenizer> getPreConfiguredTokenizers() {
                return Arrays.asList(
                        PreConfiguredTokenizer.singleton("no_version", () -> new FixedTokenizer("no_version"),
                                noVersionSupportsMultiTerm ? () -> AppendTokenFilter.factoryForSuffix("no_version") : null),
                        PreConfiguredTokenizer.luceneVersion("lucene_version",
                                luceneVersion -> new FixedTokenizer(luceneVersion.toString()),
                                luceneVersionSupportsMultiTerm ?
                                        luceneVersion -> AppendTokenFilter.factoryForSuffix(luceneVersion.toString()) : null),
                        PreConfiguredTokenizer.elasticsearchVersion("elasticsearch_version",
                                esVersion -> new FixedTokenizer(esVersion.toString()),
                                elasticsearchVersionSupportsMultiTerm ?
                                        esVersion -> AppendTokenFilter.factoryForSuffix(esVersion.toString()) : null)
                        );
            }
        })).getAnalysisRegistry();

        Version version = VersionUtils.randomVersion(random());
        IndexAnalyzers analyzers = getIndexAnalyzers(registry, Settings.builder()
                .put("index.analysis.analyzer.no_version.tokenizer", "no_version")
                .put("index.analysis.analyzer.lucene_version.tokenizer", "lucene_version")
                .put("index.analysis.analyzer.elasticsearch_version.tokenizer", "elasticsearch_version")
                .put(IndexMetaData.SETTING_VERSION_CREATED, version)
                .build());
        assertTokenStreamContents(analyzers.get("no_version").tokenStream("", "test"), new String[] {"no_version"});
        assertTokenStreamContents(analyzers.get("lucene_version").tokenStream("", "test"), new String[] {version.luceneVersion.toString()});
        assertTokenStreamContents(analyzers.get("elasticsearch_version").tokenStream("", "test"), new String[] {version.toString()});

        // These are current broken by https://github.com/elastic/elasticsearch/issues/24752
//        assertEquals("test" + (noVersionSupportsMultiTerm ? "no_version" : ""),
//                analyzers.get("no_version").normalize("", "test").utf8ToString());
//        assertEquals("test" + (luceneVersionSupportsMultiTerm ? version.luceneVersion.toString() : ""),
//                analyzers.get("lucene_version").normalize("", "test").utf8ToString());
//        assertEquals("test" + (elasticsearchVersionSupportsMultiTerm ? version.toString() : ""),
//                analyzers.get("elasticsearch_version").normalize("", "test").utf8ToString());
    }

    public void testRegisterHunspellDictionary() throws Exception {
        Settings settings = Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                .build();
        Environment environment = new Environment(settings);
        InputStream aff = getClass().getResourceAsStream("/indices/analyze/conf_dir/hunspell/en_US/en_US.aff");
        InputStream dic = getClass().getResourceAsStream("/indices/analyze/conf_dir/hunspell/en_US/en_US.dic");
        Dictionary dictionary;
        try (Directory tmp = new SimpleFSDirectory(environment.tmpFile())) {
            dictionary = new Dictionary(tmp, "hunspell", aff, dic);
        }
        AnalysisModule module = new AnalysisModule(environment, singletonList(new AnalysisPlugin() {
            @Override
            public Map<String, Dictionary> getHunspellDictionaries() {
                return singletonMap("foo", dictionary);
            }
        }));
        assertSame(dictionary, module.getHunspellService().getDictionary("foo"));
    }

    // Simple char filter that appends text to the term
    public static class AppendCharFilter extends CharFilter {
        private final char[] appendMe;
        private int offsetInAppendMe = -1;

        public AppendCharFilter(Reader input, String appendMe) {
            super(input);
            this.appendMe = appendMe.toCharArray();
        }

        @Override
        protected int correct(int currentOff) {
            return currentOff;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (offsetInAppendMe < 0) {
                int read = input.read(cbuf, off, len);
                if (read == len) {
                    return read;
                }
                off += read;
                len -= read;
                int allowedLen = Math.min(len, appendMe.length);
                System.arraycopy(appendMe, 0, cbuf, off, allowedLen);
                offsetInAppendMe = allowedLen;
                return read + allowedLen;
            }
            if (offsetInAppendMe >= appendMe.length) {
                return -1;
            }
            int allowedLen = Math.max(len, appendMe.length - offsetInAppendMe);
            System.arraycopy(appendMe, offsetInAppendMe, cbuf, off, allowedLen);
            return allowedLen;
        }
    }

    // Simple token filter that appends text to the term
    private static class AppendTokenFilter extends TokenFilter {
        public static TokenFilterFactory factoryForSuffix(String suffix) {
            return new TokenFilterFactory() {
                @Override
                public String name() {
                    return suffix;
                }

                @Override
                public TokenStream create(TokenStream tokenStream) {
                    return new AppendTokenFilter(tokenStream, suffix);
                }
            };
        }

        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final char[] appendMe;

        protected AppendTokenFilter(TokenStream input, String appendMe) {
            super(input);
            this.appendMe = appendMe.toCharArray();
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (false == input.incrementToken()) {
                return false;
            }
            term.resizeBuffer(term.length() + appendMe.length);
            System.arraycopy(appendMe, 0, term.buffer(), term.length(), appendMe.length);
            term.setLength(term.length() + appendMe.length);
            return true;
        }
    }

}
