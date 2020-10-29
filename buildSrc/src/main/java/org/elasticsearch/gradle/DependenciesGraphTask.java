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

package org.elasticsearch.gradle;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;

import java.util.HashSet;
import java.util.Set;

/**
 * A task to generate a dependency graph of our runtime dependencies and push that via
 * an API call to a given endpoint of a SCA tool/service.
 * The graph is built according to the specification in https://github.com/snyk/dep-graph#depgraphdata
 *
 * Due to the nature of our dependency resolution in gradle, we are abusing the aforementioned graph definition as
 * the graph we construct has a single root ( the subproject ) and all dependencies are children of that root,
 * irrespective of if they are direct dependencies or transitive ones ( that should be children of other children ).
 * Although we end up lacking some contextual information, this allows us to scan and monitor only the dependencies
 * that are bundled and used in runtime.
 */
public class DependenciesGraphTask extends DefaultTask {

    private Configuration runtimeConfiguration;
    private Configuration compileOnlyConfiguration;

    @InputFiles
    public Configuration getRuntimeConfiguration() {
        return runtimeConfiguration;
    }

    @InputFiles
    public Configuration getCompileOnlyConfiguration() {
        return compileOnlyConfiguration;
    }

    public void setRuntimeConfiguration(Configuration runtimeConfiguration) {
        this.runtimeConfiguration = runtimeConfiguration;
    }

    public void setCompileOnlyConfiguration(Configuration compileOnlyConfiguration) {
        this.compileOnlyConfiguration = compileOnlyConfiguration;
    }

    @TaskAction
    void generateDependenciesGraph() {

        final String url = System.getenv("SCA_URL");
        final String token = System.getenv("SCA_TOKEN");
        if (null == url || null == token) {
            throw new GradleException("The environment variables SCA_URL and SCA_TOKEN need to be set before this task is run");
        }
        final DependencySet runtimeDependencies = runtimeConfiguration.getAllDependencies();
        final Set<String> packages = new HashSet<>();
        final Set<String> nodes = new HashSet<>();
        final Set<String> nodeIds = new HashSet<>();
        for (final Dependency dependency : runtimeDependencies) {
            final String id = dependency.getGroup() + ":" + dependency.getName();
            final String versionedId = id + "@" + dependency.getVersion();
            final StringBuilder packageString = new StringBuilder();
            final StringBuilder nodeString = new StringBuilder();
            if (dependency instanceof ProjectDependency) {
                continue;
            }
            packageString.append("{\"id\": \"")
                .append(versionedId)
                .append("\",\"info\": {\"name\": \"")
                .append(id)
                .append("\",\"version\": \"")
                .append(dependency.getVersion())
                .append("\"}}");
            packages.add(packageString.toString());
            nodeString.append("{\"nodeId\": \"")
                .append(versionedId)
                .append("\",\"pkgId\": \"")
                .append(versionedId)
                .append("\",\"deps\": []}");
            nodes.add(nodeString.toString());
            nodeIds.add("{\"nodeId\": \"" + versionedId + "\"}");
        }
        // We add one package and one node for each dependency, it suffices to check packages.
        if (packages.size() > 0) {
            final String projectName = "elastic/elasticsearch" + getProject().getPath();
            final StringBuilder output = new StringBuilder();
            output.append("{\"depGraph\": {\"schemaVersion\": \"1.2.0\",\"pkgManager\": {\"name\": \"gradle\"},\"pkgs\": [")
                .append("{\"id\": \"")
                .append(projectName)
                .append("@0.0.0")
                .append("\", \"info\": {\"name\": \"")
                .append(projectName)
                .append("\", \"version\": \"0.0.0\"}},")
                .append(String.join(",", packages))
                .append("],\"graph\": {\"rootNodeId\": \"")
                .append(projectName)
                .append("@0.0.0")
                .append("\",\"nodes\": [")
                .append("{\"nodeId\": \"")
                .append(projectName)
                .append("@0.0.0")
                .append("\",\"pkgId\": \"")
                .append(projectName)
                .append("@0.0.0")
                .append("\",\"deps\": [")
                .append(String.join(",", nodeIds))
                .append("]},")
                .append(String.join(",", nodes))
                .append("]}}}");
            getLogger().debug("Dependency Graph: " + output.toString());
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost postRequest = new HttpPost(url);
                postRequest.addHeader("Authorization", "token " + token);
                postRequest.addHeader("Content-Type", "application/json");
                postRequest.setEntity(new StringEntity(output.toString()));
                CloseableHttpResponse response = client.execute(postRequest);
                getLogger().info("API call response status: " + response.getStatusLine().getStatusCode());
                getLogger().debug(EntityUtils.toString(response.getEntity()));
            } catch (Exception e) {
                throw new GradleException("Failed to call API endpoint to submit updated dependency graph", e);
            }
        }
    }
}
