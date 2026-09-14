package org.jpstale.server.game.service;

/**
 * 服务端怪 AI 冻结参数(文档 §3.3 M5 / D3/D5/D9/D10)。
 *
 * 值来源见 docs/monster-ai-entity-design.md:
 * - ACTIVE_RADIUS = 1810(世界):此范围内才模拟怪(D10)
 * - NO_PLAYER_REMOVE_MS = 60s:连续无玩家临近回收(D10)
 * - SCAN_HEIGHT_DIFF = 140(世界):索敌高度差(原版 srAutoCharMain)
 * - ATTACK_HEIGHT_DIFF = 64(世界):近战高度差(原版 64*fONE→64)
 * - MIN_LOSE_RANGE = 1086(世界):丢目标下限
 * - WALK/RUN_STEP_TICK @20Hz = 64/128 world/s(D5;与原版 16Hz×4/8 同值)
 *
 * ⚠ 本类是**怪 AI 的行为参数**，与"AOI 可见距离"（{@link AOIManager}，现为 1000）是两件事，
 * 不要跟着 AOI 一起改（用户 2026-09-14 明确）：AOI 决定"发给哪些玩家看"，ACTIVE_RADIUS 决定
 * "服务端模拟哪些怪的 AI"。两者不等（1810 > 1000）是**有意的** —— 怪在你视野外仍在活动，
 * 走近时才出现在视野里，而不是走近了才开始动。
 */
public final class AIConstants {

    private AIConstants() {
    }

    /** 邻近模拟半径(world):此范围内才开始跑怪 AI+移动（独立于 AOI 可见距离，见类注释） */
    public static final float ACTIVE_RADIUS = 1810.0f;

    /** 连续无玩家临近超过该时长(ms)则回收(D10) */
    public static final long NO_PLAYER_REMOVE_MS = 60_000L;

    /** 索敌高度差容差(world,原版 |ΔY|<140) */
    public static final double SCAN_HEIGHT_DIFF = 140.0;

    /** 近战高度差容差(world,原版 |ΔY|<64*fONE → 64) */
    public static final double ATTACK_HEIGHT_DIFF = 64.0;

    /** 目标丢失下限(world):怪追到这个距离就放弃（同样不随 AOI 可见距离变） */
    public static final double MIN_LOSE_RANGE = 1086.0;

    /** 怪走路每 tick(20Hz)步长(world);walk = 4/16Hz 语义 → 64 world/s(D5) */
    public static final double WALK_STEP_TICK = 3.2;

    /** 怪跑步每 tick(20Hz)步长(world);run = 8/16Hz 语义 → 128 world/s(D5) */
    public static final double RUN_STEP_TICK = 6.4;

    /** 服务端主循环 tick 率(Hz) */
    public static final double TICK_RATE_HZ = 20.0;
}
