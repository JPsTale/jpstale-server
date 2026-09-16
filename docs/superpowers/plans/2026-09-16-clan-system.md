# Clan System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a modern JSON clan API for pt-web-server, with bidirectional Redis pub/sub between game server and web server for write operations.

**Architecture:** Read operations (detail, members, ranking, check-name) are handled directly by pt-web-server querying clandb. Write operations (create, dissolve, invite, kick, leave, transfer-leader, sub-leader) are handled by pt-game-server (memory authority for gold/player state), which then publishes Redis messages to pt-web-server for database persistence. Both servers share a Redis messaging infrastructure via pt-common.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Sa-Token 1.45.0, MyBatis-Plus 3.5.15, Redis Pub/Sub, PostgreSQL 16, Jackson

## Global Constraints

- Response format: `{ "code": 0, "msg": "ok", "data": {...}, "page": null }` — code=0 success, code≠0 failure
- All endpoints: `POST /api/clan/*.json`, `Content-Type: application/json`
- Authentication: Sa-Token `@SaCheckLogin`, userId from `StpUtil.getSession().getString("accountName")`
- Existing ASP endpoints (`/Clan/*.asp`) are removed, no backward compatibility needed
- Existing `ClanService` business logic is preserved, only the controller/response layer changes
- Gold field: `userdb.characterinfo.gold` (integer, 32-bit)

---

## Phase 1: Redis Messaging Infrastructure (pt-common)

### Task 1: Add Spring Data Redis + Jackson deps to pt-common

**Files:**
- Modify: `pt-common/pom.xml`

- [ ] **Step 1: Add dependencies**

Add to pt-common/pom.xml `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-json</artifactId>
    <optional>true</optional>
</dependency>
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/pom.xml
git commit -m "chore(pt-common): add optional Redis + JSON deps for messaging"
```

---

### Task 2: Create CommonMsg DTO

**Files:**
- Create: `pt-common/src/main/java/org/jpstale/server/common/redis/CommonMsg.java`

- [ ] **Step 1: Create CommonMsg**

```java
package org.jpstale.server.common.redis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommonMsg implements Serializable {

    private String requestId;
    private String type;
    private String data;

    public CommonMsg(String type, String data) {
        this.type = type;
        this.data = data;
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/src/main/java/org/jpstale/server/common/redis/CommonMsg.java
git commit -m "feat(pt-common): add CommonMsg DTO for Redis messaging"
```

---

### Task 3: Create RedisMsgListener interface

**Files:**
- Create: `pt-common/src/main/java/org/jpstale/server/common/redis/RedisMsgListener.java`

- [ ] **Step 1: Create interface**

```java
package org.jpstale.server.common.redis;

public interface RedisMsgListener {
    String getType();
    void onMessage(CommonMsg message);
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/src/main/java/org/jpstale/server/common/redis/RedisMsgListener.java
git commit -m "feat(pt-common): add RedisMsgListener interface"
```

---

### Task 4: Create RedisMsgDispatcher

**Files:**
- Create: `pt-common/src/main/java/org/jpstale/server/common/redis/RedisMsgDispatcher.java`

- [ ] **Step 1: Create dispatcher**

```java
package org.jpstale.server.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RedisMsgDispatcher implements MessageListener, ApplicationContextAware {

    private final Map<String, List<RedisMsgListener>> listenerMap = new HashMap<>();
    private final GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        CommonMsg msg;
        try {
            msg = (CommonMsg) serializer.deserialize(message.getBody(), CommonMsg.class);
        } catch (Exception e) {
            log.error("Failed to deserialize Redis message", e);
            return;
        }
        if (msg == null || msg.getType() == null || msg.getType().isBlank()) {
            log.debug("Empty or typeless Redis message ignored");
            return;
        }
        List<RedisMsgListener> listeners = listenerMap.get(msg.getType());
        if (listeners == null || listeners.isEmpty()) {
            log.debug("No listener for type: {}", msg.getType());
            return;
        }
        for (RedisMsgListener listener : listeners) {
            try {
                listener.onMessage(msg);
            } catch (Exception e) {
                log.error("Listener error for type: {}", msg.getType(), e);
            }
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        Map<String, RedisMsgListener> beans = ctx.getBeansOfType(RedisMsgListener.class);
        for (RedisMsgListener listener : beans.values()) {
            listenerMap.computeIfAbsent(listener.getType(), k -> new ArrayList<>()).add(listener);
        }
        log.info("RedisMsgDispatcher initialized, listeners: {}", listenerMap.keySet());
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/src/main/java/org/jpstale/server/common/redis/RedisMsgDispatcher.java
git commit -m "feat(pt-common): add RedisMsgDispatcher for pub/sub routing"
```

