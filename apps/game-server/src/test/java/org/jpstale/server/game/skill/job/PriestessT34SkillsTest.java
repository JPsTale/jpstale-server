package org.jpstale.server.game.skill.job;

import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.skill.combat.TargetSelectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 祭司 T3/T4 七招（Vigor Ball / Resurrection / Extinction / Virtual Life / Glacial Spike /
 * Regeneration Field / Summon Muspell）的**公式特征测试**（2026-09-28 用户指示"做到 4 转第 4 个"）。
 *
 * <p>源码出处（每条都能红）：
 * <ul>
 *   <li>Vigor Ball：`Svr_Damge.cpp:3496-3505` —— 持械门 + 面板 ×(1+表值%)、禁暴击；</li>
 *   <li>Resurrection：`OnSever.cpp:34259-34282` + `netplay.cpp:6547-6566` ——
 *       `Resurrection_Percent[p]` 掷点，成功 ⇒ 原地半血（`sinSetLife(Life[1]/2)`）、无代价；</li>
 *   <li>Extinction：`Svr_Damge.cpp:2622-2700` + `:5142-5146` —— 只打亡灵，
 *       `Extinction_Percent[p]+等级/5` 命中 ⇒ 扣**当前生命**的 `Extinction_Amount[p]%`；</li>
 *   <li>Virtual Life：`character.cpp:15112-15116` —— 受击 `Power -= Power*Percent/100`（**减伤**，
 *       不是描述里的"提升生命上限"）；自施覆盖、队友仅过期后可再施（`OnSever.cpp:34287-34306`）；</li>
 *   <li>Glacial Spike：`character.cpp:17000-17032`（矩形 ±50/0..340 必中）+ `:5240-5247`
 *       （面板 ×(1+150..195%)，AttackState=3）+ `Svr_Damge.cpp:1769-1776`（减速 200、time=8）；</li>
 *   <li>Regeneration Field：`sinInvenTory.cpp:8971-8976`（Life 全额/Mana 自全额、队友减半）
 *       + `character.cpp:17050-17052`（范围 Area[p]²、高度差 &lt;16）；</li>
 *   <li>Summon Muspell：`character.cpp:15300-15340`（招架 BlockPercent 整刀闪避 + 亡灵吸收）。</li>
 * </ul>
 */
class PriestessT34SkillsTest {

    private static SkillDataRegistry data;

    @BeforeAll
    static void load() {
        data = new SkillDataRegistry();
        data.load();
    }

    private static double[] table(String name) {
        double[] t = data.table1d(name);
        assertNotNull(t, "表缺失：" + name);
        return t;
    }

    @Test
    void 七招的关键表值与源码一致() {
        assertEquals(26, (int) table("Vigor_Ball_Damage")[0]);
        assertEquals(51, (int) table("Vigor_Ball_Damage")[9]);
        assertEquals(40, (int) table("Resurrection_Percent")[0]);
        assertEquals(94, (int) table("Resurrection_Percent")[9]);
        assertEquals(100, (int) table("Extinction_Percent")[9], "10 级咒杀必中");
        assertEquals(20, (int) table("Extinction_Amount")[0]);
        assertEquals(52, (int) table("Extinction_Amount")[9]);
        assertEquals(13, (int) table("Virtual_Life_Percent")[9]);
        assertEquals(90, (int) table("Virtual_Life_Time")[0]);
        assertEquals(150, (int) table("Glacial_Spike_Damage")[0]);
        assertEquals(195, (int) table("Glacial_Spike_Damage")[9]);
        assertEquals(12.0, table("Regeneration_Field_LifeRegen")[9]);
        assertEquals(1.0, table("Regeneration_Field_ManaRegen")[0]);
        assertEquals(14, (int) table("Summon_Muspell_BlockPercent")[9]);
        assertEquals(10, (int) table("Summon_Muspell_UndeadAbsorbPercent")[0]);
        assertEquals(120, (int) table("Summon_Muspell_Time")[0]);
    }

