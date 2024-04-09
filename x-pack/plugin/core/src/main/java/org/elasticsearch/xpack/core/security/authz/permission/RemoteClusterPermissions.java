/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.security.authz.permission;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.core.security.authz.privilege.ClusterPrivilegeResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.action.ValidateActions.addValidationError;

/**
 * Represents a group of permissions for a remote cluster. This is intended to be the model for both the {@link RoleDescriptor}
 * and {@link Role}. This model is not intended to be sent to a remote cluster, but can be (wire) serialized within a single cluster
 * as well as the Xcontent serialization for the REST API and persistence of the role in the security index. The privileges modeled here
 * will be converted to the appropriate cluster privileges when sent to a remote cluster.
 * For example, on the local/querying cluster this model represents the following:
 * <code>
 * "remote_cluster" : [
 *         {
 *             "privileges" : ["foo"],
 *             "clusters" : ["clusterA"]
 *         },
 *         {
 *             "privileges" : ["bar"],
 *             "clusters" : ["clusterB"]
 *         }
 *     ]
 * </code>
 * when sent to the remote cluster "clusterA", the privileges will be converted to the appropriate cluster privileges. For example:
 * <code>
 *   "cluster": ["foo"]
 * </code>
 * and when sent to the remote cluster "clusterB", the privileges will be converted to the appropriate cluster privileges. For example:
 * <code>
 *   "cluster": ["bar"]
 * </code>
 */
public class RemoteClusterPermissions implements Writeable, ToXContentObject {

    private final List<RemoteClusterPermissionGroup> remoteClusterPermissionGroups;
    private static final Set<String> allowedRemoteClusterPermissions = Set.of("monitor_enrich");
    static{
        assert ClusterPrivilegeResolver.names().containsAll(allowedRemoteClusterPermissions);
    }

    public static final RemoteClusterPermissions NONE = new RemoteClusterPermissions();

    public static Set<String> getSupportRemoteClusterPermissions() {
        //if there are ever more than 1 allowed permission make related logs/error messages plural
        return allowedRemoteClusterPermissions;
    }

    public RemoteClusterPermissions(StreamInput in) throws IOException {
        remoteClusterPermissionGroups = in.readCollectionAsList(RemoteClusterPermissionGroup::new);
    }

    public RemoteClusterPermissions() {
        remoteClusterPermissionGroups = new ArrayList<>();
    }

    public RemoteClusterPermissions addGroup(RemoteClusterPermissionGroup remoteClusterPermissionGroup) {
        Objects.requireNonNull(remoteClusterPermissionGroup, "remoteClusterPermissionGroup must not be null");
        if (this == NONE) {
            throw new IllegalArgumentException("Cannot add a group to the `NONE` instance");
        }
        remoteClusterPermissionGroups.add(remoteClusterPermissionGroup);
        return this;
    }

    public String[] privilegeNames(final String remoteClusterAlias) {
        return remoteClusterPermissionGroups.stream()
            .filter(group -> group.hasPrivileges(remoteClusterAlias))
            .flatMap(groups -> Arrays.stream(groups.clusterPrivileges()))
            .distinct()
            .sorted()
            .toArray(String[]::new);
    }

    /**
     * Validates the remote cluster permissions. This method will throw an {@link IllegalArgumentException} if the permissions are invalid.
     * Generally, this method is just a safety check and validity should be checked before adding the permissions to this class.
     */
    public void validate() {
        assert hasPrivileges();
        Set<String> invalid = getUnsupportedPrivileges();
        if (invalid.isEmpty() == false) {
            throw new IllegalArgumentException(
                "Invalid remote_cluster permissions found. Please remove the remove the following: "
                    + invalid
                    + " Only "
                    + allowedRemoteClusterPermissions
                    + " is allowed"
            );
        }
        for (RemoteClusterPermissionGroup group : remoteClusterPermissionGroups) {
            if (Arrays.asList(group.remoteClusterAliases()).contains("")) {
                throw new IllegalArgumentException("remote_cluster - cluster alias cannot be an empty string");
            }
        }
    }

    /**
     * Returns the unsupported privileges in the remote cluster permissions. Empty set if all privileges are supported.
     */
    public Set<String> getUnsupportedPrivileges(){
        Set<String> invalid = new HashSet<>();
        for (RemoteClusterPermissionGroup group : remoteClusterPermissionGroups) {
            for (String namedPrivilege : group.clusterPrivileges()) {
                String toCheck = namedPrivilege.toLowerCase(Locale.ROOT);
                if (allowedRemoteClusterPermissions.contains(toCheck) == false) {
                    invalid.add(namedPrivilege);
                }
            }
        }
        return invalid;
    }

    public boolean hasPrivileges(final String remoteClusterAlias) {
        return remoteClusterPermissionGroups.stream().anyMatch(remoteIndicesGroup -> remoteIndicesGroup.hasPrivileges(remoteClusterAlias));
    }

    public boolean hasPrivileges() {
        return remoteClusterPermissionGroups.isEmpty() == false;
    }

    public List<RemoteClusterPermissionGroup> groups() {
        return Collections.unmodifiableList(remoteClusterPermissionGroups);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        for (RemoteClusterPermissionGroup remoteClusterPermissionGroup : remoteClusterPermissionGroups) {
            builder.value(remoteClusterPermissionGroup);
        }
        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeCollection(remoteClusterPermissionGroups);
    }
}
