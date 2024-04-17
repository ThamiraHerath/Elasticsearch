/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.repositories.hdfs;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.cluster.util.resource.Resource;
import org.elasticsearch.test.fixtures.hdfs.HdfsClientThreadLeakFilter;
import org.elasticsearch.test.fixtures.hdfs.HdfsFixture;
import org.elasticsearch.test.fixtures.krb5kdc.Krb5kDcContainer;
import org.elasticsearch.test.fixtures.testcontainers.TestContainersThreadFilter;
import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.util.Map;

@ThreadLeakFilters(filters = { HdfsClientThreadLeakFilter.class, TestContainersThreadFilter.class })
public class SecureRepositoryHdfsClientYamlTestSuiteIT extends ESClientYamlSuiteTestCase {
    public static Krb5kDcContainer krb5Fixture = new Krb5kDcContainer();

    public static HdfsFixture hdfsFixture = new HdfsFixture().withKerberos(() -> krb5Fixture.getPrincipal(), () -> krb5Fixture.getKeytab());

    public static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .plugin("repository-hdfs")
        .setting("xpack.license.self_generated.type", "trial")
        .setting("xpack.security.enabled", "false")
        .systemProperty("java.security.krb5.conf", () -> krb5Fixture.getConfPath().toString())
        .configFile("repository-hdfs/krb5.conf", Resource.fromString(() -> krb5Fixture.getConf()))
        .configFile("repository-hdfs/krb5.keytab", Resource.fromFile(() -> krb5Fixture.getEsKeytab()))
        .build();

    @ClassRule
    public static TestRule ruleChain = RuleChain.outerRule(krb5Fixture).around(hdfsFixture).around(cluster);

    public SecureRepositoryHdfsClientYamlTestSuiteIT(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return createParameters(new String[] { "secure_hdfs_repository" }, Map.of("secure_hdfs_port", hdfsFixture.getPort()));
    }
}
