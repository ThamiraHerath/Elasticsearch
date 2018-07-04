/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.security.authc.kerberos;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.core.security.authc.AuthenticationResult;
import org.elasticsearch.xpack.core.security.authc.kerberos.KerberosRealmSettings;
import org.elasticsearch.xpack.core.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.security.authc.support.UserRoleMapper.UserData;
import org.ietf.jgss.GSSException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import javax.security.auth.login.LoginException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class KerberosRealmTests extends KerberosRealmTestCase {

    public void testSupports() {
        final KerberosRealm kerberosRealm = createKerberosRealm("test@REALM");

        final KerberosAuthenticationToken kerberosAuthenticationToken = new KerberosAuthenticationToken(randomByteArrayOfLength(5));
        assertThat(kerberosRealm.supports(kerberosAuthenticationToken), is(true));
        final UsernamePasswordToken usernamePasswordToken =
                new UsernamePasswordToken(randomAlphaOfLength(5), new SecureString(new char[] { 'a', 'b', 'c' }));
        assertThat(kerberosRealm.supports(usernamePasswordToken), is(false));
    }

    public void testAuthenticateWithValidTicketSucessAuthnWithUserDetails() throws LoginException, GSSException {
        final KerberosRealm kerberosRealm = createKerberosRealm("test@REALM");

        final User expectedUser = new User("test@REALM", roles.toArray(new String[roles.size()]), null, null, null, true);
        final byte[] decodedTicket = "base64encodedticket".getBytes(StandardCharsets.UTF_8);
        final Path keytabPath = config.env().configFile().resolve(KerberosRealmSettings.HTTP_SERVICE_KEYTAB_PATH.get(config.settings()));
        final boolean krbDebug = KerberosRealmSettings.SETTING_KRB_DEBUG_ENABLE.get(config.settings());
        mockKerberosTicketValidator(decodedTicket, keytabPath, krbDebug, new Tuple<>("test@REALM", "out-token"), null);
        final KerberosAuthenticationToken kerberosAuthenticationToken = new KerberosAuthenticationToken(decodedTicket);

        final PlainActionFuture<AuthenticationResult> future = new PlainActionFuture<>();
        kerberosRealm.authenticate(kerberosAuthenticationToken, future);
        assertSuccessAuthenticationResult(expectedUser, "out-token", future.actionGet());

        verify(mockKerberosTicketValidator, times(1)).validateTicket(aryEq(decodedTicket), eq(keytabPath), eq(krbDebug),
                any(ActionListener.class));
        verify(mockNativeRoleMappingStore).refreshRealmOnChange(kerberosRealm);
        verify(mockNativeRoleMappingStore).resolveRoles(any(UserData.class), any(ActionListener.class));
        verifyNoMoreInteractions(mockKerberosTicketValidator, mockNativeRoleMappingStore);
    }

    public void testFailedAuthorization() throws LoginException, GSSException {
        final KerberosRealm kerberosRealm = createKerberosRealm("test@REALM");
        final byte[] decodedTicket = "base64encodedticket".getBytes(StandardCharsets.UTF_8);
        final Path keytabPath = config.env().configFile().resolve(KerberosRealmSettings.HTTP_SERVICE_KEYTAB_PATH.get(config.settings()));
        final boolean krbDebug = KerberosRealmSettings.SETTING_KRB_DEBUG_ENABLE.get(config.settings());
        mockKerberosTicketValidator(decodedTicket, keytabPath, krbDebug, new Tuple<>("does-not-exist@REALM", "out-token"), null);

        final PlainActionFuture<AuthenticationResult> future = new PlainActionFuture<>();
        kerberosRealm.authenticate(new KerberosAuthenticationToken(decodedTicket), future);

        ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class, future::actionGet);
        assertThat(e.status(), is(RestStatus.FORBIDDEN));
        assertThat(e.getMessage(), equalTo("Expected UPN '" + Arrays.asList("test@REALM") + "' but was 'does-not-exist@REALM'"));
    }

    public void testLookupUser() {
        final KerberosRealm kerberosRealm = createKerberosRealm("test@REALM");
        final PlainActionFuture<User> future = new PlainActionFuture<>();
        kerberosRealm.lookupUser("test@REALM", future);
        assertThat(future.actionGet(), is(nullValue()));
    }

}