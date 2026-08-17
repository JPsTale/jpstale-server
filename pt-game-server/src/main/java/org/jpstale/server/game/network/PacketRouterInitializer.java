package org.jpstale.server.game.network;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PacketRouter 初始化器
 * 自动扫描所有 @GamePacketHandler 注解的类并注册到 PacketRouter
 */
@Slf4j
@Component
public class PacketRouterInitializer {

    @Autowired
    private PacketRouter packetRouter;

    @Autowired
    private ApplicationContext applicationContext;

    @PostConstruct
    public void init() {
        // 自动扫描并注册所有 Handler
        registerAnnotatedHandlers();

        log.info("PacketRouter initialized with {} handlers", packetRouter.getHandlerCount());
    }

    private void registerAnnotatedHandlers() {
        // 获取所有实现了 PacketHandler 的 Bean
        Map<String, PacketHandler> handlerBeans = applicationContext.getBeansOfType(PacketHandler.class);

        for (Map.Entry<String, PacketHandler> entry : handlerBeans.entrySet()) {
            PacketHandler handler = entry.getValue();
            GamePacketHandler annotation = handler.getClass().getAnnotation(GamePacketHandler.class);

            if (annotation != null) {
                int messageType = annotation.value();
                packetRouter.register(messageType, handler);
                log.debug("Auto-registered handler: {} for message type: {}", 
                    handler.getClass().getSimpleName(), messageType);
            } else {
                log.warn("Handler {} does not have @GamePacketHandler annotation", 
                    handler.getClass().getSimpleName());
            }
        }
    }
}
