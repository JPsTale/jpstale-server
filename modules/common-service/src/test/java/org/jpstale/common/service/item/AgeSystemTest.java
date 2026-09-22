package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.AgeList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锻造（Aging）核心特征测试：**掷点顺序**（EU `GetAgingResultType`）、**曲线行的等级偏移**、
 * 以及**按类型的属性增长**（EU `OnUpAge`，含"拳套按爪"）。
 *
 * <p>
 * 曲线数值取我们库里 `gamedb.agelist` 的真实行（例如 6 级：fail=4/plus2=35/minus2=2/minus1=97/broken=1；
 * 1 级：fail=0/plus2=60/broken=0 ⇒ 首次锻造必成功且 60% 跳级）。
 */
class AgeSystemTest {

    private static AgeList curve(int number, int fail, int plus2, int minus2, int minus1, int broken, int ageStone) {
        AgeList r = new AgeList();
        r.setAgeNumber(number);
        r.setFailChance(fail);
        r.setPlus2Chance(plus2);
        r.setMinus2Chance(minus2);
        r.setMinus1Chance(minus1);
        r.setBrokenChance(broken);
        r.setAgeStone(ageStone);
        return r;
    }

    /** 库里的 1 级 / 6 级两行（实测值）。 */
    private static AgeListService table() {
        return AgeListService.forRows(List.of(
                curve(1, 0, 60, 0, 0, 0, 0),
                curve(6, 4, 35, 2, 97, 1, 0)
        ));
    }

    private static ItemInstance item(int idCode) {
        return item(idCode, 1L);
    }

    private static ItemInstance item(int idCode, long uid) {
        ItemInstance it = new ItemInstance();
        it.setId(uid);
        it.setItemCode(idCode);
        it.setLocation(ItemLocations.BAG_PAGE);
        it.setTemplate(new org.jpstale.dao.gamedb.entity.ItemList());
        return it;
    }

    // ---------------------------------------------------------------- 曲线行偏移

    /** 等级 → 曲线行：EU 是 `AgeNumber = 等级 + 1`；越界返回 null（不兜底）。 */
    /** 派生属性重算器：测试里必须显式装配（否则读有效值只有基准值）*/
    @org.junit.jupiter.api.BeforeAll
    static void installDerivedStats() {
        ItemDerivedStats.installGlobal(MixRecipeService.forRows(java.util.List.of()));
    }

    @Test
    void 曲线行的等级偏移() {
        AgeListService t = table();
        assertEquals(1, t.rowForLevel(0).getAgeNumber(), "等级 0 → agenumber 1（EU: WHERE AgeNumber = sAgeLevel + 1）");
        assertEquals(6, t.rowForLevel(5).getAgeNumber(), "等级 5 → agenumber 6");
        assertNull(t.rowForLevel(6), "等级 6 在这份两行曲线里没有 → null（调用方按超出上限处理）");
        assertEquals(6, t.maxAgeNumber(), "curve 覆盖到 agenumber 6");
        assertEquals(5, t.maxLevel(), "最高可锻造等级 = 最大 agenumber − 1（EU: AgeNumber = 等级 + 1）");
    }

    // ---------------------------------------------------------------- 掷点

    /** **用锻造石必成功**：`agestone` 列（我们库里全 0）= 用石时的失败率 ⇒ 永不进失败分支。 */
    @Test
    void 用锻造石必成功() {
        AgeList row = curve(6, 60, 35, 30, 55, 15, 0);   // fail=60 但 agestone=0
        assertEquals(AgeRoll.Result.PLUS_ONE,
                AgeRoll.roll(5, AgeRoll.STONE_AGING, row, 20, false, 0, 99), "用石：r1=0 也不失败");
        assertEquals(AgeRoll.Result.PLUS_TWO,
                AgeRoll.roll(5, AgeRoll.STONE_AGING, row, 20, false, 0, 10), "用石：r2<plus2 ⇒ 跳级");
        assertEquals(60, AgeRoll.failChanceOf(AgeRoll.STONE_NONE, row), "不用石时用 failchance");
        assertEquals(0, AgeRoll.failChanceOf(AgeRoll.STONE_AGING, row), "用石时用 agestone");
    }

