package org.jpstale.server.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对应原版 C++ 的 CServerManager。
 *
 * 负责管理多个 {@link Server} 实例，并在单线程循环中驱动 tick。
 */
@Slf4j
public class ServerManager implements Runnable {

    private final List<Server> servers = new ArrayList<>();

    /**
     * tick 间隔（毫秒）。
     */
    private final long tickIntervalMs;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public ServerManager(long tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public void addServer(Server server) {
        this.servers.add(server);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            Thread t = new Thread(this, "pt-server-core-loop");
            t.setDaemon(true);
            t.start();
        }
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        log.info("ServerManager loop started, tick={} ms", tickIntervalMs);
        servers.forEach(Server::init);
        try {
            while (running.get()) {
                long now = System.currentTimeMillis();
                for (Server server : servers) {
                    try {
                        server.tick(now);
                    } catch (Exception e) {
                        log.error("Server tick error", e);
                    }
                }
                long elapsed = System.currentTimeMillis() - now;
                long sleep = tickIntervalMs - elapsed;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            servers.forEach(Server::shutdown);
            log.info("ServerManager loop stopped");
        }
    }
}

