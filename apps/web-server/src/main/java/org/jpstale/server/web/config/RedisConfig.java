package org.jpstale.server.web.config;

import org.jpstale.common.mq.RedisMqConfig;
import org.jpstale.common.mq.RedisMsgDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * web 侧的 Redis **订阅**装配。
 *
 * 通道名、序列化器、`RedisTemplate`、生产者都在 common-service 的 {@link RedisMqConfig} 里
 * （两端共用，只有一处定义 —— 以前两处各写一份，序列化器一旦漂移就是静默失败）。
 * 这里只留**只有订阅方需要**的东西：把 {@link RedisMsgDispatcher} 包成监听适配器，挂到血盟通道上。
 *
 * 为什么订阅容器不放进共享配置：game-server 是发布方；给它也挂一个容器，同一条消息会被
 * 两个进程**各收一份**（pub/sub 是广播而非竞争消费），纯属浪费。
 */
@Configuration
public class RedisConfig {

    @Bean
    public MessageListenerAdapter commonListenerAdapter(RedisMsgDispatcher dispatcher) {
        return new MessageListenerAdapter(dispatcher, "onMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory factory,
                                                       MessageListenerAdapter adapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(adapter, new ChannelTopic(RedisMqConfig.CLAN_TOPIC));
        return container;
    }
}
