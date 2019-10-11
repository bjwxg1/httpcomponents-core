/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.core5.http.impl.io;

import java.io.IOException;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElements;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.UnsupportedHttpVersionException;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.impl.Http1StreamListener;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.io.Closer;
import org.apache.hc.core5.util.Args;

/**
 * {@code HttpRequestExecutor} is a client side HTTP protocol handler based
 * on the blocking (classic) I/O model.
 * <p>
 * {@code HttpRequestExecutor} relies on {@link HttpProcessor} to generate
 * mandatory protocol headers for all outgoing messages and apply common,
 * cross-cutting message transformations to all incoming and outgoing messages.
 * Application specific processing can be implemented outside
 * {@code HttpRequestExecutor} once the request has been executed and
 * a response has been received.
 *
 * @since 4.0
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class HttpRequestExecutor {

    public static final int DEFAULT_WAIT_FOR_CONTINUE = 3000;

    private final int waitForContinue;
    //http链接重用策略，用于判断http底层的链接是否可以重复使用
    private final ConnectionReuseStrategy connReuseStrategy;
    private final Http1StreamListener streamListener;

    /**
     * Creates new instance of HttpRequestExecutor.
     *
     * @since 4.3
     */
    public HttpRequestExecutor(
            final int waitForContinue,
            final ConnectionReuseStrategy connReuseStrategy,
            final Http1StreamListener streamListener) {
        super();
        this.waitForContinue = Args.positive(waitForContinue, "Wait for continue time");
        this.connReuseStrategy = connReuseStrategy != null ? connReuseStrategy : DefaultConnectionReuseStrategy.INSTANCE;
        this.streamListener = streamListener;
    }

    public HttpRequestExecutor(final ConnectionReuseStrategy connReuseStrategy) {
        this(DEFAULT_WAIT_FOR_CONTINUE, connReuseStrategy, null);
    }

    public HttpRequestExecutor() {
        this(DEFAULT_WAIT_FOR_CONTINUE, null, null);
    }

    /**
     * Sends the request and obtain a response.
     *
     * @param request   the request to execute.
     * @param conn      the connection over which to execute the request.
     * @param informationCallback   callback to execute upon receipt of information status (1xx).
     *                              May be null.
     * @param context the context
     * @return  the response to the request.
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public ClassicHttpResponse execute(
            final ClassicHttpRequest request,
            final HttpClientConnection conn,
            final HttpResponseInformationCallback informationCallback,
            final HttpContext context) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(conn, "Client connection");
        Args.notNull(context, "HTTP context");
        try {
            //设置context信息
            context.setAttribute(HttpCoreContext.SSL_SESSION, conn.getSSLSession());
            context.setAttribute(HttpCoreContext.CONNECTION_ENDPOINT, conn.getEndpointDetails());
            final ProtocolVersion transportVersion = request.getVersion();
            if (transportVersion != null) {
                if (transportVersion.greaterEquals(HttpVersion.HTTP_2)) {
                    throw new UnsupportedHttpVersionException(transportVersion);
                }
                context.setProtocolVersion(transportVersion);
            }
            //发送httprequest头信息
            conn.sendRequestHeader(request);
            if (streamListener != null) {
                streamListener.onRequestHead(conn, request);
            }
            boolean expectContinue = false;
            final HttpEntity entity = request.getEntity();
            if (entity != null) {
                //Expect:100-Continue握手的目的，是为了允许客户端在发送请求内容之前，判断源服务器是否愿意接受 请求（基于请求头部）。
                //Expect:100-Continue握手需谨慎使用，因为遇到不支持HTTP/1.1协议的服务器或者代理时会引起问题。
                final Header expect = request.getFirstHeader(HttpHeaders.EXPECT);
                expectContinue = expect != null && HeaderElements.CONTINUE.equalsIgnoreCase(expect.getValue());
                //如果expectContinue为false则发送请求提信息
                if (!expectContinue) {
                    conn.sendRequestEntity(request);
                }
            }
            //刷新输出
            conn.flush();
            ClassicHttpResponse response = null;
            while (response == null) {
                //如果expectContinue为true
                if (expectContinue) {
                    if (conn.isDataAvailable(this.waitForContinue)) {
                        response = conn.receiveResponseHeader();
                        if (streamListener != null) {
                            streamListener.onResponseHead(conn, response);
                        }
                        final int status = response.getCode();
                        //因为expectContinue为true，说明发送request的header中带有except header信息，
                        // 并且请求时只发送了请求行和请求头信息，如果服务器返回100，标明可以继续发送请求体信息
                        if (status == HttpStatus.SC_CONTINUE) {
                            // discard 100-continue
                            response = null;
                            //继续发送请求体
                            conn.sendRequestEntity(request);
                        } else if (status < HttpStatus.SC_SUCCESS) {
                            if (informationCallback != null) {
                                informationCallback.execute(response, conn, context);
                            }
                            response = null;
                            continue;
                        }
                        //如果返回400，关闭连接
                        else if (status >= HttpStatus.SC_CLIENT_ERROR){
                            conn.terminateRequest(request);
                        } else {
                            conn.sendRequestEntity(request);
                        }
                    } else {
                        conn.sendRequestEntity(request);
                    }
                    conn.flush();
                    expectContinue = false;
                } else {
                    //接收response header信息
                    response = conn.receiveResponseHeader();
                    if (streamListener != null) {
                        streamListener.onResponseHead(conn, response);
                    }
                    final int status = response.getCode();
                    if (status < HttpStatus.SC_INFORMATIONAL) {
                        throw new ProtocolException("Invalid response: " + new StatusLine(response));
                    }
                    if (status < HttpStatus.SC_SUCCESS) {
                        if (informationCallback != null && status != HttpStatus.SC_CONTINUE) {
                            informationCallback.execute(response, conn, context);
                        }
                        response = null;
                    }
                }
            }
            if (MessageSupport.canResponseHaveBody(request.getMethod(), response)) {
                conn.receiveResponseEntity(response);
            }
            return response;

        } catch (final HttpException | IOException | RuntimeException ex) {
            Closer.closeQuietly(conn);
            throw ex;
        }
    }

    /**
     * Sends the request and obtain a response.
     *
     * @param request   the request to execute.
     * @param conn      the connection over which to execute the request.
     * @param context the context
     * @return  the response to the request.
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public ClassicHttpResponse execute(
            final ClassicHttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context) throws IOException, HttpException {
        return execute(request, conn, null, context);
    }

    /**
     * Pre-process the given request using the given protocol processor and
     * initiates the process of request execution.
     *
     * @param request   the request to prepare
     * @param processor the processor to use
     * @param context   the context for sending the request
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public void preProcess(
            final ClassicHttpRequest request,
            final HttpProcessor processor,
            final HttpContext context) throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        Args.notNull(processor, "HTTP processor");
        Args.notNull(context, "HTTP context");
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
        processor.process(request, request.getEntity(), context);
    }

    /**
     * Post-processes the given response using the given protocol processor and
     * completes the process of request execution.
     * <p>
     * This method does <i>not</i> read the response entity, if any.
     * The connection over which content of the response entity is being
     * streamed from cannot be reused until the response entity has been
     * fully consumed.
     *
     * @param response  the response object to post-process
     * @param processor the processor to use
     * @param context   the context for post-processing the response
     *
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation or a processing
     *   problem.
     */
    public void postProcess(
            final ClassicHttpResponse response,
            final HttpProcessor processor,
            final HttpContext context) throws HttpException, IOException {
        Args.notNull(response, "HTTP response");
        Args.notNull(processor, "HTTP processor");
        Args.notNull(context, "HTTP context");
        final ProtocolVersion transportVersion = response.getVersion();
        if (transportVersion != null) {
            context.setProtocolVersion(transportVersion);
        }
        context.setAttribute(HttpCoreContext.HTTP_RESPONSE, response);
        processor.process(response, response.getEntity(), context);
    }

    /**
     * Determines whether the connection can be kept alive and is safe to be re-used for subsequent message exchanges.
     *
     * @param request current request object.
     * @param response  current response object.
     * @param connection actual connection.
     * @param context current context.
     * @return {@code true} is the connection can be kept-alive and re-used.
     * @throws IOException in case of an I/O error.
     */
    //判断当前链接可以keep-alive
    public boolean keepAlive(
            final ClassicHttpRequest request,
            final ClassicHttpResponse response,
            final HttpClientConnection connection,
            final HttpContext context) throws IOException {
        Args.notNull(connection, "HTTP connection");
        Args.notNull(request, "HTTP request");
        Args.notNull(response, "HTTP response");
        Args.notNull(context, "HTTP context");
        final boolean keepAlive = connection.isConsistent() && connReuseStrategy.keepAlive(request, response, context);
        if (streamListener != null) {
            streamListener.onExchangeComplete(connection, keepAlive);
        }
        return keepAlive;
    }

}
