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
    /**
     * 当前**控制**该实体的会话。
     *
     * 曾经是 `final`：实体在第一次 `ensureEntity` 时就与那个会话永久绑定，于是刷新页面
     * （新连接先登录、旧连接的关闭事件随后才到）时，同一角色的新会话拿不到实体
     * （`ensureEntity` 见到已存在的实体就原样返回，没给新会话 `setEntity`），
     * 而 `MonsterAOI.syncSessions` / `MovementService.tickPlayers` 都从 `session.getEntity()` 取实体
     * → 新会话被**整体跳过**：收不到怪物 Appear/Move/Death，服务端也不再应用其移动上报
     * （用户 2026-09-14 实测：刷新后看不到附近的怪却一直掉血、怪死了客户端不知道）。
     *
     * 实体是**每角色一个**、会话是它的当前控制者 —— 所以这里允许接管（见 PlayerService.ensureEntity）。
     */
    private PlayerSession session;

    /** 移动状态机(IDLE/WALK/RUN/ATTACK/DEAD) */
    private volatile PlayerMoveState moveState = PlayerMoveState.IDLE;
    /** 已广播的动画状态值(0x0040 STAND/0x0050 WALK/0x0060 RUN),-1=未广播 */
    private volatile int lastSyncedAnimState = -1;
    /**
     * 「使用道具」广播序号（`S2C_PlayerMove.use_seq`），每次使用 +1。
     *
     * 用途：旁观者的去重键是 (anim_state, anim_index)，而站着连喝两瓶时两者完全相同
     * ⇒ 只靠它们去重会**吞掉第二次**。序号让每次使用都成为一条"新事件"。
     */
    private final java.util.concurrent.atomic.AtomicInteger useSeq = new java.util.concurrent.atomic.AtomicInteger();

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

    /**
     * 把实体交给另一个会话控制（刷新页面/断线重连时**唯一**的接管入口，见 PlayerService.ensureEntity）。
     * 只应由 PlayerService 在确认"旧会话已不代表这个世界状态"之后调用。
     */
    public void setSession(PlayerSession session) {
        this.session = session;
    }

    /** 显示名：数据宿主 Player 持有，实体仅转发（与 getHp/getLevel 同）。 */
    public String getName() {
        return player.getName();
    }

    public boolean isPlaying() {
        return session != null && session.isPlaying();
    }

    /**
     * 是否处于死亡态（躺下等待复活；对齐原版 CHRMOTION_STATE_DEAD）。
     *
     * ⚠ 真值在 `Player.dead`，**不是** `moveState`：`moveState` 是移动状态机的状态，
     * 迟到的移动包会把它从 DEAD 改回站立（`MovementService.applyClientMove` 限速校验用 `session` 而非实体），
     * 于是死亡态被悄悄抹掉。2026-09-16 实测症状链：死亡 → 迟到移动包覆盖 → 怪重新锁定 +
     * 回血服务给死人回血 → 反复广播死亡（`(51->0)`、`(48->0)`…）。
     */
    public boolean isDead() {
        return player.isDead();
    }

    /**
     * 能否被怪物/他人选为目标 —— **唯一判定**。
     *
     * 以前各处只判 `isPlaying()`，于是死亡躺下的玩家仍是有效目标：怪物会围着尸体继续打。
     * 原版行为是死亡即移出目标列表、重新搜寻其他目标或回归（用户 2026-09-13 明确）。
     * ⚠ 以后要给"目标有效性"加条件，**只改这里**。
     */
    public boolean isTargetable() {
        return isPlaying() && !isDead();
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

    /** 取下一个「使用道具」广播序号（从 1 起；0 保留给"非使用道具"） */
    public int nextUseSeq() {
        return useSeq.incrementAndGet();
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
