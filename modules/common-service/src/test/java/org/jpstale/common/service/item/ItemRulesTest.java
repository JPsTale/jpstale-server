package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemRules} 的判定矩阵特征测试 —— 钉住**原版表**在当前实现下的取值。
 *
 * 这些规则会被 web-server 复用（模拟器"穿不上"提示必须与游戏内同源），
 * 也被装备槽/背包的红底渲染依赖，所以是本轮重构的重点保护对象。
 */
class ItemRulesTest {

    private static final int MASK2_ARMOR_PHYS = 0x02010000;   // DA1 铠甲（物理系）
    private static final int MASK2_ARMOR_MAGIC = 0x02050000;  // DA2 法袍（法系）
    private static final int MASK2_ORB = 0x03030000;          // OM1 法球
    private static final int MASK2_DAGGER = 0x010A0000;       // WD1 匕首
    private static final int MASK2_TOTEM = 0x01090000;        // WN1 图腾
    private static final int MASK2_KNUCKLE = 0x010B0000;      // WV1 拳套

    private static final int JOB_FIGHTER = 1;
    private static final int JOB_ARCHER = 3;
    private static final int JOB_ATALANTA = 5;
    private static final int JOB_MAGICIAN = 7;
    private static final int JOB_PRIESTESS = 8;
    private static final int JOB_ASSASSIN = 9;
    private static final int JOB_SHAMAN = 10;
    private static final int JOB_MARTIAL = 11;

    private static Player player(int level, int str, int spi, int tal, int agi, int hea) {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setLevel(level);
        p.setStrength(str);
        p.setSpirit(spi);
        p.setTalent(tal);
        p.setAgility(agi);
        p.setHealth(hea);
        return p;
    }

    private static ItemInstance req(int level, int str, int spi, int tal, int agi, int hea) {
        ItemInstance it = new ItemInstance();
        it.setReqLevel(level);
        it.setReqStrength(str);
        it.setReqSpirit(spi);
        it.setReqTalent(tal);
        it.setReqAgility(agi);
        it.setReqHealth(hea);
        return it;
    }

    // ===================== 需求门（等级 + 5 属性） =====================

    @Test
    void 需求门逐项相等即通过_任一项差一点即拒绝() {
        ItemInstance need = req(20, 30, 10, 12, 15, 25);

        assertTrue(ItemRules.meetsRequirements(player(20, 30, 10, 12, 15, 25), need), "全部恰好相等应通过");
        assertTrue(ItemRules.meetsRequirements(player(21, 31, 11, 13, 16, 26), need), "全部超过应通过");

        assertFalse(ItemRules.meetsRequirements(player(19, 30, 10, 12, 15, 25), need), "等级差 1");
        assertFalse(ItemRules.meetsRequirements(player(20, 29, 10, 12, 15, 25), need), "力量差 1");
        assertFalse(ItemRules.meetsRequirements(player(20, 30, 9, 12, 15, 25), need), "精神差 1");
        assertFalse(ItemRules.meetsRequirements(player(20, 30, 10, 11, 15, 25), need), "才能差 1");
        assertFalse(ItemRules.meetsRequirements(player(20, 30, 10, 12, 14, 25), need), "敏捷差 1");
        assertFalse(ItemRules.meetsRequirements(player(20, 30, 10, 12, 15, 24), need), "体力差 1");
    }

    @Test
    void 需求门对空实例判否() {
        assertFalse(ItemRules.meetsRequirements(player(99, 99, 99, 99, 99, 99), null));
    }

    // ===================== 职业门：甲/法袍/法球 =====================

