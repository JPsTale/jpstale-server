package org.jpstale.server.game.network;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 消息处理器注解
 * 标注在 PacketHandler 实现类上，指定处理的消息类型字段号
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GamePacketHandler {
    /**
     * 消息类型字段号（对应 ClientMessage oneof 的字段号）
     */
    int value();
}
