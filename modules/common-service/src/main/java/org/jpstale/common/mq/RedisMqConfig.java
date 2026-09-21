package org.jpstale.common.mq;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 消息队列的**唯一**装配点：通道名 + 序列化器 + 生产者。
 *
 * 此前 game-server 与 web-server 各写一份（通道名字面量 `pt:clan:topic`、那一整串
 * `activateDefaultTyping` 序列化器配置）。那种重复的失败方式是**静默**的：
 * 两边序列化器只要有一点不一致，跨进程消息就反序列化不出来 —— 而**编译不报错**，
 * 症状是"公会操作没反应"。所以这些必须只有一处定义。
 *
 * 分工（别把两者混起来）：
 *  - 这里放**两端都要**的东西：通道名、`RedisTemplate`、`RedisMsgProducer`；
 *  - 只在 web 侧需要的**订阅容器**（`RedisMessageListenerContainer`）留在 web 自己的
 *    `RedisConfig` 里 —— game-server 是发布方，不需要订阅；反过来给它挂个容器，
 *    同一条消息会被两个进程各收一份（pub/sub 是广播，不是竞争消费），纯属浪费。
 */
@Configuration
public class RedisMqConfig {

    /** 血盟事件通道：web 订阅（落库）、game 发布（把游戏内的公会操作甩给 web）。 */
    public static final String CLAN_TOPIC = "pt:clan:topic";

    /**
     * 消息专用模板。命名刻意不叫 `redisTemplate`：那个名字被 Spring Boot 的
     * `RedisAutoConfiguration` 占着（`@ConditionalOnMissingBean(name="redisTemplate")`），
     * 用同名会把自动配置顶掉，牵连到别的组件（如 Sa-Token 的会话）—— 那些不是我们的关注点。
     */
    @Bean
    public RedisTemplate<String, Object> mqRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisMsgProducer redisMsgProducer(RedisTemplate<String, Object> mqRedisTemplate) {
        return new RedisMsgProducer(mqRedisTemplate, CLAN_TOPIC);
    }

    /**
     * 跨进程消息的序列化器。**必须两端完全一致**（含 `activateDefaultTyping` 写进去的类型信息），
     * 否则一边写、另一边读不出来 —— 所以只有这一处。
     */
    static GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new GenericJackson2JsonRedisSerializer(om);
    }
}
