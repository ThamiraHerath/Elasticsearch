/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.template;

import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyUtils;
import org.elasticsearch.xpack.core.ilm.LifecycleType;
import org.elasticsearch.xpack.core.ilm.RolloverAction;
import org.elasticsearch.xpack.core.ilm.ShrinkAction;
import org.elasticsearch.xpack.core.ilm.TimeseriesLifecycleType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Describes an index lifecycle policy to be loaded from a resource file for use with an {@link IndexTemplateRegistry}.
 */
public class LifecyclePolicyConfig {

    private final String policyName;
    private final String fileName;
    private final Map<String, String> templateVariables;

    /**
     * Describes a lifecycle policy definition to be loaded from a resource file.
     *
     * @param policyName The name that will be used for the policy.
     * @param fileName The filename the policy definition should be loaded from. Literal, should include leading {@literal /} and
     *                 extension if necessary.
     */
    public LifecyclePolicyConfig(String policyName, String fileName) {
        this(policyName, fileName, Collections.emptyMap());
    }

    /**
     * Describes a lifecycle policy definition to be loaded from a resource file.
     *
     * @param policyName The name that will be used for the policy.
     * @param fileName The filename the policy definition should be loaded from. Literal, should include leading {@literal /} and
     *                 extension if necessary.
     * @param templateVariables A map containing values for template variables present in the resource file.
     */
    public LifecyclePolicyConfig(String policyName, String fileName, Map<String, String> templateVariables) {
        this.policyName = policyName;
        this.fileName = fileName;
        this.templateVariables = templateVariables;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getFileName() {
        return fileName;
    }

    public LifecyclePolicy load(NamedXContentRegistry xContentRegistry) {
        return LifecyclePolicyUtils.loadPolicy(policyName, fileName, templateVariables, xContentRegistry);
    }
}
