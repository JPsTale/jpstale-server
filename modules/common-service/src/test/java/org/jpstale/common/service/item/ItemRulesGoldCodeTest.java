package org.jpstale.common.service.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 金币 idcode 的回归（源码 {@link ItemRules#CODE_GOLD} 的 javadoc 指名了这个类）。
 *
 * 背景：该常量曾写成 `FAMILY_GOLD | 0x00010000` = **0x05020000** —— 把子索引 `sinNN` 挪到了高 16 位，
 * 而它实际在 `0xNN00` 位 ⇒ 物品表里查不到这个 idcode ⇒ 金币掷中了也造不出那枚金币
 * ⇒ 地上永远不掉钱（用户 2026-09-14 实测"服务端根本就不掉钱"）。
 *
 * 这里把**正确值**与**曾经写错的那个值**都钉住，防止再滑回去。
 */
class ItemRulesGoldCodeTest {

    @Test
    void 金币码是高十六位家族加低十六位子索引() {
        // gamedb.itemlist 里 Gold 行：id=484, name=Gold, codeimg1=GG101, idcode=83951872
        assertEquals(83951872, ItemRules.CODE_GOLD);
        assertEquals(0x05010100, ItemRules.CODE_GOLD);
        assertEquals(ItemRules.FAMILY_GOLD | 0x00000100, ItemRules.CODE_GOLD);
    }

    @Test
    void 曾经写错的高位子索引不再是金币() {
        // 错法：子索引挪到高 16 位
        assertNotEquals(ItemRules.CODE_GOLD, 0x05020000);
        assertFalse(ItemRules.isGoldFamily(0x05020000), "0x05020000 是那个错误值，不能算金币家族");
    }

    @Test
    void 金币家族认高十六位() {
        assertTrue(ItemRules.isGoldFamily(ItemRules.CODE_GOLD));
        assertTrue(ItemRules.isGoldFamily(0x05010000), "家族本身（无子索引）也算");
        assertTrue(ItemRules.isGoldFamily(0x05010500), "同家族任意子索引都算");
        assertFalse(ItemRules.isGoldFamily(0), "0 不算任何家族");
        assertFalse(ItemRules.isGoldFamily(0x05020000));
        assertFalse(ItemRules.isGoldFamily(0x01010100));
    }

    /** 判据是"家族命中 **且** 带金额"，缺一不可（只看金额会误判其它家族，只看家族会误判钱=0 的同类外观物）。 */
    @Test
    void 金币掉落物要家族与金额同时成立() {
        assertTrue(ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 100));
        assertTrue(ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 1));
        assertFalse(ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 0), "家族对但没金额：是普通物品");
        assertFalse(ItemRules.isGoldDrop(0x01010100, 100), "有金额但家族不对");
        assertFalse(ItemRules.isGoldDrop(0, 100));
    }

    /** 金币码不能是"任务家族"（0x07010000）也不能被"能否丢/能否卖"挡住 —— 它走的是不入背包那条路。 */
    @Test
    void 金币码不属于任务家族() {
        assertFalse(ItemRules.isQuestFamily(ItemRules.CODE_GOLD));
        assertEquals(0x05010000, ItemRules.CODE_GOLD & 0xFFFF0000);
    }
}