    @Test
    void 法系穿不了铠甲_物理系穿不了法袍与法球() {
        assertFalse(ItemRules.canUse(JOB_MAGICIAN, MASK2_ARMOR_PHYS | 0x0100), "法系禁 DA1");
        assertFalse(ItemRules.canUse(JOB_PRIESTESS, MASK2_ARMOR_PHYS | 0x0100));
        assertFalse(ItemRules.canUse(JOB_SHAMAN, MASK2_ARMOR_PHYS | 0x0100));
        assertTrue(ItemRules.canUse(JOB_FIGHTER, MASK2_ARMOR_PHYS | 0x0100));

        assertFalse(ItemRules.canUse(JOB_FIGHTER, MASK2_ARMOR_MAGIC | 0x0100), "物理系禁 DA2");
        assertTrue(ItemRules.canUse(JOB_MAGICIAN, MASK2_ARMOR_MAGIC | 0x0100));

        assertFalse(ItemRules.canUse(JOB_FIGHTER, MASK2_ORB | 0x0100), "物理系禁法球");
        assertFalse(ItemRules.canUse(JOB_ASSASSIN, MASK2_ORB | 0x0100));
        assertTrue(ItemRules.canUse(JOB_MAGICIAN, MASK2_ORB | 0x0100));
        assertTrue(ItemRules.canUse(JOB_PRIESTESS, MASK2_ORB | 0x0100));
    }

    /**
     * 甲按**男/女外观码**分派：源码第一分支拒绝女性职业 ⇒ 那 10 个码是男款。
     * 用非魔法职业（1 男 / 3 女）隔离出性别这一条，避免与 ② 的魔法职业规则混在一起。
     */
    @Test
    void 男女外观码只能穿自己那一款() {
        int maleVariant = MASK2_ARMOR_PHYS | 0x2F00;
        int femaleVariant = MASK2_ARMOR_PHYS | 0x3100;

        assertFalse(ItemRules.canUse(JOB_ARCHER, maleVariant), "女性职业穿不了男款");
        assertFalse(ItemRules.canUse(JOB_ATALANTA, maleVariant));
        assertFalse(ItemRules.canUse(JOB_PRIESTESS, maleVariant));
        assertFalse(ItemRules.canUse(JOB_ASSASSIN, maleVariant));
        assertFalse(ItemRules.canUse(JOB_MARTIAL, maleVariant));
        assertTrue(ItemRules.canUse(JOB_FIGHTER, maleVariant), "男性职业可以穿男款");

        assertFalse(ItemRules.canUse(JOB_FIGHTER, femaleVariant), "男性职业穿不了女款");
        assertTrue(ItemRules.canUse(JOB_ARCHER, femaleVariant), "女性职业可以穿女款");
    }

    /** 全部 10 个男款码对女性职业都应拒绝，10 个女款码对男性职业都应拒绝（防止只改了一半）。 */
    @Test
    void 男女外观码十个一组整体生效() {
        int[] maleVariants = {0x2F00, 0x3000, 0x3300, 0x3400, 0x3700, 0x3800, 0x3B00, 0x3C00, 0x4300, 0x4600};
        int[] femaleVariants = {0x3100, 0x3200, 0x3500, 0x3600, 0x3900, 0x3A00, 0x3D00, 0x3E00, 0x4400, 0x4700};
        for (int m3 : maleVariants) {
            assertFalse(ItemRules.canUse(JOB_ARCHER, MASK2_ARMOR_PHYS | m3), "男款码 0x" + Integer.toHexString(m3));
            assertTrue(ItemRules.canUse(JOB_FIGHTER, MASK2_ARMOR_PHYS | m3), "男款码 0x" + Integer.toHexString(m3));
        }
        for (int m3 : femaleVariants) {
            assertTrue(ItemRules.canUse(JOB_ARCHER, MASK2_ARMOR_PHYS | m3), "女款码 0x" + Integer.toHexString(m3));
            assertFalse(ItemRules.canUse(JOB_FIGHTER, MASK2_ARMOR_PHYS | m3), "女款码 0x" + Integer.toHexString(m3));
        }
    }

