package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ItemRollService} 的特征测试。
 *
 * 为什么重点保护它：web-server 的模拟器里曾有一份**已经分叉的**副本
 * （`SimulatorService.rollFromDef`：小数步长 0.01 vs 这里的 0.1、缺 REQ_MOD、缺一组 spec 字段）。
 * 那份副本已**整体删除**（连带 `POST /api/simulator/roll` 接口，接口待重新设计），
 * 于是本类成为全仓唯一实现 —— 正因如此它必须被钉死。
 *
 * 钉住手段：固定随机种子 → 结果完全确定 → 与**统一前实测的签名**逐字比较。
 * 任何字段被读错、区间被改、顺序被动，都会在这里看出 diff。
 */
class ItemRollServiceTest {

    /** 与实测签名配对的种子；换种子等于换期望值，不要随便改。 */
    private static final long SEED = 20260921L;

    /**
     * 重构前（阶段 0 之前）实测的掷点签名，种子 {@link #SEED}、模板 {@link ItemFixtures#sampleWeapon()}、
     * 期望职业掩码 0（走"从模板的可选职业里随机挑"分支）。
     *
     * ⚠ 这个种子下**职业特效判定未命中**（30% 命中率的那 70% 一侧）——
     * 即本条签名覆盖的是"基础掷点"路径，`applyJobEffects` 的字段全是 0。
     * 命中路径由 {@link #命中职业特效时的签名钉住} 单独钉住，两条合起来才是完整覆盖。
     */
    private static final String EXPECTED_SIGNATURE =
            "dur=32/42|resBio=11|resFire=14|resIce=3|resLight=4|resPoison=4|dmg=20-42|atkRating=176"
                    + "|absorb=3.7|defence=6|block=0.4|speed=1.3|manaRegen=1.5|lifeRegen=0.5|stmRegen=1.4"
                    + "|incLife=23.0|incMana=17.0|incStm=10.0|price=1000|jobMask=0"
                    + "|req=L20/S30/P10/T12/A15/H25"
                    + "|specAbsorb=0.0|specDefence=0|specSpeed=0.0|specPerManaRegen=0.0|specLevAtkRating=0"
                    + "|specLevDmgMax=0|specAtkSpeed=0|specCritical=0|specRange=0|specBlock=0.0"
                    + "|specPerLifeRegen=0.0|specPerStmRegen=0.0";

    /** 命中职业特效时的种子（30% 命中率那一侧；由阶段 0 用固定种子扫描确定）。 */
    private static final long HIT_SEED = 20260923L;

    /**
     * 命中职业特效时的签名（种子 {@link #HIT_SEED}、期望掩码 64 = Magician）。
     *
     * 这一条覆盖 {@link ItemRollService#applyJobEffects} 的全部产物，也是 web 模拟器
     * **目前缺失**的那一段：
     *  - 价格 1000 → **1200**（命中 +20%）
     *  - 需求被 REQ_MOD 百分比修正：基础 `S30/P10/T12/A15` → `S22/P13/T10/A12`
     *    （Magician 的 str[-30,-25] / spr[25,30] / tal[-15,-10] / agi[-20,-15]）
     *  - 一组 spec_* 掷点（模板 add_spec_* 区间）从 0 变成实际值
     *  - jobCodeMask 原样采用传入的 64
     */
    private static final String EXPECTED_HIT_SIGNATURE =
            "dur=37/47|resBio=12|resFire=16|resIce=2|resLight=3|resPoison=5|dmg=16-34|atkRating=173"
                    + "|absorb=2.1|defence=11|block=2.7|speed=2.1|manaRegen=2.0|lifeRegen=2.0|stmRegen=1.5"
                    + "|incLife=28.0|incMana=17.0|incStm=6.0|price=1200|jobMask=64"
                    + "|req=L20/S22/P13/T10/A12/H25"
                    + "|specAbsorb=2.6|specDefence=2|specSpeed=1.9|specPerManaRegen=2.0|specLevAtkRating=38"
                    + "|specLevDmgMax=5|specAtkSpeed=2|specCritical=3|specRange=30|specBlock=2.0"
                    + "|specPerLifeRegen=1.0|specPerStmRegen=0.5";

    @Test
    void 命中职业特效时的签名钉住() {
        ItemRollService svc = new ItemRollService(null);
        ItemRollService.setRngOverride(new Random(HIT_SEED));

        ItemInstance it = svc.roll(ItemFixtures.sampleWeapon(), 64);

        assertEquals(1200, it.getPrice(), "该种子必须命中职业特效，否则本期望值失效");
        assertEquals(64, it.getJobCodeMask());
        assertEquals(EXPECTED_HIT_SIGNATURE, ItemFixtures.rollSignature(it));
    }

