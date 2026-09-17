package org.jpstale.server.game.item;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 金币道具 idcode 的回归（用户 2026-09-14 实测"服务端根本就不掉钱"）。
 *
 * 事实依据（`deploy/postgres/init/gamedb.sql` 的 `gamedb.itemlist` COPY 段）：
 * 金币行是 `id=484, idcode=83951872, name=Gold, codeimg1=GG101`，
 * 而 83951872 = **0x05010100** = `sinGG1 | sin01`（子索引 `sinNN` 在 `0xNN00` 位，
 * 同项目里的 `Steel Axe = 16843264 = 0x01010200`、`Skull Beads = 0x03030500`）。
 *
 * 曾经的写法 `FAMILY_GOLD | 0x00010000` = 0x05020000 把子索引挪到了高 16 位 ⇒ 物品表里查不到该 idcode
 * ⇒ `CombatService` 掷中金币后 `rollByIdCode(CODE_GOLD)` 返回 null ⇒ 只打一条 error，
 * 地上永远没有那枚金币。所以这里把"码的值"钉死：它必须等于物品表里那一行。
 */
public class ItemRulesGoldCodeTest {

    /** `gamedb.itemlist` 里 Gold 行的 idcode（十进制） */
    private static final int GOLD_ROW_IDCODE = 83951872;

    @Test
    public void goldItemCodeMatchesItemListRow() {
        assertEquals("CODE_GOLD 必须等于 itemlist 里 Gold 行的 idcode",
            GOLD_ROW_IDCODE, ItemRules.CODE_GOLD);
        assertEquals("按位写出来是 sinGG1|sin01（子索引在 0xNN00 位）",
            0x05010100, ItemRules.CODE_GOLD);
    }

    @Test
    public void goldCodeIsInGoldFamily() {
        // 拾取侧（GroundItemAOI / ItemNetworkHandler）按**家族**判定金币，所以这条也必须成立
        assertTrue(ItemRules.isGoldFamily(ItemRules.CODE_GOLD));
        assertEquals("家族 = 高 16 位", 0x05010000, ItemRules.CODE_GOLD & 0xFFFF0000);
    }

    @Test
    public void goldDropNeedsBothFamilyAndMoney() {
        assertTrue(ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 160));
        assertFalse("家族对但没金额 → 不是金币掉落物", ItemRules.isGoldDrop(ItemRules.CODE_GOLD, 0));
        assertFalse("有金额但家族不对 → 不是金币", ItemRules.isGoldDrop(0x01010100, 160));
    }
}
