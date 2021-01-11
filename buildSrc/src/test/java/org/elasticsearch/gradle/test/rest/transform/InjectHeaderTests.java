/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gradle.test.rest.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import org.elasticsearch.gradle.test.GradleUnitTestCase;
import org.hamcrest.CoreMatchers;
import org.hamcrest.core.IsCollectionContaining;
import org.junit.Test;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;


public class InjectHeaderTests extends GradleUnitTestCase {

    private static final YAMLFactory yaml = new YAMLFactory();
    private static final ObjectMapper mapper = new ObjectMapper(yaml);

    private static final Map<String, String> headers =
        Map.of("Content-Type", "application/vnd.elasticsearch+json;compatible-with=7",
            "Accept", "application/vnd.elasticsearch+json;compatible-with=7"
        );

    /**
     * test file does not have setup: block
     */
    @Test
    public void testInjectHeadersWithoutSetupBlock() throws Exception {
        File testFile = new File(getClass().getResource("/rest/header_inject/no_setup.yml").toURI());
        YAMLParser yamlParser = yaml.createParser(testFile);
        List<ObjectNode> tests = mapper.readValues(yamlParser, ObjectNode.class).readAll();
        RestTestTransformer transformer = new RestTestTransformer();
        //validate no setup
        assertThat(tests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(0L));

        List<ObjectNode> transformedTests = transformer.transformRestTests(new LinkedList<>(tests),
            Collections.singletonList(new InjectHeaders(headers)));
        //ensure setup is correct
        assertThat(transformedTests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));
        transformedTests.stream().filter(node -> node.get("setup") != null).forEach(this::assertSetupForHeaders);
        //ensure do body is correct
        transformedTests
            .forEach(test -> {
                Iterator<Map.Entry<String, JsonNode>> testsIterator = test.fields();
                while (testsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> testObject = testsIterator.next();
                    assertThat(testObject.getValue(), CoreMatchers.instanceOf(ArrayNode.class));
                    ArrayNode testBody = (ArrayNode) testObject.getValue();
                    assertTestBodyForHeaders(testBody, headers);
                }
            });
    }

    /**
     * test file has a setup: block , but no relevant children
     */
    @Test
    public void testInjectHeadersWithSetupBlock() throws Exception {
        File testFile = new File(getClass().getResource("/rest/header_inject/with_setup.yml").toURI());
        YAMLParser yamlParser = yaml.createParser(testFile);
        List<ObjectNode> tests = mapper.readValues(yamlParser, ObjectNode.class).readAll();
        RestTestTransformer transformer = new RestTestTransformer();

        //validate setup exists
        assertThat(tests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));

        List<ObjectNode> transformedTests = transformer.transformRestTests(new LinkedList<>(tests),
            Collections.singletonList(new InjectHeaders(headers)));
        //ensure setup is correct
        assertThat(transformedTests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));
        transformedTests.stream().filter(node -> node.get("setup") != null).forEach(this::assertSetupForHeaders);
        //ensure do body is correct
        transformedTests
            .forEach(test -> {
                Iterator<Map.Entry<String, JsonNode>> testsIterator = test.fields();
                while (testsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> testObject = testsIterator.next();
                    assertThat(testObject.getValue(), CoreMatchers.instanceOf(ArrayNode.class));
                    ArrayNode testBody = (ArrayNode) testObject.getValue();
                    assertTestBodyForHeaders(testBody, headers);
                }
            });
    }

    /**
     * test file has a setup: then skip: but does not have the features: block
     */
    @Test
    public void testInjectHeadersWithSkipBlock() throws Exception {
        File testFile = new File(getClass().getResource("/rest/header_inject/with_skip.yml").toURI());
        YAMLParser yamlParser = yaml.createParser(testFile);
        List<ObjectNode> tests = mapper.readValues(yamlParser, ObjectNode.class).readAll();
        RestTestTransformer transformer = new RestTestTransformer();

        //validate setup exists
        assertThat(tests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));

        List<ObjectNode> skipNodes = tests.stream().filter(node -> node.get("setup") != null)
            .filter(node -> getSkipNode((ArrayNode) node.get("setup")) != null)
            .map(node -> getSkipNode((ArrayNode) node.get("setup"))).collect(Collectors.toList());

        //validate features does not exists
        assertThat(skipNodes.size(), CoreMatchers.equalTo(1));
        assertNull(skipNodes.get(0).get("features"));

        List<ObjectNode> transformedTests = transformer.transformRestTests(new LinkedList<>(tests),
            Collections.singletonList(new InjectHeaders(headers)));
        //ensure setup is correct
        assertThat(transformedTests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));
        transformedTests.stream().filter(node -> node.get("setup") != null).forEach(this::assertSetupForHeaders);
        //ensure do body is correct
        transformedTests
            .forEach(test -> {
                Iterator<Map.Entry<String, JsonNode>> testsIterator = test.fields();
                while (testsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> testObject = testsIterator.next();
                    assertThat(testObject.getValue(), CoreMatchers.instanceOf(ArrayNode.class));
                    ArrayNode testBody = (ArrayNode) testObject.getValue();
                    assertTestBodyForHeaders(testBody, headers);
                }
            });
    }


    /**
     * test file has a setup: then skip:, then features: block , but does not have the headers feature defined
     */
    @Test
    public void testInjectHeadersWithFeaturesBlock() throws Exception {
        File testFile = new File(getClass().getResource("/rest/header_inject/with_features.yml").toURI());
        YAMLParser yamlParser = yaml.createParser(testFile);
        List<ObjectNode> tests = mapper.readValues(yamlParser, ObjectNode.class).readAll();
        RestTestTransformer transformer = new RestTestTransformer();

        //validate setup exists
        assertThat(tests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));

        List<ObjectNode> skipNodes = tests.stream().filter(node -> node.get("setup") != null)
            .filter(node -> getSkipNode((ArrayNode) node.get("setup")) != null)
            .map(node -> getSkipNode((ArrayNode) node.get("setup"))).collect(Collectors.toList());

        //validate features exists
        assertThat(skipNodes.size(), CoreMatchers.equalTo(1));
        assertThat(skipNodes.get(0).get("features"), CoreMatchers.notNullValue());

        List<ObjectNode> transformedTests = transformer.transformRestTests(new LinkedList<>(tests),
            Collections.singletonList(new InjectHeaders(headers)));
        //ensure setup is correct
        assertThat(transformedTests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));
        transformedTests.stream().filter(node -> node.get("setup") != null).forEach(this::assertSetupForHeaders);
        //ensure do body is correct
        transformedTests
            .forEach(test -> {
                Iterator<Map.Entry<String, JsonNode>> testsIterator = test.fields();
                while (testsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> testObject = testsIterator.next();
                    assertThat(testObject.getValue(), CoreMatchers.instanceOf(ArrayNode.class));
                    ArrayNode testBody = (ArrayNode) testObject.getValue();
                    assertTestBodyForHeaders(testBody, headers);
                }
            });
    }

    /**
     * test file has a setup: then skip:, then features: block , and already has the headers feature defined
     */
    @Test
    public void testInjectHeadersWithHeadersBlock() throws Exception {
        File testFile = new File(getClass().getResource("/rest/header_inject/with_headers.yml").toURI());
        YAMLParser yamlParser = yaml.createParser(testFile);
        List<ObjectNode> tests = mapper.readValues(yamlParser, ObjectNode.class).readAll();
        RestTestTransformer transformer = new RestTestTransformer();

        //validate setup exists
        assertThat(tests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));

        List<ObjectNode> skipNodes = tests.stream().filter(node -> node.get("setup") != null)
            .filter(node -> getSkipNode((ArrayNode) node.get("setup")) != null)
            .map(node -> getSkipNode((ArrayNode) node.get("setup"))).collect(Collectors.toList());

        //validate features exists
        assertThat(skipNodes.size(), CoreMatchers.equalTo(1));
        assertThat(skipNodes.get(0).get("features"), CoreMatchers.notNullValue());

        JsonNode featureValues = skipNodes.get(0).get("features");
        List<String> features = new ArrayList<>(1);
        if (featureValues.isArray()) {
            Iterator<JsonNode> featuresIt = featureValues.elements();
            while (featuresIt.hasNext()) {
                JsonNode feature = featuresIt.next();
                features.add(feature.asText());
            }
        } else if (featureValues.isTextual()) {
            features.add(featureValues.asText());
        }
        assertThat(features, IsCollectionContaining.hasItem("headers"));

        List<ObjectNode> transformedTests = transformer.transformRestTests(new LinkedList<>(tests),
            Collections.singletonList(new InjectHeaders(headers)));
        //ensure setup is correct
        assertThat(transformedTests.stream().filter(node -> node.get("setup") != null).count(), CoreMatchers.equalTo(1L));
        transformedTests.stream().filter(node -> node.get("setup") != null).forEach(this::assertSetupForHeaders);
        //ensure do body is correct
        transformedTests
            .forEach(test -> {
                Iterator<Map.Entry<String, JsonNode>> testsIterator = test.fields();
                while (testsIterator.hasNext()) {
                    Map.Entry<String, JsonNode> testObject = testsIterator.next();
                    assertThat(testObject.getValue(), CoreMatchers.instanceOf(ArrayNode.class));
                    ArrayNode testBody = (ArrayNode) testObject.getValue();
                    assertTestBodyForHeaders(testBody, headers);
                }
            });
    }

    private void assertTestBodyForHeaders(ArrayNode testBody, Map<String, String> headers) {
        testBody.forEach(arrayObject -> {
            assertThat(arrayObject, CoreMatchers.instanceOf(ObjectNode.class));
            ObjectNode testSection = (ObjectNode) arrayObject;
            if (testSection.get("do") != null) {
                ObjectNode doSection = (ObjectNode) testSection.get("do");
                assertThat(doSection.get("headers"), CoreMatchers.notNullValue());
                ObjectNode headersNode = (ObjectNode) doSection.get("headers");
                LongAdder assertions = new LongAdder();
                headers.forEach((k, v) -> {
                    assertThat(headersNode.get(k), CoreMatchers.notNullValue());
                    TextNode textNode = (TextNode) headersNode.get(k);
                    assertThat(textNode.asText(), CoreMatchers.equalTo(v));
                    assertions.increment();
                });
                assertThat(assertions.intValue(), CoreMatchers.equalTo(headers.size()));
            }
        });
    }

    private void assertSetupForHeaders(ObjectNode setupNode) {
        assertThat(setupNode.get("setup"), CoreMatchers.instanceOf(ArrayNode.class));
        ObjectNode skipNode = getSkipNode((ArrayNode) setupNode.get("setup"));
        assertThat(skipNode, CoreMatchers.notNullValue());
        //transforms always results in an array of features, even if it is an array of 1
        assertThat(skipNode.get("features"), CoreMatchers.instanceOf(ArrayNode.class));
        ArrayNode features = (ArrayNode) skipNode.get("features");
        List<String> featureValues = new ArrayList<>();
        features.forEach(x -> {
            if (x.isTextual()) {
                featureValues.add(x.asText());
            }
        });
        assertThat(featureValues, IsCollectionContaining.hasItem("headers"));
    }

    private ObjectNode getSkipNode(ArrayNode setupNodeValue) {
        Iterator<JsonNode> setupIt = setupNodeValue.elements();
        while (setupIt.hasNext()) {
            JsonNode arrayEntry = setupIt.next();
            if (arrayEntry.isObject()) {
                ObjectNode skipCandidate = (ObjectNode) arrayEntry;
                if (skipCandidate.get("skip") != null) {
                    ObjectNode skipNode = (ObjectNode) skipCandidate.get("skip");
                    return skipNode;
                }
            }
        }
        return null;
    }
}