    @AfterEach
    void 复位随机源() {
        ItemRollService.setRngOverride(null);
    }

    @Test
    void 固定种子下的掷点签名钉住() {
        ItemRollService svc = new ItemRollService(null);
        ItemRollService.setRngOverride(new Random(SEED));

        ItemInstance it = svc.roll(ItemFixtures.sampleWeapon(), 0);

        assertNotNull(it);
        assertEquals(EXPECTED_SIGNATURE, ItemFixtures.rollSignature(it));
    }

    /** 同种子重放必须完全一致（若某处偷偷用了 ThreadLocalRandom，这条会飘）。 */
    @Test
    void 同种子两次掷点完全一致() {
        ItemRollService set1 = new ItemRollService(null);
        ItemRollService.setRngOverride(new Random(SEED));
        String a = ItemFixtures.rollSignature(set1.roll(ItemFixtures.sampleWeapon(), 0));

        ItemRollService.setRngOverride(new Random(SEED));
        String b = ItemFixtures.rollSignature(new ItemRollService(null).roll(ItemFixtures.sampleWeapon(), 0));

        assertEquals(a, b);
    }

    /** 不给覆盖时不能崩（正常路径仍是 ThreadLocalRandom）。 */
    @Test
    void 无覆盖时走真实随机且结果落在区间内() {
        ItemRollService svc = new ItemRollService(null);
        ItemList def = ItemFixtures.sampleWeapon();

        ItemInstance it = svc.roll(def, 0);

        assertTrue(it.getDurability() >= it.getDurabilityMax() / 2, "当前耐久掷点区间是 [max/2, max]");
        assertTrue(it.getDurability() <= it.getDurabilityMax());
        assertTrue(it.getDurabilityMax() >= 20 && it.getDurabilityMax() <= 60);
        assertTrue(it.getDamageMin() >= 10 && it.getDamageMin() <= 20, "小攻击区间 = [AtkPow1Min, AtkPow2Min]");
        assertTrue(it.getDamageMax() >= 30 && it.getDamageMax() <= 50, "大攻击区间 = [AtkPow1Max, AtkPow2Max]");
        assertTrue(it.getResFire() >= 10 && it.getResFire() <= 30);
        assertTrue(it.getAttackRating() >= 100 && it.getAttackRating() <= 200);
        assertTrue(it.getDefence() >= 5 && it.getDefence() <= 15);
    }

    /** 模板直通值必须照抄，不参与掷点。 */
    @Test
    void 模板单值字段直通() {
        ItemRollService svc = new ItemRollService(null);
        ItemRollService.setRngOverride(new Random(SEED));

        ItemInstance it = svc.roll(ItemFixtures.sampleWeapon(), 0);

        assertEquals(7, it.getCritical());
        assertEquals(0, it.getShootingRange());
        assertEquals(3, it.getAttackSpeed());
        assertEquals(1001, it.getItemListId());
        assertEquals(0x01010100, it.getItemCode());
        assertEquals(20, it.getReqLevel(), "需求基础值来自模板");
    }

    /** 传了职业掩码时：命中职业特效则原样采用该掩码，且价格 +20%。 */
    @Test
    void 指定职业掩码时走固定职业分支() {
        ItemRollService svc = new ItemRollService(null);
        ItemList def = ItemFixtures.sampleWeapon();

        // 先找出"本次种子下会命中职业特效"的那次调用：30% 命中率，靠循环找不稳定，
        // 改为直接断言"命中时 price 只可能是 1000 或 1200 二者之一"。
        for (int i = 0; i < 40; i++) {
            ItemRollService.setRngOverride(new Random(SEED + i));
            ItemInstance it = svc.roll(def, 64 /* Magician 位 */);
            int price = it.getPrice();
            assertTrue(price == 1000 || price == 1200, "价格只能是基础价或基础价+20%，实际 " + price);
            if (price == 1200) {
                assertEquals(64, it.getJobCodeMask(), "指定掩码时必须原样采用");
                assertTrue(it.getSpecAbsorb() >= 1.0 && it.getSpecAbsorb() <= 3.0);
                assertTrue(it.getSpecDefence() >= 2 && it.getSpecDefence() <= 8);
                assertTrue(it.getSpecLevDamageMax() >= 5 && it.getSpecLevDamageMax() <= 15);
                assertEquals(2, it.getSpecAttackSpeed(), "模板单值特效直通");
                assertEquals(3, it.getSpecCritical());
                assertEquals(30, it.getSpecShootingRange());
                assertEquals(2.0, it.getSpecBlockRating());
                assertEquals(1.0, it.getSpecPerLifeRegen());
                assertEquals(0.5, it.getSpecPerStaminaRegen());
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("40 个种子都没命中职业特效（30% 命中率下概率极低，判据可能坏了）");
    }
}
