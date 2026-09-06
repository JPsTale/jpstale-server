package org.jpstale.server.game.entity;

import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 玩家实体(场上玩家身份的运行时实体,BaseEntity 子类)。
 *
 * 按 docs/monster-ai-entity-design.md:
 * - id: 全局运行时 ID(EntityIdSource),与业务字段 charId 解耦(D11)
 * - charId: 业务/持久标识(DB characterId),只做业务查询
 * - player/session: 引用现有游戏数据与网络会话;后续坐标/属性收敛到本实体后,
 *   PlayerSession 回归纯网络(不再持游戏字段)
 *
 * 怪 AI/索敌/攻击以 PlayerEntity 为目标,不直接依赖 PlayerSession 的游戏字段。
 */
public class PlayerEntity extends BaseEntity {

    private final long charId;
    private final Player player;
    private final PlayerSession session;

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

    /**
     * 过渡期坐标委托 PlayerSession(其坐标镜像仍是移动/AOI 权威写源);
     * 玩家坐标收敛完成后改为读本实体字段,D11。
     */
    @Override
    public double getX() {
        return session != null ? session.getX() : super.getX();
    }

    @Override
    public double getY() {
        return session != null ? session.getY() : super.getY();
    }

    @Override
    public double getZ() {
        return session != null ? session.getZ() : super.getZ();
    }

    @Override
    public int getMapId() {
        return session != null ? session.getCurrentMapId() : super.getMapId();
    }
}
