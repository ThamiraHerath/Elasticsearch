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

package org.elasticsearch.gradle.http;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.io.File;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import org.elasticsearch.gradle.test.GradleUnitTestCase;

public class WaitForHttpResourceTests extends GradleUnitTestCase {

    public void testBuildTrustStoreFromFile() throws Exception {
        final WaitForHttpResource http = new WaitForHttpResource(new URL("https://localhost/"));
        final URL ca = getClass().getResource("/ca.p12");
        assertThat(ca, notNullValue());
        http.setTrustStoreFile(new File(ca.getPath()));
        http.setTrustStorePassword("password");
        final KeyStore store = http.buildTrustStore();
        final Certificate certificate = store.getCertificate("ca");
        assertThat(certificate, notNullValue());
        assertThat(certificate, instanceOf(X509Certificate.class));
        assertThat(
                ((X509Certificate) certificate).getSubjectDN().toString(),
                equalTo("CN=Elastic Certificate Tool Autogenerated CA"));
    }

    public void testBuildTrustStoreFromCA() throws Exception {
        final WaitForHttpResource http = new WaitForHttpResource(new URL("https://localhost/"));
        final URL ca = getClass().getResource("/ca.pem");
        assertThat(ca, notNullValue());
        http.setCertificateAuthorities(new File(ca.getPath()));
        final KeyStore store = http.buildTrustStore();
        final Certificate certificate = store.getCertificate("cert-0");
        assertThat(certificate, notNullValue());
        assertThat(certificate, instanceOf(X509Certificate.class));
        assertThat(
                ((X509Certificate) certificate).getSubjectDN().toString(),
                equalTo("CN=Elastic Certificate Tool Autogenerated CA"));
    }
}
