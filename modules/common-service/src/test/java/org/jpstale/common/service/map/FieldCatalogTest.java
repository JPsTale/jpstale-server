package org.jpstale.common.service.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FieldCatalog} 的特征测试。
 *
 * 它会在阶段 3 连同 `fields/fields.json` 一起从 game-server 搬到 common-service ——
 * 那时最容易出的事是**资源没跟着走**（`loadFromClasspath()` 抛 IllegalStateException）
 * 或**换了个同名资源**。所以这里既钉"能载入"，也钉若干张图的**具体数值**。
 *
 * 数据来源：`apps/game-server/src/main/resources/fields/fields.json`（63 张图，id 0~62）。
 */
class FieldCatalogTest {

    @Test
    void 从classpath载入而不是抛异常() {
        FieldCatalog catalog = FieldCatalog.get();
        assertNotNull(catalog);
        assertEquals(63, catalog.size(), "fields.json 的图数量");
        assertEquals(62, catalog.maxId());
        assertTrue(catalog.has(0));
        assertTrue(catalog.has(62));
        assertFalse(catalog.has(63), "越界 id 不应存在于目录");
    }

    @Test
    void 单例是进程级懒加载() {
        assertSame(FieldCatalog.get(), FieldCatalog.get());
    }

    /** 图 0（fore3）：中心点、模型、小地图、出生点、边界门、传送门落点。 */
    @Test
    void 图零的静态数据逐项钉住() {
        FieldInfo f = FieldCatalog.get().get(0);
        assertNotNull(f);
        assertEquals("fore3", f.getShortname());
        assertEquals("forest/fore-3.smd", f.getModel());
        assertEquals("fore-3", f.getMinimap());
        assertEquals(-16419, f.getCenter()[0]);
        assertEquals(7054, f.getCenter()[1]);

        assertEquals(2, f.getStartPoints().size());
        assertEquals(-10585, f.getStartPoints().get(0)[0]);
        assertEquals(11810, f.getStartPoints().get(0)[1]);

        assertEquals(1, f.getFieldGates().size());
        assertEquals(1, f.getFieldGates().get(0).getTo(), "图 0 的边界门通往图 1");
        assertEquals(-8508, f.getFieldGates().get(0).getX());
        assertEquals(10576, f.getFieldGates().get(0).getZ());

        assertEquals(1, f.getWarpGates().size());
        FieldInfo.WarpGate gate = f.getWarpGates().get(0);
        assertEquals(64, gate.getSize());
        assertEquals(32, gate.getHeight());
        assertEquals(1, gate.getDestinations().size());
        assertEquals(24, gate.getDestinations().get(0).getMap(), "落点是另一张图");
        assertEquals(55, gate.getDestinations().get(0).getLevel());
    }

    /** 图 1（fore2）有 bounds —— 客户端判图与服务端 findMapPrecise 共用这一份。 */
    @Test
    void 图一的包围盒与边界门() {
        FieldInfo f = FieldCatalog.get().get(1);
        assertNotNull(f);
        assertEquals("fore2", f.getShortname());
        assertNotNull(f.getBounds());
        assertEquals(4, f.getBounds().length);
        assertEquals(-8574.953125, f.getBounds()[0]);
        assertEquals(1, f.getFieldGates().size());
        assertEquals(2, f.getFieldGates().get(0).getTo());
    }

    @Test
    void 出生兜底点优先第一个出生点() {
        FieldInfo f = FieldCatalog.get().get(0);
        assertSame(f.getStartPoints().get(0), f.fallbackStart());

        FieldInfo noStart = new FieldInfo();
        noStart.setCenter(new int[]{7, 8});
        assertSame(noStart.getCenter(), noStart.fallbackStart(), "没有出生点时退到中心");
    }

    @Test
    void 目录按id索引() {
        for (FieldInfo f : FieldCatalog.get().list()) {
            assertSame(f, FieldCatalog.get().get(f.getId()));
        }
    }
}
