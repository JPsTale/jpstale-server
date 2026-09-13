package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：**需求不满足的装备"属性不生效"** —— 原版 `SetItemToChar` 在累加属性时
 * `if (InvenItem[i].sItemInfo.NotUseFlag) continue;`（`sinInvenTory.cpp:7355`）。
 *
 * 场景：洗点/降级后属性掉下来，原本穿得上的装备变成穿不上 → 它的防御/抗性/攻击加成**当即为 0**
 * （外观照旧、**负重照算**），客户端同时把该格与装备槽涂红（`sinInvenTory.cpp:944`）。
 * 判据唯一实现在 `ItemRules.meetsRequirements`（属性侧）与 `ItemRules.canUse`（职业侧）。
 */
public class EquipRequirementTest {

    private static ItemList armor(int defense, int weight) {
        ItemList t = new ItemList();
        t.setId(210);
        t.setName("Titan Armor");
        t.setClassItem(ItemClass.ARMOR);
        t.setWidth(44);
        t.setHeight(44);
        t.setDefenseMin(defense);
        t.setDefenseMax(defense);
        t.setWeight(weight);
        return t;
    }

    private static Player player(int strength) {
        Player p = new Player(null, 0);
        p.setItems(new PlayerItems());
        p.setCharacterId(1L);
        p.setName("tester");
        p.setLevel(50);
        p.setStrength(strength);
        p.setSpirit(10);
        p.setTalent(10);
        p.setAgility(10);
        p.setHealth(10);
        return p;
    }

    private static ItemInstance worn(ItemList def, int defense, int reqStrength) {
        ItemInstance it = new ItemInstance();
        it.setId(1L);
        it.setCharacterId(1);
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(ItemLocations.SLOT_ARMOR);
        it.setTemplate(def);
        it.setItemListId(210);
        it.setCount(1);
        it.setReqStrength(reqStrength);   // 判据读**实例**需求值（由掷点/模板填入）
        it.setDefence(defense);
        return it;
    }

    @Test
    public void equippedButRequirementUnmetContributesNothing() {
        ItemList def = armor(50, 30);            // 需要力量 100
        PlayerItems items = new PlayerItems();
        ItemInstance it = worn(def, 50, 100);
        items.index(it);
        Player p = player(150);                       // 力量够 → 生效
        p.getItems().index(it);
        PlayerStatCalculator calc = new PlayerStatCalculator();

        // ⚠ 用 `EquipSummary`（**纯装备贡献**）而不是 `calc.defense`（它还含角色基础防御）
        assertEquals("满足要求时装备防御生效", 50, EquipSummary.of(p).defense);
        assertEquals("负重照算", 30, calc.currentWeight(p));

        // 洗点：力量掉到 50 < 100 → 同一件装备不再算数
        p.setStrength(50);
        assertEquals("属性不满足 → 装备防御不生效", 0, EquipSummary.of(p).defense);
        assertEquals("但负重仍然算它", 30, calc.currentWeight(p));
        assertFalse("判据本身也说穿不上", ItemRules.meetsRequirements(p, it));

        // 属性恢复 → 又生效（红底/属性都是**动态**的）
        p.setStrength(150);
        assertEquals("属性恢复后重新生效", 50, EquipSummary.of(p).defense);
        assertTrue(ItemRules.meetsRequirements(p, it));
    }

    @Test
    public void unequippedBagItemStillCountsWeightAndIsNotAffected() {
        // 背包里那件（没穿）不影响装备属性，但负重照算 —— 与"穿了但不满足"区别在于后者在 EQUIP 段
        ItemList def = armor(50, 30);
        PlayerItems items = new PlayerItems();
        ItemInstance bagged = worn(def, 50, 100);
        bagged.setLocation(ItemLocations.BAG_PAGE);
        bagged.setSlot(0);
        items.index(bagged);
        Player p = player(10);
        p.getItems().index(bagged);
        PlayerStatCalculator calc = new PlayerStatCalculator();

        assertEquals("没穿就没有装备属性", 0, EquipSummary.of(p).defense);
        assertEquals("背包件照样占负重", 30, calc.currentWeight(p));
    }
}