    /** 失败分支的**顺序**：先 −1 → 再 −2 → 铜矿保命 → 活动保命 → 否则破坏（EU 逐字）。 */
    @Test
    void 失败分支的顺序() {
        // 6 级：fail=4（r1<4 即失败）、minus1=97、minus2=2
        AgeList r6 = curve(6, 4, 35, 2, 97, 1, 0);
        assertEquals(AgeRoll.Result.MINUS_ONE,
                AgeRoll.roll(5, AgeRoll.STONE_NONE, r6, 20, false, 3, 10), "r2 < minus1(97) → −1");
        assertEquals(AgeRoll.Result.MINUS_TWO,
                AgeRoll.roll(5, AgeRoll.STONE_NONE, r6, 20, false, 3, 98), "r2 扣掉 minus1 后 < minus2(2) → −2");
        assertEquals(AgeRoll.Result.DESTRUCTION,
                AgeRoll.roll(5, AgeRoll.STONE_NONE, r6, 20, false, 3, 99), "兜底段 → 破坏");

        // 换成铜矿：同样的兜底段 → 不破坏，只 −1
        assertEquals(AgeRoll.Result.MINUS_ONE_COPPER_ORE,
                AgeRoll.roll(5, AgeRoll.STONE_COPPER_ORE, r6, 20, false, 3, 99), "铜矿保命（EU: MinusOneCopperOre）");
        // 活动保护同理
        assertEquals(AgeRoll.Result.MINUS_ONE_NO_BREAK_EVENT,
                AgeRoll.roll(5, AgeRoll.STONE_NONE, r6, 20, true, 3, 99), "活动免破坏");

        // 1 级：fail=0 ⇒ 永不失败
        AgeList r1 = curve(1, 0, 60, 0, 0, 0, 0);
        assertEquals(AgeRoll.Result.PLUS_ONE, AgeRoll.roll(0, AgeRoll.STONE_NONE, r1, 20, false, 99, 99));
    }

    /** 跳级不能越上限（EU：`if (iAgingLevel + 1 <= iAgeTotal)` 才 +2，否则降级为 +1）。 */
    @Test
    void 跳级不越上限() {
        AgeList row = curve(1, 0, 60, 0, 0, 0, 0);
        assertEquals(AgeRoll.Result.PLUS_TWO, AgeRoll.roll(5, AgeRoll.STONE_NONE, row, 20, false, 99, 10), "5→7 允许");
        assertEquals(AgeRoll.Result.PLUS_ONE, AgeRoll.roll(19, AgeRoll.STONE_NONE, row, 20, false, 99, 10),
                "19→21 越上限 ⇒ 只能 +1");
    }

    // ---------------------------------------------------------------- 流程（投石 / 战斗养）

    /** 投石流程的拒绝路径（都可分辨；都不落库 ⇒ 可在 storage=null 下测）。 */
    @Test
    void 投石流程的拒绝原因() {
        AgeListService curves = table();
        AgeService svc = new AgeService(curves, null);

        Player empty = new Player(0);
        empty.setCharacterId(1L);
        assertEquals(AgeService.Reason.TARGET_NOT_FOUND, svc.ageWithStone(empty, 999L, 1L).reason, "目标不存在");

        // 目标不是装备（材料石自己）
        ItemInstance stoneTarget = item(0x02350100);
        stoneTarget.getTemplate().setClassItem(ItemClass.SHELTOM);
        Player p1 = playerWith(stoneTarget, -1);
        assertEquals(AgeService.Reason.TARGET_NOT_EQUIPMENT,
                svc.ageWithStone(p1, 1L, 1L).reason, "材料石不能当锻造目标");

        // 目标在鼠标位（不在背包）
        ItemInstance held = item(0x01010100);
        held.getTemplate().setClassItem(ItemClass.ONE_HAND_WEAPON);
        held.setLocation(ItemLocations.EQUIP);
        held.setSlot(ItemLocations.HELD_SLOT);
        Player p2 = playerWith(held, -1);
        assertEquals(AgeService.Reason.TARGET_NOT_IN_BAG,
                svc.ageWithStone(p2, 1L, 1L).reason, "目标必须在背包里");

        // 石头不是材料石
        ItemInstance axe = item(0x01010100);
        axe.getTemplate().setClassItem(ItemClass.ONE_HAND_WEAPON);
        ItemInstance notStone = item(0x01010100, 2L);
        notStone.getTemplate().setClassItem(ItemClass.ONE_HAND_WEAPON);
        Player p3 = playerWith(axe, notStone);
        assertEquals(AgeService.Reason.STONE_NOT_ALLOWED,
                svc.ageWithStone(p3, 1L, 2L).reason, "拿武器当石头 → 拒绝");

        // 已到上限（这份曲线只到 agenumber 6 ⇒ 最高等级 5）
        ItemInstance maxed = item(0x01010100);
        maxed.getTemplate().setClassItem(ItemClass.ONE_HAND_WEAPON);
        maxed.setAgingNum(5);
        ItemInstance st = item(0x02350100, 9L);
        st.getTemplate().setClassItem(ItemClass.SHELTOM);
        Player p4 = playerWith(maxed, st);
        assertEquals(AgeService.Reason.MAX_LEVEL, svc.ageWithStone(p4, 1L, 9L).reason, "等级已到曲线覆盖的上限");

        // 协议 key
        assertEquals("item.op.age.max-level", AgeService.Reason.MAX_LEVEL.key());
    }

