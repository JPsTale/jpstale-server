package org.jpstale.server.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.server.web.dto.LoginRequest;
import org.jpstale.server.web.dto.Result;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.jpstale.server.web.service.LoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/game")
public class GameLoginController {

    private static final Logger log = LoggerFactory.getLogger(GameLoginController.class);

    private static final String GAME_SERVER_KEY_PREFIX = "pt:game:";
    private final LoginService loginService;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public GameLoginController(LoginService loginService, StringRedisTemplate redis) {
        this.loginService = loginService;
        this.redis = redis;
    }

    @PostMapping("/login")
    public Result<Map<String, String>> login(@Valid @RequestBody LoginRequest request) {
        UserInfo user = loginService.validate(request.getAccount(), request.getPassword());
        if (user == null) {
            // 账号不存在 / 密码错误 / 被封禁 / 未激活 一律同一口径，不泄露账号是否存在
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        // sa-token login → generates token, stored in Redis
        StpUtil.login(user.getId());

        return Result.ok(Map.of("token", StpUtil.getTokenValue()));
    }

    /** 服务器列表（独立接口，不复用登录 —— 登录也用于 Web 其它页面，不跟选服耦合）。 */
    @GetMapping("/servers")
    public Result<List<Map<String, Object>>> servers() {
        List<Map<String, Object>> servers = new ArrayList<>();
        Set<String> keys = redis.keys(GAME_SERVER_KEY_PREFIX + "*");
        if (keys != null) {
            for (String key : keys) {
                try {
                    String json = redis.opsForValue().get(key);
                    if (json != null) {
                        JsonNode node = mapper.readTree(json);
                        Map<String, Object> server = new LinkedHashMap<>();
                        server.put("id", node.get("id").asInt());
                        server.put("name", node.get("name").asText());
                        server.put("ip", node.get("ip").asText());
                        server.put("port", node.get("port").asInt());
                        server.put("online", node.get("online").asBoolean());
                        servers.add(server);
                    }
                } catch (Exception e) {
                    // 单条注册信息坏掉不影响整份列表，但要说出来（否则"少一台服"无从归因）
                    log.warn("跳过损坏的游戏服务器注册信息 key={}: {}", key, e.getMessage());
                }
            }
        }
        servers.sort(Comparator.comparingInt(s -> (int) s.get("id")));
        return Result.ok(servers);
    }
}
