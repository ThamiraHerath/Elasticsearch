/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.dataframe.action;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractWireSerializingTestCase;
import org.elasticsearch.xpack.core.dataframe.action.StartDataFrameTransformAction.Request;

public class StartDataFrameTransformActionRequestTests extends AbstractWireSerializingTestCase<Request> {
    @Override
    protected Request createTestInstance() {
        return new Request(randomAlphaOfLengthBetween(1, 20));
    }

    @Override
    protected Writeable.Reader<Request> instanceReader() {
        return Request::new;
    }
}