    /** 石头类型映射：Aging Stone=用石必成功那条曲线、Copper Ore=保命、其它材料石=普通（有风险）。 */
    @Test
    void 石头类型映射() {
        assertEquals(AgeRoll.STONE_AGING, AgeService.stoneTypeOf(0x080b0a00), "Aging Stone");
        assertEquals(AgeRoll.STONE_COPPER_ORE, AgeService.stoneTypeOf(0x080b0b00), "Copper Ore（失败不破坏）");
        assertEquals(AgeRoll.STONE_MAX_AGE, AgeService.stoneTypeOf(0x080b3400), "Mature Stone (A) = 一键拉满石");
        assertEquals(AgeRoll.STONE_NONE, AgeService.stoneTypeOf(0x02350100), "普通材料石（Lucidy）");
        assertEquals(AgeRoll.STONE_NONE, AgeService.stoneTypeOf(null));
        assertTrue(AgeService.isMaterialStone(0x02350100), "Lucidy 是材料石");
        assertTrue(AgeService.isMaterialStone(0x02350e00), "Oredo 是材料石");
        assertFalse(AgeService.isMaterialStone(0x02350f00), "第 15 档不是（我们只有 14 颗）");
        assertFalse(AgeService.isMaterialStone(0x03060100), "力量石不是材料石");
    }

    /**
     * 战斗养阈值 = 原版**按家族/等级查表**（ex-machina `sinTrade.cpp:197-205` 的 `_W_SERVER` 块）。
     * ⚠ 这里原本断言的是我们自造的 `30 + 5×等级`（已删）—— 换成表的真实值，
     * 并顺带钉住"匕首/拳套归 Critical、图腾归 Attack"这三条**由我们定**的映射（依据见 `MatureProgress` 类注释）。
     */
    @Test
    void 战斗养阈值按家族查表() {
        // 剑 sinWS2 → Critical 表
        assertEquals(12, MatureProgress.needFor(0x01070100, 0), "剑 +0 要 12 点");
        assertEquals(21, MatureProgress.needFor(0x01070100, 2), "剑 +2 要 21 点（我自造的公式给的是 40）");
        assertEquals(1730, MatureProgress.needFor(0x01070100, 99), "超表长夹到表尾");
        // 斧 sinWA1 → Attack 表
        assertEquals(100, MatureProgress.needFor(0x01010100, 0));
        assertEquals(169, MatureProgress.needFor(0x01010100, 2));
        // 盾 → Block；甲/法球/靴/护手/护腕 → Hit
        assertEquals(15, MatureProgress.needFor(0x02040100, 0), "盾");
        assertEquals(75, MatureProgress.needFor(0x02010100, 2), "甲 +2 要 75 点");
        assertEquals(75, MatureProgress.needFor(0x03030100, 2), "法球与甲同表");
        // ★ 我们定的三族（ex-machina 是 8 职业时代，这三族那会儿还没有）
        assertEquals(21, MatureProgress.needFor(0x010A0100, 2), "匕首 WD 按剑/爪 → Critical");
        assertEquals(21, MatureProgress.needFor(0x010B0100, 2), "拳套 WV 按爪 → Critical");
        assertEquals(169, MatureProgress.needFor(0x01090100, 2), "图腾 WN 按杖 → Attack");
        // 未列出的家族 → 源码 else 支的 60*20
        assertEquals(1200, MatureProgress.needFor(0x09090100, 0), "未登记家族 → 1200");
        assertEquals(1200, MatureProgress.needFor(null, 0), "空码 → 1200（不猜）");
    }

