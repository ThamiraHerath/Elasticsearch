/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.common.util.concurrent;

import java.util.concurrent.ThreadPoolExecutor;

public class EsAbortPolicy extends EsRejectedExecutionHandler {

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown() == false && task instanceof AbstractRunnable r && r.isForceExecution()) {
            if (executor.getQueue() instanceof SizeBlockingQueue<Runnable> sizeBlockingQueue) {
                try {
                    sizeBlockingQueue.forcePut(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("forced execution, but got interrupted", e);
                }
            } else {
                throw new IllegalStateException("expected but did not find SizeBlockingQueue: " + executor);
            }
        }
        incrementRejections();
        throw newRejectedException(task, executor, executor.isShutdown());
    }
}
