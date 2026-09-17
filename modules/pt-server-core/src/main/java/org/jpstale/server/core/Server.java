package org.jpstale.server.core;

/**
 * 对应原版 C++ Server 工程中的 CServer。
 *
 * 作为单个逻辑服务器实例的抽象，由 {@link ServerManager} 统一驱动 tick。
 */
public interface Server {

    /**
     * 服务器初始化入口。
     * 由外部在 Netty / Spring 等基础设施准备就绪后调用。
     */
    default void init() {
    }

    /**
     * 每个逻辑帧调用一次。
     *
     * @param currentTimeMillis 当前时间戳，方便内部做超时判断等。
     */
    void tick(long currentTimeMillis);

    /**
     * 关闭服务器，释放资源。
     */
    default void shutdown() {
    }
}

