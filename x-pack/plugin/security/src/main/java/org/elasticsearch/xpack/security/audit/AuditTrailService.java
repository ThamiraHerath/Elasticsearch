/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.audit;

import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.AuthenticationToken;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.core.security.authz.AuthorizationEngine.AuthorizationInfo;
import org.elasticsearch.xpack.security.transport.filter.SecurityIpFilterRule;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

public class AuditTrailService {

    private static final AuditTrail NOOP_AUDIT_TRAIL = new NoopAuditTrail();
    private final CompositeAuditTrail compositeAuditTrail;
    private final XPackLicenseState licenseState;

    public AuditTrailService(List<AuditTrail> auditTrails, XPackLicenseState licenseState) {
        this.compositeAuditTrail = new CompositeAuditTrail(Collections.unmodifiableList(auditTrails));
        this.licenseState = licenseState;
    }

    public AuditTrail get() {
        if (compositeAuditTrail.isEmpty() == false &&
            licenseState.isSecurityEnabled() && licenseState.isAuditingAllowed()) {
            return compositeAuditTrail;
        } else {
            return NOOP_AUDIT_TRAIL;
        }
    }

    // TODO: this method only exists for access to LoggingAuditTrail in a Node for testing.
    // DO NOT USE IT, IT WILL BE REMOVED IN THE FUTURE
    public List<AuditTrail> getAuditTrails() {
        return compositeAuditTrail.auditTrails;
    }

    private static class NoopAuditTrail implements AuditTrail {

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public void authenticationSuccess(String requestId, String realm, User user, RestRequest request) {}

        @Override
        public void authenticationSuccess(String requestId, String realm, User user, String action, TransportRequest transportRequest) {}

        @Override
        public void anonymousAccessDenied(String requestId, String action, TransportRequest transportRequest) {}

        @Override
        public void anonymousAccessDenied(String requestId, RestRequest request) {}

        @Override
        public void authenticationFailed(String requestId, RestRequest request) {}

        @Override
        public void authenticationFailed(String requestId, String action, TransportRequest transportRequest) {}

        @Override
        public void authenticationFailed(String requestId, AuthenticationToken token, String action, TransportRequest transportRequest) {}

        @Override
        public void authenticationFailed(String requestId, AuthenticationToken token, RestRequest request) {}

        @Override
        public void authenticationFailed(String requestId, String realm, AuthenticationToken token,
                                         String action, TransportRequest transportRequest) {}

        @Override
        public void authenticationFailed(String requestId, String realm, AuthenticationToken token, RestRequest request) {}

        @Override
        public void accessGranted(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                  AuthorizationInfo authorizationInfo) {}

        @Override
        public void accessDenied(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                 AuthorizationInfo authorizationInfo) {}

        @Override
        public void tamperedRequest(String requestId, RestRequest request) {}

        @Override
        public void tamperedRequest(String requestId, String action, TransportRequest transportRequest) {}

        @Override
        public void tamperedRequest(String requestId, User user, String action, TransportRequest transportRequest) {}

        @Override
        public void connectionGranted(InetAddress inetAddress, String profile, SecurityIpFilterRule rule) {}

        @Override
        public void connectionDenied(InetAddress inetAddress, String profile, SecurityIpFilterRule rule) {}

        @Override
        public void runAsGranted(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                 AuthorizationInfo authorizationInfo) {}

        @Override
        public void runAsDenied(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                AuthorizationInfo authorizationInfo) {}

        @Override
        public void runAsDenied(String requestId, Authentication authentication, RestRequest request,
                                AuthorizationInfo authorizationInfo) {}

        @Override
        public void explicitIndexAccessEvent(String requestId, AuditLevel eventType, Authentication authentication,
                                             String action, String indices, String requestName, TransportAddress remoteAddress,
                                             AuthorizationInfo authorizationInfo) {}
    }

    private static class CompositeAuditTrail implements AuditTrail {

        private final List<AuditTrail> auditTrails;

        private CompositeAuditTrail(List<AuditTrail> auditTrails) {
            this.auditTrails = auditTrails;
        }

