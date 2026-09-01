package org.jpstale.server.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.server.web.dto.LoginRequest;
import org.jpstale.server.web.service.LoginService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/game")
public class GameLoginController {

    private static final String GAME_SERVER_KEY_PREFIX = "pt:game:";
    private final LoginService loginService;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public GameLoginController(LoginService loginService, StringRedisTemplate redis) {
        this.loginService = loginService;
        this.redis = redis;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        UserInfo user = loginService.validate(request.getAccount(), request.getPassword());
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "账号或密码错误"));
        }

        // sa-token login → generates token, stored in Redis
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();

        // Read game servers from Redis
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
                    // skip malformed entries
                }
            }
        }

        // Sort by id
        servers.sort(Comparator.comparingInt(s -> (int) s.get("id")));

        return ResponseEntity.ok(Map.of(
                "success", true,
                "token", token,
                "servers", servers
        ));
    }
}
