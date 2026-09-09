package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 战斗日志 — 把战斗/经验/升级事件作为系统消息写入聊天窗日志。
 *
 * minecraft 式翻译：服务端只发翻译 key + 命名参数，客户端按 locale 渲染模板；
 * key 缺失时客户端 fallback 显示 key 本身（对应原版 AddChatBuff(msg, type) 机制，
 * 这里统一走 S2C_SystemMessage 进"系统"tab）。
 */
@Slf4j
@Service
public class BattleLogService {

    private static final String KEY_MONSTER_KILLED = "chat.log.monsterKilled";
    private static final String KEY_PLAYER_HURT = "chat.log.playerHurt";
    private static final String KEY_LEVEL_UP = "chat.log.levelUp";

    @Autowired
    private GameMessageSender messageSender;

    /** 击杀怪物：EXP + 金币（对齐原版 playsub > Ganhou EXP） */
    public void monsterKilled(PlayerSession killer, String monsterName, long exp, int gold) {
        if (killer == null) return;
        send(killer, KEY_MONSTER_KILLED, Map.of(
            "monster", monsterName,
            "exp", String.valueOf(exp),
            "gold", String.valueOf(gold)));
    }

    /** 玩家受击：怪物对你造成伤害 */
    public void playerHurt(PlayerSession victim, String monsterName, int damage) {
        if (victim == null) return;
        send(victim, KEY_PLAYER_HURT, Map.of(
            "monster", monsterName,
            "damage", String.valueOf(damage)));
    }

    /** 升级：获得自由属性点 */
    public void levelUp(PlayerSession session, int level, int points) {
        if (session == null) return;
        send(session, KEY_LEVEL_UP, Map.of(
            "level", String.valueOf(level),
            "points", String.valueOf(points)));
    }

    private void send(PlayerSession session, String key, Map<String, String> params) {
        if (session.getCharacterId() == null) return;
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                .setKey(key)
                .putAllParams(params)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build();
        messageSender.sendToPlayer(session.getCharacterId(), msg);
    }
}