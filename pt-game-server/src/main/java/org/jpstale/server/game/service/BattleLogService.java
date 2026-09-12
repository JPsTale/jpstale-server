package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 战斗日志 — 把战斗/经验/升级事件写入聊天窗。
 *
 * minecraft 式翻译：服务端只发翻译 key + 命名参数，客户端按 locale 渲染模板；
 * key 缺失时客户端 fallback 显示 key 本身（对应原版 AddChatBuff(msg, type) 机制）。
 *
 * **频道**（用户 2026-09-12）：受击/击杀这类高频战斗刷屏走 `CHAT_BATTLE`（"战斗" tab），
 * 不再占用系统频道的注意力；升级这种低频、需要被看见的事件仍留在系统频道。
 */
@Slf4j
@Service
public class BattleLogService {

    private static final String KEY_MONSTER_KILLED = "chat.log.monsterKilled";
    private static final String KEY_PLAYER_HURT = "chat.log.playerHurt";
    private static final String KEY_LEVEL_UP = "chat.log.levelUp";
    private static final String KEY_PLAYER_HIT = "chat.log.playerHit";
    private static final String KEY_PLAYER_CRIT = "chat.log.playerCrit";
    private static final String KEY_PLAYER_MISS = "chat.log.playerMiss";
    private static final String KEY_PLAYER_WHIFF = "chat.log.playerWhiff";
    private static final String KEY_MONSTER_MISS = "chat.log.monsterMiss";
    private static final String KEY_PLAYER_RESPAWN = "chat.log.playerRespawn";

    @Autowired
    private GameMessageSender messageSender;

    /** 击杀怪物：EXP + 金币（对齐原版 playsub > Ganhou EXP） */
    public void monsterKilled(PlayerSession killer, String monsterName, long exp, int gold) {
        if (killer == null) return;
        send(killer, KEY_MONSTER_KILLED, Map.of(
            "monster", monsterName,
            "exp", String.valueOf(exp),
            "gold", String.valueOf(gold)), true);
    }

    /** 玩家打怪命中：造成伤害（暴击走另一条模板，便于一眼看出来） */
    public void playerDealtDamage(PlayerSession attacker, String monsterName, int damage, boolean critical) {
        if (attacker == null) return;
        send(attacker, critical ? KEY_PLAYER_CRIT : KEY_PLAYER_HIT, Map.of(
            "monster", monsterName,
            "damage", String.valueOf(damage)), true);
    }

    /** 玩家攻击未命中（命中判定没过） */
    public void playerMissed(PlayerSession attacker, String monsterName) {
        if (attacker == null) return;
        send(attacker, KEY_PLAYER_MISS, Map.of("monster", monsterName), true);
    }

    /** 玩家这一刀落空（够不着 / 目标已消失 —— 没有目标名字可写） */
    public void playerWhiffed(PlayerSession attacker) {
        if (attacker == null) return;
        send(attacker, KEY_PLAYER_WHIFF, Map.of(), true);
    }

    /** 怪物打玩家未命中（玩家回避成功） */
    public void monsterMissed(PlayerSession victim, String monsterName) {
        if (victim == null) return;
        send(victim, KEY_MONSTER_MISS, Map.of("monster", monsterName), true);
    }

    /** 玩家受击：怪物对你造成伤害 */
    public void playerHurt(PlayerSession victim, String monsterName, int damage) {
        if (victim == null) return;
        send(victim, KEY_PLAYER_HURT, Map.of(
            "monster", monsterName,
            "damage", String.valueOf(damage)), true);
    }

    /** 死亡重生：已被送回出生地并恢复半血（低频、需要被看见 → 系统频道，同升级） */
    public void playerRespawned(PlayerSession session) {
        if (session == null) return;
        send(session, KEY_PLAYER_RESPAWN, Map.of(), false);
    }

    /** 升级：获得自由属性点（低频、需要被看见 → 留在系统频道） */
    public void levelUp(PlayerSession session, int level, int points) {
        if (session == null) return;
        send(session, KEY_LEVEL_UP, Map.of(
            "level", String.valueOf(level),
            "points", String.valueOf(points)), false);
    }

    private void send(PlayerSession session, String key, Map<String, String> params, boolean battle) {
        if (session.getCharacterId() == null) return;
        MessageProto.S2C_SystemMessage.Builder body = MessageProto.S2C_SystemMessage.newBuilder()
            .setKey(key)
            .putAllParams(params)
            .setTimestamp(System.currentTimeMillis());
        if (battle) {
            body.setChannel(CommonProto.ChatChannel.CHAT_BATTLE);
        }
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setSystemMessage(body.build())
            .build();
        messageSender.sendToPlayer(session.getCharacterId(), msg);
    }
}
