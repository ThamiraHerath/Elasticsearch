/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.precommit;

import org.elasticsearch.gradle.internal.InternalPlugin;
import org.elasticsearch.gradle.precommit.PrecommitPlugin;
import org.elasticsearch.gradle.util.GradleUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;

import javax.inject.Inject;
import java.util.stream.Collectors;

public class ForbiddenPatternsPrecommitPlugin extends PrecommitPlugin implements InternalPlugin {

    private final ProviderFactory providerFactory;

    @Inject
    public ForbiddenPatternsPrecommitPlugin(ProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    @Override
    public TaskProvider<? extends Task> createTask(Project project) {
        return project.getTasks().register("forbiddenPatterns", ForbiddenPatternsTask.class, forbiddenPatternsTask -> {
            forbiddenPatternsTask.getSourceFolders()
                .addAll(
                    providerFactory.provider(
                        () -> GradleUtils.getJavaSourceSets(project).stream().map(s -> s.getAllSource()).collect(Collectors.toList())
                    )
                );
            forbiddenPatternsTask.getRootDir().set(project.getRootDir());
        });
    }
}
