package org.jpstale.server.game.item;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：负重口径（背包+当前装备套；副装备栏不计；备用武器不计）与超重预检。
 * test-背包装具系统 §6.1（WGT-005）+ 定稿口径 note。
 */
public class PlayerStatCalculatorWeightTest {

    private static ItemList template(int classItem, int weight) {
        ItemList t = new ItemList();
        t.setClassItem(classItem);
        t.setWeight(weight);
        return t;
    }

    private static Player playerWith(PlayerItems items, int strength) {
        Player p = new Player(null, 0);
        p.setItems(items);
        p.setStrength(strength);
        return p;
    }

    private static ItemInstance itemIn(PlayerItems items, int location, int slot, ItemList template, int count) {
        ItemInstance it = new ItemInstance();
        it.setId((long) (location * 1000L + slot));
        it.setLocation(location);
        it.setSlot(slot);
        it.setTemplate(template);
        it.setItemListId(1);
        it.setCount(count);
        items.index(it);
        return it;
    }

    @Test
    public void weightCountsBagAndEquipOnly() { // WGT-005：副套不计、备用不计
        PlayerItems items = new PlayerItems();
        itemIn(items, ItemLocations.BAG_PAGE, 0, template(0, 30), 1);         // 背包 +30
        itemIn(items, ItemLocations.EQUIP, 1, template(4, 50), 1);            // 装备(主手) +50
        itemIn(items, ItemLocations.BACKUP_EQUIP, 1, template(4, 500), 1);    // 副装备(未激活) 不计
        Player p = playerWith(items, 10);

        int w = new PlayerStatCalculator().currentWeight(p);
        assertTrue("副套 500 不计入 → 应只 30+50=80", w == 80);
    }

    @Test
    public void potionCountedByBottleCount() { // 药水按瓶数，weight 字段忽略
        PlayerItems items = new PlayerItems();
        itemIn(items, ItemLocations.BAG_PAGE, 0, template(8192, 5), 7); // 7 瓶红药
        Player p = playerWith(items, 10);

        int w = new PlayerStatCalculator().currentWeight(p);
        assertTrue("药水按瓶数=7（非 7*5）", w == 7);
    }

    @Test
    public void negativeWeightIgnored() { // weight<0 忽略（原版语义）
        PlayerItems items = new PlayerItems();
        itemIn(items, ItemLocations.BAG_PAGE, 0, template(0, -5), 1);
        Player p = playerWith(items, 10);

        int w = new PlayerStatCalculator().currentWeight(p);
        assertTrue(w == 0);
    }

    @Test
    public void overWeightPrediction() { // 拾取预检：含新物后 > 上限 → true
        PlayerItems items = new PlayerItems();
        itemIn(items, ItemLocations.BAG_PAGE, 0, template(0, 30), 1);
        Player p = playerWith(items, 1); // strength=1 → maxWeight 很小
        ItemInstance fresh = new ItemInstance();
        fresh.setTemplate(template(0, 99999));

        PlayerStatCalculator calc = new PlayerStatCalculator();
        assertTrue(calc.isOverWeight(p, fresh));
        assertFalse(calc.isOverWeight(p, fresh) && calc.maxWeight(p) > 1000000);
    }

    @Test
    public void underWeightPrediction() { // 拿下随身物品可抬 → false
        PlayerItems items = new PlayerItems();
        itemIn(items, ItemLocations.BAG_PAGE, 0, template(0, 1), 1);
        Player p = playerWith(items, 30); // 力量足够大
        ItemInstance fresh = new ItemInstance();
        fresh.setTemplate(template(0, 100));

        PlayerStatCalculator calc = new PlayerStatCalculator();
        assertFalse(calc.isOverWeight(p, fresh));
    }
}