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

package org.elasticsearch.index.query;

import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.lucene.search.MatchNoDocsQuery;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.EnvironmentModule;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNameModule;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.index.cache.IndexCacheModule;
import org.elasticsearch.index.query.functionscore.FunctionScoreModule;
import org.elasticsearch.index.settings.IndexSettingsModule;
import org.elasticsearch.index.similarity.SimilarityModule;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.indices.query.IndicesQueriesModule;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class IdsQueryBuilderTest extends ElasticsearchTestCase {

    protected QueryParseContext context;
    protected Injector injector;

    private XContentParser parser;

    private IdsQueryBuilder testQuery;

    @Before
    public void initContext() throws IOException {
        Settings settings = ImmutableSettings.settingsBuilder()
                .put("path.conf", this.getResourcePath("config"))
                .put("name", getClass().getName())
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                .build();

        Index index = new Index("test");
        injector = new ModulesBuilder().add(
                new EnvironmentModule(new Environment(settings)),
                new SettingsModule(settings),
                new ThreadPoolModule(settings),
                new IndicesQueriesModule(),
                new ScriptModule(settings),
                new IndexSettingsModule(index, settings),
                new IndexCacheModule(settings),
                new AnalysisModule(settings),
                new SimilarityModule(settings),
                new IndexNameModule(index),
                new IndexQueryParserModule(settings),
                new FunctionScoreModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ClusterService.class).toProvider(Providers.of((ClusterService) null));
                        bind(CircuitBreakerService.class).to(NoneCircuitBreakerService.class);
                    }
                }
        ).createInjector();

        IndexQueryParserService queryParserService = injector.getInstance(IndexQueryParserService.class);
        context = new QueryParseContext(index, queryParserService);
        testQuery = createTestQuery();
        parser = XContentFactory.xContent(testQuery.toString()).createParser(testQuery.toString());
        context.reset(parser);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        terminate(injector.getInstance(ThreadPool.class));
    }

    @Test
    public void testFromXContent() throws IOException {
        IdsQueryBuilder newQuery = new IdsQueryBuilder();
        newQuery.fromXContent(context);
        // compare these
        assertEquals(testQuery, newQuery);
    }

    @Test
    public void testToQuery() throws IOException {
        Query query = testQuery.toQuery(context);
        if (testQuery.ids().size() == 0) {
            assertThat(query, is(instanceOf(MatchNoDocsQuery.class)));
        } else {
            assertThat(query, is(instanceOf(ConstantScoreQuery.class)));
            ConstantScoreQuery csQuery = (ConstantScoreQuery) query;
            // compare these
            assertThat(csQuery.getBoost(), is(testQuery.boost()));
            // TODO how to extract more info from lucene query?
        }
    }

    @Test()
    public void testSerialization() throws IOException {
        BytesStreamOutput output = new BytesStreamOutput();
        testQuery.writeTo(output);

        BytesStreamInput bytesStreamInput = new BytesStreamInput(output.bytes());
        IdsQueryBuilder deserializedQuery = new IdsQueryBuilder();
        deserializedQuery.readFrom(bytesStreamInput);

        assertNotSame(deserializedQuery, testQuery);
        assertThat(deserializedQuery.boost(), equalTo(testQuery.boost()));
    }

    IdsQueryBuilder createTestQuery() {
        IdsQueryBuilder query = new IdsQueryBuilder();
        int numberOfTypes = randomIntBetween(1, 10);
        String[] types = new String[numberOfTypes];
        for (int i = 0; i < numberOfTypes; i++) {
            types[i] = randomAsciiOfLength(8);
        }
        query = new IdsQueryBuilder(types);
        if (randomBoolean()) {
            int numberOfIds = randomIntBetween(1, 10);
            for (int i = 0; i < numberOfIds; i++) {
                query.addIds(randomAsciiOfLength(8));
            }
        }
        if (randomBoolean()) {
            query.boost(2.0f / randomIntBetween(1, 20));
        }
        return query;
    }
}
