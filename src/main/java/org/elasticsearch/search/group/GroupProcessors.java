/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.group;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.inject.Inject;

import java.util.Set;

/**
 *
 */
public class GroupProcessors {

    private final ImmutableMap<String, GroupProcessor> processors;

    @Inject
    public GroupProcessors(Set<GroupProcessor> processors) {
        MapBuilder<String, GroupProcessor> builder = MapBuilder.newMapBuilder();
        for (GroupProcessor processor : processors) {
            for (String type : processor.types()) {
                builder.put(type, processor);
            }
        }
        this.processors = builder.immutableMap();
    }

    public GroupProcessor processor(String type) {
        return processors.get(type);
    }
}