---

### Task 5: Create RedisMsgProducer

**Files:**
- Create: `pt-common/src/main/java/org/jpstale/server/common/redis/RedisMsgProducer.java`

- [ ] **Step 1: Create producer**

```java
package org.jpstale.server.common.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

@Slf4j
public class RedisMsgProducer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final String topicName;

    public RedisMsgProducer(RedisTemplate<String, Object> redisTemplate, String topicName) {
        this.redisTemplate = redisTemplate;
        this.topicName = topicName;
    }

    public void sendMessage(CommonMsg message) {
        log.debug("Publishing Redis message: type={}, requestId={}", message.getType(), message.getRequestId());
        redisTemplate.convertAndSend(topicName, message);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/src/main/java/org/jpstale/server/common/redis/RedisMsgProducer.java
git commit -m "feat(pt-common): add RedisMsgProducer for pub/sub publishing"
```

---

### Task 6: Create ClanMessageTypes constants

**Files:**
- Create: `pt-common/src/main/java/org/jpstale/server/common/redis/ClanMessageTypes.java`

- [ ] **Step 1: Create constants**

```java
package org.jpstale.server.common.redis;

public final class ClanMessageTypes {
    public static final String CLAN_CREATE = "CLAN_CREATE";
    public static final String CLAN_DISSOLVE = "CLAN_DISSOLVE";
    public static final String CLAN_INVITE = "CLAN_INVITE";
    public static final String CLAN_KICK = "CLAN_KICK";
    public static final String CLAN_LEAVE = "CLAN_LEAVE";
    public static final String CLAN_TRANSFER_LEADER = "CLAN_TRANSFER_LEADER";
    public static final String CLAN_SET_SUB_LEADER = "CLAN_SET_SUB_LEADER";
    public static final String CLAN_RELEASE_SUB_LEADER = "CLAN_RELEASE_SUB_LEADER";
    private ClanMessageTypes() {}
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/src/main/java/org/jpstale/server/common/redis/ClanMessageTypes.java
git commit -m "feat(pt-common): add ClanMessageTypes constants"
```

---

### Task 7: Create ClanMessageData DTOs

**Files:**
- Create: `pt-common/src/main/java/org/jpstale/server/common/redis/ClanMessageData.java`

- [ ] **Step 1: Create DTOs**

```java
package org.jpstale.server.common.redis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClanMessageData {
    private String clanName;
    private String userId;
    private String charName;
    private Integer charType;
    private Integer level;
    private String targetName;
    private Integer targetType;
    private Integer targetLevel;
    private String targetUserId;

    public static ClanMessageData create(String clanName, String userId, String charName, Integer charType, Integer level) {
        return new ClanMessageData(clanName, userId, charName, charType, level, null, null, null, null);
    }

    public static ClanMessageData simple(String clanName, String userId, String charName) {
        return new ClanMessageData(clanName, userId, charName, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-common compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-common/src/main/java/org/jpstale/server/common/redis/ClanMessageData.java
git commit -m "feat(pt-common): add ClanMessageData DTO for clan operations"
```

---

## Phase 2: Clan API Refactor (pt-web-server)

### Task 8: Add Redis config + bean to pt-web-server

