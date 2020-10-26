/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.search.action;

import org.elasticsearch.action.ActionType;

public class OpenPointInTimeAction extends ActionType<OpenPointInTimeResponse> {
    public static final String NAME = "indices:data/read/open_point_in_time";
    public static final OpenPointInTimeAction INSTANCE = new OpenPointInTimeAction();

    private OpenPointInTimeAction() {
        super(NAME, OpenPointInTimeResponse::new);
    }
}
