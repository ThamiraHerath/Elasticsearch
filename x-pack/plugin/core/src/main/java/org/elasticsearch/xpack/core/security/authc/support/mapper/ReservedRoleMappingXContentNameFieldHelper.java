/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.security.authc.support.mapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class to avoid name erasure when cluster-state role mappings on disk.
 * When cluster-state role mappings are written to disk, their XContent format is used.
 * This format omits the role mapping's name.
 * This means that if CS role mappings are ever recovered from disk (e.g., during a master-node restart), their names are erased.
 * To address this, this class augments CS role mapping serialization to persist the name of a mapping in a reserved metadata field,
 * and recover it from metadata during de-serialization.
 * Storing the name in metadata allows us to persist the name without BWC-breaks in role mapping `XContent` format (as opposed to changing
 * XContent serialization to persist the top-level name field).
 * It also ensures that role mappings are re-written to cluster state in the new,
 * name-preserving format the first time operator file settings are processed.
 * {@link #copyWithNameInMetadata(ExpressionRoleMapping)}} is used to copy the name of a role mapping into its metadata field.
 * This is called when the role mappings are read from operator file settings (at which point the correct name is still available).
 * {@link #parseWithNameFromMetadata(XContentParser)} is used to parse a role mapping from XContent restoring the name from metadata.
 */
public final class ReservedRoleMappingXContentNameFieldHelper {
    private static final Logger logger = LogManager.getLogger(ReservedRoleMappingXContentNameFieldHelper.class);

    public static final String METADATA_NAME_FIELD = "_es_reserved_role_mapping_name";
    public static final String FALLBACK_NAME = "name_not_available_after_deserialization";

    private ReservedRoleMappingXContentNameFieldHelper() {}

    public static ExpressionRoleMapping copyWithNameInMetadata(ExpressionRoleMapping roleMapping) {
        Map<String, Object> metadata = new HashMap<>(roleMapping.getMetadata());
        // note: can't use Maps.copyWith... since these create maps that don't support `null` values in map entries
        if (metadata.put(METADATA_NAME_FIELD, roleMapping.getName()) != null) {
            logger.error(
                "Metadata field [{}] is reserved and will be overwritten with an internal system value. "
                    + "Rename this field in your role mapping configuration.",
                METADATA_NAME_FIELD
            );
        }
        return new ExpressionRoleMapping(
            roleMapping.getName(),
            roleMapping.getExpression(),
            roleMapping.getRoles(),
            roleMapping.getRoleTemplates(),
            metadata,
            roleMapping.isEnabled()
        );
    }

    public static boolean hasFallbackName(ExpressionRoleMapping expressionRoleMapping) {
        return expressionRoleMapping.getName().equals(FALLBACK_NAME);
    }

    public static void removeNameFromMetadata(Map<String, Object> metadata) {
        assert metadata instanceof HashMap<String, Object>;
        metadata.remove(METADATA_NAME_FIELD);
    }

    public static ExpressionRoleMapping parseWithNameFromMetadata(XContentParser parser) throws IOException {
        ExpressionRoleMapping roleMapping = ExpressionRoleMapping.parse(FALLBACK_NAME, parser);
        return new ExpressionRoleMapping(
            getNameFromMetadata(roleMapping),
            roleMapping.getExpression(),
            roleMapping.getRoles(),
            roleMapping.getRoleTemplates(),
            roleMapping.getMetadata(),
            roleMapping.isEnabled()
        );
    }

    private static String getNameFromMetadata(ExpressionRoleMapping roleMapping) {
        Map<String, Object> metadata = roleMapping.getMetadata();
        if (metadata.containsKey(METADATA_NAME_FIELD) && metadata.get(METADATA_NAME_FIELD) instanceof String name) {
            return name;
        } else {
            // This is valid the first time we recover from cluster-state: the old format metadata won't have a name stored in metadata yet
            return FALLBACK_NAME;
        }
    }
}