**Files:**
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/config/RedisConfig.java`

- [ ] **Step 1: Create RedisConfig**

```java
package org.jpstale.server.web.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.jpstale.server.common.redis.CommonMsg;
import org.jpstale.server.common.redis.RedisMsgDispatcher;
import org.jpstale.server.common.redis.RedisMsgProducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    public static final String CLAN_TOPIC = "pt:clan:topic";

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
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
    public MessageListenerAdapter commonListenerAdapter(RedisMsgDispatcher dispatcher) {
        return new MessageListenerAdapter(dispatcher, "onMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory factory,
                                                         MessageListenerAdapter adapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(adapter, new ChannelTopic(CLAN_TOPIC));
        return container;
    }

    @Bean
    public RedisMsgProducer redisMsgProducer(RedisTemplate<String, Object> redisTemplate) {
        return new RedisMsgProducer(redisTemplate, CLAN_TOPIC);
    }

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new GenericJackson2JsonRedisSerializer(om);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/config/RedisConfig.java
git commit -m "feat(web): add Redis pub/sub config for clan messaging"
```

---

### Task 9: Create ApiResponse wrapper

**Files:**
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/dto/ApiResponse.java`

- [ ] **Step 1: Create ApiResponse**

```java
package org.jpstale.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;
    private Object page;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, null);
    }

    public static <T> ApiResponse<T> ok(String msg, T data) {
        return new ApiResponse<>(0, msg, data, null);
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null, null);
    }

    public static <T> ApiResponse<T> fail(String msg) {
        return new ApiResponse<>(1, msg, null, null);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/dto/ApiResponse.java
git commit -m "feat(web): add ApiResponse wrapper {code, msg, data, page}"
```

---

### Task 10: Create ClanRequest DTOs

**Files:**
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/ClanCreateRequest.java`
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/ClanDetailRequest.java`
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/ClanNameRequest.java`

- [ ] **Step 1: Create request DTOs**

```java
// ClanCreateRequest.java
package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClanCreateRequest {
    @NotBlank @Size(min = 1, max = 50)
    private String clanName;
    @NotBlank @Size(min = 1, max = 50)
    private String charName;
    private Integer charType;
    private Integer level;
}
```

```java
// ClanDetailRequest.java
package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClanDetailRequest {
    @NotBlank
    private String charName;
}
```

```java
// ClanNameRequest.java
package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClanNameRequest {
    @NotBlank @Size(min = 1, max = 50)
    private String clanName;
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/
git commit -m "feat(web): add clan request DTOs"
```

---

### Task 11: Create ClanDetail response DTOs

**Files:**
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/ClanDetailResponse.java`
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/ClanMemberDto.java`
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/ClanRankDto.java`

- [ ] **Step 1: Create response DTOs**

```java
// ClanDetailResponse.java
package org.jpstale.server.web.clan.dto;

import lombok.Data;
import java.util.List;

@Data
public class ClanDetailResponse {
    private Integer clanId;
    private String clanName;
    private String leader;
    private String subLeader;
    private String note;
    private Integer memberCount;
    private Integer iconId;
    private String regiDate;
    private String limitDate;
    private Long clanMoney;
    private Integer cPoint;
    private Integer rank;
    private boolean isLeader;
    private boolean isSubLeader;
    private List<ClanMemberDto> members;
}
```

```java
// ClanMemberDto.java
package org.jpstale.server.web.clan.dto;

import lombok.Data;

@Data
public class ClanMemberDto {
    private String charName;
    private String userId;
    private Integer charType;
    private Integer charLevel;
    private String permission;
    private String joinDate;
}
```

```java
// ClanRankDto.java
package org.jpstale.server.web.clan.dto;

import lombok.Data;

@Data
public class ClanRankDto {
    private Integer clanId;
    private String clanName;
    private String leader;
    private Integer memberCount;
    private Integer iconId;
    private Integer cPoint;
    private Long clanMoney;
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/clan/dto/
git commit -m "feat(web): add clan response DTOs"
```

---

### Task 12: Refactor ClanService for new responses

**Files:**
- Modify: `pt-web-server/src/main/java/org/jpstale/server/web/clan/ClanService.java`

**Changes:** Add new methods that return DTOs instead of ClanResponse. Keep existing methods for backward compat during transition.

- [ ] **Step 1: Add new methods to ClanService**

Add these methods to the existing ClanService class:

```java
// --- New JSON API methods ---

public org.jpstale.server.web.clan.dto.ClanDetailResponse getClanDetail(String charName) {
    String chnameTrim = charName != null ? charName.trim() : "";
    String clanName = ulMapper.selectClanNameByChName(chnameTrim);
    if (clanName == null || clanName.isEmpty()) return null;

    Cl cl = clMapper.selectClanZangMemCntNoteMIconCntRegiDateLimitDatePFlagKFlagClanMoneyByClanName(clanName);
    if (cl == null) return null;

    org.jpstale.server.web.clan.dto.ClanDetailResponse resp = new org.jpstale.server.web.clan.dto.ClanDetailResponse();
    resp.setClanId(cl.getId());
    resp.setClanName(clanName);
    resp.setLeader(cl.getClanZang());
    resp.setNote(cl.getNote());
    resp.setMemberCount(cl.getMemCnt());
    resp.setIconId(cl.getMIconCnt());
    resp.setRegiDate(cl.getRegiDate() != null ? cl.getRegiDate().format(REGI_LIMIT_FORMAT) : "");
    resp.setLimitDate(cl.getLimitDate() != null ? cl.getLimitDate().format(REGI_LIMIT_FORMAT) : "");
    resp.setClanMoney(cl.getClanMoney());
    resp.setCPoint(cl.getCPoint());
    resp.setLeader(chnameTrim.equals(cl.getClanZang()));

    String subChief = ulMapper.selectChNameByPermi2AndClanName(clanName);
    resp.setSubLeader(subChief != null ? subChief : "");
    resp.setSubLeader(subChief != null && subChief.equals(chnameTrim));

    List<Cl> rankList = clMapper.selectByClanNameOrderByCpointDesc();
    resp.setRank(0);
    if (rankList != null && cl.getCPoint() != null && cl.getCPoint() > 0) {
        for (int i = 0; i < rankList.size(); i++) {
            if (clanName.equals(rankList.get(i).getClanName())) {
                resp.setRank(i + 1);
                break;
            }
        }
    }

    List<org.jpstale.server.web.clan.dto.ClanMemberDto> members = new java.util.ArrayList<>();
    List<String> names = ulMapper.selectChNameListByClanName(clanName);
    if (names != null) {
        for (String name : names) {
            Ul u = ulMapper.selectByChName(name);
            if (u == null) continue;
            org.jpstale.server.web.clan.dto.ClanMemberDto md = new org.jpstale.server.web.clan.dto.ClanMemberDto();
            md.setCharName(u.getChName());
            md.setUserId(u.getUserId());
            md.setCharType(u.getChType());
            md.setCharLevel(u.getChLv());
            md.setPermission(u.getPermi());
            md.setJoinDate(u.getJoinDate() != null ? u.getJoinDate().format(REGI_LIMIT_FORMAT) : "");
            members.add(md);
        }
    }
    resp.setMembers(members);

    return resp;
}

public List<org.jpstale.server.web.clan.dto.ClanRankDto> getRanking() {
    List<Cl> list = clMapper.selectByClanNameOrderByCpointDesc();
    List<org.jpstale.server.web.clan.dto.ClanRankDto> result = new java.util.ArrayList<>();
    if (list == null) return result;
    for (Cl c : list) {
        if (c.getCPoint() == null || c.getCPoint() <= 0) continue;
        org.jpstale.server.web.clan.dto.ClanRankDto rank = new org.jpstale.server.web.clan.dto.ClanRankDto();
        rank.setClanId(c.getId());
        rank.setClanName(c.getClanName());
        rank.setLeader(c.getClanZang());
        rank.setMemberCount(c.getMemCnt());
        rank.setIconId(c.getMIconCnt());
        rank.setCPoint(c.getCPoint());
        rank.setClanMoney(c.getClanMoney());
        result.add(rank);
    }
    return result;
}

public boolean isClanNameTaken(String clanName) {
    String zang = clMapper.selectClanZangByClanName(clanName != null ? clanName.trim() : "");
    return zang != null;
}

// --- Redis message handlers (called by listeners) ---

public void handleClanCreate(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    // Reuse existing newClan logic but without gold check (game server already deducted)
    newClan(d.getUserId(), "redis", d.getCharName(), d.getClanName(), d.getCharType(), d.getLevel());
}

public void handleClanDissolve(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    deleteClan(d.getUserId(), "redis", d.getCharName(), d.getClanName());
}

public void handleClanInvite(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    inviteClan(d.getUserId(), "redis", d.getCharName(), d.getClanName(),
            d.getTargetName(), d.getTargetUserId(), 0, d.getTargetType(), d.getTargetLevel(), "0");
}

public void handleClanKick(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    leavePlayer(d.getUserId(), "redis", d.getCharName(), d.getClanName(), d.getTargetName(), "0");
}

public void handleClanLeave(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    leavePlayerSelf(d.getUserId(), "redis", d.getCharName(), d.getClanName());
}

public void handleClanTransferLeader(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    changeLeader(d.getTargetName(), "redis", d.getClanName());
}

public void handleClanSetSubLeader(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    subLeaderUpdate(d.getCharName(), "redis");
}

public void handleClanReleaseSubLeader(String json) {
    ClanMessageData d = parseMessage(json);
    if (d == null) return;
    subLeaderRelease(d.getCharName(), "redis");
}

private ClanMessageData parseMessage(String json) {
    try {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, ClanMessageData.class);
    } catch (Exception e) {
        log.error("Failed to parse clan message: {}", json, e);
        return null;
    }
}
```

- [ ] **Step 2: Add import for ClanMessageData**

Add to imports:
```java
import org.jpstale.server.common.redis.ClanMessageData;
import lombok.extern.slf4j.Slf4j;
```

Add `@Slf4j` to class annotation.

- [ ] **Step 3: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/clan/ClanService.java
git commit -m "feat(web): add JSON API methods + Redis message handlers to ClanService"
```

---

### Task 13: Refactor ClanController

**Files:**
- Modify: `pt-web-server/src/main/java/org/jpstale/server/web/controller/ClanController.java`

**Changes:** Remove all old `.asp` paths and dual-path logic. Replace with clean JSON-only endpoints using `@SaCheckLogin` and `@Valid @RequestBody`.

- [ ] **Step 1: Rewrite ClanController**

Replace entire file:

```java
package org.jpstale.server.web.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import org.jpstale.server.web.clan.ClanService;
import org.jpstale.server.web.clan.dto.*;
import org.jpstale.server.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clan")
public class ClanController {

    private final ClanService clanService;

    public ClanController(ClanService clanService) {
        this.clanService = clanService;
    }

    @PostMapping("/detail.json")
    @SaCheckLogin
    public ApiResponse<ClanDetailResponse> detail(@Valid @RequestBody ClanDetailRequest req) {
        String charName = StpUtil.getSession().getString("accountName");
        ClanDetailResponse resp = clanService.getClanDetail(req.getCharName());
        if (resp == null) return ApiResponse.fail(1, "角色不在公会中");
        return ApiResponse.ok(resp);
    }

    @PostMapping("/members.json")
    @SaCheckLogin
    public ApiResponse<ClanDetailResponse> members(@Valid @RequestBody ClanDetailRequest req) {
        ClanDetailResponse resp = clanService.getClanDetail(req.getCharName());
        if (resp == null) return ApiResponse.fail(1, "角色不在公会中");
        return ApiResponse.ok(resp);
    }

    @PostMapping("/ranking.json")
    @SaCheckLogin
    public ApiResponse<List<ClanRankDto>> ranking() {
        return ApiResponse.ok(clanService.getRanking());
    }

    @PostMapping("/check-name.json")
    @SaCheckLogin
    public ApiResponse<Void> checkName(@Valid @RequestBody ClanNameRequest req) {
        boolean taken = clanService.isClanNameTaken(req.getClanName());
        if (taken) return ApiResponse.fail(2, "公会名已存在");
        return ApiResponse.ok("公会名可用", null);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/controller/ClanController.java
git commit -m "refactor(web): rewrite ClanController with JSON API + Sa-Token auth"
```

---

### Task 14: Create Redis message listeners for clan write operations

**Files:**
- Create: `pt-web-server/src/main/java/org/jpstale/server/web/clan/listener/ClanMessageListener.java`

- [ ] **Step 1: Create unified listener**

```java
package org.jpstale.server.web.clan.listener;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.common.redis.CommonMsg;
import org.jpstale.server.common.redis.RedisMsgListener;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClanMessageListener implements RedisMsgListener {

    private final ClanService clanService;

    public ClanMessageListener(ClanService clanService) {
        this.clanService = clanService;
    }

    @Override
    public String getType() {
        return "CLAN";  // wildcard: handles all CLAN_* types
    }

    @Override
    public void onMessage(CommonMsg message) {
        String type = message.getType();
        String data = message.getData();
        log.info("Received clan message: type={}", type);
        try {
            switch (type) {
                case ClanMessageTypes.CLAN_CREATE -> clanService.handleClanCreate(data);
                case ClanMessageTypes.CLAN_DISSOLVE -> clanService.handleClanDissolve(data);
                case ClanMessageTypes.CLAN_INVITE -> clanService.handleClanInvite(data);
                case ClanMessageTypes.CLAN_KICK -> clanService.handleClanKick(data);
                case ClanMessageTypes.CLAN_LEAVE -> clanService.handleClanLeave(data);
                case ClanMessageTypes.CLAN_TRANSFER_LEADER -> clanService.handleClanTransferLeader(data);
                case ClanMessageTypes.CLAN_SET_SUB_LEADER -> clanService.handleClanSetSubLeader(data);
                case ClanMessageTypes.CLAN_RELEASE_SUB_LEADER -> clanService.handleClanReleaseSubLeader(data);
                default -> log.debug("Unknown clan message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error handling clan message: type={}", type, e);
        }
    }
}
```

Wait — the dispatcher routes by `getType()` exact match. A wildcard listener won't work with the current dispatcher design. Need to fix the dispatcher to support prefix matching, OR register 8 separate listeners.

Simpler approach: register 8 listeners, one per type. Let me revise.

- [ ] **Step 1: Create listener base class + 8 implementations**

```java
// ClanMessageListener.java (base - not registered)
package org.jpstale.server.web.clan.listener;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.redis.CommonMsg;
import org.jpstale.server.common.redis.RedisMsgListener;
import org.jpstale.server.web.clan.ClanService;

@Slf4j
public abstract class ClanMessageListener implements RedisMsgListener {
    protected final ClanService clanService;

    protected ClanMessageListener(ClanService clanService) {
        this.clanService = clanService;
    }

    @Override
    public void onMessage(CommonMsg message) {
        log.info("Clan message received: type={}", message.getType());
        try {
            handle(message.getData());
        } catch (Exception e) {
            log.error("Error handling clan message: type={}", message.getType(), e);
        }
    }

    protected abstract void handle(String data);
}
```

```java
// ClanCreateListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanCreateListener extends ClanMessageListener {
    public ClanCreateListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_CREATE; }
    @Override protected void handle(String data) { clanService.handleClanCreate(data); }
}
```

```java
// ClanDissolveListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanDissolveListener extends ClanMessageListener {
    public ClanDissolveListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_DISSOLVE; }
    @Override protected void handle(String data) { clanService.handleClanDissolve(data); }
}
```

```java
// ClanInviteListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanInviteListener extends ClanMessageListener {
    public ClanInviteListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_INVITE; }
    @Override protected void handle(String data) { clanService.handleClanInvite(data); }
}
```

```java
// ClanKickListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanKickListener extends ClanMessageListener {
    public ClanKickListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_KICK; }
    @Override protected void handle(String data) { clanService.handleClanKick(data); }
}
```

```java
// ClanLeaveListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

