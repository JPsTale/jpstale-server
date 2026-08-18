package org.jpstale.server.game.network;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * PacketRouter 初始化器
 * 自动扫描所有标注 @GamePacketHandler 方法并注册到 PacketRouter
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
        registerAnnotatedHandlers();
        log.info("PacketRouter initialized with {} handlers", packetRouter.getHandlerCount());
    }

    private void registerAnnotatedHandlers() {
        // 扫描所有 Bean，找到标注 @GamePacketHandler 的方法
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = bean.getClass();

            for (Method method : beanClass.getMethods()) {
                GamePacketHandler annotation = method.getAnnotation(GamePacketHandler.class);
                if (annotation != null) {
                    int messageType = annotation.value();
                    packetRouter.register(messageType, bean, method);
                    log.debug("Auto-registered handler: {}.{} for message type: {}",
                        beanClass.getSimpleName(), method.getName(), messageType);
                }
            }
        }
    }
}