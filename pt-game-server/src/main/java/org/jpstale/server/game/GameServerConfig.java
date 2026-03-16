package org.jpstale.server.game;
import org.jpstale.server.core.ServerManager;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GameServerConfig {

    @Value("${pt.game.loop.tick-interval-ms:50}")
    private long tickIntervalMs;

    @Bean
    public ServerManager serverManager(GameServer gameServer) {
        ServerManager manager = new ServerManager(tickIntervalMs);
        manager.addServer(gameServer);
        return manager;
    }

    /**
     * 在 Spring 完成依赖注入后启动主循环。
     */
    @Bean
    public InitializingBean startServerLoop(ServerManager serverManager) {
        return () -> serverManager.start();
    }
}