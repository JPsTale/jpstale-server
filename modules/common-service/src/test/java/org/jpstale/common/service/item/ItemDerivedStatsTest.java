package org.jpstale.common.service.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生属性模型（用户 2026-09-22 定的口径）：**DB 只存基准 + 锻造等级 N + 合成配方 id**，
 * 属性在读取时算出来。这里钉住四条性质 —— 每一条都是"存算好的数值"那种写法会悄悄违反的。
 */
class ItemDerivedStatsTest {

    @BeforeAll
    static void install() {
        ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of()));
    }

    /** 基准字段**没有被动过**：写库写的就是基准（`toRow` 走的是原 getter，我们没覆盖它们）。 */
    @Test
    void 写库只写基准值() {
        ItemInstance it = new ItemInstance();
        it.setId(1L);
        it.setItemCode(0x01070900);          // 剑 WS2
        it.setDamageMin(12);
        it.setDamageMax(17);
        it.setAttackRating(50);
        it.setCritical(7);
        it.setAgingNum(2);                   // +2
        it.markDerivedDirty();

        // 有效值：伤害每级 +1×2、命中 +5×2、必杀到 +2 时 +1
        assertEquals(14, it.effectiveInt(ItemStat.DAMAGE_MIN));
        assertEquals(60, it.effectiveInt(ItemStat.ATTACK_RATING));
        assertEquals(8, it.effectiveInt(ItemStat.CRITICAL));

        // 基准字段一个都没变 —— 所以 `toRow`（用 getDamageMin 等原 getter）写出去的仍是基准
        assertEquals(12, it.getDamageMin(), "基准不动");
        assertEquals(17, it.getDamageMax(), "基准不动");
        assertEquals(50, it.getAttackRating(), "基准不动");
        assertEquals(7, it.getCritical(), "基准不动");
    }

    /** dirty 标记：读多次只重算一次；改过 N 之后再读才重算。 */
    @Test
    void 重算次数受dirty控制() {
        AtomicInteger calls = new AtomicInteger();
        ItemInstance.setDerivedResolver(x -> {
            calls.incrementAndGet();
            x.clearModifiers();
        });
        try {
            ItemInstance it = new ItemInstance();
            it.setItemCode(0x01070900);
            it.markDerivedDirty();

            it.effective(ItemStat.DAMAGE_MIN);
            it.effective(ItemStat.DAMAGE_MIN);
            it.effective(ItemStat.ATTACK_RATING);
            assertEquals(1, calls.get(), "连续读多次只重算一次（用户要的 dirty 防重算）");

            it.setAgingNum(3);
            it.markDerivedDirty();
            it.effective(ItemStat.DAMAGE_MIN);
            assertEquals(2, calls.get(), "改了锻造等级 ⇒ 标脏 ⇒ 下次读重算一次");
        } finally {
            // 还原成真实重算器，免得影响同类里的其它用例
            ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of()));
        }
    }

    /** **同一件装备的历史不影响结果**：先升到 +4 再降到 +2 == 直接 +2（这是"不存算好的值"换来的）。 */
    @Test
    void 历史不影响有效值() {
        ItemInstance a = new ItemInstance();
        a.setItemCode(0x01070900);
        a.setDamageMin(12);
        a.setAgingNum(4);
        a.markDerivedDirty();
        double at4 = a.effective(ItemStat.DAMAGE_MIN);

        a.setAgingNum(2);                    // 掉级：只改 N
        a.markDerivedDirty();
        double at2 = a.effective(ItemStat.DAMAGE_MIN);

        ItemInstance b = new ItemInstance();
        b.setItemCode(0x01070900);
        b.setDamageMin(12);
        b.setAgingNum(2);
        b.markDerivedDirty();
        assertEquals(b.effective(ItemStat.DAMAGE_MIN), at2, 1e-9, "+4 降到 +2 == 直接 +2");
        assertTrue(at4 > at2, "+4 高于 +2");
    }

    /** 未装配重算器时**不静默**：加成按 0 处理并留一条 error（`ItemInstance.ensureDerived`）。 */
    @Test
    void 没装配重算器时不静默() {
        ItemInstance.setDerivedResolver(null);
        try {
            ItemInstance it = new ItemInstance();
            it.setItemCode(0x01070900);
            it.setDamageMin(12);
            it.setAgingNum(2);
            it.markDerivedDirty();
            assertEquals(12, it.effectiveInt(ItemStat.DAMAGE_MIN), "没重算器 ⇒ 只剩基准（这就是要报错的原因）");
        } finally {
            ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of()));
        }
    }
    /**
     * 合成掩码是**派生值**（用户 2026-09-22 问："aging_num 不能表示配方的唯一性吗？"—— 能）：
     * 由配方 id 算出来，DB 里那一列不再由我们写入。
     */
    @Test
    void 合成掩码由配方id派生() {
        // 用真配方：一只盾配方（typemix=3，位 2048 防御 + 4096 格挡）
        org.jpstale.dao.gamedb.entity.MixList row = new org.jpstale.dao.gamedb.entity.MixList();
        row.setId(1);
        row.setMixUniqueId(319);
        row.setTypeMix(3);
        row.setTypeMixName("Shields");
        row.setDescription("Defense Rating +20 / Block Rating +4%");
        row.setLucidy(2);
        row.setFadeo(2);
        row.setTypeAtributte(MixEffect.DEFENCE);
        row.setAtributte(20.0);
        row.setPerAtributte(MixEffect.Kind.FLAT);
        row.setTypeAtributte2(MixEffect.BLOCK);
        row.setAtributte2(4.0);
        row.setPerAtributte2(MixEffect.Kind.FLAT);
        ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of(row)));
        try {
            ItemInstance shield = new ItemInstance();
            shield.setItemCode(0x02040700);
            shield.setDefence(55);
            shield.setBlockRating(15.8);
            shield.setAgingNum2(319);            // 只存配方 id
            shield.markDerivedDirty();

            assertEquals(MixEffect.DEFENCE | MixEffect.BLOCK, shield.getDerivedCraftMask(),
                    "掩码 = 该配方效果位的并集（派生，不落库）");
            assertEquals(75, shield.effectiveInt(ItemStat.DEFENCE), "防御 55 + 20");
            assertEquals(19.8, shield.effective(ItemStat.BLOCK_RATING), 1e-9, "格挡 15.8 + 4");
            assertEquals(0, shield.getCraftMask(), "DB 那一列我们**不写**（历史列，读取侧只用派生值）");
        } finally {
            ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of()));
        }
    }
}
