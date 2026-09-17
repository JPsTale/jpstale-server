package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.jpstale.dao.gamedb.entity.DropItem;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class LootServiceTest {

    private static DropItem row(int dropId, String items, int chance, int gmin, int gmax) {
        DropItem d = new DropItem();
        d.setDropId(dropId);
        d.setItems(items);
        d.setChance(chance);
        d.setGoldMin(gmin);
        d.setGoldMax(gmax);
        return d;
    }

    @Test
    public void weightedPickHitsOnlyRowInRange() {
        // 权重 [Air 100, Gold 300, ITEMS 600]
        List<DropItem> rows = List.of(
                row(1, "Air", 100, 0, 0),
                row(1, "Gold", 300, 5, 5),
                row(1, "wa105", 600, 0, 0));
        Map<Integer, LootService.DropTable> tables =
                LootService.buildTables(rows, Map.of("wa105", 16844032));

        assertEquals(LootService.DropType.AIR, LootService.rollStatic(tables.get(1), 0.05).type);
        assertEquals(LootService.DropType.GOLD, LootService.rollStatic(tables.get(1), 0.30).type);
        assertEquals(5, LootService.rollStatic(tables.get(1), 0.30).gold);
        LootService.DropResult it = LootService.rollStatic(tables.get(1), 0.90);
        assertEquals(LootService.DropType.ITEMS, it.type);
        assertEquals(16844032, it.itemCode);
    }

    @Test
    public void unknownTokenSkippedAndNoTable() {
        Map<Integer, LootService.DropTable> tables =
                LootService.buildTables(List.of(row(2, "nosuchcode", 100, 0, 0)), Map.of());
        assertNull(tables.get(2)); // 全部无法解析 → 不建表
        assertNull(LootService.rollStatic(tables.get(2), 0.5));
    }
}
