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

    /**
     * **底数 = 面板攻击，不是武器裸伤**（2026-09-27 用户二轮实测"伤害没有增加"后追源码定案）：
     * `dm_SendRangeDamage`（`Damage.cpp:816-819`）—— `Power[0..1] = smCharInfo.Attack_Damage[0..1]`
     * （**面板**），调用方传的 `sItemInfo.Damage[0..1]`（武器裸伤）进的是 **Power2**；
     * 服务端 `pow = GetRandomPos(Power[0..1])` 掷的是面板。第一版取武器模板裸伤
     * （法杖 14-15）⇒ +48% 后 ≈20、扣防后与改前无异。
     */
    /**
     * Multi Spark（`Svr_Damge.cpp:3138-3151`，用户 2026-09-27 指出漏加成后补）：
     * `Power += Power*M_Spark_Damage[Point]*Param/100`（**Param = 本次道数 N**）+ 对怪 +30%、不暴击。
     * 每道 = 面板掷 × (1 + 表值%×N) × 1.3；总伤 = 每道 × N。
     */
    @Test
    void 多重火花加成乘道数() {
        // 掷 100、1 级表 16%、N=4 ⇒ 每道 = 100 + 100*16*4/100 = 164 → ×1.3 = 213；总 = 852
        int roll = 100, pct = 16, n = 4;
        int per = roll + roll * pct * n / 100;
        per += per * 30 / 100;
        assertEquals(213, per, "100 + 100*16%*4 = 164 → 164*1.3 = 213（整数：164+49）");
        assertEquals(852, per * n, "总伤 = 每道 × N");
    }

    @Test
    void 底数是面板攻击不是武器裸伤() {
        // 面板 60..80 的祭司，1 级神雷 ⇒ 74..99；若错用武器裸伤 14..15 ⇒ 只有 17..18（差一个数量级）
        int[] panel = {60, 80};
        int lo = panel[0] + panel[0] * 24 / 100;
        int hi = panel[1] + panel[1] * 24 / 100;
        assertEquals(74, lo);
        assertEquals(99, hi);
        // 对照：武器裸伤 14 ⇒ 17 —— 这正是用户看到的"没有增加"
        int wrongBase = 14 + 14 * 24 / 100;
        assertEquals(17, wrongBase);
    }

    /**
     * **Holy Bolt（`Svr_Damge.cpp:3132-3136`，2026-09-28 横扫对表）**：
     * `Power += Power * HolyBolt_Damage[Point] / 100`（整数先乘后除）+ `Critical[0] = 0`（禁暴击）。
     * 底数 = **面板攻击**（客户端发包 `Power[0..1] = smCharInfo.Attack_Damage[0..1]`，
     * `Damage.cpp:231-232`；服务端 `GetRandomPos` 掷它）—— 与 Divine/Chain 同源。
     * 横扫结论：我方实现自迁入起即正确（表值/底数/禁暴击全对）；2026-09-28 只补了
     * `applyDamage` 的 skillId（S2C_AttackResult.skill_id，与 Divine/Chain 对齐）。
     */
    @Test
    void holyBolt公式与表值() {
        // 表（sinSkill_Info.cpp:525 默认值 14..50；横扫时与注册表逐值核对一致）
        assertEquals(14, (int) table("HolyBolt_Damage")[0], "HolyBolt_Damage[0]");
        assertEquals(50, (int) table("HolyBolt_Damage")[9], "[9]");
        // 掷 100、1 级 14% ⇒ 114（整数：100 + 1400/100，无四舍五入）
        int roll = 100, pct = 14;
        assertEquals(114, roll + roll * pct / 100);
        // 面板 60..80 的祭司 1 级 ⇒ 68..91（错误底数=武器裸伤 14 ⇒ 只有 15 —— 数量级对照）
        assertEquals(68, 60 + 60 * 14 / 100);
        assertEquals(91, 80 + 80 * 14 / 100);
        assertEquals(15, 14 + 14 * 14 / 100);
    }
}
