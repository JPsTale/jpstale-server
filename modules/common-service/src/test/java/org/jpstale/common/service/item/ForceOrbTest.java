package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 力量石（Force Orb）特征测试：**数值表**（EU 三张表）、**档位判定**、
 * 以及最容易写错的**伤害应用顺序**（百分比以基础攻击力为基数、flat 最后加）。
 */
class ForceOrbTest {

    @Test
    void 档位判定与EU一致() {
        assertEquals(0, ForceOrb.tierIndexOf(0x03060100), "Lucidy Force = 第 1 档");
        assertEquals(13, ForceOrb.tierIndexOf(0x03060e00), "Oredo Force = 第 14 档");
        assertEquals(-1, ForceOrb.tierIndexOf(0x03060f00), "第 15 档越界（我们只有 14 颗）");
        assertEquals(-1, ForceOrb.tierIndexOf(0x02350100), "材料石（OS 族）不是力量石");
        assertEquals(-1, ForceOrb.tierIndexOf(null));
    }

    @Test
    void 三张表的取值() {
        assertEquals(2, ForceOrb.flatDamage(0), "第 1 档 flat = 2（EU ForceDamageTable）");
        assertEquals(180, ForceOrb.flatDamage(13), "第 14 档 flat = 180");
        assertEquals(0, ForceOrb.percentDamage(5), "前 6 档没有百分比");
        assertEquals(10, ForceOrb.percentDamage(6), "第 7 档起 +10%");
        assertEquals(10, ForceOrb.percentDamage(13));
        assertEquals(500, ForceOrb.durationSec(0), "第 1 档 500 秒");
        assertEquals(3600, ForceOrb.durationSec(13), "第 14 档 3600 秒");
        assertEquals(1_200_000L, ForceOrb.durationMs(6), "第 7 档 1200 秒 = 1_200_000 ms");
    }

    @Test
    void 越界档位夹紧而不抛() {
        assertEquals(2, ForceOrb.flatDamage(-5), "负数夹到第 1 档");
        assertEquals(180, ForceOrb.flatDamage(99), "越界夹到最后一档");
    }

    /** ★ **应用顺序**：百分比按**基础**攻击力算、flat 最后加（EU 注释逐字）。 */
    @Test
    void 攻击力加成按EU顺序应用() {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setJob(1);
        p.setLevel(50);
        p.setStrength(28 + 49 * 5);
        p.setSpirit(6);
        p.setTalent(21);
        p.setAgility(17);
        p.setHealth(27);

        PlayerStatCalculator calc = new PlayerStatCalculator();
        int[] bare = calc.attackPower(p);

        // 无 buff：与未加 buff 一致
        assertEquals(bare[0] + bare[1], calc.attackPower(p)[0] + calc.attackPower(p)[1], "无 buff 时不变");

        // 设成"激活中"：+10% 与 +20 flat（截止时间放到 1 分钟后）
        p.setForceOrbPercent(10);
        p.setForceOrbFlat(20);
        p.setForceOrbUntil(System.currentTimeMillis() + 60_000);
        int[] buffed = calc.attackPower(p);
        // 正确顺序：(min * 1.1) + 20；错误顺序：(min + 20) * 1.1 ⇒ 会多出 2 点，这条断言就是判据
        assertEquals(bare[0] * 110 / 100 + 20, buffed[0], "min：先百分比（按基础值）、再 flat");
        assertEquals(bare[1] * 110 / 100 + 20, buffed[1], "max：同上");
        assertNotEquals((bare[0] + 20) * 110 / 100, buffed[0], "不能把 flat 也算进百分比的基数");

        // 过期：立即失效（绝对时间戳）
        p.setForceOrbUntil(System.currentTimeMillis() - 1);
        assertEquals(bare[0], calc.attackPower(p)[0], "过期后回到基础值");
    }
}
