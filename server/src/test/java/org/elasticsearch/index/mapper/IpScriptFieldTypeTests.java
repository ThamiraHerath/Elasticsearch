/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.common.lucene.search.function.ScriptScoreQuery;
import org.elasticsearch.index.fielddata.BinaryScriptFieldData;
import org.elasticsearch.index.fielddata.ScriptDocValues.Strings;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.script.DocReader;
import org.elasticsearch.script.IpFieldScript;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.MultiValueMode;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

public class IpScriptFieldTypeTests extends AbstractScriptFieldTypeTestCase {

    public void testFormat() throws IOException {
        assertThat(simpleMappedField().docValueFormat(null, null), sameInstance(DocValueFormat.IP));
        Exception e = expectThrows(IllegalArgumentException.class, () -> simpleMappedField().docValueFormat("ASDFA", null));
        assertThat(e.getMessage(), equalTo("Field [test] of type [ip] does not support custom formats"));
        e = expectThrows(IllegalArgumentException.class, () -> simpleMappedField().docValueFormat(null, ZoneId.of("America/New_York")));
        assertThat(e.getMessage(), equalTo("Field [test] of type [ip] does not support custom time zones"));
    }

    @Override
    public void testDocValues() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.2\", \"192.168.1\"]}"))));
            List<Object> results = new ArrayList<>();
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                MappedField mappedField = build("append_param", Map.of("param", ".1"));
                BinaryScriptFieldData ifd = (BinaryScriptFieldData)
                    mappedField.fielddataBuilder("test", mockContext()::lookup).build(null, null);
                DocValueFormat format = mappedField.docValueFormat(null, null);
                searcher.search(new MatchAllDocsQuery(), new Collector() {
                    @Override
                    public ScoreMode scoreMode() {
                        return ScoreMode.COMPLETE_NO_SCORES;
                    }

                    @Override
                    public LeafCollector getLeafCollector(LeafReaderContext context) {
                        SortedBinaryDocValues dv = ifd.load(context).getBytesValues();
                        return new LeafCollector() {
                            @Override
                            public void setScorer(Scorable scorer) {}

                            @Override
                            public void collect(int doc) throws IOException {
                                if (dv.advanceExact(doc)) {
                                    for (int i = 0; i < dv.docValueCount(); i++) {
                                        results.add(format.format(dv.nextValue()));
                                    }
                                }
                            }
                        };
                    }
                });
                assertThat(results, equalTo(List.of("192.168.0.1", "192.168.1.1", "192.168.2.1")));
            }
        }
    }

    @Override
    public void testSort() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.4\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.2\"]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                BinaryScriptFieldData ifd = (BinaryScriptFieldData)
                    simpleMappedField().fielddataBuilder("test", mockContext()::lookup).build(null, null);
                SortField sf = ifd.sortField(null, MultiValueMode.MIN, null, false);
                TopFieldDocs docs = searcher.search(new MatchAllDocsQuery(), 3, new Sort(sf));
                assertThat(
                    reader.document(docs.scoreDocs[0].doc).getBinaryValue("_source").utf8ToString(),
                    equalTo("{\"foo\": [\"192.168.0.1\"]}")
                );
                assertThat(
                    reader.document(docs.scoreDocs[1].doc).getBinaryValue("_source").utf8ToString(),
                    equalTo("{\"foo\": [\"192.168.0.2\"]}")
                );
                assertThat(
                    reader.document(docs.scoreDocs[2].doc).getBinaryValue("_source").utf8ToString(),
                    equalTo("{\"foo\": [\"192.168.0.4\"]}")
                );
            }
        }
    }

    @Override
    public void testUsedInScript() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.4\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.2\"]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                SearchExecutionContext searchContext = mockContext(true, simpleMappedField());
                assertThat(searcher.count(new ScriptScoreQuery(new MatchAllDocsQuery(), new Script("test"), new ScoreScript.LeafFactory() {
                    @Override
                    public boolean needs_score() {
                        return false;
                    }

                    @Override
                    public ScoreScript newInstance(DocReader docReader) {
                        return new ScoreScript(Map.of(), searchContext.lookup(), docReader) {
                            @Override
                            public double execute(ExplanationHolder explanation) {
                                Strings bytes = (Strings) getDoc().get("test");
                                return Integer.parseInt(bytes.getValue().substring(bytes.getValue().lastIndexOf(".") + 1));
                            }
                        };
                    }
                }, searchContext.lookup(), 2.5f, "test", 0, Version.CURRENT)), equalTo(1));
            }
        }
    }

    @Override
    public void testExistsQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": []}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                assertThat(searcher.count(simpleMappedField().existsQuery(mockContext())), equalTo(1));
            }
        }
    }

    @Override
    public void testRangeQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"200.0.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"1.1.1.1\"]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                assertThat(
                    searcher.count(
                        simpleMappedField().rangeQuery("192.0.0.0", "200.0.0.0", false, false, null, null, null, mockContext())
                    ),
                    equalTo(1)
                );
            }
        }
    }

    @Override
    protected Query randomRangeQuery(MappedField mappedField, SearchExecutionContext ctx) {
        return mappedField.rangeQuery("192.0.0.0", "200.0.0.0", randomBoolean(), randomBoolean(), null, null, null, ctx);
    }

    @Override
    public void testTermQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"200.0.0\"]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                MappedField mappedField = build("append_param", Map.of("param", ".1"));
                assertThat(searcher.count(mappedField.termQuery("192.168.0.1", mockContext())), equalTo(1));
                assertThat(searcher.count(mappedField.termQuery("192.168.0.7", mockContext())), equalTo(0));
                assertThat(searcher.count(mappedField.termQuery("192.168.0.0/16", mockContext())), equalTo(2));
                assertThat(searcher.count(mappedField.termQuery("10.168.0.0/16", mockContext())), equalTo(0));
            }
        }
    }

    @Override
    protected Query randomTermQuery(MappedField mappedField, SearchExecutionContext ctx) {
        return mappedField.termQuery(randomIp(randomBoolean()), ctx);
    }

    @Override
    public void testTermsQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"192.168.1.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"200.0.0.1\"]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"foo\": [\"1.1.1.1\"]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                assertThat(
                    searcher.count(simpleMappedField().termsQuery(List.of("192.168.0.1", "1.1.1.1"), mockContext())),
                    equalTo(2)
                );
                assertThat(
                    searcher.count(simpleMappedField().termsQuery(List.of("192.168.0.0/16", "1.1.1.1"), mockContext())),
                    equalTo(3)
                );
            }
        }
    }

    @Override
    protected Query randomTermsQuery(MappedField mappedField, SearchExecutionContext ctx) {
        return mappedField.termsQuery(randomList(100, () -> randomIp(randomBoolean())), ctx);
    }

    @Override
    protected MappedField simpleMappedField() {
        return build("read_foo", Map.of());
    }

    @Override
    protected MappedField loopField() {
        return build("loop", Map.of());
    }

    @Override
    protected String typeName() {
        return "ip";
    }

    private static MappedField build(String code, Map<String, Object> params) {
        return build(new Script(ScriptType.INLINE, "test", code, params));
    }

    private static IpFieldScript.Factory factory(Script script) {
        return switch (script.getIdOrCode()) {
            case "read_foo" -> (fieldName, params, lookup) -> (ctx) -> new IpFieldScript(fieldName, params, lookup, ctx) {
                @Override
                public void execute() {
                    for (Object foo : (List<?>) lookup.source().get("foo")) {
                        emit(foo.toString());
                    }
                }
            };
            case "append_param" -> (fieldName, params, lookup) -> (ctx) -> new IpFieldScript(fieldName, params, lookup, ctx) {
                @Override
                public void execute() {
                    for (Object foo : (List<?>) lookup.source().get("foo")) {
                        emit(foo.toString() + getParams().get("param"));
                    }
                }
            };
            case "loop" -> (fieldName, params, lookup) -> {
                // Indicate that this script wants the field call "test", which *is* the name of this field
                lookup.forkAndTrackFieldReferences("test");
                throw new IllegalStateException("shoud have thrown on the line above");
            };
            default -> throw new IllegalArgumentException("unsupported script [" + script.getIdOrCode() + "]");
        };
    }

    private static MappedField build(Script script) {
        return new MappedField("test", new IpScriptFieldType(factory(script), script, emptyMap()));
    }
}