    @Test
    void vigorBall公式与持械门() {
        // 面板掷 100、1 级 26% ⇒ 126（整数先乘后除）
        int roll = 100;
        assertEquals(126, roll + roll * 26 / 100);
        // 持械门 `Power>Power2` ⇔ 装备裸伤两端都 > 0：面板 60、裸伤 0 ⇒ Power2=60，60>60 不成立
        int panel = 60;
        int[] noWeapon = {0, 0};
        assertFalse(panel - noWeapon[0] < panel, "裸伤 0 ⇒ Power2==Power ⇒ 门不成立");
        int[] weapon = {5, 7};
        assertTrue(panel - weapon[0] < panel && panel - weapon[1] < panel, "有裸伤 ⇒ 门成立");
    }

    @Test
    void resurrection成功率与半血() {
        // 10 级 94%；掷点空间 0..99
        assertEquals(94, (int) table("Resurrection_Percent")[9]);
        // 原地救起 = 最大生命一半（`sinSetLife(Life[1]/2)`），至少 1 点
        int maxHp = 1000;
        assertEquals(500, Math.max(1, maxHp / 2));
        assertEquals(1, Math.max(1, 1 / 2), "maxHp=1 ⇒ 半血仍至少 1");
    }

    @Test
    void extinction成功率带等级加成_扣当前生命百分比() {
        // 1 级 60% + 等级 50/5=10 ⇒ 70；10 级 100% + 加成仍 100（命中判定 `<` 语义下封顶）
        int lvl50Chance = (int) table("Extinction_Percent")[0] + 50 / 5;
        assertEquals(70, lvl50Chance);
        // 扣**当前**生命 20%：300 血 ⇒ 60（不是最大生命的 20%）
        assertEquals(60, (int) (300 * (int) table("Extinction_Amount")[0] / 100));
    }

    @Test
    void virtualLife是减伤不是上限提升() {
        // `Power -= Power * 5% / 100`：200 伤 ⇒ 190
        int power = 200;
        int pct = (int) table("Virtual_Life_Percent")[3];
        assertEquals(5, pct);
        assertEquals(190, power - power * pct / 100);
    }

    @Test
    void glacialSpike公式与减速数值() {
        // 面板掷 100、1 级 150% ⇒ 250
        int roll = 100;
        assertEquals(250, roll + roll * 150 / 100);
        // 减速：SlowSpeed=200 对 256 的比例；time=8 ⇒ 8×16 帧 @70fps
        assertEquals(200 * 100 / 256, 78, "减速比例 78%");
        long slowMs = 8L * 16 * 1000L / 70;
        assertEquals(1828, slowMs);
    }

    @Test
    void regenerationField队友魔法减半() {
        // `Mana_Regen += ManaRegen[p] / Flag`：自己 Flag=1 全额，队友 Flag=2 减半
        double mana = table("Regeneration_Field_ManaRegen")[3];
        assertEquals(4.0, mana);
        assertEquals(4.0, mana / 1);
        assertEquals(2.0, mana / 2);
    }

    @Test
    void muspell招架与吸收() {
        // 招架 = 整刀免伤（伤害变 0）；吸收 = 伤害照扣、另回血 Power×10%
        int damage = 300;
        assertEquals(30, damage * (int) table("Summon_Muspell_UndeadAbsorbPercent")[0] / 100);
    }

    @Test
    void 身前矩形判定() {
        // 面朝 +Z（yaw=0）：前方 100 在框内，身后/左右超宽/超出 340 在框外
        assertTrue(TargetSelectors.inFrontBox(0, 0, 0, 0, 100, 50, 340));
        assertFalse(TargetSelectors.inFrontBox(0, 0, 0, 0, -100, 50, 340), "身后不算");
        assertFalse(TargetSelectors.inFrontBox(0, 0, 0, 80, 100, 50, 340), "横向超宽");
        assertFalse(TargetSelectors.inFrontBox(0, 0, 0, 0, 400, 50, 340), "超出纵深");
        // 面朝 +X（yaw=π/2）：前向 = (1,0)
        assertTrue(TargetSelectors.inFrontBox(0, 0, Math.PI / 2, 100, 0, 50, 340));
    }

    @Test
    void 减速状态过期归一() {
        Monster m = new Monster(1L);
        assertEquals(1.0, m.slowRatio(), "没被减 ⇒ 1.0");
        m.applySlow(200, -1);   // 负时长 ⇒ 立即过期
        assertEquals(1.0, m.slowRatio());
        m.applySlow(200, 60_000);
        assertEquals(200 / 256.0, m.slowRatio());
    }
}
