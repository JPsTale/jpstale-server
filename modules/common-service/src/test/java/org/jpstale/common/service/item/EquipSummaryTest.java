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
 * 算离线角色属性，所以每个字段的**归属条件**（逐件累加 / 职业特效按掩码 / 等级档整除）都必须钉死。
 *
 * ⚠ 2026-09-22 重写：旧版本把"只有甲/靴/手算 defence、只有主手算攻速/射程"当成规则钉住，
 * 那是我们自造的分部位口径，**导致盾牌（classitem=2）与臂环（2048）的躲避从未计入属性**
 * （用户实测：装/卸盾面板躲避恒为 121）。现按原版 `SetItemToChar` 改为逐件累加，见
 * {@link EquipSummary} 类注释里的字段清单与出处行号。
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
     * **逐件累加、不分部位**：攻速/射程/伤害都是每件装备自己的值相加
     * （原版 `sinWeaponSpeed += Attack_Speed`、`sinShooting_Range += Shooting_Range`、
     * `sinAttack_Damage[x] += Damage[x]`）。
     */
    @Test
    void 攻速射程伤害逐件累加() {
        Player p = player(1, 50);
        ItemInstance main = item(101, ItemLocations.SLOT_MAIN_HAND, ItemClass.ONE_HAND_WEAPON, 1, 30);
        main.setDamageMin(21);
        main.setDamageMax(47);
        main.setAttackSpeed(3);
        main.setShootingRange(70);
        p.getItems().byUidPut(main);
        assertEquals(3, EquipSummary.of(p).attackSpeed);
        assertEquals(70, EquipSummary.of(p).range);

        ItemInstance off = item(102, ItemLocations.SLOT_OFF_HAND, ItemClass.OFF_HAND, 1, 40);
        off.setAttackSpeed(2);
        off.setShootingRange(90);
        p.getItems().byUidPut(off);

        EquipSummary s = EquipSummary.of(p);
        assertTrue(s.hasWeapon);
        assertEquals(21, s.damageMin);
        assertEquals(47, s.damageMax);
        assertEquals(ItemClass.ONE_HAND_WEAPON, s.weaponClassItem);
        assertEquals(5, s.attackSpeed, "主手 3 + 副手 2");
        assertEquals(160, s.range, "射程相加（原版 `+=`）＝ 70 + 90，不是取较大者");
        assertEquals(70, s.weight, "负重 = 模板 weight 之和（30 + 40）");
    }

    /**
     * 用户 2026-09-22 实测的那件装备：躲避不再分部位。
     *
     * 原报告：`tanknight`（18 级，DEX24/TAL39）装备 Tower Shield（defence 46）后
     * 面板躲避仍是 121 —— 因为旧实现只把"甲/靴/手"算进 defence：
     * 基础 `defenseOf` = 24/2 + 39/4 + 18*1.4 = 46，躯干三件 28 + 32 + 15 = 75，合计 121。
     */
    @Test
    void 盾牌与臂环的躲避也逐件计入() {
        Player p = player(6, 18);
        ItemInstance armor = item(103, ItemLocations.SLOT_ARMOR, ItemClass.ARMOR, 1, 120);
        armor.setDefence(28);
        p.getItems().byUidPut(armor);

        ItemInstance shield = item(104, ItemLocations.SLOT_OFF_HAND, ItemClass.OFF_HAND, 1, 40);
        shield.setDefence(46);                     // 旧实现：classitem=2 → 落 else 分支 → 丢掉
        shield.setBlockRating(13.6);
        shield.setAbsorb(2.0);
        p.getItems().byUidPut(shield);

        ItemInstance gauntlets = item(105, ItemLocations.SLOT_GLOVES, ItemClass.GLOVES, 1, 30);
        gauntlets.setDefence(32);
        p.getItems().byUidPut(gauntlets);

        ItemInstance boots = item(106, ItemLocations.SLOT_BOOTS, ItemClass.BOOTS, 1, 20);
        boots.setDefence(15);
        p.getItems().byUidPut(boots);

        ItemInstance armlets = item(107, ItemLocations.SLOT_ARMLET, ItemClass.ARMLET, 1, 5);
        armlets.setDefence(5);                     // 旧实现同样丢掉（classitem=2048）
        p.getItems().byUidPut(armlets);

        EquipSummary s = EquipSummary.of(p);
        assertEquals(126, s.defense, "盾 46 + 臂环 5 + 甲 28 + 手 32 + 靴 15 —— 一件都不许漏");
        assertEquals(13.6, s.block, 1e-9);
        assertEquals(2.0, s.absorb, 1e-9);
    }

    /** 吸收按原版**逐件**截到 0.1（`(int)(v*10.000001f)/10.0f`），不是对总和取整。 */
    @Test
    void 吸收逐件截到一位小数() {
        Player p = player(1, 50);
        ItemInstance a = item(108, ItemLocations.SLOT_RING_L, ItemClass.LRING, 1, 1);
        a.setAbsorb(0.46);      // 截到 0.4
        p.getItems().byUidPut(a);
        ItemInstance b = item(109, ItemLocations.SLOT_RING_R, ItemClass.RRING, 1, 1);
        b.setAbsorb(0.46);      // 截到 0.4
        p.getItems().byUidPut(b);

        assertEquals(0.8, EquipSummary.of(p).absorb, 1e-9,
                "逐件截断 = 0.4 + 0.4；若先求和再截会得 0.9");
    }

    /**
     * 需求不满足的装备：`EquipSummary` **整件跳过**（连它的 weight 字段也不加），
     * 但**真实负重照算** —— 这是两条不同的路径，别混。
     *
     * - `EquipSummary.of` 在第一行循环里就 `continue`（对齐原版 `SetItemToChar` 的 `if NotUseFlag continue`）。
     * - 真实负重走 `PlayerStatCalculator.currentWeight` → `weightOf(BAG_PAGE + EQUIP)`，
     *   它按模板 weight 求和、**不看需求**，所以"属性不生效但负重照算"成立。
     *
     * ⚠ 顺带记录：`EquipSummary.weight` 全仓**没有任何读取点**（只有赋值），
     * 它不是负重权威 —— 本用例把这条语义差别固定下来，免得后来人误用它。
     */
    @Test
    void 需求不满足的装备在聚合里整件跳过但真实负重照算() {
        Player p = player(1, 10);
        ItemInstance tooHigh = item(110, ItemLocations.SLOT_ARMOR, ItemClass.ARMOR, 50, 120);
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
        ItemInstance held = item(111, ItemLocations.HELD_SLOT, ItemClass.ONE_HAND_WEAPON, 1, 30);
        held.setDamageMin(21);
        held.setDamageMax(47);
        held.setDefence(60);
        p.getItems().byUidPut(held);

        EquipSummary s = EquipSummary.of(p);
        assertFalse(s.hasWeapon, "拿在手上不等于装备");
        assertEquals(0, s.defense);
        assertEquals(0, s.weight);
    }

    /**
     * 职业特效：**每一项**都要按掩码生效，公式照抄 `sinInvenTory.cpp:7446-7492`。
     * 门 = `装备掩码 & (1 << (职业-1))`（原版 `sinChar->JobBitMask & JobCodeMask`）。
     */
    @Test
    void 特效全字段按掩码累加_等级档整除() {
        Player knight = player(6, 18);              // 职业 6 → 位 32（= 用户那面"游侠特效"盾）
        ItemInstance shield = item(112, ItemLocations.SLOT_OFF_HAND, ItemClass.OFF_HAND, 1, 40);
        shield.setDefence(46);
        shield.setBlockRating(13.6);
        shield.setAbsorb(2.0);
        shield.setSpeed(1.0);
        shield.setJobCodeMask(1 << (6 - 1));
        shield.setSpecDefence(7);
        shield.setSpecBlockRating(4.0);
        shield.setSpecAbsorb(0.4);
        shield.setSpecSpeed(1.3);
        shield.setSpecAttackSpeed(1);
        shield.setSpecCritical(2);
        shield.setSpecShootingRange(30);
        shield.setSpecLevLife(5);
        shield.setSpecLevMana(6);
        shield.setSpecLevAttackRating(9);
        shield.setSpecLevDamageMax(5);
        shield.setSpecPerLifeRegen(2.0);
        shield.setSpecPerManaRegen(1.0);
        shield.setSpecPerStaminaRegen(0.5);
        shield.setSpecResFire(10);
        shield.setSpecLevResIce(6);
        knight.getItems().byUidPut(shield);

        EquipSummary s = EquipSummary.of(knight);
        assertEquals(53, s.defense, "盾基础 46 + 特效 7");
        assertEquals(17.6, s.block, 1e-9, "13.6 + 特效 4.0");
        assertEquals(2.4, s.absorb, 1e-9, "2.0 + 特效 0.4");
        assertEquals(2.3, s.moveSpeedBonus, 1e-9, "掷点 1.0 + 特效 1.3");
        assertEquals(1, s.attackSpeed, "特效攻速 1 进档");
        assertEquals(2, s.critical);
        assertEquals(30, s.range);
        assertEquals(18 / 5, s.specLevLife, "Lev_Life 5 → 等级/v = 18/5 = 3（整除）");
        assertEquals(18 / 6, s.specLevMana);
        assertEquals(18 / 9, s.specLevAttackRating);
        assertEquals(18 / 5, s.specLevDamage, "Lev_Damage[1] 5 → 伤害上限 +3");
        assertEquals(1.0, s.regenHp, 1e-9, "Per_Life_Regen 2.0 / 2");
        assertEquals(0.5, s.regenMp, 1e-9, "Per_Mana_Regen 1.0 / 2");
        assertEquals(0.25, s.regenStm, 1e-9, "Per_Stamina_Regen 0.5 / 2");
        assertEquals(10, s.res[2], "火抗特效直加");
        assertEquals(3, s.res[3], "冰抗等级档 = 18/6（**只加 等级/v**，不加除数本身）");

        // 掩码不含本职业 → 只剩基础值，特效一项都不生效（同一个实例可用于另一玩家：聚合只读）
        Player magician = player(7, 18);            // 法师位 ≠ 32
        magician.getItems().byUidPut(shield);
        EquipSummary t = EquipSummary.of(magician);
        assertEquals(46, t.defense);
        assertEquals(13.6, t.block, 1e-9);
        assertEquals(2.0, t.absorb, 1e-9);
        assertEquals(1.0, t.moveSpeedBonus, 1e-9);
        assertEquals(0, t.attackSpeed);
        assertEquals(0, t.specLevLife);
        assertEquals(0, t.specLevDamage);
        assertEquals(0.0, t.regenHp, 1e-9);
        assertEquals(0, t.res[2]);
        assertEquals(0, t.res[3]);

        // 掩码 0 = 无特效
        Player none = player(6, 18);
        ItemInstance plain = item(113, ItemLocations.SLOT_OFF_HAND, ItemClass.OFF_HAND, 1, 40);
        plain.setSpecDefence(7);
        plain.setSpecSpeed(5.0);
        plain.setJobCodeMask(0);
        none.getItems().byUidPut(plain);
        assertEquals(0, EquipSummary.of(none).defense);
        assertEquals(0.0, EquipSummary.of(none).moveSpeedBonus, 1e-9);
    }

    /** 基础与特效的三种"上限/回复"都进同一个累加器（原版 `sinfIncre*` 与 `sinLev_*` 分开累、同一处使用）。 */
    @Test
    void 上限与回复按实例值累加() {
        Player p = player(1, 50);
        ItemInstance a = item(114, ItemLocations.SLOT_RING_R, ItemClass.RRING, 1, 1);
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
        assertEquals(1.5, s.regenHp, 1e-9);
        assertEquals(2.5, s.regenMp, 1e-9);
        assertEquals(0.5, s.regenStm, 1e-9);
    }

    /** 基础抗性（8 元素）逐件累加，顺序 = 原版 EElementID。 */
    @Test
    void 基础抗性逐件累加() {
        Player p = player(1, 50);
        ItemInstance a = item(115, ItemLocations.SLOT_ARMOR, ItemClass.ARMOR, 1, 10);
        a.setResBionic(1);
        a.setResEarth(2);
        a.setResFire(3);
        a.setResIce(4);
        a.setResLighting(5);
        a.setResPoison(6);
        a.setResWater(7);
        a.setResWind(8);
        p.getItems().byUidPut(a);
        ItemInstance b = item(116, ItemLocations.SLOT_BOOTS, ItemClass.BOOTS, 1, 10);
        b.setResFire(10);
        p.getItems().byUidPut(b);

        assertArrayEquals(new int[]{1, 2, 13, 4, 5, 6, 7, 8}, EquipSummary.of(p).res);
    }
}