    /**
     * **掉级不需要"反向函数"**（2026-09-22 重构后的核心性质）：
     * 属性是读时按 N 重算的，所以"降到 N−k"就是"用 N−k 重算一次" —— **精确**回到原值，
     * 不存在原版那套 `DownDamage/DownCritical/DownDefense` 的取整/等级相关抵消误差
     * （见 `ItemStat` 与 `docs/打造系统-实现与UI.md §10`）。
     */
    @Test
    void 掉级等价于用低等级重算() {
        // 同一件拳套（家族 0x010B = 爪）：先算 +4，再算 +2，值必须与"直接就是 +2"完全一致
        ItemInstance up4 = item(0x010b0100);
        withLevel(up4, 4);
        ItemInstance up2 = item(0x010b0100);
        withLevel(up2, 2);
        ItemInstance straight2 = item(0x010b0100);
        withLevel(straight2, 2);
        assertEquals(straight2.effectiveInt(ItemStat.DAMAGE_MIN), up2.effectiveInt(ItemStat.DAMAGE_MIN),
                "先升到 +4 再降到 +2 == 直接 +2（伤害）");
        assertEquals(straight2.effectiveInt(ItemStat.ATTACK_RATING), up2.effectiveInt(ItemStat.ATTACK_RATING),
                "命中同样精确");
        assertEquals(straight2.effectiveInt(ItemStat.CRITICAL), up2.effectiveInt(ItemStat.CRITICAL), "必杀同样精确");
        // +4 比 +2 高（顺带确认等级真的起作用）
        assertTrue(up4.effectiveInt(ItemStat.DAMAGE_MIN) > up2.effectiveInt(ItemStat.DAMAGE_MIN));

        // 一次升两级（+0 → +2，跳级档）也与"逐级升两次"一致
        ItemInstance jump = item(0x010b0100);
        withLevel(jump, 2);
        assertEquals(straight2.effectiveInt(ItemStat.DAMAGE_MIN), jump.effectiveInt(ItemStat.DAMAGE_MIN));
        assertEquals(straight2.effectiveInt(ItemStat.CRITICAL), jump.effectiveInt(ItemStat.CRITICAL));

        // 盾的法球/吸收（百分比成长）也一样：降到 0 必须**精确**回到基准值
        ItemInstance shield = item(0x02040100);
        shield.setDefence(100);
        shield.setAbsorb(3.0);
        withLevel(shield, 1);
        assertTrue(shield.effectiveInt(ItemStat.DEFENCE) > 100, "升一级防御 +5%");
        withLevel(shield, 0);
        assertEquals(100, shield.effectiveInt(ItemStat.DEFENCE), "降到 +0 精确回到基准 100（无反向误差）");
        assertEquals(3.0, shield.effective(ItemStat.ABSORB), 1e-9, "吸收同样精确回到 3.0");
    }

    /** 把装备置为"恰好 +N 级"并按 N 重算（等价于服务端加载时的做法）。 */
    private static void withLevel(ItemInstance it, int level) {
        it.setAgingNum(level);
        it.markDerivedDirty();
        it.effective(ItemStat.DAMAGE_MIN);      // 触发一次重算
    }