@Component
public class ClanLeaveListener extends ClanMessageListener {
    public ClanLeaveListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_LEAVE; }
    @Override protected void handle(String data) { clanService.handleClanLeave(data); }
}
```

```java
// ClanTransferLeaderListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

public class ClanTransferLeaderListener extends ClanMessageListener {
    public ClanTransferLeaderListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_TRANSFER_LEADER; }
    @Override protected void handle(String data) { clanService.handleClanTransferLeader(data); }
}
```

```java
// ClanSetSubLeaderListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

public class ClanSetSubLeaderListener extends ClanMessageListener {
    public ClanSetSubLeaderListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_SET_SUB_LEADER; }
    @Override protected void handle(String data) { clanService.handleClanSetSubLeader(data); }
}
```

```java
// ClanReleaseSubLeaderListener.java
package org.jpstale.server.web.clan.listener;

import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.web.clan.ClanService;
import org.springframework.stereotype.Component;

public class ClanReleaseSubLeaderListener extends ClanMessageListener {
    public ClanReleaseSubLeaderListener(ClanService cs) { super(cs); }
    @Override public String getType() { return ClanMessageTypes.CLAN_RELEASE_SUB_LEADER; }
    @Override protected void handle(String data) { clanService.handleClanReleaseSubLeader(data); }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-web-server/src/main/java/org/jpstale/server/web/clan/listener/
git commit -m "feat(web): add 8 clan Redis message listeners"
```

---

## Phase 3: Game Server Integration (pt-game-server)

### Task 15: Add RedisConfig + RedisMsgProducer to game server

**Files:**
- Create: `pt-game-server/src/main/java/org/jpstale/server/game/config/RedisPubSubConfig.java`

- [ ] **Step 1: Create Redis config for game server**

```java
package org.jpstale.server.game.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.jpstale.server.common.redis.RedisMsgDispatcher;
import org.jpstale.server.common.redis.RedisMsgProducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisPubSubConfig {

    public static final String CLAN_TOPIC = "pt:clan:topic";

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
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
    public MessageListenerAdapter commonListenerAdapter(RedisMsgDispatcher dispatcher) {
        return new MessageListenerAdapter(dispatcher, "onMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory factory,
                                                         MessageListenerAdapter adapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(adapter, new ChannelTopic(CLAN_TOPIC));
        return container;
    }

    @Bean
    public RedisMsgProducer redisMsgProducer(RedisTemplate<String, Object> redisTemplate) {
        return new RedisMsgProducer(redisTemplate, CLAN_TOPIC);
    }

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new GenericJackson2JsonRedisSerializer(om);
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/config/RedisPubSubConfig.java
git commit -m "feat(game): add Redis pub/sub config for clan messaging"
```

---

### Task 16: Create ClanService in game server

**Files:**
- Create: `pt-game-server/src/main/java/org/jpstale/server/game/service/GameClanService.java`

- [ ] **Step 1: Create GameClanService**

```java
package org.jpstale.server.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.redis.ClanMessageData;
import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.common.redis.CommonMsg;
import org.jpstale.server.common.redis.RedisMsgProducer;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GameClanService {

    private final RedisMsgProducer producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameClanService(RedisMsgProducer producer) {
        this.producer = producer;
    }

    public boolean createClan(String userId, String charName, String clanName, Integer charType, Integer level, int gold) {
        // Gold validation happens here (in-memory authority)
        // Actual gold deduction is done by the caller (e.g., ItemService or PlayerService)
        // This method only sends the message to web server
        try {
            ClanMessageData data = ClanMessageData.create(clanName, userId, charName, charType, level);
            String json = objectMapper.writeValueAsString(data);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_CREATE, json));
            log.info("Clan create message sent: clan={}, char={}", clanName, charName);
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan create message", e);
            return false;
        }
    }

    public boolean dissolveClan(String userId, String charName, String clanName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_DISSOLVE, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan dissolve message", e);
            return false;
        }
    }

