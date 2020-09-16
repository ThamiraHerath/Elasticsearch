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

package org.elasticsearch.gradle.test;

import com.carrotsearch.randomizedtesting.ThreadFilter;

/**
 * Filter out threads controlled by gradle that may be created during unit tests.
 *
 * Currently this includes pooled threads for Exec as well as file system event watcher threads.
 */
public class GradleThreadsFilter implements ThreadFilter {

    @Override
    public boolean reject(Thread t) {
        return t.getName().startsWith("Exec process") ||
                t.getName().startsWith("File watcher consumer") ||
                t.getName().startsWith("Memory manager");

    }
}
