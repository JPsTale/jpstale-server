package org.jpstale.common.service.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 怪物水晶分派表的特征测试。
 *
 * <p>
 * 断言的判据全部来自 `docs/召唤物系统-源码分析.md` §4.1（原版 `srCristalMonster[]` 的怪名与
 * `RndCount`）与 §3.1（`CodeCount = ((code & 0xFFFF) >> 8) - 1`，一码一怪）。
 * 这里把**数值逐条钉住**，因为它们是"照抄原版"这件事的唯一凭据。
 */
class CrystalServiceTest {

    @Test
    void 一码一怪_十二个固定水晶逐条对得上专用召唤体() {
        // idcode 由 GP1NN 推出：sinGP1 | (NN << 8)；monsterid 取自 gamedb.monsterlist 的 *_Crystal 行
        assertFixed(0x08020100, 1207, "GP101 Hopy");
        assertFixed(0x08020200, 1209, "GP102 Hobgoblin");
        assertFixed(0x08020300, 1211, "GP103 Decoy");
        assertFixed(0x08020400, 1213, "GP104 Bargon");
        assertFixed(0x08020500, 1217, "GP105 Head Cutter");
        assertFixed(0x08020600, 1219, "GP106 Figon");
        assertFixed(0x08020700, 1221, "GP107 King Hopy");
        assertFixed(0x08020800, 1223, "GP108 Hulk");
        assertFixed(0x08020A00, 1235, "GP110 Guardian Saint");
        assertFixed(0x08020B00, 1215, "GP111 Web");
        assertFixed(0x08020C00, 1225, "GP112 Dark Specter");
        assertFixed(0x08020D00, 1227, "GP113 Iron Guard");
    }

    private static void assertFixed(int idCode, int monsterId, String label) {
        CrystalService.CrystalDef def = CrystalService.defOf(idCode);
        assertNotNull(def, label + " 应在表内");
        assertEquals(1, def.pool().size(), label + " 是单怪水晶，池里只有一项");
        assertEquals(monsterId, def.pool().get(0).monsterId(), label + " 的召唤体");
        assertEquals(100, def.pool().get(0).weight());
        assertFalse(def.note().isBlank(), "每行必须有依据（note）");
    }

    @Test
    void 神秘水晶的权重逐条照抄原版_且Hulk权重为0抽不到() {
        CrystalService.CrystalDef mystic = CrystalService.defOf(CrystalService.MYSTIC_CRYSTAL);
        assertNotNull(mystic, "GP109 神秘水晶应在表内");

        // 原版 srCristalMonster[0..7] 的 RndCount（OnSever.cpp:303-310）：20/20/15/15/15/10/5/0
        assertEquals(List.of(20, 20, 15, 15, 15, 10, 5, 0),
            mystic.pool().stream().map(CrystalService.Roll::weight).toList(),
            "权重必须逐条等于原版");
        assertEquals(List.of(1207, 1209, 1211, 1213, 1217, 1219, 1221, 1223),
            mystic.pool().stream().map(CrystalService.Roll::monsterId).toList(),
            "池里的怪与原版 srCristalMonster[0..7] 同序");

        // ★ 钉住原版缺陷：权重合计恰好 100，而掷点是 0..99 ⇒ 第 8 项（Hulk）永远不可达。
        //   原版客户端的物品说明里却列了 Hulk（SrcGame/src/sinbaram/sinItem.cpp:2529-2544）。
        //   我们照原版数值保留，**不要"顺手修好"** —— 没有权威数据说该给它多少。
        int total = mystic.pool().stream().mapToInt(CrystalService.Roll::weight).sum();
        assertEquals(100, total, "权重合计 100 ⇒ 权重 0 的最后一项不可达（原版亦然）");
        assertEquals(7, CrystalService.reachableOf(mystic).size(), "8 项里只有 7 项可达");
        for (int rnd = 0; rnd < 100; rnd++) {
            assertNotEquals(1223, CrystalService.rollSummon(mystic, rnd),
                "掷点 " + rnd + " 不该抽到 Hulk（权重 0）");
        }
    }

