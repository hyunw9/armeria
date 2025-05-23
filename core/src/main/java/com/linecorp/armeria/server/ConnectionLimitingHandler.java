/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;

/**
 * Limit the number of open connections to the configured value.
 * {@link ConnectionLimitingHandler} instance would be set to {@link ServerBootstrap#handler(ChannelHandler)}.
 */
final class ConnectionLimitingHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionLimitingHandler.class);

    private final Set<Channel> childChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Channel> unmodifiableChildChannels = Collections.unmodifiableSet(childChannels);
    private final int maxNumConnections;

    /**
     * AtomicInteger is used to read the number of active connections frequently.
     */
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicBoolean loggingScheduled = new AtomicBoolean();
    private final LongAdder numDroppedConnections = new LongAdder();

    ConnectionLimitingHandler(int maxNumConnections) {
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
    }

    /**
     * Returns the maximum allowed number of open connections.
     */
    int maxNumConnections() {
        return maxNumConnections;
    }

    /**
     * Returns the number of open connections.
     */
    int numConnections() {
        return activeConnections.get();
    }

    /**
     * Returns the immutable set of child {@link Channel}s.
     */
    Set<Channel> children() {
        return unmodifiableChildChannels;
    }

    /**
     * Validates the maximum allowed number of open connections. It must be a positive number.
     */
    static int validateMaxNumConnections(int maxNumConnections) {
        if (maxNumConnections <= 0) {
            throw new IllegalArgumentException("maxNumConnections: " + maxNumConnections + " (expected: > 0)");
        }
        return maxNumConnections;
    }

    ChannelHandler newChildHandler(ServerPortMetric serverPortMetric) {
        return new ConnectionLimitingChildHandler(serverPortMetric);
    }

    @Sharable
    private class ConnectionLimitingChildHandler extends ChannelInboundHandlerAdapter {

        private final ServerPortMetric serverPortMetric;

        ConnectionLimitingChildHandler(ServerPortMetric serverPortMetric) {
            this.serverPortMetric = serverPortMetric;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            final Channel child = (Channel) msg;
            final int conn = activeConnections.incrementAndGet();
            if (conn > 0 && conn <= maxNumConnections) {
            serverPortMetric.increaseActiveConnections();
                childChannels.add(child);
                child.closeFuture().addListener(future -> {
                    childChannels.remove(child);
                    activeConnections.decrementAndGet();
                    serverPortMetric.decreaseActiveConnections();
                });
                super.channelRead(ctx, msg);
            } else {
                activeConnections.decrementAndGet();

                // Set linger option to 0 so that the server doesn't get too many TIME_WAIT states.
                child.config().setOption(ChannelOption.SO_LINGER, 0);
                child.unsafe().closeForcibly();

                numDroppedConnections.increment();

                if (loggingScheduled.compareAndSet(false, true)) {
                    ctx.executor().schedule(this::writeNumDroppedConnectionsLog, 1, TimeUnit.SECONDS);
                }
            }
        }

        private void writeNumDroppedConnectionsLog() {
            loggingScheduled.set(false);

            final long dropped = numDroppedConnections.sumThenReset();
            if (dropped > 0) {
                logger.warn("Dropped {} connection(s) to limit the number of open connections to {}",
                            dropped, maxNumConnections);
            }
        }
    }
}
