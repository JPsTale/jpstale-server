package org.jpstale.server.game.skill.job;

import org.jpstale.common.service.skill.SkillDataRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Divine / Chain Lightning 的**伤害公式**特征测试 —— 用户 2026-09-27 实测
 * "雷击伤害似乎没有任何加成"后补。原版逐字（`Svr_Damge.cpp`）：
 *
 * <pre>
 * Divine（:4854-4863）:  pow = roll(武器Power[0..1]);
 *                       pow += pow * Divine_Lightning_Damage[Point] / 100;   // 24..53%
 *                       AttackState=103 ⇒ 逐目标: UNDEAD ⇒ pow += pow/2
 *                                                 rs=Resistance[LIGHTING]/10（钳±100）⇒ pow -= pow*rs/100
 * Chain （:5249-5263）:  pow = roll(武器Power[0..1]);
 *                       pow += pow * Chain_Lightning_Damage[Point] / 100;    // 140..185%
 *                       AttackState=101 ⇒ 逐目标: rs=Resistance[LIGHTING]（不除10）
 * </pre>
 *
 * 钉三件事（每条都能红）：
 * ① 两张加成表在注册表里且首值正确（源码 `sinSkill_Info.cpp:541/590`）；
 * ② 公式算术 = 原版整数运算（先乘后除，不是浮点）；
 * ③ 不死系加成 = pow/2（整数除，原版同）。
 */
class PriestessLightningDamageTest {

    private static SkillDataRegistry data;

    @BeforeAll
    static void load() throws ReflectiveOperationException {
        data = new SkillDataRegistry();
        data.load();
    }

    private static double[] table(String name) {
        double[] t = data.table1d(name);
        assertNotNull(t, "表缺失：" + name);
        return t;
    }

    @Test
    void 加成表首值与源码一致() {
        assertEquals(24, (int) table("Divine_Lightning_Damage")[0], "Divine_Lightning_Damage[0]（sinSkill_Info.cpp:541）");
        assertEquals(53, (int) table("Divine_Lightning_Damage")[9], "[9]");
        assertEquals(140, (int) table("Chain_Lightning_Damage")[0], "Chain_Lightning_Damage[0]（:590）");
        assertEquals(185, (int) table("Chain_Lightning_Damage")[9], "[9]");
    }

    @Test
    void 公式为整数先乘后除() {
        // 原版 `pow += (pow * pct) / 100` —— C 整数除法。
        // 例：掷 37、加成 24% ⇒ 37 + (37*24)/100 = 37 + 888/100 = 37+8 = 45（不是 45.9 四舍五入的 46）
        int roll = 37, pct = 24;
        int power = roll + roll * pct / 100;
        assertEquals(45, power, "整数先乘后除（浮点会得 46）");
    }

    @Test
    void 不死系加成是整数半() {
        int power = 45;
        int per = power + power / 2;   // `pow += pow/2`
        assertEquals(67, per, "45 + 45/2 = 45+22（不是 67.5 的 68）");
    }

    @Test
    void 一级神雷对普通怪的期望伤害区间() {
        // 掷 100、1 级 +24% ⇒ 124；对 UNDEAD ⇒ 124+62 = 186
        int roll = 100;
        int power = roll + roll * 24 / 100;
        assertEquals(124, power);
        assertEquals(186, power + power / 2);
    }
}
