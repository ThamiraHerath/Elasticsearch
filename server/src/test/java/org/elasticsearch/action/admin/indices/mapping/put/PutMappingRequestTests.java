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

package org.elasticsearch.action.admin.indices.mapping.put;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.RandomCreateIndexGenerator;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;

public class PutMappingRequestTests extends ESTestCase {

    public void testValidation() {
        PutMappingRequest r = new PutMappingRequest("myindex");
        ActionRequestValidationException ex = r.validate();
        assertNotNull("source validation should fail", ex);
        assertTrue(ex.getMessage().contains("source is missing"));

        r.source("", XContentType.JSON);
        ex = r.validate();
        assertNotNull("source validation should fail", ex);
        assertTrue(ex.getMessage().contains("source is empty"));

        r.source("somevalidmapping", XContentType.JSON);
        ex = r.validate();
        assertNull("validation should succeed", ex);

        r.setConcreteIndex(new Index("foo", "bar"));
        ex = r.validate();
        assertNotNull("source validation should fail", ex);
        assertEquals(ex.getMessage(),
            "Validation Failed: 1: either concrete index or unresolved indices can be set," +
                " concrete index: [[foo/bar]] and indices: [myindex];");
    }

    /**
     * Test that {@link PutMappingRequest#buildFromSimplifiedDef(String, Object...)}
     * rejects inputs where the {@code Object...} varargs of field name and properties are not
     * paired correctly
     */
    public void testBuildFromSimplifiedDef() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> PutMappingRequest.buildFromSimplifiedDef("type", "only_field"));
        assertEquals("mapping source must be pairs of fieldnames and properties definition.", e.getMessage());
    }

    public void testFromXContent() throws IOException {
        XContentBuilder mapping = randomMapping();
        String mappingSource = BytesReference.bytes(shuffleXContent(mapping)).utf8ToString();

        PutMappingRequest request = new PutMappingRequest();
        request.source(mappingSource, mapping.contentType());
        assertMappingsEqual(mappingSource, request.source());
    }

    private void assertMappingsEqual(String expected, String actual) throws IOException {

        try (XContentParser expectedJson = createParser(XContentType.JSON.xContent(), expected);
            XContentParser actualJson = createParser(XContentType.JSON.xContent(), actual)) {
            assertEquals(expectedJson.mapOrdered(), actualJson.mapOrdered());
        }
    }

    private static XContentBuilder randomMapping() throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        RandomCreateIndexGenerator.randomMappingFields(builder, true);
        builder.endObject();
        return builder;
    }

    /**
     * Returns a random {@link PutMappingRequest}.
     */
    private static PutMappingRequest createTestItem() throws IOException {
        String index = randomAlphaOfLength(5);
        PutMappingRequest request = new PutMappingRequest(index);
        request.source(RandomCreateIndexGenerator.randomMapping("_doc"));
        return request;
    }
}
