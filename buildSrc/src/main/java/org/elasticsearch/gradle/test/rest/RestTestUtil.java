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

package org.elasticsearch.gradle.test.rest;

import org.elasticsearch.gradle.VersionProperties;
import org.elasticsearch.gradle.info.BuildParams;
import org.elasticsearch.gradle.plugin.PluginPropertiesExtension;
import org.elasticsearch.gradle.test.RestIntegTestTask;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;

/**
 * Utility class to configure the necessary tasks and dependencies.
 */
public class RestTestUtil {

    private RestTestUtil() {}

    /**
     * Creates a task with the source set name of type {@link RestIntegTestTask}
     */
    static RestIntegTestTask setupTask(Project project, String sourceSetName) {
        // create task - note can not use .register due to the work in RestIntegTestTask's constructor :(
        // see: https://github.com/elastic/elasticsearch/issues/47804
        RestIntegTestTask testTask = project.getTasks().create(sourceSetName, RestIntegTestTask.class);
        testTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
        testTask.setDescription("Runs the REST tests against an external cluster");
        // make the new test run after unit tests
        testTask.mustRunAfter(project.getTasks().named("test"));
        return testTask;
    }

    /**
     * Creates the runner task and configures the test clusters
     */
    static void setupRunnerTask(Project project, RestIntegTestTask testTask, SourceSet sourceSet) {
        testTask.setTestClassesDirs(sourceSet.getOutput().getClassesDirs());
        testTask.setClasspath(sourceSet.getRuntimeClasspath());
    }

    /**
     * Setup the dependencies needed for the REST tests.
     */
    static void setupDependencies(Project project, SourceSet sourceSet) {
        if (BuildParams.isInternal()) {
            project.getDependencies().add(sourceSet.getImplementationConfigurationName(), project.project(":test:framework"));
        } else {
            project.getDependencies()
                .add(
                    sourceSet.getImplementationConfigurationName(),
                    "org.elasticsearch.test:framework:" + VersionProperties.getElasticsearch()
                );
        }

    }

}
