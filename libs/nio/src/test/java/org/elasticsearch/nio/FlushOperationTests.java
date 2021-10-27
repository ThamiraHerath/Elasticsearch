/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nio;

import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

import static org.mockito.Mockito.mock;

public class FlushOperationTests extends ESTestCase {

    private BiConsumer<Void, Exception> listener;

    @Before
    @SuppressWarnings("unchecked")
    public void setFields() {
        listener = mock(BiConsumer.class);
    }

    public void testFullyFlushedMarker() {
        ByteBuffer[] buffers = { ByteBuffer.allocate(10) };
        FlushOperation writeOp = new FlushOperation(buffers, listener);

        writeOp.incrementIndex(10);

        assertTrue(writeOp.isFullyFlushed());
    }

    public void testPartiallyFlushedMarker() {
        ByteBuffer[] buffers = { ByteBuffer.allocate(10) };
        FlushOperation writeOp = new FlushOperation(buffers, listener);

        writeOp.incrementIndex(5);

        assertFalse(writeOp.isFullyFlushed());
    }

    public void testMultipleFlushesWithCompositeBuffer() throws IOException {
        ByteBuffer[] buffers = { ByteBuffer.allocate(10), ByteBuffer.allocate(15), ByteBuffer.allocate(3) };
        FlushOperation writeOp = new FlushOperation(buffers, listener);

        writeOp.incrementIndex(5);
        assertFalse(writeOp.isFullyFlushed());
        ByteBuffer[] byteBuffers = writeOp.getBuffersToWrite();
        assertEquals(3, byteBuffers.length);
        assertEquals(5, byteBuffers[0].remaining());
        ByteBuffer[] byteBuffersWithLimit = writeOp.getBuffersToWrite(10);
        assertEquals(2, byteBuffersWithLimit.length);
        assertEquals(5, byteBuffersWithLimit[0].remaining());
        assertEquals(5, byteBuffersWithLimit[1].remaining());

        writeOp.incrementIndex(5);
        assertFalse(writeOp.isFullyFlushed());
        byteBuffers = writeOp.getBuffersToWrite();
        assertEquals(2, byteBuffers.length);
        assertEquals(15, byteBuffers[0].remaining());
        assertEquals(3, byteBuffers[1].remaining());
        byteBuffersWithLimit = writeOp.getBuffersToWrite(10);
        assertEquals(1, byteBuffersWithLimit.length);
        assertEquals(10, byteBuffersWithLimit[0].remaining());

        writeOp.incrementIndex(2);
        assertFalse(writeOp.isFullyFlushed());
        byteBuffers = writeOp.getBuffersToWrite();
        assertEquals(2, byteBuffers.length);
        assertEquals(13, byteBuffers[0].remaining());
        assertEquals(3, byteBuffers[1].remaining());
        byteBuffersWithLimit = writeOp.getBuffersToWrite(10);
        assertEquals(1, byteBuffersWithLimit.length);
        assertEquals(10, byteBuffersWithLimit[0].remaining());

        writeOp.incrementIndex(15);
        assertFalse(writeOp.isFullyFlushed());
        byteBuffers = writeOp.getBuffersToWrite();
        assertEquals(1, byteBuffers.length);
        assertEquals(1, byteBuffers[0].remaining());
        byteBuffersWithLimit = writeOp.getBuffersToWrite(10);
        assertEquals(1, byteBuffersWithLimit.length);
        assertEquals(1, byteBuffersWithLimit[0].remaining());

        writeOp.incrementIndex(1);
        assertTrue(writeOp.isFullyFlushed());
        byteBuffers = writeOp.getBuffersToWrite();
        assertEquals(0, byteBuffers.length);
        byteBuffersWithLimit = writeOp.getBuffersToWrite(10);
        assertEquals(0, byteBuffersWithLimit.length);
    }
}
