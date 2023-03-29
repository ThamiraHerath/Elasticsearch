/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.remotecluster;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.Strings;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;

public class RemoteClusterSecurityRestWithMultipleRemotesIT extends AbstractRemoteClusterSecurityTestCase {

    private static final AtomicReference<Map<String, Object>> API_KEY_MAP_REF = new AtomicReference<>();
    private static final ElasticsearchCluster secondFulfillingCluster;
    private static RestClient secondFulfillingClusterClient;

    static {
        fulfillingCluster = ElasticsearchCluster.local()
            .name("fulfilling-cluster-1")
            .apply(commonClusterConfig)
            .setting("remote_cluster_server.enabled", "true")
            .setting("remote_cluster.port", "0")
            .setting("xpack.security.remote_cluster_server.ssl.enabled", "true")
            .setting("xpack.security.remote_cluster_server.ssl.key", "remote-cluster.key")
            .setting("xpack.security.remote_cluster_server.ssl.certificate", "remote-cluster.crt")
            .keystore("xpack.security.remote_cluster_server.ssl.secure_key_passphrase", "remote-cluster-password")
            .build();

        secondFulfillingCluster = ElasticsearchCluster.local().name("fulfilling-cluster-2").apply(commonClusterConfig).build();

        queryCluster = ElasticsearchCluster.local()
            .name("query-cluster")
            .apply(commonClusterConfig)
            .setting("xpack.security.remote_cluster_client.ssl.enabled", "true")
            .setting("xpack.security.remote_cluster_client.ssl.certificate_authorities", "remote-cluster-ca.crt")
            .keystore("cluster.remote.my_remote_cluster.credentials", () -> {
                if (API_KEY_MAP_REF.get() == null) {
                    final Map<String, Object> apiKeyMap = createCrossClusterAccessApiKey("""
                        [
                          {
                             "names": ["cluster1_index*"],
                             "privileges": ["read", "read_cross_cluster"]
                          }
                        ]""");
                    API_KEY_MAP_REF.set(apiKeyMap);
                }
                return (String) API_KEY_MAP_REF.get().get("encoded");
            })
            .build();
    }

    @ClassRule
    // Use a RuleChain to ensure that fulfilling cluster is started before query cluster
    public static TestRule clusterRule = RuleChain.outerRule(fulfillingCluster).around(secondFulfillingCluster).around(queryCluster);

    @BeforeClass
    public static void initSecondFulfillingClusterClient() {
        secondFulfillingClusterClient = buildRestClient(secondFulfillingCluster);
    }

    @AfterClass
    public static void closeSecondFulfillingClusterClient() throws IOException {
        IOUtils.close(secondFulfillingClusterClient);
    }

