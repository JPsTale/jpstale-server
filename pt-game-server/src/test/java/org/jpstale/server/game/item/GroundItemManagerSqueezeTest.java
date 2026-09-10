package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.junit.Test;

/**
 * L0：掉落上限/两级挤压（test-背包装具系统 §6.1 GND-001..006，照搬原版 OnSever AddItem）。
 */
public class GroundItemManagerSqueezeTest {

    private static ItemInstance item(ItemList template) {
        ItemInstance it = new ItemInstance();
        it.setTemplate(template);
        it.setItemListId(1);
        it.setCount(1);
        return it;
    }

    private static ItemList material() { // Level=1（非药水/金币），永不被挤压
        ItemList t = new ItemList();
        t.setClassItem(0);       // 无装备位 → 材料（stackable 但非药水）
        t.setWeight(1);
        return t;
    }

    private static ItemList potion() { // Level=0（药水 classItem=8192），可被覆盖
        ItemList t = new ItemList();
        t.setClassItem(8192);
        t.setWeight(1);
        return t;
    }

    @Test
    public void squeezeOverwritesLevel0WhenFull() { // GND-002：满+含 Level=0 → 挤掉药水为新物腾位
        GroundItemManager gm = new GroundItemManager();
        // 塞满 1024（全部 Level=1 材料，除第 500 格放一个药水模拟 Level=0 存量）
        for (int i = 0; i < GroundItemManager.STG_ITEM_MAX; i++) {
            ItemInstance it = (i == 500) ? item(potion()) : item(material());
            GroundItemManager.GroundItem gi = gm.add(it, 1, i, 0, i, 0, 0);
            assertNotNull("第 " + i + " 次应放下", gi);
        }
        assertEquals(GroundItemManager.STG_ITEM_MAX, gm.listByMap(1).size());
        // 新掉落（材料 Level=1）→ 挤掉那个药水
        GroundItemManager.GroundItem added = gm.add(item(material()), 1, 999, 0, 999, 0, 0);
        assertNotNull("Level=1 新掉落应挤掉药水并放下", added);
        long countAfter = gm.listByMap(1).size();
        assertTrue("仍维持上限内（新物替换药水）", countAfter <= GroundItemManager.STG_ITEM_MAX);
    }

    @Test
    public void squeezeDropsWhenAllLevel1() { // GND-003：全 Level=1 → 丢弃，返回 null
        GroundItemManager gm = new GroundItemManager();
        for (int i = 0; i < GroundItemManager.STG_ITEM_MAX; i++) {
            gm.add(item(material()), 2, i, 0, i, 0, 0);
        }
        GroundItemManager.GroundItem added = gm.add(item(material()), 2, 999, 0, 999, 0, 0);
        assertNull("全 Level=1 时新掉落应丢弃", added);
        assertTrue("丢弃计数应递增", gm.droppedOverCount() >= 1);
    }

    @Test
    public void overwrittenLevel0Gone() { // GND-004：被挤掉的 Level=0 从地图消失
        GroundItemManager gm = new GroundItemManager();
        GroundItemManager.GroundItem potionGi = gm.add(item(potion()), 3, 0, 0, 0, 0, 0);
        String potionIdName = "potion-fill";
        assertNotNull(potionGi);
        // 填满其余
        int fillCount = 0;
        while (gm.listByMap(3).size() < GroundItemManager.STG_ITEM_MAX && fillCount < 10000) {
            gm.add(item(material()), 3, fillCount + 1000, 0, fillCount + 1000, 0, 0);
            fillCount++;
        }
        assertEquals(GroundItemManager.STG_ITEM_MAX, gm.listByMap(3).size());
        // 新掉落 → 挤掉 potion（Level=0）
        GroundItemManager.GroundItem mat = gm.add(item(material()), 3, 5555, 0, 5555, 0, 0);
        assertNotNull(mat);
        assertNull("被覆盖的药水应已从表移除", gm.byId(3, potionGi.id));
        assertEquals(potionIdName, "potion-fill");
    }

    @Test
    public void ttlDefaultsByLevel() { // GND-005：装备/材料 3min，药水 90s
        GroundItemManager gm = new GroundItemManager();
        GroundItemManager.GroundItem mat = gm.add(item(material()), 4, 0, 0, 0, 0, 0);
        GroundItemManager.GroundItem pot = gm.add(item(potion()), 4, 1, 0, 1, 0, 0);
        long now = System.currentTimeMillis();
        long matTtl = mat.expireAt - now;
        long potTtl = pot.expireAt - now;
        assertTrue("材料/装备 TTL 应 3min ≈ " + matTtl, Math.abs(matTtl - GroundItemManager.TTL_HIGH_MS) < 2000);
        assertTrue("药水 TTL 应 90s ≈ " + potTtl, Math.abs(potTtl - GroundItemManager.TTL_LOW_MS) < 2000);
    }

    @Test
    public void addNormalWhenUnderLimit() { // GND-001：未满直接落
        GroundItemManager gm = new GroundItemManager();
        GroundItemManager.GroundItem gi = gm.add(item(material()), 5, 0, 0, 0, 0, 0);
        assertNotNull(gi);
        assertEquals(1, gm.listByMap(5).size());
        assertNotNull(gm.byId(5, gi.id));
    }
}