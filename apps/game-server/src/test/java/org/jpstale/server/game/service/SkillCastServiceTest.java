package org.jpstale.server.game.service;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
