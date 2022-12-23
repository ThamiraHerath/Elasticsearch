/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test.cluster.util.resource;

import org.elasticsearch.test.cluster.util.IOUtils;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

class ClasspathTextResource implements TextResource {
    private final String resourcePath;

    ClasspathTextResource(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    @Override
    public String getText() {
        final Set<ClassLoader> classLoadersToSearch = new HashSet<>();
        // try context and system classloaders as well
        classLoadersToSearch.add(Thread.currentThread().getContextClassLoader());
        classLoadersToSearch.add(ClassLoader.getSystemClassLoader());
        classLoadersToSearch.add(ClasspathTextResource.class.getClassLoader());

        for (final ClassLoader classLoader : classLoadersToSearch) {
            if (classLoader == null) {
                continue;
            }

            InputStream resource = classLoader.getResourceAsStream(resourcePath);
            if (resource != null) {
                return IOUtils.readStringFromStream(resource);
            }

            // Be lenient if an absolute path was given
            if (resourcePath.startsWith("/")) {
                resource = classLoader.getResourceAsStream(resourcePath.replaceFirst("/", ""));
                if (resource != null) {
                    return IOUtils.readStringFromStream(resource);
                }
            }
        }

        throw new IllegalArgumentException(
            "Resource with path " + resourcePath + " could not be found on any of these classloaders: " + classLoadersToSearch
        );
    }
}
