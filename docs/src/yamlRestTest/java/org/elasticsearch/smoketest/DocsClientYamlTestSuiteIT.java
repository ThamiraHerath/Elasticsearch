/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.smoketest;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;

import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.apache.lucene.tests.util.TimeUnits;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.yaml.ClientYamlDocsTestClient;
import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ClientYamlTestClient;
import org.elasticsearch.test.rest.yaml.ClientYamlTestExecutionContext;
import org.elasticsearch.test.rest.yaml.ClientYamlTestResponse;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;
import org.elasticsearch.test.rest.yaml.restspec.ClientYamlSuiteRestSpec;
import org.elasticsearch.test.rest.yaml.section.ExecutableSection;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParser.Token;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

//The default 20 minutes timeout isn't always enough, but Darwin CI hosts are incredibly slow...
@TimeoutSuite(millis = 40 * TimeUnits.MINUTE)
public class DocsClientYamlTestSuiteIT extends ESClientYamlSuiteTestCase {

    public DocsClientYamlTestSuiteIT(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        NamedXContentRegistry executableSectionRegistry = new NamedXContentRegistry(
            CollectionUtils.appendToCopy(
                ExecutableSection.DEFAULT_EXECUTABLE_CONTEXTS,
                new NamedXContentRegistry.Entry(ExecutableSection.class, new ParseField("compare_analyzers"), CompareAnalyzers::parse)
            )
        );
        return ESClientYamlSuiteTestCase.createParameters(executableSectionRegistry);
    }

    @Override
    protected void afterIfFailed(List<Throwable> errors) {
        super.afterIfFailed(errors);
        String name = getTestName().split("=")[1];
        name = name.substring(0, name.length() - 1);
        name = name.replaceAll("/([^/]+)$", ".asciidoc:$1");
        logger.error(
            "This failing test was generated by documentation starting at {}. It may include many snippets. "
                + "See docs/README.asciidoc for an explanation of test generation.",
            name
        );
    }

    @Override
    protected boolean randomizeContentType() {
        return false;
    }

    @Override
    protected ClientYamlTestClient initClientYamlTestClient(
        final ClientYamlSuiteRestSpec restSpec,
        final RestClient restClient,
        final List<HttpHost> hosts,
        final Version esVersion,
        final Version masterVersion,
        final String os
    ) {
        return new ClientYamlDocsTestClient(
            restSpec,
            restClient,
            hosts,
            esVersion,
            masterVersion,
            os,
            this::getClientBuilderWithSniffedHosts
        );
    }

    @Before
    public void waitForRequirements() throws Exception {
        if (isCcrTest() || isGetLicenseTest() || isXpackInfoTest()) {
            ESRestTestCase.waitForActiveLicense(adminClient());
        }
    }

    private static boolean snapshotRepositoryPopulated;

    @Before
    public void populateSnapshotRepository() throws IOException {

        if (snapshotRepositoryPopulated) {
            return;
        }

        // The repository UUID is only created on the first write to the repo, so it may or may not exist when running the tests. However to
        // include the output from the put-repository and get-repositories APIs in the docs we must be sure whether the UUID is returned or
        // not, so we prepare by taking a snapshot first to ensure that the UUID really has been created.
        super.initClient();

        final Request putRepoRequest = new Request("PUT", "/_snapshot/test_setup_repo");
        putRepoRequest.setJsonEntity("{\"type\":\"fs\",\"settings\":{\"location\":\"my_backup_location\"}}");
        assertOK(adminClient().performRequest(putRepoRequest));

        final Request putSnapshotRequest = new Request("PUT", "/_snapshot/test_setup_repo/test_setup_snap");
        putSnapshotRequest.addParameter("wait_for_completion", "true");
        assertOK(adminClient().performRequest(putSnapshotRequest));

        final Request deleteSnapshotRequest = new Request("DELETE", "/_snapshot/test_setup_repo/test_setup_snap");
        assertOK(adminClient().performRequest(deleteSnapshotRequest));

        final Request deleteRepoRequest = new Request("DELETE", "/_snapshot/test_setup_repo");
        assertOK(adminClient().performRequest(deleteRepoRequest));

        snapshotRepositoryPopulated = true;
    }

    @After
    public void cleanup() throws Exception {
        if (isMachineLearningTest() || isTransformTest()) {
            ESRestTestCase.waitForPendingTasks(adminClient());
        }

        // check that there are no templates
        Request request = new Request("GET", "_cat/templates");
        request.addParameter("h", "name");
        String templates = EntityUtils.toString(adminClient().performRequest(request).getEntity());
        if (false == "".equals(templates)) {
            for (String template : templates.split("\n")) {
                if (isXPackTemplate(template)) continue;
                if ("".equals(template)) {
                    throw new IllegalStateException("empty template in templates list:\n" + templates);
                }
                throw new RuntimeException("Template " + template + " not cleared after test");
            }
        }
    }

    @Override
    protected boolean preserveSLMPoliciesUponCompletion() {
        return isSLMTest() == false;
    }

    @Override
    protected boolean preserveILMPoliciesUponCompletion() {
        return isILMTest() == false;
    }

    /**
     * Tests are themselves responsible for cleaning up templates, which speeds up build.
     */
    @Override
    protected boolean preserveTemplatesUponCompletion() {
        return true;
    }

    protected boolean isSLMTest() {
        String testName = getTestName();
        return testName != null && (testName.contains("/slm/") || testName.contains("\\slm\\") || (testName.contains("\\slm/")) ||
        // TODO: Remove after backport of https://github.com/elastic/elasticsearch/pull/48705 which moves SLM docs to correct folder
            testName.contains("/ilm/") || testName.contains("\\ilm\\") || testName.contains("\\ilm/"));
    }