    /**
     * 性别分派对 DA1/DA2 **两个家族都生效**（源码里两个分支各列了 10 个码）。
     *
     * ⚠ 两条规则是**叠加**的，判"能不能穿"时顺序无关但结论要看交集：
     * 法袍(DA2) 本身只许法系（7 法师 / 8 祭司 / 10 萨满）穿，
     * 所以"男性职业能穿 DA2 的男款码"这句话只对**法系里的男性职业**（7 法师）成立 ——
     * 男性物理职业（1 武士）连 DA2 都进不去，轮不到性别规则。
     */
    @Test
    void 性别分派对法袍同样生效且与法系门叠加() {
        int maleVariantDa2 = MASK2_ARMOR_MAGIC | 0x2F00;
        int femaleVariantDa2 = MASK2_ARMOR_MAGIC | 0x3100;

        assertTrue(ItemRules.canUse(JOB_MAGICIAN, maleVariantDa2), "法师是男性法系：男款法袍可用");
        assertFalse(ItemRules.canUse(JOB_PRIESTESS, maleVariantDa2), "祭司是女性法系：男款法袍不可用");

        assertTrue(ItemRules.canUse(JOB_PRIESTESS, femaleVariantDa2), "祭司是女性法系：女款法袍可用");
        assertFalse(ItemRules.canUse(JOB_MAGICIAN, femaleVariantDa2), "法师是男性法系：女款法袍不可用");

        assertFalse(ItemRules.canUse(JOB_FIGHTER, maleVariantDa2), "物理职业被法系门挡在前面");
        assertFalse(ItemRules.canUse(JOB_FIGHTER, femaleVariantDa2));
    }

    // ===================== 职业门：全族一致的 4 个族 =====================

    @Test
    void 匕首图腾拳套是专属族() {
        assertTrue(ItemRules.canUse(JOB_ASSASSIN, MASK2_DAGGER | 0x0100));
        assertFalse(ItemRules.canUse(JOB_FIGHTER, MASK2_DAGGER | 0x0100));
        assertFalse(ItemRules.canUse(JOB_MAGICIAN, MASK2_DAGGER | 0x0100));

        assertTrue(ItemRules.canUse(JOB_SHAMAN, MASK2_TOTEM | 0x0100));
        assertFalse(ItemRules.canUse(JOB_FIGHTER, MASK2_TOTEM | 0x0100));
        assertFalse(ItemRules.canUse(JOB_MAGICIAN, MASK2_TOTEM | 0x0100));

        assertTrue(ItemRules.canUse(JOB_MARTIAL, MASK2_KNUCKLE | 0x0100));
        assertFalse(ItemRules.canUse(JOB_FIGHTER, MASK2_KNUCKLE | 0x0100));
        assertFalse(ItemRules.canUse(JOB_MAGICIAN, MASK2_KNUCKLE | 0x0100));
    }

    @Test
    void 零码与未收族一律放行() {
        assertTrue(ItemRules.canUse(JOB_FIGHTER, 0), "idCode 0 不是物品");
        assertTrue(ItemRules.canUse(JOB_FIGHTER, 0x01010100), "剑族不在 4 条族锁里");
        assertTrue(ItemRules.canUse(JOB_MAGICIAN, 0x01010100));
    }

    // ===================== 丢/卖/金币家族 =====================

    @Test
    void 任务家族一律不许丢也不许卖() {
        assertFalse(ItemRules.isDroppable(0x07010007), "原版 NotDrow_Item_CODE 明列");
        assertFalse(ItemRules.isDroppable(0x07010008), "原版 NotDrow_Item_CODE 明列");
        assertFalse(ItemRules.isDroppable(0x07010009), "任务家族其余成员按近似规则也不许丢");
        assertFalse(ItemRules.isSellable(0x07010007));
        assertFalse(ItemRules.isSellable(0x07010008));
        assertFalse(ItemRules.isSellable(0x07010009));
    }

    @Test
    void 普通物品可丢可卖_零码放行() {
        assertTrue(ItemRules.isDroppable(0x01010100));
        assertTrue(ItemRules.isSellable(0x01010100));
        assertTrue(ItemRules.isDroppable(0), "无 idcode 不构成禁丢理由");
        assertTrue(ItemRules.isSellable(0));
    }

    @Test
    void 任务家族判据只看高十六位() {
        assertTrue(ItemRules.isQuestFamily(0x07010000));
        assertTrue(ItemRules.isQuestFamily(0x0701FFFF));
        assertFalse(ItemRules.isQuestFamily(0x07020000));
        assertFalse(ItemRules.isQuestFamily(0));
    }
}
