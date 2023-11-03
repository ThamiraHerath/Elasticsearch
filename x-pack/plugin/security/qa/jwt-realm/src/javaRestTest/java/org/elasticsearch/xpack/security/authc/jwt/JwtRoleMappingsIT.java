/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.authc.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.core.Strings;
import org.elasticsearch.test.TestSecurityClient;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.cluster.util.resource.Resource;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.user.User;
import org.hamcrest.Matchers;
import org.junit.BeforeClass;
import org.junit.ClassRule;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.instanceOf;

public class JwtRoleMappingsIT extends ESRestTestCase {

    @ClassRule
    public static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .nodes(1)
        .distribution(DistributionType.DEFAULT)
        .configFile("http.key", Resource.fromClasspath("ssl/http.key"))
        .configFile("http.crt", Resource.fromClasspath("ssl/http.crt"))
        .configFile("ca.crt", Resource.fromClasspath("ssl/ca.crt"))
        .configFile("rsa.jwkset", Resource.fromClasspath("jwk/rsa-public-jwkset.json"))
        .setting("xpack.ml.enabled", "false")
        .setting("xpack.license.self_generated.type", "trial")
        .setting("xpack.security.enabled", "true")
        .setting("xpack.security.http.ssl.enabled", "true")
        .setting("xpack.security.transport.ssl.enabled", "false")
        .setting("xpack.security.authc.token.enabled", "true")
        .setting("xpack.security.authc.api_key.enabled", "true")

        .setting("xpack.security.http.ssl.enabled", "true")
        .setting("xpack.security.http.ssl.certificate", "http.crt")
        .setting("xpack.security.http.ssl.key", "http.key")
        .setting("xpack.security.http.ssl.key_passphrase", "http-password")
        .setting("xpack.security.http.ssl.certificate_authorities", "ca.crt")
        .setting("xpack.security.http.ssl.client_authentication", "optional")

        .setting("xpack.security.authc.realms.file.admin_file.order", "0")

        .setting("xpack.security.authc.realms.jwt.jwt1.order", "1")
        .setting("xpack.security.authc.realms.jwt.jwt1.allowed_issuer", "https://issuer.example.com/")
        .setting("xpack.security.authc.realms.jwt.jwt1.allowed_audiences", "https://audience.example.com/")
        .setting("xpack.security.authc.realms.jwt.jwt1.claims.principal", "sub")
        .setting("xpack.security.authc.realms.jwt.jwt1.claims.groups", "roles")
        .setting("xpack.security.authc.realms.jwt.jwt1.claims.dn", "dn")
        .setting("xpack.security.authc.realms.jwt.jwt1.claims.name", "name")
        .setting("xpack.security.authc.realms.jwt.jwt1.claims.mail", "mail")
        .setting("xpack.security.authc.realms.jwt.jwt1.required_claims.token_use", "id")
        .setting("xpack.security.authc.realms.jwt.jwt1.required_claims.version", "2.0")
        .setting("xpack.security.authc.realms.jwt.jwt1.client_authentication.type", "NONE")
        .setting("xpack.security.authc.realms.jwt.jwt1.pkc_jwkset_path", "rsa.jwkset")

        .setting("xpack.security.authc.role_mapping.cache_last_successful_load", "true")
        .user("admin_user", "admin-password")
        .build();

    private static Path httpCertificateAuthority;
    private TestSecurityClient adminSecurityClient;

    @BeforeClass
    public static void findTrustStore() throws Exception {
        httpCertificateAuthority = findResource("/ssl/ca.crt");
    }

    private static Path findResource(String name) throws FileNotFoundException, URISyntaxException {
        final URL resource = JwtRoleMappingsIT.class.getResource(name);
        if (resource == null) {
            throw new FileNotFoundException("Cannot find classpath resource " + name);
        }
        final Path path = PathUtils.get(resource.toURI());
        return path;
    }

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    @Override
    protected String getProtocol() {
        return "https";
    }

