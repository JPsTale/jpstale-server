package org.jpstale.server.game.network;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * PacketRouter 初始化器
 * 自动扫描所有标注 @GamePacketHandler 方法并注册到 PacketRouter
 */
@Slf4j
@Component
public class PacketRouterInitializer implements SmartInitializingSingleton {

    @Autowired
    private PacketRouter packetRouter;

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public void afterSingletonsInstantiated() {
        registerAnnotatedHandlers();
        log.info("PacketRouter initialized with {} handlers", packetRouter.getHandlerCount());
    }

    private void registerAnnotatedHandlers() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = bean.getClass();

            for (Method method : beanClass.getMethods()) {
                GamePacketHandler annotation = AnnotationUtils.findAnnotation(method, GamePacketHandler.class);
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