    @Test
    void 神秘水晶的区间边界() {
        CrystalService.CrystalDef m = CrystalService.defOf(CrystalService.MYSTIC_CRYSTAL);
        // 累积权重：20,40,55,70,85,95,100
        assertEquals(1207, CrystalService.rollSummon(m, 0), "0 → Hopy");
        assertEquals(1207, CrystalService.rollSummon(m, 19), "19 是 Hopy 的最后一点");
        assertEquals(1209, CrystalService.rollSummon(m, 20), "20 起是 Hobgoblin");
        assertEquals(1211, CrystalService.rollSummon(m, 54), "55 是 Decoy 的上界前一点");
        assertEquals(1217, CrystalService.rollSummon(m, 84), "85 是 Head Cutter 的上界前一点");
        assertEquals(1219, CrystalService.rollSummon(m, 85), "85 起是 Figon");
        assertEquals(1221, CrystalService.rollSummon(m, 99), "95..99 是 King Hopy（最后一项可达的）");
        assertEquals(-1, CrystalService.rollSummon(m, 100), "越界掷点不静默挑一个，返回 -1");
    }

    @Test
    void 单怪水晶无论掷点都出那一只() {
        CrystalService.CrystalDef def = CrystalService.defOf(0x08020100);
        for (int rnd : new int[]{0, 1, 50, 99}) {
            assertEquals(1207, CrystalService.rollSummon(def, rnd));
        }
    }

    @Test
    void 本期未实现的水晶返回null_由调用方按不支持处理() {
        // GP114-116 城堡兵（需要 Bless Castle 模式 + 一整套上限）
        assertNull(CrystalService.defOf(0x08020E00), "GP114");
        assertNull(CrystalService.defOf(0x08020F00), "GP115");
        assertNull(CrystalService.defOf(0x08021000), "GP116");
        // GP117-121/125 事件档（billing 池 / Marvel 池 / 越界缺陷）
        assertNull(CrystalService.defOf(0x08021100), "GP117");
        assertNull(CrystalService.defOf(0x08021300), "GP119");
        assertNull(CrystalService.defOf(0x08021700), "GP120");
        assertNull(CrystalService.defOf(0x08022000), "GP121");
        assertNull(CrystalService.defOf(0x08021900), "GP125");
        // GP2xx 灵魂石（原版造出来的是敌对怪，不是友军）
        assertNull(CrystalService.defOf(0x080D0100), "GP201");
        // 别的家族 / null
        assertNull(CrystalService.defOf(0x04020100), "生命药水不是水晶");
        assertNull(CrystalService.defOf(0x03060100), "力量石不是水晶");
        assertNull(CrystalService.defOf(null));
    }

    @Test
    void 已实现的码恰好十三个_升序且不重复() {
        List<Integer> codes = CrystalService.supportedCodes();
        assertEquals(13, codes.size(), "12 个单怪 + 神秘水晶");
        assertEquals(codes.stream().sorted().distinct().toList(), codes, "升序且不重复");
        for (int c : codes) {
            assertTrue(CrystalService.isSupported(c));
            assertEquals(CrystalService.FAMILY, c & 0xFFFF0000, "都在 sinGP1 族内");
        }
    }

    @Test
    void 池全为0权重时返回负一而非静默挑一个() {
        CrystalService.CrystalDef bad = new CrystalService.CrystalDef(0x0802FF00,
            List.of(new CrystalService.Roll(1, 0), new CrystalService.Roll(2, 0)), "测试用：数据坏了");
        assertEquals(-1, CrystalService.rollSummon(bad, 0));
        assertEquals(-1, CrystalService.rollSummon(new CrystalService.CrystalDef(
            0x0802FE00, List.of(), "测试用：空池"), 0));
    }
}
