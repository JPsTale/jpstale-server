package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EquipSummary} 的聚合口径特征测试。
 *
 * 为什么保护它：它是**面板与战斗共用的同一份**聚合（`PlayerStatCalculator` 与 `DamageCalculator`
 * 都读它），历史上有过"两处口径各写一遍、攻速翻倍"的事故。本轮重构后 web-server 也要用它
 * 算离线角色属性，所以每个字段的**归属条件**（谁是主手、什么算防具系、职业特效按掩码生效）
 * 都必须钉死。
 *
 * 本类不依赖金标准快照：夹具的每个掷点值都由测试自己给定，期望聚合值可以手算。
 */
class EquipSummaryTest {

    private static Player player(int job, int level) {
        Player p = new Player(0);
        p.setCharacterId(1L);
        p.setJob(job);
        p.setLevel(level);
        p.setStrength(99);
        p.setSpirit(99);
        p.setTalent(99);
        p.setAgility(99);
        p.setHealth(99);
        return p;
    }

    /** 造一件已掷点的装备实例；模板只用于 classitem/weight/需求。 */
    private static ItemInstance item(long uid, int slot, int classItem, int reqLevel, int weight) {
        ItemList def = new ItemList();
        def.setId((int) uid);
        def.setIdCode(0x01010100 + (int) uid);
        def.setName("it" + uid);
        def.setClassItem(classItem);
        def.setWeight(weight);
        def.setReqLevel(reqLevel);
        def.setReqStrengh(1);
        def.setReqSpirit(1);
        def.setReqTalent(1);
        def.setReqAgility(1);
        def.setReqHealth(1);

        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setItemListId((int) uid);
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(slot);
        it.setTemplate(def);
        return it;
    }

    @Test
    void 空装备栏聚合出零值() {
        EquipSummary s = EquipSummary.of(player(1, 50));
        assertEquals(0, s.defense);
        assertEquals(0.0, s.absorb);
        assertEquals(0.0, s.block);
        assertEquals(0, s.attackSpeed);
        assertEquals(0, s.weight);
        assertFalse(s.hasWeapon);
    }

    /**
     * 攻速/射程的**主手不重复计**规则。
     *
     * `EquipSummary` 里那个 `if (!mainHand)` 守卫防的是"主手自己再加一遍"（历史事故：攻速翻倍），
     * **不是**"副手不算"—— 副手/饰品的攻速照加，射程取 max。
     */
    @Test
    void 主手攻速不重复计而副手照加() {
        Player only = player(1, 50);
        ItemInstance main = item(101, ItemLocations.SLOT_MAIN_HAND, ItemClass.ONE_HAND_WEAPON, 1, 30);
        main.setDamageMin(21);
        main.setDamageMax(47);
        main.setAttackSpeed(3);
        main.setShootingRange(70);
        only.getItems().byUidPut(main);
        assertEquals(3, EquipSummary.of(only).attackSpeed, "只有主手时攻速是 3，不是 6");
        assertEquals(70, EquipSummary.of(only).range);

        Player both = player(1, 50);
        ItemInstance main2 = item(111, ItemLocations.SLOT_MAIN_HAND, ItemClass.ONE_HAND_WEAPON, 1, 30);
        main2.setDamageMin(21);
        main2.setDamageMax(47);
        main2.setAttackSpeed(3);
        main2.setShootingRange(70);
        main2.setAbsorb(1.0);
        main2.setBlockRating(0.5);
        main2.setCritical(5);
        both.getItems().byUidPut(main2);

        ItemInstance off = item(102, ItemLocations.SLOT_OFF_HAND, ItemClass.OFF_HAND, 1, 40);
        off.setAttackSpeed(2);
        off.setShootingRange(90);
        both.getItems().byUidPut(off);

        EquipSummary s = EquipSummary.of(both);
        assertTrue(s.hasWeapon);
        assertEquals(21, s.weaponDamageMin);
        assertEquals(47, s.weaponDamageMax);
        assertEquals(ItemClass.ONE_HAND_WEAPON, s.weaponClassItem);
        assertEquals(5, s.attackSpeed, "主手 3 + 副手 2（主手只被计一次）");
        assertEquals(90, s.range, "射程取主手与副手的较大者");
        assertEquals(70, s.weight, "负重 = 模板 weight 之和（30 + 40）");
    }

    @Test
    void 防具系与其余装备的归属条件不同() {
        Player p = player(1, 50);
        // 甲（防具系）：defence/block/absorb/critical 都计
        ItemInstance armor = item(103, ItemLocations.SLOT_ARMOR, ItemClass.ARMOR, 1, 120);
        armor.setDefence(40);
        armor.setBlockRating(2.0);
        armor.setAbsorb(3.0);
        armor.setCritical(2);
        p.getItems().byUidPut(armor);

        // 靴子（防具系）：另外把**实例掷点 speed** 计入 bootsSpeed
        ItemInstance boots = item(104, ItemLocations.SLOT_BOOTS, ItemClass.BOOTS, 1, 20);
        boots.setDefence(10);
        boots.setBlockRating(0.5);
        boots.setAbsorb(1.0);
        boots.setSpeed(1.8);
        p.getItems().byUidPut(boots);

        // 项链（非防具系、非主手）：absorb/block/critical 取自实例，但**不计 defence**
        ItemInstance amulet = item(105, ItemLocations.SLOT_AMULET, ItemClass.AMULET, 1, 5);
        amulet.setDefence(999);
        amulet.setBlockRating(1.0);
        amulet.setAbsorb(2.0);
        amulet.setCritical(3);
        amulet.setSpeed(9.9);
        p.getItems().byUidPut(amulet);

        EquipSummary s = EquipSummary.of(p);
        assertEquals(50, s.defense, "只有防具系计 defence：40 + 10，项链的 999 不算");
        assertEquals(6.0, s.absorb, "3.0(甲) + 1.0(靴) + 2.0(项链)");
        assertEquals(3.5, s.block, "2.0(甲) + 0.5(靴) + 1.0(项链)");
        assertEquals(5, s.critical, "2(甲) + 0(靴) + 3(项链)");
        assertEquals(1.8, s.bootsSpeed, "移速只来自靴子，项链的 speed 不计");
        assertEquals(145, s.weight, "120 + 20 + 5");
    }