    /** 一键拉满那颗石 → 目标类别（服务端自己找槽位；码位与源码差一档，按位置对应 —— 见 AgeService 注释）。 */
    @Test
    void 一键拉满石的目标类别() {
        assertEquals(0, AgeService.maxAgeKindOf(0x080b3400), "Mature Stone (A) → 武器");
        assertEquals(1, AgeService.maxAgeKindOf(0x080b3500), "Mature Stone (B) → 盾/法球");
        assertEquals(2, AgeService.maxAgeKindOf(0x080b3600), "Mature Stone (C) → 甲/法袍");
        assertEquals(-1, AgeService.maxAgeKindOf(0x080b0a00), "Aging Stone 不是拉满石");
        assertEquals(-1, AgeService.maxAgeKindOf(0x02350100), "材料石不是拉满石");
        assertEquals(-1, AgeService.maxAgeKindOf(null));
    }

    /** 拉满流程的拒绝路径：没有对应类别的已装备件 → NO_TARGET_EQUIPPED（不静默、不随便挑一件）。 */
    @Test
    void 一键拉满要求目标已装备() {
        AgeService svc = new AgeService(table(), null);
        Player p = new Player(0);
        p.setCharacterId(1L);
        ItemInstance stone = item(0x080b3400, 5L);          // Mature Stone (A)：找**武器**槽
        stone.getTemplate().setClassItem(ItemClass.SHELTOM);
        stone.setSlot(0);
        p.getItems().byUidPut(stone);
        assertEquals(AgeService.Reason.NO_TARGET_EQUIPPED,
                svc.useMaxAgeStone(p, 5L).reason, "武器槽空着 ⇒ 不能拉满");

        // 石头不在（uid 不存在）
        assertEquals(AgeService.Reason.STONE_NOT_FOUND, svc.useMaxAgeStone(p, 999L).reason);
        // 拿非拉满石来用
        ItemInstance notMax = item(0x02350100, 6L);
        notMax.getTemplate().setClassItem(ItemClass.SHELTOM);
        notMax.setSlot(1);
        p.getItems().byUidPut(notMax);
        assertEquals(AgeService.Reason.STONE_NOT_ALLOWED, svc.useMaxAgeStone(p, 6L).reason);
    }

    /** 辅助：造玩家并把给定物品放进背包（每件占一槽；`skipSlotOf` 可指定已占槽的件 -1 表示不占）。 */
    private static Player playerWith(ItemInstance first, int sentinel) {
        Player p = new Player(0);
        p.setCharacterId(1L);
        int slot = 0;
        if (first.getLocation() == ItemLocations.BAG_PAGE) {
            first.setSlot(slot++);
        }
        p.getItems().byUidPut(first);
        return p;
    }

    private static Player playerWith(ItemInstance a, ItemInstance b) {
        Player p = new Player(0);
        p.setCharacterId(1L);
        int slot = 0;
        for (ItemInstance it : new ItemInstance[]{a, b}) {
            if (it.getLocation() == ItemLocations.BAG_PAGE) {
                it.setSlot(slot++);
            }
            p.getItems().byUidPut(it);
        }
        return p;
    }

    /** 等级变化量与"是否破坏"的换算。 */
    @Test
    void 等级变化量() {
        assertEquals(1, AgeRoll.Result.PLUS_ONE.levelDelta());
        assertEquals(2, AgeRoll.Result.PLUS_TWO.levelDelta());
        assertEquals(-1, AgeRoll.Result.MINUS_ONE.levelDelta());
        assertEquals(-1, AgeRoll.Result.MINUS_ONE_COPPER_ORE.levelDelta());
        assertEquals(-2, AgeRoll.Result.MINUS_TWO.levelDelta());
        assertTrue(AgeRoll.Result.DESTRUCTION.broke());
        assertFalse(AgeRoll.Result.MINUS_ONE_COPPER_ORE.broke());
    }

    // ---------------------------------------------------------------- 属性增长

