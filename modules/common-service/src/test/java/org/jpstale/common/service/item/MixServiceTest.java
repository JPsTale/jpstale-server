package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.entity.MixList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合成（Mix）的特征测试 —— 覆盖**纯逻辑**：基材类判定、配方匹配（逐档严格相等）、
 * 效果加法（数值 vs 按当前值百分比）、石头档位解析、以及失败原因（不静默兜底）。
 *
 * <p>
 * 为什么这样切：`MixService` 的成功路径最后要落库（`ItemStorageService`），而本模块**没有 Mockito**
 * （只引 JUnit）。好在**所有失败分支都 return 在任何落库调用之前**，所以失败路径可以在
 * {@code storage == null} 下直接测；效果**数值**则通过包内可见的 {@link MixService#applyEffect} 单独钉住
 * （这段才是真正会算错的地方）。
 *
 * <p>
 * 配方夹具取**库里真实配方**的数值（例如 id=178「Defense +15 / Absorb +0.5 / Resistances +1」= 4×Fadeo，
 * 见 `docs/合成配方全表.md`）。
 */
class MixServiceTest {

    /** 造一行配方：typemix + 14 档石头（按下标给个数）+ 至多 3 组效果 {位, 值, 加法类型}。 */
    private static MixList recipe(int id, int uniqueId, int typeMix, int[] stones, int[][] effects) {
        MixList r = new MixList();
        r.setId(id);
        r.setMixUniqueId(uniqueId);
        r.setTypeMix(typeMix);
        r.setTypeMixName(MixType.fromCode(typeMix).label());
        r.setDescription("test#" + id);
        int[] st = new int[MixRecipe.STONE_SLOTS];
        System.arraycopy(stones, 0, st, 0, Math.min(stones.length, st.length));
        r.setLucidy(st[0]);
        r.setSereneo(st[1]);
        r.setFadeo(st[2]);
        r.setSparky(st[3]);
        r.setRaident(st[4]);
        r.setTransparo(st[5]);
        r.setMurky(st[6]);
        r.setDevine(st[7]);
        r.setCelesto(st[8]);
        r.setMirage(st[9]);
        r.setInferna(st[10]);
        r.setEnigma(st[11]);
        r.setBellum(st[12]);
        r.setOredo(st[13]);
        // 8 组效果槽全部按需写入（⚠ 原来只写前 3 槽，于是"第 7 槽"那种配方在测试里根本表达不出来 ——
        //    而真实数据里有 12 条配方用第 7 槽，我第一版就是只读了 6 槽、静默丢掉它们）
        for (int i = 0; i < effects.length && i < MixRecipe.EFFECT_SLOTS; i++) {
            setSlot(r, i + 1, effects[i][0], (double) effects[i][1], effects[i][2]);
        }
        return r;
    }

    /** 按下标（1..8）写一组效果槽 —— 显式 switch，改动 MixRecipe 槽数时编译期就能看到这里。 */
    private static void setSlot(MixList r, int idx, int bit, double value, int kind) {
        switch (idx) {
            case 1 -> { r.setTypeAtributte(bit); r.setAtributte(value); r.setPerAtributte(kind); }
            case 2 -> { r.setTypeAtributte2(bit); r.setAtributte2(value); r.setPerAtributte2(kind); }
            case 3 -> { r.setTypeAtributte3(bit); r.setAtributte3(value); r.setPerAtributte3(kind); }
            case 4 -> { r.setTypeAtributte4(bit); r.setAtributte4(value); r.setPerAtributte4(kind); }
            case 5 -> { r.setTypeAtributte5(bit); r.setAtributte5(value); r.setPerAtributte5(kind); }
            case 6 -> { r.setTypeAtributte6(bit); r.setAtributte6(value); r.setPerAtributte6(kind); }
            case 7 -> { r.setTypeAtributte7(bit); r.setAtributte7(value); r.setPerAtributte7(kind); }
            case 8 -> { r.setTypeAtributte8(bit); r.setAtributte8(value); r.setPerAtributte8(kind); }
            default -> throw new IllegalArgumentException("idx=" + idx);
        }
    }

    private static MixService serviceWith(MixList... rows) {
        // storage 传 null：本类只走「落库之前」的路径 + 直接调 applyEffect
        return new MixService(MixRecipeService.forRows(List.of(rows)), null);
    }

    private static MixRecipeService tableWith(MixList... rows) {
        return MixRecipeService.forRows(List.of(rows));
    }

    private static ItemInstance item(long uid, int idCode, int classItem) {
        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setItemCode(idCode);
        it.setLocation(ItemLocations.BAG_PAGE);
        it.setSlot(0);
        ItemList def = new ItemList();
        def.setId(1);
        def.setClassItem(classItem);
        def.setName("test#" + uid);
        it.setTemplate(def);
        return it;
    }

    private static Player playerWith(ItemInstance... items) {
        Player p = new Player(0);
        p.setCharacterId(1L);
        int slot = 0;
        for (ItemInstance it : items) {
            // ⚠ 同一 (location, slot) 只能放一件（PlayerItems 的槽位冲突守卫会拒第二件，
            //   那会让"测试里的第二件根本没进容器" ⇒ 断言变成 STONE_NOT_FOUND 而不是预期的原因）
            if (it.getLocation() == ItemLocations.BAG_PAGE) {
                it.setSlot(slot++);
            }
            p.getItems().byUidPut(it);
        }
        return p;
    }

    // ---------------------------------------------------------------- 基材类

    /** 基材类判定：照 EU `GetTypeMixByItemCode`（base 字节 + 家族细分），含 11 职业的拳套 WV。 */
    /** 派生属性重算器：测试里必须显式装配（否则读有效值只有基准值）*/
    @org.junit.jupiter.api.BeforeAll
    static void installDerivedStats() {
        ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of()));
    }

    @Test
    void 基材类判定与EU一致() {
        assertEquals(MixType.WEAPONS, MixType.ofItemCode(0x01010100), "斧 = 武器");
        assertEquals(MixType.WEAPONS, MixType.ofItemCode(0x010b0100), "★ 拳套 WV（0x010B）也是武器 —— 按爪处理");
        assertEquals(MixType.ARMOUR_ROBE, MixType.ofItemCode(0x02010100), "甲 = 甲/法袍");
        assertEquals(MixType.ARMOUR_ROBE, MixType.ofItemCode(0x02050100), "法袍 DA2 = 甲/法袍");
        assertEquals(MixType.SHIELDS, MixType.ofItemCode(0x02040100), "盾 DS1");
        assertEquals(MixType.GAUNTLETS, MixType.ofItemCode(0x02030100), "护手 DG1");
        assertEquals(MixType.BOOTS, MixType.ofItemCode(0x02020100), "靴 DB1");
        assertEquals(MixType.ORBS, MixType.ofItemCode(0x03030100), "法球 OM1");
        assertEquals(MixType.BRACELETS, MixType.ofItemCode(0x03020100), "护腕 OA2");
        assertEquals(MixType.UNKNOWN, MixType.ofItemCode(0x02350100), "材料石（OS 族）不是可合成目标");
        assertEquals(MixType.UNKNOWN, MixType.ofItemCode(0), "空码");
    }

    // ---------------------------------------------------------------- 匹配

    /** 匹配：基材类一致 + **逐档严格相等**（多一颗都不行，照 EU 的 `!=` 判据）。 */
    @Test
    void 匹配要求石头逐档严格相等() {
        int[] fourFadeo = {0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        MixRecipeService table = tableWith(recipe(178, 603, 3, fourFadeo, new int[][]{
                {MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT},
                {MixEffect.ABSORB, 1, MixEffect.Kind.FLAT},
        }));

        assertNotNull(table.find(MixType.SHIELDS, fourFadeo), "4×Fadeo + 盾 → 命中");
        assertNull(table.find(MixType.SHIELDS, new int[]{0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
                "5×Fadeo → 不命中（严格相等，不是『至少』）");
        assertNull(table.find(MixType.SHIELDS, new int[]{0, 0, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
                "换了石头种类 → 不命中");
        assertNull(table.find(MixType.GAUNTLETS, fourFadeo), "基材类不符 → 不命中");
        assertNull(table.find(MixType.UNKNOWN, fourFadeo), "不可合成类型 → 永远不命中");
    }

    /** 掩码 = 效果位按位或；配方 id 就是物品要记的那一格（`aging_num2`）。 */
    @Test
    void 掩码与配方id() {
        MixRecipe r = MixRecipe.of(recipe(178, 603, 3, new int[]{0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new int[][]{
                        {MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT},
                        {MixEffect.ABSORB, 1, MixEffect.Kind.FLAT},
                }));
        assertEquals(MixEffect.DEFENCE | MixEffect.ABSORB, r.mask(), "效果类型值就是掩码位");
        assertEquals(603, r.uniqueId(), "配方 id（写进物品 aging_num2，供读取侧反查数值）");
        assertEquals(MixType.SHIELDS, r.type());
    }

    // ---------------------------------------------------------------- 效果加法

    /** 效果加法：`peratributte = 1 → 直加`；`= 0 → 按**当前值**百分比`（EU `AddAttributeBonusToItem`）。 */
    @Test
    void 效果加法数值与百分比() {
        MixService svc = serviceWith();

        ItemInstance it = item(1L, 0x02040100, ItemClass.OFF_HAND);
        it.setDefence(100);
        MixEffect.apply(it, new MixRecipe.Slot(MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT));
        assertEquals(115, it.effectiveInt(ItemStat.DEFENCE), "直加：100 + 15");

        MixEffect.apply(it, new MixRecipe.Slot(MixEffect.DEFENCE, 10, MixEffect.Kind.PERCENT));
        assertEquals(126, it.effectiveInt(ItemStat.DEFENCE), "百分比按当前值：115 + 115*10/100 = 126（截断）");

        it.setAbsorb(2.0);
        MixEffect.apply(it, new MixRecipe.Slot(MixEffect.ABSORB, 0.5, MixEffect.Kind.FLAT));
        assertEquals(2.5, it.effective(ItemStat.ABSORB), 1e-9, "小数直加");

        it.setResFire(10);
        MixEffect.apply(it, new MixRecipe.Slot(MixEffect.FIRE, 3, MixEffect.Kind.FLAT));
        assertEquals(13, it.effectiveInt(ItemStat.RES_FIRE), "抗性直加");
        it.setResBionic(4);
        MixEffect.apply(it, new MixRecipe.Slot(MixEffect.ORGANIC, 2, MixEffect.Kind.FLAT));
        assertEquals(6, it.effectiveInt(ItemStat.RES_BIONIC), "生体抗 = bit16（EU 的 saElementalDef[0]）");
    }

    // ---------------------------------------------------------------- 石头档位

    /** 石头档位：OS 族 1..14 档 → 0..13；别的家族/越界档一律拒绝（不猜）。 */
    @Test
    void 石头档位解析() {
        assertEquals(0, MixService.stoneIndexOf(0x02350100), "Lucidy = 第 1 档");
        assertEquals(13, MixService.stoneIndexOf(0x02350e00), "Oredo = 第 14 档");
        assertEquals(-1, MixService.stoneIndexOf(0x02350f00), "第 15 档越界（我们只有 14 颗）");
        assertEquals(-1, MixService.stoneIndexOf(0x01010100), "斧不是材料石");
        assertEquals(-1, MixService.stoneIndexOf(0x03060100), "力量石（FO 族）不是合成材料");
        assertEquals(-1, MixService.stoneIndexOf(null));
    }

    // ---------------------------------------------------------------- 失败原因

    /** 失败原因必须可分（协议按 key 走，不匹配文案）：目标/石头/类型/配方各一条。 */
    @Test
    void 失败原因可分辨() {
        MixService svc = serviceWith(recipe(1, 301, 1,
                new int[]{5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new int[][]{{MixEffect.ORGANIC, 5, MixEffect.Kind.FLAT}}));

        Player empty = playerWith();
        assertEquals(MixService.Reason.TARGET_NOT_FOUND, svc.mix(empty, 999L, List.of(1L)).reason, "目标不存在");

        ItemInstance stone = item(1L, 0x02350100, ItemClass.SHELTOM);
        assertEquals(MixService.Reason.NOT_MIXABLE,
                svc.mix(playerWith(stone), 1L, List.of(1L)).reason, "材料石不能当合成目标");

        ItemInstance axe = item(1L, 0x01010100, ItemClass.ONE_HAND_WEAPON);
        Player withAxe = playerWith(axe);
        assertEquals(MixService.Reason.NO_STONES, svc.mix(withAxe, 1L, List.of()).reason, "没给石头");

        assertEquals(MixService.Reason.STONE_NOT_FOUND,
                svc.mix(withAxe, 1L, List.of(4242L)).reason, "石头不存在");

        ItemInstance notStone = item(7L, 0x01010100, ItemClass.ONE_HAND_WEAPON);
        assertEquals(MixService.Reason.STONE_NOT_ALLOWED,
                svc.mix(playerWith(axe, notStone), 1L, List.of(7L)).reason, "拿武器当材料 → 拒绝");

        ItemInstance one = item(11L, 0x02350100, ItemClass.SHELTOM);
        assertEquals(MixService.Reason.NO_RECIPE,
                svc.mix(playerWith(axe, one), 1L, List.of(11L)).reason, "1 颗 Lucidy ≠ 配方要求的 5 颗");

        ItemInstance held = item(1L, 0x01010100, ItemClass.ONE_HAND_WEAPON);
        held.setLocation(ItemLocations.EQUIP);
        held.setSlot(ItemLocations.HELD_SLOT);
        assertEquals(MixService.Reason.TARGET_NOT_IN_BAG,
                svc.mix(playerWith(held), 1L, List.of(11L)).reason, "目标必须在背包里（鼠标位不算）");

        assertEquals("item.op.mix.no-recipe", MixService.Reason.NO_RECIPE.key(), "协议 key（按 key 回滚，不匹配文案）");
    }

    // ---------------------------------------------------------------- 效果槽位数（⚠ 真 bug 的回归）

    /**
     * **第 7/8 效果槽必须生效** —— `mixlist` 有 8 组效果槽（`typeatributte` + `..8`），
     * 我第一版只读了 6 组 ⇒ 静默丢掉第 7 槽；实测有 **12 条**配方用它（位 0x10 生体抗 +1/+2/+3，
     * 描述里的 "Resistances +N"，`mixuniqueid` 603/608/…）。这条断言就是那次漏读的钉子。
     *
     * <p>走的是"行 → 配方 → 逐槽落到物品"这条链（`MixRecipe.of` 读列、`applyEffect` 写字段）——
     * 不能只测 `applyEffect`（那样第 7 槽没被**读进来**也照样过）。
     */
    @Test
    void 第七与第八效果槽也要生效() {
        MixList row = recipe(9, 909, 1, new int[]{5}, new int[][]{
                {MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT},
                {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
                {MixEffect.ORGANIC, 2, MixEffect.Kind.FLAT},   // ← 第 7 槽（真实数据就是这个形状）
                {MixEffect.FIRE, 3, MixEffect.Kind.FLAT},      // ← 第 8 槽（真实数据全 0，这里只为钉住读取）
        });
        MixRecipe parsed = MixRecipe.of(row);
        assertEquals(MixRecipe.EFFECT_SLOTS, parsed.slots().size(), "效果槽数必须是 8（= mixlist 的列数）");

        ItemInstance axe = item(1L, 0x01010100, ItemClass.ONE_HAND_WEAPON);
        MixService svc = serviceWith(row);
        for (MixRecipe.Slot slot : parsed.slots()) {
            if (slot.used()) {
                MixEffect.apply(axe, slot);
            }
        }
        assertEquals(15, axe.effectiveInt(ItemStat.DEFENCE), "第 1 槽（防御 +15）");
        assertEquals(2, axe.effectiveInt(ItemStat.RES_BIONIC), "第 7 槽（生体抗 +2）必须落进来 —— 漏读第 7 槽时这里是 0");
        assertEquals(3, axe.effectiveInt(ItemStat.RES_FIRE), "第 8 槽（火抗 +3）也必须落进来");
    }

    // ---------------------------------------------------------------- 预览（服务端下发信息）

    /**
     * 预览的两条硬性质：① **只算不写**（不改物品、不消费石头）② **预览的数就是真加的数**。
     *
     * <p>为什么这两条必须钉：预览与真合成是同一个 `match()` + 同一张 `applyEffect` switch，
     * 一旦有人把预览改写成"客户端自己算"或"另写一份算术"，就会出现
     * "窗口上显示 +15、物品实际 +12" 这种**两边都不报错**的分叉（本仓最贵的一类 bug）。
     */
    @Test
    void 预览只算不写且数值与真加一致() {
        MixList row = recipe(21, 921, 1, new int[]{2}, new int[][]{
                {MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT},
                {MixEffect.FIRE, 3, MixEffect.Kind.FLAT},
                {MixEffect.HP_REGEN, 3, MixEffect.Kind.FLAT},      // 小数**字段**、整数**值**（吸收 0.5 那类由另一条用例覆盖）
                {MixEffect.CRITICAL, 10, MixEffect.Kind.PERCENT},  // 第 4 槽：必杀 +10%（按当前值算）
        });
        // ⚠ 这里**刻意不写重复的位**：干跑是"逐槽读当前值"，重复位会少算一次
        //   （实测库里 283 条配方无一重复位，故不影响真实数据；改数据前先看 applyEffect 的 javadoc）。
        MixService svc = serviceWith(row);
        ItemInstance axe = item(1L, 0x01010100, ItemClass.ONE_HAND_WEAPON);
        axe.setDefence(100);
        axe.setResFire(1);
        axe.setCritical(10);
        ItemInstance s1 = item(11L, 0x02350100, ItemClass.SHELTOM);
        ItemInstance s2 = item(12L, 0x02350100, ItemClass.SHELTOM);
        Player p = playerWith(axe, s1, s2);

        MixService.Preview pv = svc.preview(p, 1L, List.of(11L, 12L));
        assertTrue(pv.ok(), "应命中：" + pv.reason);
        assertEquals("test#21", pv.recipeName, "配方名一并下发（客户端不查表）");
        assertEquals(4, pv.effects.size());
        assertEquals("mixe.defence", pv.effects.get(0).key(), "效果名走 key（文案在客户端）");
        assertEquals(100.0, pv.effects.get(0).before(), 1e-9);
        assertEquals(115.0, pv.effects.get(0).after(), 1e-9, "防御 +15 直加");
        assertEquals(3.0, pv.effects.get(2).value(), 1e-9, "效果值原样下发");
        assertFalse(pv.effects.get(2).intField(), "生命再生是小数位（客户端显示时保留小数）");

        // ① 只算不写
        assertEquals(100, axe.effectiveInt(ItemStat.DEFENCE), "预览不能改物品");
        assertEquals(1, axe.effectiveInt(ItemStat.RES_FIRE), "预览不能改物品");
        assertFalse(axe.getCraftMask() != 0, "预览不能写 craft_mask");
        assertNotNull(p.getItems().byUid(11L), "预览不能消费石头");

        // ② 预览的数 = 真加的数（同一条路径）
        MixEffect.apply(axe, new MixRecipe.Slot(MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT));
        assertEquals(pv.effects.get(0).after(), axe.effectiveInt(ItemStat.DEFENCE), 1e-9, "预览的 after 必须等于真写进去的值");

        // 百分比项按**当前值**算（这正是干跑必须读物品的原因）：必杀 10 + 10% = 11
        assertEquals(10.0, pv.effects.get(3).before(), 1e-9);
        assertEquals(11.0, pv.effects.get(3).after(), 1e-9);
    }

    /** 预览失败时也要回**原因 key**（客户端按 key 显示，不匹配文案）。 */
    @Test
    void 预览未命中回原因key() {
        MixService svc = serviceWith(recipe(31, 931, 1, new int[]{5},
                new int[][]{{MixEffect.DEFENCE, 15, MixEffect.Kind.FLAT}}));
        ItemInstance axe = item(1L, 0x01010100, ItemClass.ONE_HAND_WEAPON);
        ItemInstance one = item(11L, 0x02350100, ItemClass.SHELTOM);
        MixService.Preview pv = svc.preview(playerWith(axe, one), 1L, List.of(11L));
        assertFalse(pv.ok(), "只投 1 颗而配方要 5 颗 → 不命中");
        assertEquals(MixService.Reason.NO_RECIPE, pv.reason);
        assertEquals("item.op.mix.no-recipe", pv.reason.key());
        assertEquals(0, pv.effects.size(), "未命中就没有效果项");
    }
}
