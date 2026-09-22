package org.jpstale.common.service.model;

import lombok.Data;
import org.jpstale.server.common.model.CharacterAppearance;

/**
 * 玩家游戏数据（**纯数据面**）。
 *
 * 依赖方向：`PlayerSession` → `PlayerEntity` → `Player`，即会话知道自己的玩家，
 * 玩家**不知道**自己的会话。要发消息给这个玩家时，用
 * `PlayerService.sessionOf(player)`（内部经实体表查）而不是让 Player 反向持有会话 ——
 * 反向引用还会在顶号/重连后指向**旧**会话，是同类 bug 的温床。
 */
@Data
public class Player {

    private final int slotIndex; // 角色槽位 (1-3)

    // 角色基础数据
    private String name;
    private int job;            // 职业
    private int level;
    private long exp;           // 累计经验（对齐 getExpForLevel 表，long 防溢出）
    private int gold;
    /** 头型（CharacterInfo.head，0-2）与转职阶级（rank，0-7）；外观计算用 */
    private int head;
    private int rank;

    // 属性
    private int strength;
    private int spirit;
    private int talent;
    private int agility;
    private int health;
    private int statePoint; // 未分配的属性点（每级 +5，对齐 exm ReformCharStatePoint）

    // 状态
    private int hp;
    private int maxHp;
    private int mp;
    private int maxMp;
    private int sp;
    private int maxSp;

    /** 物品持有状态（画布版权威容器：背包12×12/仓库9×9/装备13槽/备用武器） */
    private org.jpstale.common.service.item.PlayerItems items;

    /** 完整外观（头/防具/时装/武器），进场时由 AccountService 计算一次缓存；AOI Appear 下发用 */
    private CharacterAppearance appearance;

    // 元素抗性**不在这里**：它是装备属性的一种，与其它读数同源（`EquipSummary.res`
    // → `PlayerStatCalculator.resistances(p)`，含装备基础 + 职业特效 + `Lev_*` 的等级档）。
    // 曾经这里有 `int[] resistances` 字段、由两处各自求和写回，两处门槛还不一致 —— 已删（2026-09-22）。

    /**
     * **死亡态**（躺下等复活选择）—— 角色级状态，与 `hp` **解耦**。
     *
     * 为什么不复用 `hp <= 0`：回血服务会把 hp 抬起来（见下），`hp` 不是可靠的"死没死"判据。
     * 为什么不复用 `PlayerEntity.moveState == DEAD`：那是**移动状态机**的状态，会被
     * 迟到的移动包覆盖（`MovementService.applyClientMove` 不判死亡 → `setMoveState(fromMode(mode))`）
     * —— 2026-09-16 实测的症状链正是：死亡 → 迟到移动包把 moveState 改回站立 →
     * `isTargetable()` 变回 true（怪重新锁定）→ 回血服务的前置检查也失效 → **给死人回血** →
     * 怪再打死 → **反复广播死亡**（`(51->0)`、`(48->0)`…）。
     *
     * 死亡是**角色**的属性，不是"移动到哪一步"的属性 —— 所以它在这里，且 `isDead()` 只有一处实现。
     */
    private boolean dead;

    /**
     * 是否处于死亡态 —— **角色"死没死"的唯一判据**。
     *
     * 所有"活着才做的事"（回血、接受移动上报、被怪物锁为目标、攻击、吃药…）都要判它，
     * **不要**去读 `PlayerEntity.moveState == DEAD`（那会被迟到的移动包覆盖，见字段注释）。
     */
    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
    }

    /**
     * 上次使用**会进 EAT 的消耗品**（药水/以太核心）的时刻（毫秒）。
     *
     * 原版的吃药延迟 `sinUsePotionDelayFlag` 是**客户端**的（`sinInvenTory.cpp:791`，50 帧 ≈ 714ms）；
     * 服务端同样挡一道 —— 客户端那层改包就能绕，而"连按刷药"是直接改战斗节奏的事。
     * 冷却值见 `ItemNetworkHandler.USE_ITEM_COOLDOWN_MS`（与原版 50 帧同值）。
     */
    private long lastUseItemAt;

    /** 派生属性缓存（PlayerStatCalculator.stats 惰性填充；升级/属性分配/装备变化后 invalidate）。非持久化 */
    private transient volatile Object statsCache;

    /** 角色 DB id（= characterinfo.id）；不依赖 session，装载/测试可独立设置 */
    private Long characterId;

    /**
     * 本次游戏会话内属性分配历史（最近 5 次，对齐原版 TempStatePoint[5]；属性分配撤销用）
     */
    private static final int MAX_ALLOC_HISTORY = 5;
    private final java.util.ArrayDeque<String> statAllocHistory = new java.util.ArrayDeque<>();

    /** 记录一次分配（超限移除最旧）；成功分配后调用 */
    public void pushStatAlloc(String stat) {
        if (statAllocHistory.size() >= MAX_ALLOC_HISTORY) {
            statAllocHistory.removeFirst();
        }
        statAllocHistory.addLast(stat);
    }

    /** 弹出最近一次分配记录；空则返回 null */
    public String pollLastStatAlloc() {
        return statAllocHistory.pollLast();
    }

    /** 清空分配历史（下线/回选角时调用，对齐原版关面板清空语义） */
    public void clearStatAllocHistory() {
        statAllocHistory.clear();
    }

    public Player(int slotIndex) {
        this.slotIndex = slotIndex;
        this.items = new org.jpstale.common.service.item.PlayerItems();
    }

    /**
     * 角色 DB id（= `characterinfo.id`）。
     *
     * **不再回退到 session**（那正是被删掉的反向引用）：装载路径 `PlayerService.load` 一定会写入它，
     * 所以这里没值就是真的没装载 —— 报错比返回 0 好：id 参与物品归属与落库，静默的 0 会把存档写坏。
     */
    public long getId() {
        if (characterId == null) {
            throw new IllegalStateException(
                    "Player.characterId 未设置：Player 只能经 PlayerService.getOrCreate/load 装载");
        }
        return characterId;
    }

    public String getName() {
        return name;
    }
}
