/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2022 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.addon.network.internal.server.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.network.internal.ChannelAttributes;
import org.zaproxy.addon.network.server.HttpMessageHandler;

/** The main handler of a HTTP server, notifies {@link HttpMessageHandler}s and acts accordingly. */
public class MainServerHandler extends SimpleChannelInboundHandler<HttpMessage> {

    private static final Logger LOGGER = LogManager.getLogger(MainServerHandler.class);

    protected final List<HttpMessageHandler> pipeline;
    protected final DefaultHttpMessageHandlerContext handlerContext;

    /**
     * Constructs a {@code HttpMessageServerBridge} with the given handlers.
     *
     * @param handlers the message handlers.
     * @throws NullPointerException if the given list is {@code null}.
     */
    public MainServerHandler(List<HttpMessageHandler> handlers) {
        this.pipeline = Objects.requireNonNull(handlers);
        this.handlerContext = new DefaultHttpMessageHandlerContext();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
        if (msg.getUserObject() instanceof Exception) {
            throw (Exception) msg.getUserObject();
        }

        ctx.channel().attr(ChannelAttributes.PROCESSING_MESSAGE).set(Boolean.TRUE);
        try {
            process(ctx, msg);
        } finally {
            ctx.channel().attr(ChannelAttributes.PROCESSING_MESSAGE).set(Boolean.FALSE);
        }
    }

    private void process(ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
        handlerContext.reset();
        Channel channel = ctx.channel();
        handlerContext.setRecursive(channel.attr(ChannelAttributes.RECURSIVE_MESSAGE).get());

        if (!processMessage(msg)) {
            close(ctx);
            return;
        }

        writeResponse(ctx, msg);

        if (postWriteResponse(ctx, msg)) {
            return;
        }

        if (isConnectionClose(msg)) {
            close(ctx);
        }
    }

    protected boolean processMessage(HttpMessage msg) {
        if (notifyMessageHandlers(msg)) {
            return false;
        }

        handlerContext.handlingResponse();

        if (notifyMessageHandlers(msg)) {
            return false;
        }

        return true;
    }

    private boolean notifyMessageHandlers(HttpMessage msg) {
        for (HttpMessageHandler handler : pipeline) {
            try {
                handler.handleMessage(handlerContext, msg);
            } catch (Throwable e) {
                LOGGER.error("An error occurred while notifying a handler:", e);
            }

            if (handlerContext.isClose()) {
                return true;
            }

            if (handlerContext.isOverridden()) {
                return false;
            }
        }

        return false;
    }

    protected boolean postWriteResponse(ChannelHandlerContext ctx, HttpMessage msg) {
        return false;
    }

    private static void writeResponse(ChannelHandlerContext ctx, HttpMessage msg) {
        ctx.writeAndFlush(msg)
                .addListener(
                        e -> {
                            if (!e.isSuccess()) {
                                LOGGER.warn(
                                        () -> {
                                            Throwable cause = e.cause();
                                            StringBuilder strBuilder = new StringBuilder(200);
                                            strBuilder.append(
                                                    "Failed to write/forward the HTTP response to the client: ");
                                            strBuilder.append(cause.getClass().getName());
                                            if (cause.getMessage() != null) {
                                                strBuilder.append(": ").append(cause.getMessage());
                                            }
                                            return strBuilder;
                                        });
                            }
                        });
    }

    protected static void close(ChannelHandlerContext ctx) {
        ctx.close()
                .addListener(
                        e -> {
                            if (!e.isSuccess()) {
                                LOGGER.debug(
                                        "An error occurred while closing the connection.",
                                        e.cause());
                            }
                        });
    }

    private static boolean isConnectionClose(HttpMessage msg) {
        if (msg.getResponseHeader().isEmpty()) {
            return true;
        }

        if (msg.getRequestHeader().isConnectionClose()) {
            return true;
        }

        if (msg.getResponseHeader().isConnectionClose()) {
            return true;
        }

        if (msg.getResponseHeader().getContentLength() == -1
                && msg.getResponseBody().length() > 0) {
            return true;
        }

        return false;
    }
}