    @Override
    protected Settings restAdminSettings() {
        String token = basicAuthHeaderValue("admin_user", new SecureString("admin-password".toCharArray()));
        return Settings.builder().put(ThreadContext.PREFIX + ".Authorization", token).put(restSslSettings()).build();
    }

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(super.restClientSettings()).put(restSslSettings()).build();
    }

    private Settings restSslSettings() {
        return Settings.builder().put(CERTIFICATE_AUTHORITIES, httpCertificateAuthority).build();
    }

    protected TestSecurityClient getAdminSecurityClient() {
        if (adminSecurityClient == null) {
            adminSecurityClient = new TestSecurityClient(adminClient());
        }
        return adminSecurityClient;
    }

    public void testAuthenticateWithCachedRoleMapping() throws Exception {
        final String principal = randomPrincipal();
        final String dn = randomDn();
        final String name = randomName();
        final String mail = randomMail();

        final String rules = Strings.format("""
            { "all": [
                { "field": { "realm.name": "jwt1" } },
                { "field": { "dn": "%s" } }
            ] }
            """, dn);

        final List<String> roles = randomRoles();
        final String roleMappingName = createRoleMapping(roles, rules);

        try {
            final SignedJWT jwt = buildAndSignJwt(principal, dn, name, mail, List.of(), Instant.now());
            final TestSecurityClient client = getSecurityClient(jwt);

            final Map<String, Object> response = client.authenticate();

            final String description = "Authentication response [" + response + "]";
            assertThat(description, response, hasEntry(User.Fields.USERNAME.getPreferredName(), principal));
            assertThat(
                description,
                JwtRestIT.assertMap(response, User.Fields.AUTHENTICATION_REALM),
                hasEntry(User.Fields.REALM_NAME.getPreferredName(), "jwt1")
            );
            assertThat(
                description,
                JwtRestIT.assertList(response, User.Fields.ROLES),
                Matchers.containsInAnyOrder(roles.toArray(String[]::new))
            );
            assertThat(description, JwtRestIT.assertMap(response, User.Fields.METADATA), hasEntry("jwt_token_type", "id_token"));

            makeSecurityIndexUnavailable();

            final String principal2 = randomFrom(principal, randomPrincipal());
            final SignedJWT jwt2 = buildAndSignJwt(principal2, dn, name, mail, List.of(), Instant.now());
            final TestSecurityClient client2 = getSecurityClient(jwt2);
            final Map<String, Object> response2 = client2.authenticate();
            final String description2 = "Authentication response [" + response2 + "]";
            assertThat(description2, response2, hasEntry(User.Fields.USERNAME.getPreferredName(), principal2));
            assertThat(
                description2,
                JwtRestIT.assertMap(response2, User.Fields.AUTHENTICATION_REALM),
                hasEntry(User.Fields.REALM_NAME.getPreferredName(), "jwt1")
            );
            assertThat(
                description2,
                JwtRestIT.assertList(response2, User.Fields.ROLES),
                Matchers.containsInAnyOrder(roles.toArray(String[]::new))
            );
            assertThat(description2, JwtRestIT.assertMap(response2, User.Fields.METADATA), hasEntry("jwt_token_type", "id_token"));
        } finally {
            restoreSecurityIndexAvailability();
            deleteRoleMapping(roleMappingName);
        }
    }

    private void restoreSecurityIndexAvailability() throws IOException {
        Request openRequest = new Request("POST", "/.security/_open");
        openRequest.setOptions(systemIndexWarningHandlerOptions(".security-7"));
        assertOK(adminClient().performRequest(openRequest));
    }

    private void makeSecurityIndexUnavailable() throws IOException {
        Request closeRequest = new Request("POST", "/.security/_close");
        closeRequest.setOptions(systemIndexWarningHandlerOptions(".security-7"));
        assertOK(adminClient().performRequest(closeRequest));
    }

    private RequestOptions.Builder systemIndexWarningHandlerOptions(String index) {
        return RequestOptions.DEFAULT.toBuilder()
            .setWarningsHandler(
                w -> w.size() > 0
                    && w.contains(
                        "this request accesses system indices: ["
                            + index
                            + "], but in a future major "
                            + "version, direct access to system indices will be prevented by default"
                    ) == false
            );
    }

    private String randomPrincipal() {
        // We append _test so that it cannot randomly conflict with builtin user
        return randomAlphaOfLengthBetween(4, 12) + "_test";
    }

    private String randomDn() {
        return "CN=" + randomPrincipal();
    }

    private String randomName() {
        return randomPrincipal() + "_name";
    }

    private String randomMail() {
        return randomPrincipal() + "_mail@example.com";
    }

    private List<String> randomRoles() {
        // We append _test so that it cannot randomly conflict with builtin roles
        return randomList(1, 3, () -> randomAlphaOfLengthBetween(4, 12) + "_test");
    }

    private SignedJWT buildAndSignJwt(String principal, String dn, String name, String mail, List<String> groups, Instant issueTime)
        throws JOSEException, ParseException, IOException {
        final JWTClaimsSet claimsSet = JwtRestIT.buildJwt(
            Map.ofEntries(
                Map.entry("iss", "https://issuer.example.com/"),
                Map.entry("aud", "https://audience.example.com/"),
                Map.entry("sub", principal),
                Map.entry("dn", dn),
                Map.entry("name", name),
                Map.entry("mail", mail),
                Map.entry("roles", groups), // Realm config has `claim.groups: "roles"`
                Map.entry("token_use", "id"),
                Map.entry("version", "2.0")
            ),
            issueTime
        );
        final RSASSASigner signer = loadRsaSigner();
        return JwtRestIT.signJWT(signer, "RS256", claimsSet);
    }

    private RSASSASigner loadRsaSigner() throws IOException, ParseException, JOSEException {
        try (var in = getDataInputStream("/jwk/rsa-private-jwkset.json")) {
            final JWKSet jwkSet = JWKSet.load(in);
            final JWK key = jwkSet.getKeyByKeyId("test-rsa-key");
            assertThat(key, instanceOf(RSAKey.class));
            return new RSASSASigner((RSAKey) key);
        }
    }

    private TestSecurityClient getSecurityClient(SignedJWT jwt) {
        final String bearerHeader = "Bearer " + jwt.serialize();
        final RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        options.addHeader("Authorization", bearerHeader);
        return new TestSecurityClient(client(), options.build());
    }

    private String createRoleMapping(List<String> roles, String rules) throws IOException {
        Map<String, Object> mapping = new HashMap<>();
        mapping.put("enabled", true);
        mapping.put("roles", roles);
        mapping.put("rules", XContentHelper.convertToMap(XContentType.JSON.xContent(), rules, true));
        final String mappingName = "test-" + getTestName() + "-" + randomAlphaOfLength(8);
        getAdminSecurityClient().putRoleMapping(mappingName, mapping);
        return mappingName;
    }

    private void deleteRoleMapping(String name) throws IOException {
        getAdminSecurityClient().deleteRoleMapping(name);
    }

}
