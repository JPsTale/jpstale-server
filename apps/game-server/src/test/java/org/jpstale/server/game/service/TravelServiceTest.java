package org.jpstale.server.game.service;

import org.jpstale.common.service.map.FieldCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TravelService 的纯函数与数据完整性断言（设计文档 §7.3 验收口径的可复算部分）。
 *
 * 门命中数学对齐原版 `field.cpp:210-212`（实测样例取 fields.json map 3 的翅膀门，
 * 与 EU `MapGame.cpp:303` 逐字节同一条）；表数据完整性防止"表里写了目录里没有的图号"。
 */
class TravelServiceTest {

    // ---------- 门触发柱命中（gateHit） ----------

    @Test
    void gateHit_centerAndEdge() {
        // fields.json map 3 的门：{x:734, z:20119, y:312, size:64, height:32}（EU MapGame.cpp:303 同源）
        double gx = 734, gz = 20119, gy = 312;
        int size = 64, height = 32;

        assertTrue(TravelService.gateHit(gx, 312, gz, gx, gy, gz, size, height), "门心必命中");
        assertTrue(TravelService.gateHit(gx + 30, 312, gz, gx, gy, gz, size, height), "半径内命中（30 < 64）");
        assertFalse(TravelService.gateHit(gx + 64, 312, gz, gx, gy, gz, size, height), "半径边界外不命中（< 严格小于）");
        assertFalse(TravelService.gateHit(gx + 64.5, 312, gz, gx, gy, gz, size, height), "半径外不命中");
    }

    @Test
    void gateHit_heightGate() {
        double gx = 0, gz = 0, gy = 312;
        assertTrue(TravelService.gateHit(0, 312 + 31, 0, gx, gy, gz, 64, 32), "高差 < height 命中");
        assertFalse(TravelService.gateHit(0, 312 + 32, 0, gx, gy, gz, 64, 32), "高差 ≥ height 不命中");
        // 原版：门 y=0 时不判高差（field.cpp:202-205 `if (!lpWarpGate->y) dy = 0`）
        assertTrue(TravelService.gateHit(0, 99999, 0, gx, 0, gz, 64, 32), "门 y=0 → 不判高差");
    }

    // ---------- 翅膀档位 → 可选门数 / 费用（sinWarpGate.cpp 口径） ----------

    @Test
    void wingGate_allowedCountByTier() {
        assertEquals(2, TravelService.allowedGateCount(0), "无翅膀 = 只有前 2 门（GateUseIndex=2）");
        for (int tier = 1; tier <= 6; tier++) {
            assertEquals(9, TravelService.allowedGateCount(tier), "有翅膀（1..6 档）= 全部 9 门");
        }
    }

    @Test
    void wingGate_costByTier_notByDestination() {
        // 前 2 门恒免费（sinWarpGate.cpp:222 `GateSelect < 2`），与翅膀档无关
        for (int tier = 0; tier <= 6; tier++) {
            assertEquals(0, TravelService.wingGateCost(0, tier), "门 0 免费");
            assertEquals(0, TravelService.wingGateCost(1, tier), "门 1 免费");
        }
        // 其余门按**持有档位**收费（WarpGateUseCost[GateUseIndex-4]，与目的门无关）
        assertEquals(100, TravelService.wingGateCost(2, 1), "Metal Wing → 100");
        assertEquals(300, TravelService.wingGateCost(8, 2), "Silver Wing → 300（即使去最贵的门）");
        assertEquals(4000, TravelService.wingGateCost(3, 6), "Imperial Wing → 4000");
        // 无翅膀时收费门费用无定义（allowedGateCount 已拦，到不了这里）——显式 -1，不编数
        assertEquals(-1, TravelService.wingGateCost(2, 0));
    }

    // ---------- Teleport Core（BI108） ----------

    @Test
    void teleportCore_codeMatch() {
        assertTrue(TravelService.isTeleportCore(0x080B0800), "BI108 = 0x080B|0x0800");
        assertFalse(TravelService.isTeleportCore(0x080B0900), "同族其他码不是 Teleport Core");
        assertFalse(TravelService.isTeleportCore(0x06010100), "以太核心不是（那是固定目的地表）");
    }

    // ---------- 数据完整性：表里的图号必须都在地图目录里 ----------

    @Test
    void tables_onlyReferenceKnownMaps() {
        FieldCatalog catalog = FieldCatalog.get();
        for (int mapId : TravelService.WING_GATE_MAPS) {
            assertTrue(catalog.has(mapId), "翅膀门网络表 map " + mapId + " 不在 fields.json 目录里");
        }
        for (int mapId : TravelService.TELEPORT_CORE_MAPS) {
            assertTrue(catalog.has(mapId), "Teleport Core 表 map " + mapId + " 不在 fields.json 目录里");
        }
        for (TravelService.NpcTeleport row : TravelService.NPC_TELEPORTS) {
            assertTrue(catalog.has(row.destMap()), "NPC 传送表 map " + row.destMap() + " 不在 fields.json 目录里");
        }
    }

    /**
     * 翅膀门网络 9 门图**全部有自指门**（= 原版 PosWarpOut 落点标记；触发器与落点一体，
     * `field.cpp:173-178`）。缺一个图，跳那个门就会显式拒绝 —— 这条断言把"数据缺口"钉在编译期可见处。
     */
    @Test
    void wingGate_everyNetworkMapHasSelfGateLanding() {
        for (int mapId : TravelService.WING_GATE_MAPS) {
            assertNotNull(TravelService.wingGateLanding(mapId),
                "翅膀门图 " + mapId + " 没有自指门（PosWarpOut 数据缺失）→ 玩家会被显式拒绝");
        }
        // 实测样例：Ricarten（map 3）自指出入口 = 822,19956（与 EU MapGame.cpp:303 逐字节同源）
        double[] landing = TravelService.wingGateLanding(3);
        assertEquals(822.0, landing[0], 0.001);
        assertEquals(19956.0, landing[1], 0.001);
    }

    /** 门分类：9 门表判定（自指门是否翅膀触发器的唯一数据判据）。 */
    @Test
    void wingGate_networkMapMembership() {
        for (int mapId : TravelService.WING_GATE_MAPS) {
            assertTrue(TravelService.isWingGateMap(mapId));
        }
        assertFalse(TravelService.isWingGateMap(33), "33 的自指门不在 9 门表（普通挪位门）");
        assertFalse(TravelService.isWingGateMap(0));
    }
}