    /**
     * 需求不满足的装备：`EquipSummary` **整件跳过**（连它的 weight 字段也不加），
     * 但**真实负重照算** —— 这是两条不同的路径，别混。
     *
     * - `EquipSummary.of` 在第一行循环里就 `continue`（对齐原版 `SetItemToChar` 的 `if NotUseFlag continue`）。
     * - 真实负重走 `PlayerStatCalculator.currentWeight` → `weightOf(BAG_PAGE + EQUIP)`，
     *   它按模板 weight 求和、**不看需求**，所以"属性不生效但负重照算"成立。
     *
     * ⚠ 顺带记录：`EquipSummary.weight` 全仓**没有任何读取点**（只有第 58 行的赋值），
     * 它不是负重权威 —— 本用例把这条语义差别固定下来，免得后来人误用它。
     */
    @Test
    void 需求不满足的装备在聚合里整件跳过但真实负重照算() {
        Player p = player(1, 10);
        ItemInstance tooHigh = item(106, ItemLocations.SLOT_ARMOR, ItemClass.ARMOR, 50, 120);
        tooHigh.setDefence(40);
        tooHigh.setReqLevel(50);
        p.getItems().byUidPut(tooHigh);

        EquipSummary s = EquipSummary.of(p);
        assertEquals(0, s.defense, "等级不够 → 防御不生效");
        assertEquals(0.0, s.absorb);
        assertEquals(0, s.weight, "聚合里整件跳过，连 weight 字段也不加");

        assertEquals(120, new PlayerStatCalculator().currentWeight(p),
                "真实负重不看需求：属性不生效、负重照算");
    }

    /** 鼠标位（slot = -1）那件还没装备，不能计入属性。 */
    @Test
    void 鼠标位那件不算装备() {
        Player p = player(1, 50);
        ItemInstance held = item(107, ItemLocations.HELD_SLOT, ItemClass.ONE_HAND_WEAPON, 1, 30);
        held.setDamageMin(21);
        held.setDamageMax(47);
        held.setDefence(60);
        p.getItems().byUidPut(held);

        EquipSummary s = EquipSummary.of(p);
        assertFalse(s.hasWeapon, "拿在手上不等于装备");
        assertEquals(0, s.defense);
        assertEquals(0, s.weight);
    }

    /** 职业特效移速只在装备掩码包含本职业位时生效。 */
    @Test
    void 职业特效移速按职业掩码生效() {
        Player warrior = player(1, 50);
        ItemInstance boots = item(108, ItemLocations.SLOT_BOOTS, ItemClass.BOOTS, 1, 20);
        boots.setSpeed(1.0);
        boots.setSpecSpeed(5.0);
        boots.setJobCodeMask(1 << (1 - 1));   // 只含武士位
        warrior.getItems().byUidPut(boots);
        assertEquals(6.0, EquipSummary.of(warrior).bootsSpeed, "掩码含武士位 → 生效");

        Player magician = player(7, 50);
        ItemInstance boots2 = item(109, ItemLocations.SLOT_BOOTS, ItemClass.BOOTS, 1, 20);
        boots2.setSpeed(1.0);
        boots2.setSpecSpeed(5.0);
        boots2.setJobCodeMask(1 << (1 - 1));  // 仍是武士位
        magician.getItems().byUidPut(boots2);
        assertEquals(1.0, EquipSummary.of(magician).bootsSpeed, "掩码不含法师位 → 不生效");

        Player noSpec = player(1, 50);
        ItemInstance boots3 = item(110, ItemLocations.SLOT_BOOTS, ItemClass.BOOTS, 1, 20);
        boots3.setSpeed(1.0);
        boots3.setSpecSpeed(5.0);
        boots3.setJobCodeMask(0);             // 掩码 0 = 无特效
        noSpec.getItems().byUidPut(boots3);
        assertEquals(1.0, EquipSummary.of(noSpec).bootsSpeed);
    }

    @Test
    void 上限与回复按实例值累加() {
        Player p = player(1, 50);
        ItemInstance a = item(111, ItemLocations.SLOT_RING_R, ItemClass.RRING, 1, 1);
        a.setIncreaseLife(12);
        a.setIncreaseMana(7);
        a.setIncreaseStamina(3);
        a.setLifeRegen(1.5);
        a.setManaRegen(2.5);
        a.setStaminaRegen(0.5);
        p.getItems().byUidPut(a);

        EquipSummary s = EquipSummary.of(p);
        assertEquals(12, s.increaseLife);
        assertEquals(7, s.increaseMana);
        assertEquals(3, s.increaseStamina);
        assertEquals(1.5, s.regenHp);
        assertEquals(2.5, s.regenMp);
        assertEquals(0.5, s.regenStm);
    }
}
