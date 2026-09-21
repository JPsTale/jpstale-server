package org.jpstale.common.service.stat;

import org.jpstale.common.service.item.ItemClass;
import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerStatCalculator} 的特征测试。
 *
 * 为什么保护它：它是面板/移动/回复的**唯一口径**（554 行、公式来自 ex-machina 原版），
 * 而且本轮重构后 web-server 要用它算离线角色属性。所有公式都拿不到"手算期望值"
 * （系数表 + 装备 + 职业函数叠在一起），所以这里用**金标准签名**：
 * 固定输入 → 全量派生属性拼成一个字符串 → 与重构前实测值逐字比较。
 *
 * 签名一旦变化，就是"重构改变了游戏手感"，必须逐项解释才能改期望值。
 */
class PlayerStatCalculatorTest {

    private final PlayerStatCalculator calc = new PlayerStatCalculator();

    /**
     * 1 级武士、无装备：初始属性 99 点按职业固定分配（INITIAL_STATS[1] = 28/6/21/17/27）。
     * 期望值 = 阶段 0（重构前）实测。
     */
    private static final String EXPECTED_BARE_L1_FIGHTER =
            "hp=79|mp=13|sp=150|atkRating=86|defense=15|absorption=2|baseAtk=3-5|atkSpeed=0|crit=0"
                    + "|block=0|range=30|maxWeight=159|moveSpeed=1|avoid=21"
                    + "|walk=42.65625|run=109.453125"
                    + "|walkAnim=0.8666666666666667|runAnim=0.8666666666666667"
                    + "|regenHp=0.15555555555555556|regenMp=0.18869565217391304|regenStm=0.28";

    /**
     * 50 级武士 + 一套（单手剑/甲/靴）：覆盖装备与职业特效进入公式的路径。
     * 期望值 = 阶段 0（重构前）实测。
     */
    private static final String EXPECTED_EQUIPPED_L50_FIGHTER =
            "hp=428|mp=43|sp=385|atkRating=179|defense=133|absorption=18|baseAtk=5-7|atkSpeed=3"
                    + "|crit=7|block=2|range=30|maxWeight=796|moveSpeed=4|avoid=24"
                    + "|walk=47.578125|run=122.109375"
                    + "|walkAnim=0.9666666666666667|runAnim=0.9666666666666667"
                    + "|regenHp=1.7907407407407405|regenMp=0.6147826086956522|regenStm=0.77";

    private static Player fighter(int level) {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setJob(1);
        p.setLevel(level);
        // 1 级初始分配（对齐 TempNewCharacterInit）
        p.setStrength(28);
        p.setSpirit(6);
        p.setTalent(21);
        p.setAgility(17);
        p.setHealth(27);
        // 从 1 级升到 level：每级 +5 自由点，全部投力量（可复现）
        int extra = (level - 1) * 5;
        p.setStrength(p.getStrength() + extra);
        p.setHp(1);
        p.setMp(1);
        p.setSp(1);
        return p;
    }

    private static String signature(PlayerStatCalculator.Stats s) {
        return String.join("|",
                "hp=" + s.maxHp,
                "mp=" + s.maxMp,
                "sp=" + s.maxSp,
                "atkRating=" + s.attackRating,
                "defense=" + s.defense,
                "absorption=" + s.absorption,
                "baseAtk=" + s.baseAttack[0] + "-" + s.baseAttack[1],
                "atkSpeed=" + s.attackSpeed,
                "crit=" + s.critical,
                "block=" + s.block,
                "range=" + s.shootingRange,
                "maxWeight=" + s.maxWeight,
                "moveSpeed=" + s.moveSpeed,
                "avoid=" + s.avoid,
                "walk=" + s.walkSpeed,
                "run=" + s.runSpeed,
                "walkAnim=" + s.walkAnimRate,
                "runAnim=" + s.runAnimRate,
                "regenHp=" + s.regenHp,
                "regenMp=" + s.regenMp,
                "regenStm=" + s.regenStm);
    }

    @Test
    void 无装备时的一级武士派生属性钉住() {
        Player p = fighter(1);
        assertEquals(EXPECTED_BARE_L1_FIGHTER, signature(calc.stats(p)));
    }

