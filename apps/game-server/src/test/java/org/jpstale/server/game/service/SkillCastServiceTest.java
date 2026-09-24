package org.jpstale.server.game.service;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P4 技能施法的**前置门**特征测试（设计文档 §9 P4）。
 *
 * <p>钉住 cast() 在进入编排之前的两道门（它们在实体/会话解析之前，可无依赖直测；
 * 后面的 MP/AoE/伤害编排依赖 EntityRegistry/AOI 等运行时实体，由真机验收覆盖）：
 * <ol>
 *   <li>非本职业的 skillId ⇒ 拒绝（数字 id 的职业段校验，§4.6.1）；</li>
 *   <li>**未学**（props 无 point）⇒ 拒绝 —— 这是 P4 之前"当普攻即时结算"时代没有的门：
 *       没学的技能不该有任何效果（AGENTS #12：显式的没有，不伪造）。</li>
 * </ol>
 */
class SkillCastServiceTest {

    private final SkillCastService service = new SkillCastService();

    private static Player pikeman() {
        Player p = new Player(0);
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
        assertNull(service.cast(p, 0x010101, 0), "fighter 技能在 pikeman 身上 ⇒ 拒绝");
    }

    @Test
    void 未学的技能拒绝() {
        Player p = pikeman();
        assertNull(service.cast(p, SkillIds.PIKE_WIND.id(), 0), "props 无 point ⇒ 未学 ⇒ 拒绝");
    }

    @Test
    void 学过但已忘记时拒绝() {
        Player p = pikeman();
        p.setPropInt(SkillKeys.point(SkillIds.PIKE_WIND.id()), 0);   // 洗点后的形状（键存在、值 0）
        assertNull(service.cast(p, SkillIds.PIKE_WIND.id(), 0), "point=0 ⇒ 未学 ⇒ 拒绝");
    }
}
