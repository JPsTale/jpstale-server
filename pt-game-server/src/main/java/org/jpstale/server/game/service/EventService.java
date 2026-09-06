package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.service.MonsterSpawnService;
import org.jpstale.server.game.model.GameMap;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.model.GameEvent;
import org.jpstale.server.game.model.EventType;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.service.MapManager;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 活动服务 — 对应原版 rsEVENT_SPAWN + rsSERVER_CONFIG
 * 管理所有游戏活动的生命周期
 */
@Slf4j
@Service
public class EventService {

    @Autowired
    private MonsterSpawnService monsterSpawnService;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private MapManager mapManager;

    private final Map<Long, GameEvent> events = new ConcurrentHashMap<>();
    private final Map<Integer, GameEvent> activeByMap = new ConcurrentHashMap<>(); // mapId -> event
    private final AtomicLong eventIdGenerator = new AtomicLong(1);

    @PostConstruct
    public void init() {
        // 初始化默认活动
        createEvent(EventType.HARDCORE, 1);   // 硬核竞技场在地图1
        createEvent(EventType.EVENT_SPAWN, 2); // 区域刷怪在地图2
        createEvent(EventType.BABEL_HORN, 3);  // 巴别号角在地图3

        log.info("EventService initialized with {} events", events.size());
    }

    /**
     * 创建活动
     */
    public GameEvent createEvent(EventType type, int mapId) {
        long id = eventIdGenerator.getAndIncrement();
        GameEvent event = new GameEvent(id, type, mapId);
        events.put(id, event);
        log.info("Created event: {} on map {}", type.getName(), mapId);
        return event;
    }

    /**
     * 启动活动
     */
    public void startEvent(long eventId) {
        GameEvent event = events.get(eventId);
        if (event == null) {
            log.warn("Event not found: {}", eventId);
            return;
        }

        if (event.isActive()) {
            log.warn("Event already active: {}", eventId);
            return;
        }

        event.onStart();
        activeByMap.put(event.getMapId(), event);

        log.info("Event started: {} on map {}", event.getType().getName(), event.getMapId());

        // 通知所有玩家
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                .setMessage("活动 [" + event.getType().getName() + "] 已开始！")
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();
        messageSender.broadcastToAll(msg);
    }

    /**
     * 结束活动
     */
    public void finishEvent(long eventId) {
        GameEvent event = events.get(eventId);
        if (event == null) return;

        event.onFinish();
        activeByMap.remove(event.getMapId());

        log.info("Event finished: {} kills={}/{}", event.getType().getName(),
            event.getKillCount(), event.getKillTarget());

        // 通知所有玩家
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                .setMessage("活动 [" + event.getType().getName() + "] 已结束！")
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();
        messageSender.broadcastToAll(msg);

        // TODO: 发放奖励给参与者
        distributeRewards(event);
    }

    /**
     * 玩家参与活动
     */
    public void joinEvent(long playerId, long eventId) {
        GameEvent event = events.get(eventId);
        if (event == null || !event.isActive()) {
            return;
        }

        event.addParticipant(playerId);
        log.debug("Player {} joined event {}", playerId, event.getType().getName());
    }

    /**
     * 活动怪物击杀
     */
    public void onEventMonsterKill(long eventId, long killerId) {
        GameEvent event = events.get(eventId);
        if (event == null || !event.isActive()) return;

        event.setKillCount(event.getKillCount() + 1);
        event.setCurrentMonsters(event.getCurrentMonsters() - 1);

        // 检查是否达到击杀目标
        if (event.getKillCount() >= event.getKillTarget()) {
            finishEvent(eventId);
        }
    }

    /**
     * 定时更新活动状态
     */
    @Scheduled(fixedRate = 5000) // 每5秒检查
    public void updateEvents() {
        long now = System.currentTimeMillis();

        for (GameEvent event : events.values()) {
            if (!event.isActive()) continue;

            // 检查活动是否超时
            if (now >= event.getEndTime()) {
                finishEvent(event.getId());
                continue;
            }

            // 检查是否需要刷怪
            if (now - event.getLastSpawnTime() >= event.getSpawnInterval()) {
                spawnEventMonsters(event);
                event.setLastSpawnTime(now);
            }
        }
    }

    /**
     * 刷活动怪物
     */
    private void spawnEventMonsters(GameEvent event) {
        int toSpawn = Math.min(10, event.getMaxMonsters() - event.getCurrentMonsters());
        if (toSpawn <= 0) return;

        GameMap gameMap = mapManager.getMap(event.getMapId());
        if (gameMap == null) return;

        for (int i = 0; i < toSpawn; i++) {
            Monster monster = new Monster();
            monster.setName("Event Monster");
            monster.setLevel(1);
            monster.setHp(100);
            monster.setMaxHp(100);
            monster.setAttack(10);
            monster.setDefense(5);
            monster.setSpeed(3.0f);
            monster.setMapId(event.getMapId());
            monster.setState(MonsterState.IDLE);
            monster.setX(100);
            monster.setZ(100);
            event.setCurrentMonsters(event.getCurrentMonsters() + 1);
        }

        log.debug("Spawned {} event monsters for event {}", toSpawn, event.getId());
    }

    /**
     * 发放活动奖励
     */
    private void distributeRewards(GameEvent event) {
        for (Long playerId : event.getParticipantIds()) {
            // 根据活动类型发放不同奖励
            int expReward = event.getKillCount() * 10;
            int goldReward = event.getKillCount() * 5;

            log.info("Distributing rewards to player {}: exp={}, gold={}", playerId, expReward, goldReward);

            // TODO: 通过 PlayerService 更新玩家数据
        }
    }

    /**
     * 获取地图上的活动
     */
    public GameEvent getActiveEventByMap(int mapId) {
        return activeByMap.get(mapId);
    }

    /**
     * 获取所有活动
     */
    public List<GameEvent> getAllEvents() {
        return new ArrayList<>(events.values());
    }
}
