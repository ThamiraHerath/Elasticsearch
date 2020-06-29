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

package org.elasticsearch.index.mapper;

import org.elasticsearch.Version;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ParametrizedMapperTests extends ESTestCase {

    private static TestMapper toType(Mapper in) {
        return (TestMapper) in;
    }

    public static class Builder extends ParametrizedFieldMapper.Builder {

        final ParametrizedFieldMapper.Parameter<Boolean> fixed
            = ParametrizedFieldMapper.Parameter.boolParam("fixed", false, m -> toType(m).fixed, true);
        final ParametrizedFieldMapper.Parameter<Boolean> fixed2
            = ParametrizedFieldMapper.Parameter.boolParam("fixed2", false, m -> toType(m).fixed2, false);
        final ParametrizedFieldMapper.Parameter<String> variable
            = ParametrizedFieldMapper.Parameter.stringParam("variable", true, m -> toType(m).variable, "default");

        protected Builder(String name) {
            super(name);
        }

        @Override
        protected List<ParametrizedFieldMapper.Parameter<?>> getParameters() {
            return List.of(fixed, fixed2, variable);
        }

        @Override
        public ParametrizedFieldMapper build(Mapper.BuilderContext context) {
            return new TestMapper(buildFullName(context), fixed.getValue(), fixed2.getValue(), variable.getValue(),
                multiFieldsBuilder.build(this, context), copyTo.build());
        }
    }

    public static class TypeParser implements Mapper.TypeParser {

        @Override
        public Mapper.Builder<?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder(name);
            builder.parse(name, parserContext, node);
            return builder;
        }
    }

    public static class TestMapper extends ParametrizedFieldMapper {

        private final boolean fixed;
        private final boolean fixed2;
        private final String variable;

        protected TestMapper(String simpleName, boolean fixed, boolean fixed2, String variable, MultiFields multiFields, CopyTo copyTo) {
            super(simpleName, new KeywordFieldMapper.KeywordFieldType("test"), multiFields, copyTo);
            this.fixed = fixed;
            this.fixed2 = fixed2;
            this.variable = variable;
        }

        @Override
        public Builder getMergeBuilder() {
            return new ParametrizedMapperTests.Builder(name()).init(this);
        }

        @Override
        protected void parseCreateField(ParseContext context) throws IOException {

        }

        @Override
        protected String contentType() {
            return "test_mapper";
        }
    }

    private static TestMapper fromMapping(String mapping) {
        Mapper.TypeParser.ParserContext pc = new Mapper.TypeParser.ParserContext(s -> null, null, s -> {
            if (Objects.equals("keyword", s)) {
                return new KeywordFieldMapper.TypeParser();
            }
            if (Objects.equals("binary", s)) {
                return new BinaryFieldMapper.TypeParser();
            }
            return null;
        }, Version.CURRENT, () -> null);
        return (TestMapper) new TypeParser()
            .parse("test", XContentHelper.convertToMap(JsonXContent.jsonXContent, mapping, true), pc)
            .build(new Mapper.BuilderContext(Settings.EMPTY, new ContentPath(0)));
    }

    // defaults - create empty builder config, and serialize with and without defaults
    public void testDefaults() throws IOException {
        String mapping = "{\"type\":\"test_mapper\"}";
        TestMapper mapper = fromMapping(mapping);

        assertTrue(mapper.fixed);
        assertEquals("default", mapper.variable);

        assertEquals("{\"test\":" + mapping + "}", Strings.toString(mapper));

        XContentBuilder builder = JsonXContent.contentBuilder();
        ToXContent.Params params = new ToXContent.MapParams(Map.of("include_defaults", "true"));
        builder.startObject();
        mapper.toXContent(builder, params);
        builder.endObject();
        assertEquals("{\"test\":{\"type\":\"test_mapper\",\"fixed\":true,\"fixed2\":false,\"variable\":\"default\"}}",
            Strings.toString(builder));
    }

    // merging - try updating 'fixed' and 'fixed2' should get an error, try updating 'variable' and verify update
    public void testMerging() {
        String mapping = "{\"type\":\"test_mapper\",\"fixed\":false}";
        TestMapper mapper = fromMapping(mapping);
        assertEquals("{\"test\":" + mapping + "}", Strings.toString(mapper));

        TestMapper badMerge = fromMapping("{\"type\":\"test_mapper\",\"fixed\":true,\"fixed2\":true}");
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> mapper.merge(badMerge));
        String expectedError = "Mapper for [test] conflicts with existing mapper:\n" +
            "\tCannot update parameter [fixed] from [false] to [true]\n" +
            "\tCannot update parameter [fixed2] from [false] to [true]";
        assertEquals(expectedError, e.getMessage());

        assertEquals("{\"test\":" + mapping + "}", Strings.toString(mapper));   // original mapping is unaffected

        // TODO: should we have to include 'fixed' here? Or should updates take as 'defaults' the existing values?
        TestMapper goodMerge = fromMapping("{\"type\":\"test_mapper\",\"fixed\":false,\"variable\":\"updated\"}");
        TestMapper merged = (TestMapper) mapper.merge(goodMerge);

        assertEquals("{\"test\":" + mapping + "}", Strings.toString(mapper));   // original mapping is unaffected
        assertEquals("{\"test\":{\"type\":\"test_mapper\",\"fixed\":false,\"variable\":\"updated\"}}", Strings.toString(merged));

    }

    // add multifield, verify, add second multifield, verify, overwrite second multifield
    public void testMultifields() {
        String mapping = "{\"type\":\"test_mapper\",\"variable\":\"foo\",\"fields\":{\"sub\":{\"type\":\"keyword\"}}}";
        TestMapper mapper = fromMapping(mapping);
        assertEquals("{\"test\":" + mapping + "}", Strings.toString(mapper));

        String addSubField = "{\"type\":\"test_mapper\",\"variable\":\"foo\",\"fields\":{\"sub2\":{\"type\":\"keyword\"}}}";
        TestMapper toMerge = fromMapping(addSubField);
        TestMapper merged = (TestMapper) mapper.merge(toMerge);
        assertEquals("{\"test\":{\"type\":\"test_mapper\",\"variable\":\"foo\"," +
            "\"fields\":{\"sub\":{\"type\":\"keyword\"},\"sub2\":{\"type\":\"keyword\"}}}}", Strings.toString(merged));

        String badSubField = "{\"type\":\"test_mapper\",\"variable\":\"foo\",\"fields\":{\"sub2\":{\"type\":\"binary\"}}}";
        TestMapper badToMerge = fromMapping(badSubField);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> merged.merge(badToMerge));
        assertEquals("mapper [test.sub2] cannot be changed from type [keyword] to [binary]", e.getMessage());
    }

    // add copy_to, verify
    public void testCopyTo() {
        String mapping = "{\"type\":\"test_mapper\",\"variable\":\"foo\",\"copy_to\":[\"other\"]}";
        TestMapper mapper = fromMapping(mapping);
        assertEquals("{\"test\":" + mapping + "}", Strings.toString(mapper));

        // On update, copy_to is completely replaced

        TestMapper toMerge = fromMapping("{\"type\":\"test_mapper\",\"variable\":\"updated\",\"copy_to\":[\"foo\",\"bar\"]}");
        TestMapper merged = (TestMapper) mapper.merge(toMerge);
        assertEquals("{\"test\":{\"type\":\"test_mapper\",\"variable\":\"updated\",\"copy_to\":[\"foo\",\"bar\"]}}",
            Strings.toString(merged));

        TestMapper removeCopyTo = fromMapping("{\"type\":\"test_mapper\",\"variable\":\"updated\"}");
        TestMapper noCopyTo = (TestMapper) merged.merge(removeCopyTo);
        assertEquals("{\"test\":{\"type\":\"test_mapper\",\"variable\":\"updated\"}}", Strings.toString(noCopyTo));
    }

}