    public void testCrossClusterSearch() throws Exception {
        configureRemoteClusters();

        // Fulfilling cluster
        {
            // Index some documents, so we can attempt to search them from the querying cluster
            final Request bulkRequest = new Request("POST", "/_bulk?refresh=true");
            bulkRequest.setJsonEntity(Strings.format("""
                { "index": { "_index": "cluster1_index1" } }
                { "cluster1_doc1": "doc_value" }
                { "index": { "_index": "cluster1_index2" } }
                { "cluster1_doc2": "doc_value" }\n"""));
            assertOK(performRequestAgainstFulfillingCluster(bulkRequest));
        }

        // Second fulfilling cluster
        {
            // In the basic model, we need to set up the role on the FC
            final var putRoleRequest = new Request("PUT", "/_security/role/" + REMOTE_SEARCH_ROLE);
            putRoleRequest.setJsonEntity("""
                {
                  "indices": [
                    {
                      "names": ["cluster*_index1"],
                      "privileges": ["read", "read_cross_cluster"]
                    }
                  ]
                }""");
            assertOK(performRequestWithDefaultUser(secondFulfillingClusterClient, putRoleRequest));

            // Index some documents, so we can attempt to search them from the querying cluster
            final Request bulkRequest = new Request("POST", "/_bulk?refresh=true");
            bulkRequest.setJsonEntity(Strings.format("""
                { "index": { "_index": "cluster2_index1" } }
                { "cluster2_doc1": "doc_value" }
                { "index": { "_index": "cluster2_index2" } }
                { "cluster2_doc2": "doc_value" }\n"""));
            assertOK(performRequestWithDefaultUser(secondFulfillingClusterClient, bulkRequest));
        }

        // Query cluster
        {
            // Index some documents, to use them in a mixed-cluster search
            final var indexDocRequest = new Request("POST", "/local_index/_doc?refresh=true");
            indexDocRequest.setJsonEntity("{\"local_doc1\": \"doc_value\"}");
            assertOK(client().performRequest(indexDocRequest));

            // Create user role with privileges for remote and local indices
            final var putRoleRequest = new Request("PUT", "/_security/role/" + REMOTE_SEARCH_ROLE);
            putRoleRequest.setJsonEntity("""
                {
                  "indices": [
                    {
                      "names": ["local_index"],
                      "privileges": ["read"]
                    }
                  ],
                  "remote_indices": [
                    {
                      "names": ["cluster*_index1"],
                      "privileges": ["read", "read_cross_cluster"],
                      "clusters": ["my_remote_cluster"]
                    }
                  ]
                }""");
            assertOK(adminClient().performRequest(putRoleRequest));
            final var putUserRequest = new Request("PUT", "/_security/user/" + REMOTE_SEARCH_USER);
            putUserRequest.setJsonEntity("""
                {
                  "password": "x-pack-test-password",
                  "roles" : ["remote_search"]
                }""");
            assertOK(adminClient().performRequest(putUserRequest));

            // Can search across local cluster and both remotes
            searchAndAssertIndicesFound(
                String.format(
                    Locale.ROOT,
                    "/local_index,%s:%s/_search?ccs_minimize_roundtrips=%s",
                    randomFrom("my_remote_*", "*"),
                    randomFrom("*_index1", "*"),
                    randomBoolean()
                ),
                "cluster1_index1",
                "cluster2_index1",
                "local_index"
            );

            // Can search across both remotes using cluster alias wildcard
            searchAndAssertIndicesFound(
                String.format(
                    Locale.ROOT,
                    "/%s:%s/_search?ccs_minimize_roundtrips=%s",
                    randomFrom("my_remote_*", "*"),
                    randomFrom("*_index1", "*"),
                    randomBoolean()
                ),
                "cluster1_index1",
                "cluster2_index1"
            );

            // Can search across both remotes cluster using explicit cluster aliases
            searchAndAssertIndicesFound(
                String.format(
                    Locale.ROOT,
                    "/my_remote_cluster:%s,my_remote_cluster_2:%s/_search?ccs_minimize_roundtrips=%s",
                    randomFrom("cluster1_index1", "*_index1", "*"),
                    randomFrom("cluster2_index1", "*_index1", "*"),
                    randomBoolean()
                ),
                "cluster1_index1",
                "cluster2_index1"
            );

            // Can search single remote cluster
            boolean searchFirstCluster = randomBoolean();
            String expectedIndex = searchFirstCluster ? "cluster1_index1" : "cluster2_index1";
            searchAndAssertIndicesFound(
                String.format(
                    Locale.ROOT,
                    "/%s:%s/_search?ccs_minimize_roundtrips=%s",
                    searchFirstCluster ? "my_remote_cluster" : "my_remote_cluster_2",
                    randomFrom(expectedIndex, "*_index1", "*"),
                    randomBoolean()
                ),
                expectedIndex
            );
        }
    }

    private void searchAndAssertIndicesFound(String searchPath, String... expectedIndices) throws IOException {
        final var searchRequest = new Request("GET", searchPath);
        final Response response = performRequestWithRemoteSearchUser(searchRequest);
        assertOK(response);
        final SearchResponse searchResponse = SearchResponse.fromXContent(responseAsParser(response));
        final List<String> actualIndices = Arrays.stream(searchResponse.getHits().getHits())
            .map(SearchHit::getIndex)
            .collect(Collectors.toList());
        assertThat(actualIndices, containsInAnyOrder(expectedIndices));
    }

    @Override
    protected void configureRemoteClusters() throws Exception {
        super.configureRemoteClusters();
        configureRemoteCluster("my_remote_cluster_2", secondFulfillingCluster, true, randomBoolean());
    }

    private Response performRequestWithRemoteSearchUser(final Request request) throws IOException {
        request.setOptions(RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", basicAuthHeaderValue(REMOTE_SEARCH_USER, PASS)));
        return client().performRequest(request);
    }
}
