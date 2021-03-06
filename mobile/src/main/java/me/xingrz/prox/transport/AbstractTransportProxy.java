/*
 * Copyright (C) 2015 XiNGRZ <chenxingyu92@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package me.xingrz.prox.transport;

import org.apache.commons.io.IOUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;

import me.xingrz.prox.logging.FormattingLogger;

/**
 * 传输层代理服务器抽象
 *
 * @param <C> 服务器通道，比如 {@link java.nio.channels.ServerSocketChannel} 或 {@link java.nio.channels.DatagramChannel}
 * @param <S> 会话
 */
public abstract class AbstractTransportProxy
        <C extends SelectableChannel, S extends AbstractTransportProxy.Session>
        implements Closeable {

    /**
     * 会话抽象
     */
    public static abstract class Session implements Closeable {

        protected final FormattingLogger logger;

        protected final Selector selector;

        private final int sourcePort;

        private final InetAddress remoteAddress;
        private final int remotePort;

        private boolean finished = false;

        long lastActive = System.currentTimeMillis();

        public Session(Selector selector, int sourcePort, InetAddress remoteAddress, int remotePort) {
            this.selector = selector;
            this.sourcePort = sourcePort;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.logger = getLogger();
        }

        protected abstract FormattingLogger getLogger();

        /**
         * @return 来源端口
         */
        public int getSourcePort() {
            return sourcePort;
        }

        /**
         * @return 远端地址
         */
        public InetAddress getRemoteAddress() {
            return remoteAddress;
        }

        /**
         * @return 远端端口
         */
        public int getRemotePort() {
            return remotePort;
        }

        /**
         * @return 该会话是否已完成，或被强行终结
         */
        public boolean isFinished() {
            return finished;
        }

        /**
         * 标记会话为完成
         */
        public void finish() {
            finished = true;
        }

        /**
         * 标记会话活动，不然过期未活动会被回收
         */
        public void active() {
            lastActive = System.currentTimeMillis();
        }

    }

    protected final FormattingLogger logger = getLogger();

    private final long sessionTimeout;

    private final NatSessionManager<S> sessions;

    protected Selector selector;
    protected C serverChannel;

    public AbstractTransportProxy(int maxSessionCount, long sessionTimeout) {

        this.sessionTimeout = sessionTimeout;
        this.sessions = new NatSessionManager<S>(maxSessionCount) {
            @Override
            protected void onRemoved(S session) {
                IOUtils.closeQuietly(session);
                if (session.isFinished()) {
                    logger.v("Removed finished session %08x", session.hashCode());
                } else {
                    logger.v("Terminated session %08x, session count: %s", session.hashCode(), size());
                }
            }

            @Override
            protected boolean shouldRecycle(S session) {
                return shouldRecycleSession(session);
            }
        };
    }

    protected abstract FormattingLogger getLogger();

    protected abstract C createChannel(Selector selector) throws IOException;

    public abstract int port();

    public void start(Selector selector) throws IOException {
        this.selector = selector;

        serverChannel = createChannel(selector);
        logger.d("Proxy running on %d", port());
    }

    public boolean isRunning() {
        return serverChannel.isOpen();
    }

    @Override
    public void close() throws IOException {
        sessions.clear();
        serverChannel.close();
    }

    /**
     * 创建新的会话，子类必须重载此方法
     *
     * @param sourcePort    来源端口，作为标识
     * @param remoteAddress 目标地址
     * @param remotePort    目标端口
     * @return 新的会话实例
     * @throws IOException 可能会因为无法绑定端口而抛出异常
     */
    protected abstract S createSession(int sourcePort, InetAddress remoteAddress, int remotePort)
            throws IOException;

    /**
     * 抽取一个会话
     * 默认实现为创建新会话并将它放入会话队列中，子类可以根据需要重载它，比如复用已有会话
     *
     * @param sourcePort    来源端口，作为标识
     * @param remoteAddress 目标地址
     * @param remotePort    目标端口
     * @return 新的会话实例
     * @throws IOException 如果是新创建会话，可能会抛出异常
     */
    public S pickSession(int sourcePort, InetAddress remoteAddress, int remotePort) throws IOException {
        S session = createSession(sourcePort, remoteAddress, remotePort);
        sessions.put(sourcePort, session);
        return session;
    }

    /**
     * 获取一个已有的会话
     *
     * @param sourcePort 来源端口，作为标识
     * @return 会话实例，或 {@code null} 表示不存在
     */
    public S getSession(int sourcePort) {
        return sessions.get(sourcePort);
    }

    /**
     * 完成并删除会话
     *
     * @param sourcePort 来源端口
     * @return 会话实例，或 {@code null} 表示不存在
     */
    public S finishSession(int sourcePort) {
        S session = sessions.get(sourcePort);
        sessions.remove(sourcePort);
        return session;
    }

    protected boolean shouldRecycleSession(S session) {
        return System.currentTimeMillis() - session.lastActive >= sessionTimeout;
    }

}
