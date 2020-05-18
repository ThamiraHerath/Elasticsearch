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

package org.elasticsearch.gradle.precommit;

import org.elasticsearch.gradle.util.GradleUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Base plugin for adding a precommit task.
 */
public abstract class PrecommitPlugin implements Plugin<Project> {
    @Override
    public final void apply(Project project) {
        TaskProvider<? extends Task> task = createTask(project);
        TaskProvider<DefaultTask> precommit = GradleUtils.maybeRegister(project.getTasks(), "precommit", DefaultTask.class, t -> {
            t.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
            t.setDescription("Runs all non-test checks");
        });
        precommit.configure(t -> t.dependsOn(task));

        project.getPluginManager().withPlugin("java", p -> {
            // We want to get any compilation error before running the pre-commit checks.
            for (SourceSet sourceSet : GradleUtils.getJavaSourceSets(project)) {
                task.configure(t -> t.shouldRunAfter(sourceSet.getClassesTaskName()));
            }

            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(t -> t.dependsOn(precommit));
            project.getTasks().named(JavaPlugin.TEST_TASK_NAME).configure(t -> t.mustRunAfter(precommit));
        });
    }

    public abstract TaskProvider<? extends Task> createTask(Project project);
}