    public boolean inviteMember(String userId, String charName, String clanName,
                                 String targetName, String targetUserId, Integer targetType, Integer targetLevel) {
        try {
            ClanMessageData data = ClanMessageData.create(clanName, userId, charName, null, null);
            data.setTargetName(targetName);
            data.setTargetUserId(targetUserId);
            data.setTargetType(targetType);
            data.setTargetLevel(targetLevel);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_INVITE, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan invite message", e);
            return false;
        }
    }

    public boolean kickMember(String userId, String charName, String clanName, String targetName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            data.setTargetName(targetName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_KICK, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan kick message", e);
            return false;
        }
    }

    public boolean leaveClan(String userId, String charName, String clanName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_LEAVE, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan leave message", e);
            return false;
        }
    }

    public boolean transferLeader(String userId, String charName, String clanName, String targetName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            data.setTargetName(targetName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_TRANSFER_LEADER, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan transfer message", e);
            return false;
        }
    }

    public boolean setSubLeader(String charName) {
        try {
            ClanMessageData data = new ClanMessageData();
            data.setCharName(charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_SET_SUB_LEADER, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send set sub-leader message", e);
            return false;
        }
    }

    public boolean releaseSubLeader(String charName) {
        try {
            ClanMessageData data = new ClanMessageData();
            data.setCharName(charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_RELEASE_SUB_LEADER, objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send release sub-leader message", e);
            return false;
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/service/GameClanService.java
git commit -m "feat(game): add GameClanService for Redis-based clan operations"
```

---

### Task 17: Remove old ClanController / ClanResponse / ClanParamUtil

**Files:**
- Delete: `pt-web-server/src/main/java/org/jpstale/server/web/clan/ClanResponse.java`
- Delete: `pt-web-server/src/main/java/org/jpstale/server/web/clan/ClanParamUtil.java`

- [ ] **Step 1: Delete old files**

```bash
git rm pt-web-server/src/main/java/org/jpstale/server/web/clan/ClanResponse.java
git rm pt-web-server/src/main/java/org/jpstale/server/web/clan/ClanParamUtil.java
```

- [ ] **Step 2: Verify build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -pl pt-web-server -am compile`
Expected: BUILD SUCCESS (no references to deleted classes remain)

- [ ] **Step 3: Commit**

```bash
git commit -m "chore(web): remove legacy ClanResponse and ClanParamUtil"
```

---

## Phase 4: Cleanup + Final Build

### Task 18: Full build verification

- [ ] **Step 1: Full build**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -q -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run existing tests**

Run: `cd E:\JPsTale\jpstale-server && mvn -o -am test`
Expected: All tests pass

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: complete clan system JSON API with Redis pub/sub"
```

---

## Summary of Changes

| Module | Files Created | Files Modified | Files Deleted |
|---|---|---|---|
| pt-common | 5 (CommonMsg, RedisMsgListener, RedisMsgDispatcher, RedisMsgProducer, ClanMessageTypes, ClanMessageData) | 1 (pom.xml) | — |
| pt-web-server | 14 (RedisConfig, ApiResponse, 3 request DTOs, 3 response DTOs, 9 listeners) | 2 (ClanController, ClanService) | 2 (ClanResponse, ClanParamUtil) |
| pt-game-server | 2 (RedisPubSubConfig, GameClanService) | — | — |
