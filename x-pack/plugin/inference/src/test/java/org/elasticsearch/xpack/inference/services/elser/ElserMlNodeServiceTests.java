/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.elser;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.inference.Model;
import org.elasticsearch.xpack.inference.TaskType;

public class ElserMlNodeServiceTests extends ESTestCase {

    public static Model randomModelConfig(String modelId, TaskType taskType) {
        return switch (taskType) {
            case SPARSE_EMBEDDING -> new Model(
                modelId,
                taskType,
                ElserMlNodeService.NAME,
                new ElserServiceSettings(),
                new ElserTaskSettings(1, 1) // TODO
            );
            default -> throw new IllegalArgumentException("task type " + taskType + " is not supported");
        };
    }
}
