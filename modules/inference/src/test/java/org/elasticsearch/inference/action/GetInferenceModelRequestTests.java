/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.inference.action;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.AbstractWireSerializingTestCase;

public class GetInferenceModelRequestTests extends AbstractWireSerializingTestCase<GetInferenceModelAction.Request> {

    public static GetInferenceModelAction.Request randomTestInstance() {
        return new GetInferenceModelAction.Request(randomAlphaOfLength(8), randomFrom(TaskType.values()));
    }

    @Override
    protected Writeable.Reader<GetInferenceModelAction.Request> instanceReader() {
        return GetInferenceModelAction.Request::new;
    }

    @Override
    protected GetInferenceModelAction.Request createTestInstance() {
        return randomTestInstance();
    }

    @Override
    protected GetInferenceModelAction.Request mutateInstance(GetInferenceModelAction.Request instance) {
        return switch (randomIntBetween(0, 1)) {
            case 0 -> new GetInferenceModelAction.Request(instance.getModelId() + "foo", instance.getTaskType());
            case 1 -> {
                var nextTaskType = TaskType.values()[(instance.getTaskType().ordinal() + 1) % TaskType.values().length];
                yield new GetInferenceModelAction.Request(instance.getModelId(), nextTaskType);
            }
            default -> throw new UnsupportedOperationException();
        };
    }
}