    protected boolean isILMTest() {
        String testName = getTestName();
        return testName != null && (testName.contains("/ilm/") || testName.contains("\\ilm\\") || testName.contains("\\ilm/"));
    }

    protected boolean isMachineLearningTest() {
        String testName = getTestName();
        return testName != null && (testName.contains("/ml/") || testName.contains("\\ml\\"));
    }

    protected boolean isTransformTest() {
        String testName = getTestName();
        return testName != null && (testName.contains("/transform/") || testName.contains("\\transform\\"));
    }

    protected boolean isCcrTest() {
        String testName = getTestName();
        return testName != null && testName.contains("/ccr/");
    }

    protected boolean isGetLicenseTest() {
        String testName = getTestName();
        return testName != null && (testName.contains("/get-license/") || testName.contains("\\get-license\\"));
    }

    protected boolean isXpackInfoTest() {
        String testName = getTestName();
        return testName != null && (testName.contains("/info/") || testName.contains("\\info\\"));
    }

    /**
     * Compares the results of running two analyzers against many random
     * strings. The goal is to figure out if two anlayzers are "the same" by
     * comparing their results. This is far from perfect but should be fairly
     * accurate, especially for gross things like missing {@code decimal_digit}
     * token filters, and should be fairly fast because it compares a fairly
     * small number of tokens.
     */
    private static class CompareAnalyzers implements ExecutableSection {
        private static final ConstructingObjectParser<CompareAnalyzers, XContentLocation> PARSER = new ConstructingObjectParser<>(
            "test_analyzer",
            false,
            (a, location) -> {
                String index = (String) a[0];
                String first = (String) a[1];
                String second = (String) a[2];
                return new CompareAnalyzers(location, index, first, second);
            }
        );
        static {
            PARSER.declareString(constructorArg(), new ParseField("index"));
            PARSER.declareString(constructorArg(), new ParseField("first"));
            PARSER.declareString(constructorArg(), new ParseField("second"));
        }

        private static CompareAnalyzers parse(XContentParser parser) throws IOException {
            XContentLocation location = parser.getTokenLocation();
            CompareAnalyzers section = PARSER.parse(parser, location);
            assert parser.currentToken() == Token.END_OBJECT : "End of object required";
            parser.nextToken(); // throw out the END_OBJECT to conform with other ExecutableSections
            return section;
        }

        private final XContentLocation location;
        private final String index;
        private final String first;
        private final String second;

        private CompareAnalyzers(XContentLocation location, String index, String first, String second) {
            this.location = location;
            this.index = index;
            this.first = first;
            this.second = second;
        }

        @Override
        public XContentLocation getLocation() {
            return location;
        }

        @Override
        public void execute(ClientYamlTestExecutionContext executionContext) throws IOException {
            int size = 100;
            int maxLength = 15;
            List<String> testText = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                /*
                 * Build a string with a few unicode sequences separated by
                 * spaces. The unicode sequences aren't going to be of the same
                 * code page which is a shame because it makes the entire
                 * string less realistic. But this still provides a fairly
                 * nice string to compare.
                 */
                int spaces = between(0, 5);
                StringBuilder b = new StringBuilder((spaces + 1) * maxLength);
                b.append(randomRealisticUnicodeOfCodepointLengthBetween(1, maxLength));
                for (int t = 0; t < spaces; t++) {
                    b.append(' ');
                    b.append(randomRealisticUnicodeOfCodepointLengthBetween(1, maxLength));
                }
                testText.add(
                    b.toString()
                        // Don't look up stashed values
                        .replace("$", "\\$")
                );
            }
            Map<String, Object> body = Maps.newMapWithExpectedSize(2);
            body.put("analyzer", first);
            body.put("text", testText);
            ClientYamlTestResponse response = executionContext.callApi(
                "indices.analyze",
                singletonMap("index", index),
                singletonList(body),
                emptyMap()
            );
            Iterator<?> firstTokens = ((List<?>) response.evaluate("tokens")).iterator();
            body.put("analyzer", second);
            response = executionContext.callApi("indices.analyze", singletonMap("index", index), singletonList(body), emptyMap());
            Iterator<?> secondTokens = ((List<?>) response.evaluate("tokens")).iterator();

            Object previousFirst = null;
            Object previousSecond = null;
            while (firstTokens.hasNext()) {
                if (false == secondTokens.hasNext()) {
                    fail(formatted("""
                        %s has fewer tokens than %s. %s has [%s] but %s is out of tokens. \
                        %s's last token was [%s] and %s's last token was' [%s]
                        """, second, first, first, firstTokens.next(), second, first, previousFirst, second, previousSecond));
                }
                Map<?, ?> firstToken = (Map<?, ?>) firstTokens.next();
                Map<?, ?> secondToken = (Map<?, ?>) secondTokens.next();
                String firstText = (String) firstToken.get("token");
                String secondText = (String) secondToken.get("token");
                // Check the text and produce an error message with the utf8 sequence if they don't match.
                if (false == secondText.equals(firstText)) {
                    fail(formatted("""
                        text differs: %s was [%s] but %s was [%s]. In utf8 those are
                        %s and
                        %s
                        """, first, firstText, second, secondText, new BytesRef(firstText), new BytesRef(secondText)));
                }
                // Now check the whole map just in case the text matches but something else differs
                assertEquals(firstToken, secondToken);
                previousFirst = firstToken;
                previousSecond = secondToken;
            }
            if (secondTokens.hasNext()) {
                fail(formatted("""
                    %s has more tokens than %s. %s has [%s] but %s is out of tokens. \
                    %s's last token was [%s] and %s's last token was [%s]
                    """, second, first, second, secondTokens.next(), first, first, previousFirst, second, previousSecond));
            }
        }
    }
}
