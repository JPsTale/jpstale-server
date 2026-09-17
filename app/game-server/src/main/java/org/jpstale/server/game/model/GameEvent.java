package org.jpstale.server.game.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 活动实例 — 对应原版 rsEVENT_SPAWN
 */
@Data
public class GameEvent {

    private long id;
    private EventType type;
    private EventState state;
    private long startTime;        // 活动开始时间
    private long endTime;          // 活动结束时间
    private long lastSpawnTime;    // 上次刷怪时间
    private int mapId;             // 活动地图
    private int maxMonsters;       // 最大怪物数
    private int currentMonsters;   // 当前怪物数
    private int spawnInterval;     // 刷怪间隔 (毫秒)
    private int killCount;         // 击杀计数
    private int killTarget;        // 击杀目标
    private List<Long> participantIds; // 参与者列表

    public GameEvent(long id, EventType type, int mapId) {
        this.id = id;
        this.type = type;
        this.mapId = mapId;
        this.state = EventState.WAITING;
        this.maxMonsters = 100;
        this.spawnInterval = 10000; // 10秒
        this.killTarget = 100;
        this.participantIds = new ArrayList<>();
    }

    public boolean isActive() {
        return state == EventState.PLAYING;
    }

    public boolean isFinished() {
        return state == EventState.FINISHED;
    }

    public void onStart() {
        this.state = EventState.PLAYING;
        this.startTime = System.currentTimeMillis();
        this.endTime = startTime + type.getPlayDuration();
        this.killCount = 0;
        this.currentMonsters = 0;
        this.lastSpawnTime = 0;
        this.participantIds.clear();
    }

    public void onFinish() {
        this.state = EventState.FINISHED;
    }

    public void addParticipant(long playerId) {
        if (!participantIds.contains(playerId)) {
            participantIds.add(playerId);
        }
    }

    public boolean isParticipant(long playerId) {
        return participantIds.contains(playerId);
    }
}
