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
package org.elasticsearch.test.rest.yaml.parser;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentLocation;
import org.elasticsearch.test.rest.yaml.section.MatchAssertion;

import java.io.IOException;

/**
 * Parser for match assert sections
 */
public class MatchParser implements ClientYamlTestFragmentParser<MatchAssertion> {

    @Override
    public MatchAssertion parse(ClientYamlTestSuiteParseContext parseContext) throws IOException, ClientYamlTestParseException {
        XContentLocation location = parseContext.parser().getTokenLocation();
        Tuple<String,Object> stringObjectTuple = parseContext.parseTuple();
        return new MatchAssertion(location, stringObjectTuple.v1(), stringObjectTuple.v2());
    }
}