    /** ★ **拳套（WV）按爪处理**：与真爪（WC）**逐项相同** —— 伤害、命中 +5、必杀（奇数级）。 */
    @Test
    void 拳套与爪的增长完全一致() {
        ItemInstance claw = item(0x01020100);
        ItemInstance knuckle = item(0x010b0100);
        AgeGrowth.apply(claw, 1);
        AgeGrowth.apply(knuckle, 1);
        assertEquals(claw.effectiveInt(ItemStat.DAMAGE_MIN), knuckle.effectiveInt(ItemStat.DAMAGE_MIN), "伤害 min 相同");
        assertEquals(claw.effectiveInt(ItemStat.DAMAGE_MAX), knuckle.effectiveInt(ItemStat.DAMAGE_MAX), "伤害 max 相同");
        assertEquals(claw.effectiveInt(ItemStat.ATTACK_RATING), knuckle.effectiveInt(ItemStat.ATTACK_RATING), "命中相同");
        assertEquals(claw.effectiveInt(ItemStat.CRITICAL), knuckle.effectiveInt(ItemStat.CRITICAL), "必杀相同");
        assertEquals(1, knuckle.effectiveInt(ItemStat.DAMAGE_MIN), "0 级升 1 级：伤害 +1");
        assertEquals(5, knuckle.effectiveInt(ItemStat.ATTACK_RATING), "命中 +5（照 EU 的 Claw 分支）");
        assertEquals(1, knuckle.effectiveInt(ItemStat.CRITICAL), "旧等级 1 是奇数 ⇒ 必杀 +1");
    }

    /** 伤害的 ≥9 级加码；奇数级才涨必杀/格挡。 */
    @Test
    void 伤害九级加码与奇数级规则() {
        ItemInstance axe = item(0x01010100);          // 斧：伤害 + 命中10（无必杀）
        AgeGrowth.apply(axe, 0);
        assertEquals(1, axe.effectiveInt(ItemStat.DAMAGE_MIN), "0 级：+1");
        assertEquals(0, axe.effectiveInt(ItemStat.CRITICAL), "斧不吃必杀");
        AgeGrowth.apply(axe, 9);
        assertEquals(3, axe.effectiveInt(ItemStat.DAMAGE_MIN), "9 级：再 +1（伤害共 +2）");
        assertEquals(20, axe.effectiveInt(ItemStat.ATTACK_RATING), "命中固定 +10 —— apply 了两次（0 级与 9 级各一次）⇒ 20");

        ItemInstance sword = item(0x01070100);        // 剑：命中5 + 必杀（奇数级）
        AgeGrowth.apply(sword, 2);
        assertEquals(0, sword.effectiveInt(ItemStat.CRITICAL), "旧等级 2 是偶数 ⇒ 必杀不加");
        AgeGrowth.apply(sword, 3);
        assertEquals(1, sword.effectiveInt(ItemStat.CRITICAL), "旧等级 3 是奇数 ⇒ 必杀 +1");
    }

    /** 盾/甲：防御**按当前值百分比**、吸收固定、格挡奇数级。 */
    @Test
    void 盾与甲的防御按当前值百分比() {
        ItemInstance shield = item(0x02040100);
        shield.setDefence(100);
        AgeGrowth.apply(shield, 1);
        assertEquals(105, shield.effectiveInt(ItemStat.DEFENCE), "防御 +5% of 100 = +5");
        assertEquals(1.0, shield.effective(ItemStat.BLOCK_RATING), 1e-9, "奇数级 ⇒ 格挡 +1");
        assertEquals(0.4, shield.effective(ItemStat.ABSORB), 1e-9, "<9 级 ⇒ 吸收 +0.4");

        AgeGrowth.apply(shield, 9);                   // 旧等级 9：伤害类不适用，但 ≥9 的吸收加码
        assertEquals(110, shield.effectiveInt(ItemStat.DEFENCE), "105 + 5% of 105 = 105+5 = 110（按当前值，复利）");
        assertEquals(1.2, shield.effective(ItemStat.ABSORB), 1e-9, "吸收：1 级 +0.4、9 级 ≥9 再 +0.4×2 ⇒ 1.2");

        ItemInstance armor = item(0x02010100);
        armor.setDefence(50);
        AgeGrowth.apply(armor, 0);
        assertEquals(53, armor.effectiveInt(ItemStat.DEFENCE), "防御 +5% of 50 = 2.5 → round → 3");
        assertEquals(0.5, armor.effective(ItemStat.ABSORB), 1e-9);
    }