    @Test
    void 带装备时的五十级武士派生属性钉住() {
        Player p = fighter(50);

        ItemList swordDef = new ItemList();
        swordDef.setId(2001);
        swordDef.setIdCode(0x01010100);
        swordDef.setName("Test Sword");
        swordDef.setClassItem(ItemClass.ONE_HAND_WEAPON);
        swordDef.setWeight(30);
        swordDef.setReqLevel(1);
        swordDef.setReqStrengh(1);
        swordDef.setReqSpirit(1);
        swordDef.setReqTalent(1);
        swordDef.setReqAgility(1);
        swordDef.setReqHealth(1);

        ItemInstance sword = new ItemInstance();
        sword.setId(2001L);
        sword.setItemListId(2001);
        sword.setLocation(ItemLocations.EQUIP);
        sword.setSlot(ItemLocations.SLOT_MAIN_HAND);
        sword.setTemplate(swordDef);
        sword.setDamageMin(21);
        sword.setDamageMax(47);
        sword.setAttackSpeed(3);
        sword.setCritical(5);
        sword.setAbsorb(1.0);
        sword.setBlockRating(0.5);
        p.getItems().byUidPut(sword);

        ItemList armorDef = new ItemList();
        armorDef.setId(2002);
        armorDef.setIdCode(0x02010100);
        armorDef.setName("Test Armor");
        armorDef.setClassItem(ItemClass.ARMOR);
        armorDef.setWeight(120);
        armorDef.setReqLevel(1);
        armorDef.setReqStrengh(1);
        armorDef.setReqSpirit(1);
        armorDef.setReqTalent(1);
        armorDef.setReqAgility(1);
        armorDef.setReqHealth(1);

        ItemInstance armor = new ItemInstance();
        armor.setId(2002L);
        armor.setItemListId(2002);
        armor.setLocation(ItemLocations.EQUIP);
        armor.setSlot(ItemLocations.SLOT_ARMOR);
        armor.setTemplate(armorDef);
        armor.setDefence(40);
        armor.setBlockRating(2.0);
        armor.setAbsorb(3.0);
        armor.setCritical(2);
        armor.setIncreaseLife(50);
        armor.setLifeRegen(1.5);
        p.getItems().byUidPut(armor);

        ItemList bootsDef = new ItemList();
        bootsDef.setId(2003);
        bootsDef.setIdCode(0x02020100);
        bootsDef.setName("Test Boots");
        bootsDef.setClassItem(ItemClass.BOOTS);
        bootsDef.setWeight(20);
        bootsDef.setReqLevel(1);
        bootsDef.setReqStrengh(1);
        bootsDef.setReqSpirit(1);
        bootsDef.setReqTalent(1);
        bootsDef.setReqAgility(1);
        bootsDef.setReqHealth(1);

        ItemInstance boots = new ItemInstance();
        boots.setId(2003L);
        boots.setItemListId(2003);
        boots.setLocation(ItemLocations.EQUIP);
        boots.setSlot(ItemLocations.SLOT_BOOTS);
        boots.setTemplate(bootsDef);
        boots.setDefence(10);
        boots.setAbsorb(1.0);
        boots.setSpeed(1.8);
        boots.setSpecSpeed(0.5);
        boots.setJobCodeMask(1);        // 武士位 → 特效移速生效
        p.getItems().byUidPut(boots);

        assertEquals(EXPECTED_EQUIPPED_L50_FIGHTER, signature(calc.stats(p)));
    }

    @Test
    void 属性点总量是九十九加每级五点() {
        assertEquals(99, PlayerStatCalculator.totalStatPoints(1));
        assertEquals(104, PlayerStatCalculator.totalStatPoints(2));
        assertEquals(99 + 49 * 5, PlayerStatCalculator.totalStatPoints(50));
    }

    /** 派生属性是惰性缓存的：invalidate 之后必须能重算出同样的值。 */
    @Test
    void 缓存失效后重算结果一致() {
        Player p = fighter(30);
        String first = signature(calc.stats(p));
        assertTrue(calc.stats(p) == calc.stats(p), "同一次会话内应命中缓存");
        calc.invalidate(p);
        assertEquals(first, signature(calc.stats(p)));
    }

    @Test
    void 职业不同则初始属性分配不同() {
        int[] fighterInit = calc.getInitialStats(1);
        int[] archerInit = calc.getInitialStats(3);
        assertEquals(28, fighterInit[0]);
        assertEquals(17, archerInit[0], "弓手初始力量 17");
        int sum = 0;
        for (int v : fighterInit) {
            sum += v;
        }
        assertEquals(99, sum, "1 级初始属性总和固定为 99");
    }
}