        boolean isEmpty() {
            return auditTrails.isEmpty();
        }

        @Override
        public String name() {
            return "service";
        }

        @Override
        public void authenticationSuccess(String requestId, String realm, User user, RestRequest request) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationSuccess(requestId, realm, user, request);
            }
        }

        @Override
        public void authenticationSuccess(String requestId, String realm, User user, String action, TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationSuccess(requestId, realm, user, action, transportRequest);
            }
        }

        @Override
        public void anonymousAccessDenied(String requestId, String action, TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.anonymousAccessDenied(requestId, action, transportRequest);
            }
        }

        @Override
        public void anonymousAccessDenied(String requestId, RestRequest request) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.anonymousAccessDenied(requestId, request);
            }
        }

        @Override
        public void authenticationFailed(String requestId, RestRequest request) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationFailed(requestId, request);
            }
        }

        @Override
        public void authenticationFailed(String requestId, String action, TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationFailed(requestId, action, transportRequest);
            }
        }

        @Override
        public void authenticationFailed(String requestId, AuthenticationToken token, String action, TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationFailed(requestId, token, action, transportRequest);
            }
        }

        @Override
        public void authenticationFailed(String requestId, String realm, AuthenticationToken token, String action,
                                         TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationFailed(requestId, realm, token, action, transportRequest);
            }
        }

        @Override
        public void authenticationFailed(String requestId, AuthenticationToken token, RestRequest request) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationFailed(requestId, token, request);
            }
        }

        @Override
        public void authenticationFailed(String requestId, String realm, AuthenticationToken token, RestRequest request) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.authenticationFailed(requestId, realm, token, request);
            }
        }

        @Override
        public void accessGranted(String requestId, Authentication authentication, String action, TransportRequest msg,
                                  AuthorizationInfo authorizationInfo) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.accessGranted(requestId, authentication, action, msg, authorizationInfo);
            }
        }

        @Override
        public void accessDenied(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                 AuthorizationInfo authorizationInfo) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.accessDenied(requestId, authentication, action, transportRequest, authorizationInfo);
            }
        }

        @Override
        public void tamperedRequest(String requestId, RestRequest request) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.tamperedRequest(requestId, request);
            }
        }

        @Override
        public void tamperedRequest(String requestId, String action, TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.tamperedRequest(requestId, action, transportRequest);
            }
        }

        @Override
        public void tamperedRequest(String requestId, User user, String action, TransportRequest transportRequest) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.tamperedRequest(requestId, user, action, transportRequest);
            }
        }

        @Override
        public void connectionGranted(InetAddress inetAddress, String profile, SecurityIpFilterRule rule) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.connectionGranted(inetAddress, profile, rule);
            }
        }

        @Override
        public void connectionDenied(InetAddress inetAddress, String profile, SecurityIpFilterRule rule) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.connectionDenied(inetAddress, profile, rule);
            }
        }

        @Override
        public void runAsGranted(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                 AuthorizationInfo authorizationInfo) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.runAsGranted(requestId, authentication, action, transportRequest, authorizationInfo);
            }
        }

        @Override
        public void runAsDenied(String requestId, Authentication authentication, String action, TransportRequest transportRequest,
                                AuthorizationInfo authorizationInfo) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.runAsDenied(requestId, authentication, action, transportRequest, authorizationInfo);
            }
        }

        @Override
        public void runAsDenied(String requestId, Authentication authentication, RestRequest request,
                                AuthorizationInfo authorizationInfo) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.runAsDenied(requestId, authentication, request, authorizationInfo);
            }
        }

        @Override
        public void explicitIndexAccessEvent(String requestId, AuditLevel eventType, Authentication authentication, String action,
                                             String indices, String requestName, TransportAddress remoteAddress,
                                             AuthorizationInfo authorizationInfo) {
            for (AuditTrail auditTrail : auditTrails) {
                auditTrail.explicitIndexAccessEvent(requestId, eventType, authentication, action, indices, requestName, remoteAddress,
                    authorizationInfo);
            }
        }
    }
}
