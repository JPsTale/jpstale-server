package org.jpstale.common.service.stat;

import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P3 被动技能的特征测试（设计文档 §9 P3 验收 ①③；机制出处 = `docs/技能系统-pikeman.md`
 * §2.2 / §2.7 / §2.11 的逐字源码）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li>Ice Attribute 是**赋值**（覆盖装备冰抗），不是累加 —— 验收①"10 级 ⇒ res[ice]=45"；</li>
 *   <li>Weapon Defense Mastery：主手族匹配才加，**副手装盾 ⇒ 完全不生效**（源码 break，验收③）；</li>
 *   <li>Critical Mastery：主手**有武器**且族 = 枪（sinWP1）才加；</li>
 *   <li>没学的被动不影响任何属性（`PlayerStatCalculatorTest` 的两条金标准签名即此保证：
 *       它们不写任何 props，签名与 P3 之前逐字一致）。</li>
 * </ol>
 */
class SkillPassivesTest {

    private final PlayerStatCalculator calc = new PlayerStatCalculator();

    /** pikeman（job 4）初始属性对齐 INITIAL_STATS[4] = 26/9/20/19/25，1 级。 */
    private static Player pikeman() {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setJob(4);
        p.setLevel(1);
        p.setStrength(26);
        p.setSpirit(9);
        p.setTalent(20);
        p.setAgility(19);
        p.setHealth(25);
        p.setHp(1);
        p.setMp(1);
        p.setSp(1);
        return p;
    }

    /** 学某被动（props 写 `skill.<0xID>.point`，与运行时同一键生成器）。 */
    private static void learn(Player p, SkillIds id, int point) {
        p.setPropInt(SkillKeys.point(id.id()), point);
    }

    /** 重算属性（与运行时 `SkillPointService` 写点后的失效同口径）。 */
    private static Player recalc(Player p) {
        p.setStatsCache(null);
        return p;
    }

    /** 一件零属性占位装备（只用于"主手族 / 副手盾"判定，不混入其他读数）。 */
    private static ItemInstance probe(int familyHigh, int slot) {
        ItemList def = new ItemList();
        def.setId(9000 + familyHigh);
        def.setIdCode((familyHigh << 16) | 0x0100);
        def.setName("Probe " + Integer.toHexString(familyHigh));
        def.setClassItem(4);
        def.setWeight(10);
        def.setReqLevel(1);
        def.setReqStrengh(1);
        def.setReqSpirit(1);
        def.setReqTalent(1);
        def.setReqAgility(1);
        def.setReqHealth(1);
        ItemInstance it = new ItemInstance();
        it.setId((long) (9000 + familyHigh));
        it.setItemListId(def.getId());
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(slot);
        it.setTemplate(def);
        return it;
    }

    private static void equip(Player p, ItemInstance it) {
        p.getItems().byUidPut(it);
    }

    @Test
    void 冰属性十级覆盖装备冰抗为45() {
        Player p = pikeman();
        // 装备先给 10 点冰抗（EquipSummary 累加路径）
        ItemList def = new ItemList();
        def.setId(9500);
        def.setIdCode(0x02010100);
        def.setName("Ice Armor");
        def.setClassItem(8);
        def.setWeight(10);
        def.setReqLevel(1);
        def.setReqStrengh(1);
        def.setReqSpirit(1);
        def.setReqTalent(1);
        def.setReqAgility(1);
        def.setReqHealth(1);
        ItemInstance armor = probe(0x0201, ItemLocations.SLOT_ARMOR);
        armor.setTemplate(def);
        armor.setResIce(10);
        equip(p, armor);

        assertEquals(10, calc.stats(p).res[3], "前置：装备冰抗 10（未学被动）");

        learn(p, SkillIds.ICE_ATTRIBUTE, 10);
        assertEquals(45, calc.stats(recalc(p)).res[3],
                "PlusIce[9]=45，且是**赋值覆盖**（不是 10+45=55）——逐字 sinInvenTory1.cpp:7194");
    }

    @Test
    void 冰属性未学为1级时冰抗等于表首值() {
        Player p = pikeman();
        learn(p, SkillIds.ICE_ATTRIBUTE, 1);
        assertEquals(8, calc.stats(recalc(p)).res[3], "PlusIce[0]=8");
    }

    @Test
    void 武器防御精通主手斧加格挡_副手盾则不生效() {
        Player p = pikeman();
        equip(p, probe(0x0101, ItemLocations.SLOT_MAIN_HAND));   // 斧 WA1
        int baseBlock = calc.stats(p).block;

        learn(p, SkillIds.WEAPONE_DEFENCE_MASTERY, 10);
        assertEquals(baseBlock + 11, calc.stats(recalc(p)).block,
                "W_D_Mastery_Block[9]=11（sinSkill_Info.cpp:209）");

        // 副手装盾 ⇒ 源码 break，技能完全不生效（验收③）：同为主手斧 + 10 级被动，盾在 = 没学一样
        Player withShield = pikeman();
        equip(withShield, probe(0x0101, ItemLocations.SLOT_MAIN_HAND));
        equip(withShield, probe(0x0204, ItemLocations.SLOT_OFF_HAND));   // sinDS1 盾族
        learn(withShield, SkillIds.WEAPONE_DEFENCE_MASTERY, 10);
        Player withoutPassive = pikeman();
        equip(withoutPassive, probe(0x0101, ItemLocations.SLOT_MAIN_HAND));
        equip(withoutPassive, probe(0x0204, ItemLocations.SLOT_OFF_HAND));
        assertEquals(calc.stats(withoutPassive).block, calc.stats(recalc(withShield)).block,
                "盾在 ⇒ 有 10 级被动与没学完全一致（sinInvenTory1.cpp:7267-7280 的 break）");
    }

    @Test
    void 武器防御精通主手族不匹配不生效() {
        Player p = pikeman();
        equip(p, probe(0x0106, ItemLocations.SLOT_MAIN_HAND));   // 弓 WS1 —— 不在 {斧,锤,枪,剑}
        int baseBlock = calc.stats(p).block;
        learn(p, SkillIds.WEAPONE_DEFENCE_MASTERY, 10);
        assertEquals(baseBlock, calc.stats(recalc(p)).block,
                "弓族不在 UseWeaponCode（sinInvenTory1.cpp:7267-7280）");
    }

    @Test
    void 暴击精通主手枪加暴击_空手不生效() {
        Player p = pikeman();
        equip(p, probe(0x0105, ItemLocations.SLOT_MAIN_HAND));   // 镰枪 WP1
        int baseCrit = calc.stats(p).critical;

        learn(p, SkillIds.CRITICAL_MASTERY, 10);
        assertEquals(baseCrit + 17, calc.stats(recalc(p)).critical,
                "Critical_Mastery_Critical[9]=17（sinSkill_Info.cpp:232；加的是值不是百分比）");

        Player bare = pikeman();
        learn(bare, SkillIds.CRITICAL_MASTERY, 10);
        assertEquals(0, calc.stats(recalc(bare)).critical,
                "逐字 if (sInven[0].ItemIndex)：主手没武器 ⇒ 不生效");
    }
}
