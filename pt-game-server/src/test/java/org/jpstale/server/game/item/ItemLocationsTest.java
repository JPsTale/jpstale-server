package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * L0：location 十进制编码矩阵（test-背包装具系统 §4.1, LOC-001..006）。
 */
public class ItemLocationsTest {

    @Test
    public void matrixLocations() { // LOC-001..005
        assertEquals(0, ItemLocations.EQUIP);
        assertEquals(1, ItemLocations.BACKUP_EQUIP);
        assertEquals(10, ItemLocations.BAG_PAGE);
        assertEquals(10, ItemLocations.BAG); // 兼容别名
        assertEquals(30, ItemLocations.WAREHOUSE);
        assertEquals(20, ItemLocations.QUEST);
        assertEquals(40, ItemLocations.STORE);
        assertEquals(1, ItemLocations.BACKUP_WEAPON); // 兼容别名 → 副装备
    }

    @Test
    public void bagDimensions() { // LOC-003
        assertEquals(12, ItemLocations.BAG_W);
        assertEquals(12, ItemLocations.BAG_H);
        assertEquals(144, ItemLocations.BAG_SLOTS);
        assertEquals(144, ItemLocations.slotsOf(ItemLocations.BAG_PAGE));
        assertEquals(12, ItemLocations.widthOf(ItemLocations.BAG_PAGE));
        assertTrue(ItemLocations.isCanvas(ItemLocations.BAG_PAGE));
        assertTrue(ItemLocations.isBagPage(ItemLocations.BAG_PAGE));
    }

    @Test
    public void warehouseDimensions() { // LOC-004
        assertEquals(9, ItemLocations.WH_W);
        assertEquals(9, ItemLocations.WH_H);
        assertEquals(81, ItemLocations.slotsOf(ItemLocations.WAREHOUSE));
        assertEquals(9, ItemLocations.widthOf(ItemLocations.WAREHOUSE));
        assertTrue(ItemLocations.isCanvas(ItemLocations.WAREHOUSE));
        assertTrue(ItemLocations.isWarehousePage(ItemLocations.WAREHOUSE));
    }

    @Test
    public void questAndStoreNotCanvas() { // LOC-005
        assertEquals(0, ItemLocations.widthOf(ItemLocations.QUEST)); // 非画布无方格
        assertEquals(0, ItemLocations.slotsOf(ItemLocations.QUEST));
        assertFalse(ItemLocations.isCanvas(ItemLocations.STORE));
    }

    @Test
    public void illegalLocationsRejected() { // LOC-006
        // 非画布/非法 location：widthOf/slotsOf 返回 0（不抛）
        assertEquals(0, ItemLocations.widthOf(-1));
        assertEquals(0, ItemLocations.widthOf(7));
        assertEquals(0, ItemLocations.widthOf(99));
        assertEquals(0, ItemLocations.widthOf(31)); // 31 非仓库页（30~39 内但无 index）
        assertFalse(ItemLocations.isCanvas(-1));
        assertFalse(ItemLocations.isBagPage(31));
    }

    @Test
    public void bagPageRange() { // 十进制个位=页号语义
        assertTrue(ItemLocations.isBagPage(11));
        assertTrue(ItemLocations.isBagPage(19));
        assertFalse(ItemLocations.isBagPage(10 + 10)); // 20 是任务栏
        assertTrue(ItemLocations.isWarehousePage(31));
        assertFalse(ItemLocations.isWarehousePage(40)); // 40 是商店
    }
}