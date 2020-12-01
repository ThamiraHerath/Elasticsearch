/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.deprecation;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.core.deprecation.DeprecationIssue;

import java.util.List;

public interface DeprecationChecker {

    /**
     * Should this deprecation checker be enabled?
     *
     * @param settings Cluster settings
     * @return True if enabled given the settings
     */
    boolean enabled(Settings settings);

    /**
     * This runs the checks for the current deprecation checker.
     *
     * @param components The components provided for the checker
     * @param deprecationIssueListener The issues found
     */
    void check(DeprecationCheckerComponents components, ActionListener<CheckResult> deprecationIssueListener);

    /**
     * @return The name of the checker
     */
    String getName();

    class CheckResult {
        private final String checkerName;
        private final List<DeprecationIssue> issues;

        public CheckResult(String checkerName, List<DeprecationIssue> issues) {
            this.checkerName = checkerName;
            this.issues = issues;
        }

        public String getCheckerName() {
            return checkerName;
        }

        public List<DeprecationIssue> getIssues() {
            return issues;
        }
    }
}
