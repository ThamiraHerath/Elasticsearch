/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.http.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.http.HttpHandlingSettings;
import org.elasticsearch.http.LLHttpChannel;
import org.elasticsearch.http.LLHttpRequest;
import org.elasticsearch.http.nio.cors.NioCorsConfigBuilder;
import org.elasticsearch.nio.FlushOperation;
import org.elasticsearch.nio.InboundChannelBuffer;
import org.elasticsearch.nio.SocketChannelContext;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiConsumer;

import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ENABLED;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_COMPRESSION;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_COMPRESSION_LEVEL;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_DETAILED_ERRORS_ENABLED;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_CHUNK_SIZE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_HEADER_SIZE;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_MAX_INITIAL_LINE_LENGTH;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_RESET_COOKIES;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_PIPELINING_MAX_EVENTS;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class HttpReadWriteHandlerTests extends ESTestCase {

    private HttpReadWriteHandler handler;
    private NioLLHttpChannel nioHttpChannel;
    private NioHttpServerTransport transport;

    private final RequestEncoder requestEncoder = new RequestEncoder();
    private final ResponseDecoder responseDecoder = new ResponseDecoder();

    @Before
    @SuppressWarnings("unchecked")
    public void setMocks() {
        transport = mock(NioHttpServerTransport.class);
        Settings settings = Settings.EMPTY;
        ByteSizeValue maxChunkSize = SETTING_HTTP_MAX_CHUNK_SIZE.getDefault(settings);
        ByteSizeValue maxHeaderSize = SETTING_HTTP_MAX_HEADER_SIZE.getDefault(settings);
        ByteSizeValue maxInitialLineLength = SETTING_HTTP_MAX_INITIAL_LINE_LENGTH.getDefault(settings);
        HttpHandlingSettings httpHandlingSettings = new HttpHandlingSettings(1024,
            Math.toIntExact(maxChunkSize.getBytes()),
            Math.toIntExact(maxHeaderSize.getBytes()),
            Math.toIntExact(maxInitialLineLength.getBytes()),
            SETTING_HTTP_RESET_COOKIES.getDefault(settings),
            SETTING_HTTP_COMPRESSION.getDefault(settings),
            SETTING_HTTP_COMPRESSION_LEVEL.getDefault(settings),
            SETTING_HTTP_DETAILED_ERRORS_ENABLED.getDefault(settings),
            SETTING_PIPELINING_MAX_EVENTS.getDefault(settings),
            SETTING_CORS_ENABLED.getDefault(settings));
        nioHttpChannel = mock(NioLLHttpChannel.class);
        handler = new HttpReadWriteHandler(nioHttpChannel, transport, httpHandlingSettings, NioCorsConfigBuilder.forAnyOrigin().build());
    }

    public void testSuccessfulDecodeHttpRequest() throws IOException {
        String uri = "localhost:9090/" + randomAlphaOfLength(8);
        HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);

        ByteBuf buf = requestEncoder.encode(httpRequest);
        int slicePoint = randomInt(buf.writerIndex() - 1);

        ByteBuf slicedBuf = buf.retainedSlice(0, slicePoint);
        ByteBuf slicedBuf2 = buf.retainedSlice(slicePoint, buf.writerIndex());
        handler.consumeReads(toChannelBuffer(slicedBuf));

        verify(transport, times(0)).incomingRequest(any(LLHttpRequest.class), any(NioLLHttpChannel.class));

        handler.consumeReads(toChannelBuffer(slicedBuf2));

        ArgumentCaptor<LLHttpRequest> requestCaptor = ArgumentCaptor.forClass(LLHttpRequest.class);
        verify(transport).incomingRequest(requestCaptor.capture(), any(NioLLHttpChannel.class));

        LLHttpRequest nioHttpRequest = requestCaptor.getValue();
        assertEquals(LLHttpRequest.HttpVersion.HTTP_1_1, nioHttpRequest.protocolVersion());
        assertEquals(RestRequest.Method.GET, nioHttpRequest.method());
    }

    public void testDecodeHttpRequestError() throws IOException {
        String uri = "localhost:9090/" + randomAlphaOfLength(8);
        HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);

        ByteBuf buf = requestEncoder.encode(httpRequest);
        buf.setByte(0, ' ');
        buf.setByte(1, ' ');
        buf.setByte(2, ' ');

        handler.consumeReads(toChannelBuffer(buf));

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(transport).incomingRequestError(any(LLHttpRequest.class), any(NioLLHttpChannel.class), exceptionCaptor.capture());

        assertTrue(exceptionCaptor.getValue() instanceof IllegalArgumentException);
    }

    public void testDecodeHttpRequestContentLengthToLongGeneratesOutboundMessage() throws IOException {
        String uri = "localhost:9090/" + randomAlphaOfLength(8);
        HttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, false);
        HttpUtil.setContentLength(httpRequest, 1025);
        HttpUtil.setKeepAlive(httpRequest, false);

        ByteBuf buf = requestEncoder.encode(httpRequest);

        handler.consumeReads(toChannelBuffer(buf));

        verify(transport, times(0)).incomingRequestError(any(), any(), any());
        verify(transport, times(0)).incomingRequest(any(), any());

        List<FlushOperation> flushOperations = handler.pollFlushOperations();
        assertFalse(flushOperations.isEmpty());

        FlushOperation flushOperation = flushOperations.get(0);
        HttpResponse response = responseDecoder.decode(Unpooled.wrappedBuffer(flushOperation.getBuffersToWrite()));
        assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status());

        flushOperation.getListener().accept(null, null);
        // Since we have keep-alive set to false, we should close the channel after the response has been
        // flushed
        verify(nioHttpChannel).close();
    }

    @SuppressWarnings("unchecked")
    public void testEncodeHttpResponse() throws IOException {
        prepareHandlerForResponse(handler);

        NioHttpResponse pipelinedResponse = new NioHttpResponse(HttpVersion.HTTP_1_1, RestStatus.OK, 0, BytesArray.EMPTY);

        SocketChannelContext context = mock(SocketChannelContext.class);
        HttpWriteOperation writeOperation = new HttpWriteOperation(context, pipelinedResponse, mock(BiConsumer.class));
        List<FlushOperation> flushOperations = handler.writeToBytes(writeOperation);

        HttpResponse response = responseDecoder.decode(Unpooled.wrappedBuffer(flushOperations.get(0).getBuffersToWrite()));

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
    }

    private LLNioHttpRequest prepareHandlerForResponse(HttpReadWriteHandler adaptor) throws IOException {
        HttpMethod method = randomBoolean() ? HttpMethod.GET : HttpMethod.HEAD;
        HttpVersion version = randomBoolean() ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
        String uri = "http://localhost:9090/" + randomAlphaOfLength(8);

        HttpRequest request = new DefaultFullHttpRequest(version, method, uri);
        ByteBuf buf = requestEncoder.encode(request);

        handler.consumeReads(toChannelBuffer(buf));

        ArgumentCaptor<LLNioHttpRequest> requestCaptor = ArgumentCaptor.forClass(LLNioHttpRequest.class);
        verify(transport).incomingRequest(requestCaptor.capture(), any(LLHttpChannel.class));

        LLNioHttpRequest nioHttpRequest = requestCaptor.getValue();
        assertNotNull(nioHttpRequest);
        assertEquals(method.name(), nioHttpRequest.method().name());
        if (version == HttpVersion.HTTP_1_1) {
            assertEquals(LLHttpRequest.HttpVersion.HTTP_1_1, nioHttpRequest.protocolVersion());
        } else {
            assertEquals(LLHttpRequest.HttpVersion.HTTP_1_0, nioHttpRequest.protocolVersion());
        }
        assertEquals(nioHttpRequest.uri(), uri);
        return nioHttpRequest;
    }

    private InboundChannelBuffer toChannelBuffer(ByteBuf buf) {
        InboundChannelBuffer buffer = InboundChannelBuffer.allocatingInstance();
        int readableBytes = buf.readableBytes();
        buffer.ensureCapacity(readableBytes);
        int bytesWritten = 0;
        ByteBuffer[] byteBuffers = buffer.sliceBuffersTo(readableBytes);
        int i = 0;
        while (bytesWritten != readableBytes) {
            ByteBuffer byteBuffer = byteBuffers[i++];
            int initialRemaining = byteBuffer.remaining();
            buf.readBytes(byteBuffer);
            bytesWritten += initialRemaining - byteBuffer.remaining();
        }
        buffer.incrementIndex(bytesWritten);
        return buffer;
    }

    private static class RequestEncoder {

        private final EmbeddedChannel requestEncoder = new EmbeddedChannel(new HttpRequestEncoder());

        private ByteBuf encode(HttpRequest httpRequest) {
            requestEncoder.writeOutbound(httpRequest);
            return requestEncoder.readOutbound();
        }
    }

    private static class ResponseDecoder {

        private final EmbeddedChannel responseDecoder = new EmbeddedChannel(new HttpResponseDecoder());

        private HttpResponse decode(ByteBuf response) {
            responseDecoder.writeInbound(response);
            return responseDecoder.readInbound();
        }
    }
}
