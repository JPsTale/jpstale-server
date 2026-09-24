package org.jpstale.server.game.service;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 技能施法的**前置门**特征测试（设计文档 §9 P4 / D7）。
 *
 * <p>链路是**两次上报**（用户 2026-09-24 指正后按 D7 重做）：`C2S_UseSkill`（意图：校验+扣MP+起手广播）
 * → 客户端动画**事件帧**回报 `C2S_SkillHit`（逐段结算）。本测试钉住 `begin` 的三道门
 * （它们在实体/会话解析之前，可无依赖直测；MP 扣除、起手广播与事件帧结算依赖运行时实体，由真机验收覆盖）：
 * <ol>
 *   <li>非本职业的 skillId ⇒ REJECTED（数字 id 的职业段校验，§4.6.1）；</li>
 *   <li>**未学**（props 无 point / point=0）⇒ REJECTED —— 没学的技能不该有任何效果；</li>
 *   <li>**未迁入本服务**的技能 ⇒ NOT_MIGRATED（调用方走旧路，本服务不碰）。</li>
 * </ol>
 */
class SkillCastServiceTest {

    private final SkillCastService service = new SkillCastService();

    private static Player pikeman() {
        Player p = new Player(0);
        p.setCharacterId(1L);   // getId() 有装载校验（PlayerService 之外造的 Player 必须显式设）
        p.setName("测试枪兵");
        p.setJob(4);   // pikeman
        p.setLevel(20);
        p.setMp(100);
        p.setSp(100);
        p.setHp(100);
        return p;
    }

    @Test
    void 非本职业技能拒绝() {
        Player p = pikeman();
        // fighter 的 Raving（0x010101，§4.6.1 的编码示例）—— job 段 = 1 ≠ 4
        assertEquals(SkillCastService.BeginResult.REJECTED,
                service.begin(p, 0x010101, 0, 0, ""), "fighter 技能在 pikeman 身上 ⇒ 拒绝");
    }

    @Test
    void 未学的技能拒绝() {
        Player p = pikeman();
        assertEquals(SkillCastService.BeginResult.REJECTED,
                service.begin(p, SkillIds.PIKE_WIND.id(), 0, 0, ""), "props 无 point ⇒ 未学 ⇒ 拒绝");
    }

    @Test
    void 洗点后的形状也拒绝() {
        Player p = pikeman();
        p.setPropInt(SkillKeys.point(SkillIds.PIKE_WIND.id()), 0);   // 洗点后：键存在、值 0
        assertEquals(SkillCastService.BeginResult.REJECTED,
                service.begin(p, SkillIds.PIKE_WIND.id(), 0, 0, ""), "point=0 ⇒ 未学 ⇒ 拒绝");
    }

    @Test
    void 未迁入的技能交回旧路() {
        Player p = pikeman();
        // Ground Pike（0x040201）本批尚未迁入 ⇒ NOT_MIGRATED（调用方保持"当普攻即时结算"）
        p.setPropInt(SkillKeys.point(SkillIds.GROUND_PIKE.id()), 5);
        assertEquals(SkillCastService.BeginResult.NOT_MIGRATED,
                service.begin(p, SkillIds.GROUND_PIKE.id(), 0, 0, ""));
    }

    @Test
    void 无起手的事件帧回报被忽略() {
        Player p = pikeman();
        // 没有 begin 过就回报事件帧 ⇒ 忽略（不结算）——防改包直接刷伤害
        assertNull(service.hit(p, SkillIds.PIKE_WIND.id(), 0, 0));
    }

    /* ────────────── CD（2026-09-25 用户报"客户端 CD 没到也能放、服务端还照算伤害"）──────────────
       `begin` 里那两行（`cooldownLeftMs(...) > 0 ⇒ REJECTED_COOLDOWN`）依赖运行时实体
       （会话/实体解析在它之前），由真机验收覆盖；这里直测**计时语义**（同一份状态的两个入口）。 */

    @Test
    void CD计时按起手时刻与时长判定() {
        long t0 = 1_000_000L;
        service.recordCast(7L, 0x040101, t0);

        assertEquals(17500L, service.cooldownLeftMs(7L, 0x040101, 17500L, t0), "刚起手：整个 CD 都在");
        assertEquals(16500L, service.cooldownLeftMs(7L, 0x040101, 17500L, t0 + 1000), "过 1 秒：还剩 16.5 秒");
        assertEquals(0L, service.cooldownLeftMs(7L, 0x040101, 17500L, t0 + 17500), "到点：可以放");
        assertEquals(0L, service.cooldownLeftMs(7L, 0x040101, 17500L, t0 + 20000), "过点更可以放");
    }

    @Test
    void CD按玩家与技能各算各的() {
        long t0 = 1_000_000L;
        service.recordCast(7L, 0x040101, t0);

        assertEquals(0L, service.cooldownLeftMs(8L, 0x040101, 17500L, t0), "别的玩家不受影响");
        assertEquals(0L, service.cooldownLeftMs(7L, 0x040104, 17500L, t0), "别的技能不受影响");
        assertEquals(0L, service.cooldownLeftMs(9L, 0x040101, 17500L, t0), "没起过手就可以放");
    }

    @Test
    void 离线清掉CD计时() {
        long t0 = 1_000_000L;
        service.recordCast(7L, 0x040101, t0);
        assertTrue(service.cooldownLeftMs(7L, 0x040101, 17500L, t0) > 0);

        service.clearPlayer(7L);
        assertEquals(0L, service.cooldownLeftMs(7L, 0x040101, 17500L, t0), "离线再回来 CD 不延续（原版也不存档）");
    }

    /* ── CD 的两个“算不出来”（都不是编一个值，而是显式未知）── */

    /** 注入真技能注册表（本模块其它测试同款反射注入）。 */
    private static SkillCastService withRealData() {
        SkillCastService svc = new SkillCastService();
        try {
            java.lang.reflect.Field f = SkillCastService.class.getDeclaredField("skillData");
            f.setAccessible(true);
            org.jpstale.common.service.skill.SkillDataRegistry data = new org.jpstale.common.service.skill.SkillDataRegistry();
            data.load();
            f.set(svc, data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 skillData 失败", e);
        }
        return svc;
    }

    /**
     * **被动没有 CD**（用户 2026-09-25：“被动技能也有熟练度和CD？”）：被动不可施放 ⇒ 无所谓冷却，
     * 原版也不给它画计量条（`sinSkill.cpp:823` 的 `USECODE != SIN_SKILL_USE_NOT`）。
     * 我们让 `cooldownMsOf` 返回 **null**（= 没有这个数），而不是算出一个 0.5 秒的假 CD。
     */
    @Test
    void 被动技能没有CD() {
        SkillCastService svc = withRealData();
        Player p = pikeman();
        int passive = SkillIds.ICE_ATTRIBUTE.id();
        int active = SkillIds.PIKE_WIND.id();
        p.setPropInt(SkillKeys.point(passive), 1);
        p.setPropInt(SkillKeys.point(active), 1);

        assertEquals(null, svc.cooldownMsOf(p, passive), "被动 ⇒ 没有 CD 这个数");
        assertTrue(svc.cooldownMsOf(p, active) != null && svc.cooldownMsOf(p, active) > 0,
                "可施放的技能照常算出 CD");
    }
}
