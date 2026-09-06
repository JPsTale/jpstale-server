package org.jpstale.server.game.entity;

import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerMoveState;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 玩家实体(场上玩家身份的运行时实体,BaseEntity 子类)。
 *
 * 按 docs/monster-ai-entity-design.md(D11/M3):
 * - id: 全局运行时 ID(EntityIdSource),与业务字段 charId 解耦
 * - charId: 业务/持久标识(DB characterId),只做业务查询
 * - 坐标(x/y/z/mapId)与移动状态(moveState/lastSyncedAnimState)权威在本实体;
 *   PlayerSession 只保留网络/连接/登录态,不再持有游戏字段(读点经 session delegate 到实体)
 * - 属性/血量/等级数据宿主仍是 Player(DB/战斗读写方),实体仅转发访问
 *
 * 怪 AI/索敌/攻击以 PlayerEntity 为目标。
 */
public class PlayerEntity extends BaseEntity {

    private final long charId;
    private final Player player;
    private final PlayerSession session;

    /** 移动状态机(IDLE/WALK/RUN/ATTACK/DEAD) */
    private volatile PlayerMoveState moveState = PlayerMoveState.IDLE;
    /** 已广播的动画状态值(0x0040 STAND/0x0050 WALK/0x0060 RUN),-1=未广播 */
    private volatile int lastSyncedAnimState = -1;

    public PlayerEntity(long runtimeId, long charId, Player player, PlayerSession session) {
        super(runtimeId);
        this.charId = charId;
        this.player = player;
        this.session = session;
    }

    /** 业务字符 ID(DB characterId) */
    public long getCharId() {
        return charId;
    }

    public Player getPlayer() {
        return player;
    }

    public PlayerSession getSession() {
        return session;
    }

    public boolean isPlaying() {
        return session != null && session.isPlaying();
    }

    public PlayerMoveState getMoveState() {
        return moveState;
    }

    public void setMoveState(PlayerMoveState moveState) {
        this.moveState = moveState;
    }

    public int getLastSyncedAnimState() {
        return lastSyncedAnimState;
    }

    public void setLastSyncedAnimState(int lastSyncedAnimState) {
        this.lastSyncedAnimState = lastSyncedAnimState;
    }

    // ======== 血量/等级:转发到 Player(数据宿主) ========

    public int getHp() {
        return player != null ? player.getHp() : 0;
    }

    public void setHp(int hp) {
        if (player != null) player.setHp(hp);
    }

    public int getMaxHp() {
        return player != null ? player.getMaxHp() : 0;
    }

    public void setMaxHp(int maxHp) {
        if (player != null) player.setMaxHp(maxHp);
    }

    public int getMp() {
        return player != null ? player.getMp() : 0;
    }

    public void setMp(int mp) {
        if (player != null) player.setMp(mp);
    }

    public int getMaxMp() {
        return player != null ? player.getMaxMp() : 0;
    }

    public void setMaxMp(int maxMp) {
        if (player != null) player.setMaxMp(maxMp);
    }

    public int getLevel() {
        return player != null ? player.getLevel() : 0;
    }

    public void setLevel(int level) {
        if (player != null) player.setLevel(level);
    }
}
