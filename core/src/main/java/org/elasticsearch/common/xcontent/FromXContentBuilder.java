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

package org.elasticsearch.common.xcontent;

import org.elasticsearch.cluster.CustomPrototypeRegistry;
import org.elasticsearch.common.ParseFieldMatcher;

import java.io.IOException;

/**
 * Indicates that the class supports XContent deserialization.
 */
public interface FromXContentBuilder<T> {
    /**
     * Parses an object with the type T from parser
     *
     * @param parser parser             The parsing containing the content to create the object
     * @param parseFieldMatcher         The matcher that matches field names and can make parsing if the field name is deprecated
     * @param registry registry         The registry used lookup how to parse custom metadata parts if exist.
     */
    T fromXContent(XContentParser parser, ParseFieldMatcher parseFieldMatcher, CustomPrototypeRegistry registry) throws IOException;
}