    /** 靴/护手/护腕这类：EU 的 switch 里没有 case ⇒ **不涨属性**（`grows()` 要能提前判出来）。 */
    @Test
    void 无增长的类型要能提前判出() {
        assertFalse(AgeGrowth.grows(0x02020100), "靴不涨");
        assertFalse(AgeGrowth.grows(0x02030100), "护手不涨");
        assertFalse(AgeGrowth.grows(0x03020100), "护腕不涨");
        assertTrue(AgeGrowth.grows(0x010b0100), "★ 拳套涨（按爪）");
        assertTrue(AgeGrowth.grows(0x03030100), "法球涨");

        ItemInstance boots = item(0x02020100);
        boots.setDefence(10);
        AgeGrowth.apply(boots, 5);
        assertEquals(10, boots.effectiveInt(ItemStat.DEFENCE), "不涨属性的类型：apply 后无变化");
        assertEquals(0, boots.effectiveInt(ItemStat.DAMAGE_MIN));
    }
    /** 养成要交的金币（EU `ItemServer::GetItemAgingPrice` = round(售价 × (等级+1) / 2)）。 */
    @Test
    void 养成金币价格照EU公式() {
        ItemInstance it = item(0x01070900);
        it.setPrice(1000);
        it.setAgingNum(0);
        assertEquals(500, AgeService.agingGoldPrice(it), "0 级：1000 × 1 / 2");
        it.setAgingNum(2);
        assertEquals(1500, AgeService.agingGoldPrice(it), "2 级：1000 × 3 / 2");
        it.setAgingNum(5);
        assertEquals(3000, AgeService.agingGoldPrice(it), "5 级：1000 × 6 / 2");
        it.setPrice(0);
        assertEquals(0, AgeService.agingGoldPrice(it), "售价 0 ⇒ 不收费（原版 round(0)=0）");
    }
    /** 生效需求等级 = 基底 + floor(N/2)，**无 88 豁免**（用户 2026-09-23 明确我们不做那个机制）。 */
    @Test
    void 生效需求等级按每两级加一() {
        ItemInstance it = item(0x01070900);
        it.setReqLevel(30);
        it.setAgingNum(0);
        assertEquals(30, AgeService.effectiveReqLevel(it), "+0");
        it.setAgingNum(1);
        assertEquals(30, AgeService.effectiveReqLevel(it), "+1 不加");
        it.setAgingNum(2);
        assertEquals(31, AgeService.effectiveReqLevel(it), "+2 加一");
        it.setAgingNum(5);
        assertEquals(32, AgeService.effectiveReqLevel(it), "+5 加二");
        // ★ 基底 90 时照涨（没有"88 以上豁免"）
        it.setReqLevel(90);
        it.setAgingNum(10);
        assertEquals(95, AgeService.effectiveReqLevel(it), "基底 90 + 5 ⇒ 95（我们不做 88 豁免）");
        assertEquals(90, it.getReqLevel(), "存储值始终是基底，不被改写");
    }
    /** 每级要投的 12 格石头（原版 `AgingLevelSheltom`；索引 = 当前等级）。 */
    @Test
    void 每级宝石表照原版() {
        // 颗数规律（照表数出来）：+0..7 每级 +1 颗（5→12），+8 掉回 11，之后 11/12 交替
        assertEquals(5, AgingMaterial.countAt(0), "+0 ⇒ 5 格");
        assertEquals(6, AgingMaterial.countAt(1), "+1 ⇒ 6 格");
        assertEquals(7, AgingMaterial.countAt(2), "+2 ⇒ 7 格");
        assertEquals(12, AgingMaterial.countAt(7), "+7 ⇒ 满 12 格");
        assertEquals(11, AgingMaterial.countAt(8), "+8 ⇒ 11 格（表里从这行起 12→11 起步）");
        assertEquals(12, AgingMaterial.countAt(9), "+9 ⇒ 12 格");
        assertArrayEquals(new int[]{3, 3, 4, 4, 5, 0, 0, 0, 0, 0, 0, 0}, AgingMaterial.slotsAt(0), "+0 的档位");
        assertArrayEquals(new int[]{4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9}, AgingMaterial.slotsAt(9), "+9 的档位");
        assertArrayEquals(new int[]{9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14}, AgingMaterial.slotsAt(19), "+19 的档位（上限）");
        assertEquals(0, AgingMaterial.countAt(20), "超出表（≥20 级）⇒ 空表（不兜底）");
        assertEquals(20, AgingMaterial.levels(), "表覆盖 20 级");
    }
}
