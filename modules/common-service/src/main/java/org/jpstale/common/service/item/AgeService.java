package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.AgeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 锻造（Aging）的**流程**：投石判定 + 战斗养进度 → 升级/降级/破坏 → 落库。
 *
 * <p>
 * 依据：EU `Server/server/itemserver.cpp`（曲线 + 掷点结果处理）与 `AgeHandler.cpp`（增长），
 * 见 `docs/锻造与合成-源码分析.md` §3.13 与 `docs/锻造与合成-我们的实现方案.md` §3。
 *
 * <h3>两条路径（都通向同一个 {@link AgeRoll}）</h3>
 * <ol>
 *   <li><b>投石</b>（{@link #ageWithStone}）：1 颗材料石 = 1 次判定。用 **Aging Stone** 时失败率取
 *       `agelist.agestone`（我们库里全 0 ⇒ **必成功**），用 **Copper Ore** 时失败不破坏（只 −1）。</li>
 *   <li><b>战斗养</b>（{@link #addBattleProgress}）：命中/格挡/被击各 +1 进度，进度满自动判定一次
 *       （走**普通曲线** = 最险那一套），然后进度清零。门槛 `|等级差| ≤ 10` 由调用方（战斗侧）判。</li>
 * </ol>
 *
 * <h3>我们定的两处（源码里读不到，已在文档写明）</h3>
 * <ul>
 *   <li><b>战斗养的每级阈值</b> {@link #battleNeedFor}：`30 + 5 × 当前等级`。
 *       EU 源码里只读到**任务武器**那套 30/40/50/100/300（`questserver.cpp:229-259`），
 *       战斗事件里的增量点**没读到**（只在客户端/被删文件里）—— 故这是我们的数值。</li>
 *   <li><b>等级上限</b>取曲线覆盖的最大 `agenumber`（现在 20；扩到 +30 只需往 `agelist` 加行，
 *       代码不用改）。</li>
 * </ul>
 * 其余照抄：耐久（成功 −1、失败 −3（≤9 级）/ −5（>9 级），EU `itemserver.cpp:2596-2670`）、
 * 每 2 级物品等级 +1（EU `AgeHandler.cpp:455-467` 的 `sAgeLevel % 2 == 0`）、
 * 破坏 = 销毁物品、升级写 `kind_code = ITEM_KIND_AGING(2)`。
 */
@Service
public class AgeService {

    private static final Logger log = LoggerFactory.getLogger(AgeService.class);

    /** 材料石家族（OS 族 `sinOS1 = 0x02350000`，14 档）—— 与合成共用那 14 颗（EU `iaSheltomAgingList` 也是它们）。 */
    private static final int STONE_FAMILY = 0x02350000;

    private final AgeListService curves;
    private final ItemStorageService storage;

    public AgeService(AgeListService curves, ItemStorageService storage) {
        this.curves = curves;
        this.storage = storage;
    }

    /** 失败/拒绝原因（协议按 {@link #key()} 走，不匹配文案）。 */
    public enum Reason {
        OK, TARGET_NOT_FOUND, TARGET_NOT_IN_BAG, TARGET_NOT_EQUIPMENT, STONE_NOT_FOUND, STONE_NOT_ALLOWED,
        MAX_LEVEL, NO_CURVE, NO_TARGET_EQUIPPED;

        public String key() {
            return "item.op.age." + name().toLowerCase().replace('_', '-');
        }
    }

    /** 结果。 */
    public static final class Result {
        public final Reason reason;
        public final ItemInstance target;
        public final AgeRoll.Result roll;
        /** 是否破坏（物品已销毁）。 */
        public final boolean broke;

        private Result(Reason reason, ItemInstance target, AgeRoll.Result roll) {
            this.reason = reason;
            this.target = target;
            this.roll = roll;
            this.broke = roll != null && roll.broke();
        }

        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private static Result fail(Reason r) {
        return new Result(r, null, null);
    }

    /*
     * ⚠ 这里原本是一个**我们自造**的公式 `30 + 5 × 等级`（2026-09-22 上午写的），当天核对源码后**删掉**：
     * 原版的熟练度阈值是**按家族、按等级查表**的（ex-machina `sinTrade.cpp:197-205` 的 `_W_SERVER` 块，
     * 用 `ItemAgingCount[1] = AgingLevelXxx[ItemAgingNum[0]]` 取），表在 {@link MatureProgress}。
     * 也就是说"练满一格要打多少下"在原版是**数据**，不是我该拍脑袋的地方 —— 形状我猜对了（随等级增长），
     * 数值与任何来源都对不上（剑在 +2 时原版要 **21** 点，我写了 40）。
     */

    // ------------------------------------------------------------------ 投石

    /**
     * 投一颗石头，立刻判定一次。
     *
     * @param stoneUid 材料石（OS 族那 14 颗）；用 Aging Stone（`0x080B0A00`）时失败率取 `agestone` 列，
     *                 用 Copper Ore（`0x080B0B00`）时失败不破坏
     */
    public Result ageWithStone(Player player, long targetUid, long stoneUid) {
        PlayerItems items = player.getItems();
        if (items == null) {
            return fail(Reason.TARGET_NOT_FOUND);
        }
        ItemInstance target = items.byUid(targetUid);
        if (target == null || target.isDeleted()) {
            return fail(Reason.TARGET_NOT_FOUND);
        }
        if (target.getLocation() != ItemLocations.BAG_PAGE) {
            return fail(Reason.TARGET_NOT_IN_BAG);
        }
        if (!isEquipment(target)) {
            return fail(Reason.TARGET_NOT_EQUIPMENT);
        }
        ItemInstance stone = items.byUid(stoneUid);
        if (stone == null || stone.isDeleted() || stone.getLocation() != ItemLocations.BAG_PAGE) {
            return fail(Reason.STONE_NOT_FOUND);
        }
        if (!isMaterialStone(stone.getItemCode())) {
            return fail(Reason.STONE_NOT_ALLOWED);
        }
        int stoneType = stoneTypeOf(stone.getItemCode());
        int level = target.getAgingNum();
        AgeList row = curves.rowForLevel(level);
        if (row == null) {
            return fail(Reason.NO_CURVE);
        }
        int ageMax = curves.maxLevel();
        if (level >= ageMax) {
            return fail(Reason.MAX_LEVEL);
        }

        // 消耗石头（先扣，判定失败也已消耗 —— 原版就是"投进去就没了"）
        items.byUidRemove(stone.getId());
        stone.setDeleted(true);
        storage.softDelete(stone.getId());

        AgeRoll.Result rr = rollOnce(level, stoneType, row, ageMax);
        if (rr.broke() || rr.levelDelta() < 0) {
            // 失败档：降级 / 破坏（材料已扣，不退还 —— 原版"投进去就没了"）—— 走同一条降级/销毁处理
            apply(player, target, level, rr, "养成失败");
            return new Result(Reason.OK, target, rr);
        }
        // ★ **成功 = 只给"养成资格"**（用户 2026-09-22 确认的机制）：
        //   `kind_code = AGING`、本轮阈值写进 `aging_exp_max`、`cur = 0`，**等级不变**（`ageNum` 只在练满时 +1）。
        //   ⚠ 返回的 `roll` 必须是 **null**：粒子的判据是"`ageNum` 上升"（`roll.levelDelta() > 0`），
        //     拿资格不是升级 ⇒ 不能借这条路把粒子播出去。
        target.setKindCode(ItemKind.AGING);
        target.setAgingExp(0);
        target.setAgingExpMax(MatureProgress.needFor(target.getItemCode(), level));
        target.markDerivedDirty();
        storage.update(target);
        log.info("[Age] {} 的 uid={}（{}）在 +{} 拿到养成资格（本轮需 {} 点熟练度，打怪填满即 +{}）",
                player.getName(), target.getId(), target.name(), level, target.getAgingExpMax(), level + 1);
        return new Result(Reason.OK, target, null);
    }

    // ------------------------------------------------------------------ 战斗养

    /**
     * 战斗事件推进进度（命中/格挡/被击各 +1，见类注释）；进度满则**自动判定一次**（普通曲线）并清零。
     *
     * @param amount 本次加多少（通常 1）
     * @return 本次发生了判定的结果；`null` = 只是涨了进度、还没满（调用方不必推包）
     */
    public Result addBattleProgress(Player player, long targetUid, int amount) {
        PlayerItems items = player.getItems();
        if (items == null || amount <= 0) {
            return null;
        }
        ItemInstance target = items.byUid(targetUid);
        if (target == null || target.isDeleted() || !isEquipment(target)) {
            return null;
        }
        if (target.getKindCode() != ItemKind.AGING) {
            return null;
        }
        if (target.getAgingExpMax() <= 0) {
            return null;
        }

        int level = target.getAgingNum();
        AgeList row = curves.rowForLevel(level);
        if (row == null) {
            log.warn("[Age] 战斗养跳过：uid={}（{}）在 +{} 找不到曲线行（agelist 的 agenumber={} 缺失）⇒ 进度不再累积",
                    target.getId(), target.name(), level, level + 1);
            return null;
        }
        int need = target.getAgingExpMax() > 0 ? target.getAgingExpMax()
                : MatureProgress.needFor(target.getItemCode(), level);
        int cur = target.getAgingExp() + amount;
        log.info("[Age] 战斗养进度：uid={}（{}，+{}）{}/{} → {}/{}（装备中={}）",
                target.getId(), target.name(), level, target.getAgingExp(), need, cur, need,
                target.getLocation() == ItemLocations.EQUIP);
        if (cur < need) {
            target.setAgingExp(cur);
            target.setAgingExpMax(need);
            storage.update(target);
            // ⚠ 返回**非 null**（roll=null，表示"只是累积、没掷点"）：调用方据此把物品推给客户端，
            //   否则客户端的进度条只在登录那一刻是准的，之后一直不动、直到某次升级才突然跳一大截
            //   （用户 2026-09-22 实测："途中没看到熟练度上升，突然就 +7 了"）。
            return new Result(Reason.OK, target, null);
        }
        // ★ **熟练度满 = 这一轮养成的结束 ⇒ 直接 +1**（用户 2026-09-22 澄清的机制：
        //   "玩家先去交宝石和金币，这时候才有了练武器的资格；武器练满之后，这一次养成才结束，ageNum 才 +1"）。
        //   ⇒ 费用是**养成开始前**交的（见 `startMaturing`），满的时候**不再收任何东西**、
        //     也**不掷 `agelist` 的失败/破坏**（原版成熟路径就是 `OnUpgradeAgingItem → OnUpAge`，直接晋升）。
        //   ⚠ 我上一轮把这里改成"夹住等玩家去 NPC 投石"，是把机制读反了 ✗。
        target.setAgingExp(0);                                   // 照原版 `OnUpAge`：成熟条清零
        target.setAgingExpMax(0);
        AgeRoll.Result rr = AgeRoll.Result.PLUS_ONE;             // 成熟完成 = 必成 +1（无失败/破坏）
        apply(player, target, level, rr, "成熟满");
        // ⚠ 不再需要额外的"满"标记：粒子的判据就是 `roll.levelDelta() > 0`（= `ageNum` 上升）。
        return new Result(Reason.OK, target, rr);
    }

    // ------------------------------------------------------------------ 战斗养（三条事件）

    /**
     * 攻击命中（普攻或暴击）→ 喂**主手武器**（EU：暴击走必杀系曲线、普攻走攻击系 —— 我们单一曲线，
     * 故这里只体现"喂主手"这一层；曲线的分系留给将来的多曲线扩展）。
     *
     * @param crit 是否暴击（记录用；曲线选择见类注释）
     */
    public Result onAttack(Player player, boolean crit) {
        ItemInstance main = equippedIn(player, ItemLocations.SLOT_MAIN_HAND);
        if (main == null) {
            log.info("[Age] 战斗养跳过：{} 主手没有装备（或拿在鼠标位）", player.getName());
            return null;
        }
        // ★ 原版按**是否暴击**分派到**不同的家族组**（`sinCheckAgingLevel(SIN_AGING_CRITICAL/ATTACK, …)`，
        //   11 职业客户端 `sinSubMain.cpp:3024+`）：暴击只推进 Critical 族（爪/剑/弓/镰·矛/匕首/标枪），
        //   普通命中只推进 Attack 族（斧/锤/杖/图腾/匕首）。
        //   ⇒ **剑这类"Critical 族"只有暴击才涨熟练度**（普通命中一点不涨是**原版行为**，不是 bug）。
        //   ⚠ 我此前每段命中都推进 ⇒ 剑比原版快 5~10 倍（用户实测"不断飘锻造成功的粒子"就是这么来的）。
        if (!battleFamilyMatches(main.getItemCode(), crit)) {
            return null;
        }
        return addBattleProgress(player, main.getId(), 1);
    }

    /**
     * 这次攻击（暴击 / 非暴击）能不能推进**主手**这件武器的熟练度。
     *
     * <p>家族分组逐字照抄 11 职业客户端 `sinCheckAgingLevel`（`sinSubMain.cpp:3024+`）的两个 case：
     * 暴击组 `sinWC1/sinWS2/sinWS1/sinWP1/sinWD1/sinWT1`、普通组 `sinWA1/sinWH1/sinWM1/sinWD1/sinWN1`
     * （匕首两族都在 ⇒ 打什么都涨）；★ 拳套 `WV` 是 11 职业才有的新族，那会儿还没有 ⇒ 我们按"爪"归**暴击组**
     * （与 `AgeGrowth`/`MatureProgress` 里"拳套按爪"的既有口径一致）。
     */
    private static boolean battleFamilyMatches(Integer idCode, boolean crit) {
        if (idCode == null) {
            return false;
        }
        int fam = idCode & 0xFFFF0000;
        if (crit) {
            return fam == 0x01020000   // 爪 WC
                    || fam == 0x01070000   // 剑 WS2
                    || fam == 0x01060000   // 弓 WS1
                    || fam == 0x01050000   // 镰/矛 WP
                    || fam == 0x010A0000   // 匕首 WD（两组都有）
                    || fam == 0x01080000   // 标枪 WT
                    || fam == 0x010B0000;  // ★ 拳套 WV（我们按爪）
        }
        return fam == 0x01010000   // 斧 WA
                || fam == 0x01030000   // 锤 WH
                || fam == 0x01040000   // 杖 WM
                || fam == 0x010A0000   // 匕首 WD
                || fam == 0x01090000;  // 图腾 WN
    }

    /** 格挡成功 → 喂**盾/法球**（副手）。 */
    public Result onBlock(Player player) {
        ItemInstance off = equippedIn(player, ItemLocations.SLOT_OFF_HAND);
        return off == null ? null : addBattleProgress(player, off.getId(), 1);
    }

    /**
     * 被击受伤 → **一次性喂五件防具**（甲 / 护腕 / 护手 / 靴 / 副手；照 EU `character.cpp:10876-10882`
     * 的五个 `sinCheckAgingLevel(DEFENSE_*)`）。
     *
     * @return 任意一件触发了判定就返回它（否则 null）
     */
    public Result onDamaged(Player player) {
        int[] slots = {ItemLocations.SLOT_ARMOR, ItemLocations.SLOT_ARMLET, ItemLocations.SLOT_GLOVES,
                ItemLocations.SLOT_BOOTS, ItemLocations.SLOT_OFF_HAND};
        Result triggered = null;
        for (int slot : slots) {
            ItemInstance it = equippedIn(player, slot);
            if (it == null) {
                continue;
            }
            Result r = addBattleProgress(player, it.getId(), 1);
            if (r != null && triggered == null) {
                triggered = r;
            }
        }
        return triggered;
    }

    private static ItemInstance equippedIn(Player player, int slot) {
        PlayerItems items = player.getItems();
        if (items == null) {
            return null;
        }
        ItemInstance it = items.at(ItemLocations.EQUIP, slot);
        if (it == null || it.isDeleted()) {
            return null;
        }
        return isEquipment(it) ? it : null;
    }

    // ------------------------------------------------------------------ 一键拉满（熟练度道具）

    /**
     * 这三颗"一键拉满"石对应的**目标类别**（服务端自己找装备槽，不需要客户端传 uid）——
     * 照 NewSourcePT `sinInvenSory` 的 use-item 分派（`sinInvenTory.cpp:3296-3330`）：
     * <pre>
     *   sinBI1|sin36 → 检查 sInven[0]（主手=武器）  → UsePremiumItem(73) → UseAgingMaster(0)
     *   sinBI1|sin37 → 检查 sInven[1]（副手=盾/法球）→ UsePremiumItem(74) → UseAgingMaster(1)
     *   sinBI1|sin38 → 检查 sInven[2]（甲槽）      → UsePremiumItem(75) → UseAgingMaster(2)
     * </pre>
     * ⚠ **我们的码位与源码差一档**：我们库里那三颗是 `0x080B3400/3500/3600`（Mature Stone A/B/C，
     * 名字来自 EU 的库），而两版源码里用的都是 `sin36/37/38`。这里**按位置对应**
     * （我们的第一颗 = 武器、第二颗 = 盾/法球、第三颗 = 甲）—— 这是**我们定的**，理由：
     * 私服各自加石头、码位不同（用户 2026-09-22 说明），而我们库里只有这三颗。
     *
     * @return 0 = 武器 / 1 = 盾·法球 / 2 = 甲；不是这三颗则 -1
     */
    public static int maxAgeKindOf(Integer idCode) {
        if (idCode == null) {
            return -1;
        }
        return switch (idCode) {
            case 0x080B3400 -> 0;   // Mature Stone (A) → 武器
            case 0x080B3500 -> 1;   // Mature Stone (B) → 盾 / 法球
            case 0x080B3600 -> 2;   // Mature Stone (C) → 甲 / 法袍
            default -> -1;
        };
    }

    /**
     * **使用**一颗"一键拉满"石（走 `C2S_UseItem`，由服务端自己判断目标）：
     * 找该类别**已装备**的那件 → 直接 +1 级（**不掷点、不会破坏** —— 原版 `UseAgingMaster` →
     * `sinCheckAgingLevel(_, true)` = 把进度灌满即升级，无失败判定）→ 消耗石头。
     *
     * ⚠ 与 EU 的一处**简化**：EU 要求目标已有"可锻造标记"（`ItemAgingNum[1] != 0`，即先用过锻造石）
     * 否则回 `MESSAGE_HAVE_NOT_AGINGITEM`。我们不做这一步（任何对应类别的装备都能直接拉满）；
     * 要改回去的话，判据用 `kindCode == ITEM_KIND_AGING`（我们没为"开启标记"单开字段）。
     */
    public Result useMaxAgeStone(Player player, long stoneUid) {
        PlayerItems items = player.getItems();
        if (items == null) {
            return fail(Reason.STONE_NOT_FOUND);
        }
        ItemInstance stone = items.byUid(stoneUid);
        if (stone == null || stone.isDeleted() || !isUsableStoneLocation(stone)) {
            return fail(Reason.STONE_NOT_FOUND);
        }
        int kind = maxAgeKindOf(stone.getItemCode());
        if (kind < 0) {
            return fail(Reason.STONE_NOT_ALLOWED);
        }
        int slot = switch (kind) {
            case 0 -> ItemLocations.SLOT_MAIN_HAND;
            case 1 -> ItemLocations.SLOT_OFF_HAND;
            default -> ItemLocations.SLOT_ARMOR;
        };
        ItemInstance target = equippedIn(player, slot);
        if (target == null || !matchesMaxAgeKind(target, kind)) {
            return fail(Reason.NO_TARGET_EQUIPPED);
        }
        int level = target.getAgingNum();
        AgeList row = curves.rowForLevel(level);
        if (row == null || level >= curves.maxLevel()) {
            return fail(Reason.MAX_LEVEL);
        }
        // 消耗石头
        items.byUidRemove(stone.getId());
        stone.setDeleted(true);
        storage.softDelete(stone.getId());
        // 直接升一级（复用与掷点成功完全相同的落地路径：增长 + 等级 + 耐久 −1 + 落库）
        apply(player, target, level, AgeRoll.Result.PLUS_ONE, "熟练度道具");
        return new Result(Reason.OK, target, AgeRoll.Result.PLUS_ONE);
    }

    /** 目标是否属于该"拉满"石要求的类别（武器 / 盾·法球 / 甲）。 */
    private static boolean matchesMaxAgeKind(ItemInstance it, int kind) {
        Integer code = it.getItemCode();
        if (code == null) {
            return false;
        }
        int family = code & 0xFFFF0000;
        return switch (kind) {
            case 0 -> (code & 0xFF000000) == 0x01000000;                       // 武器基类
            case 1 -> family == 0x02040000 || family == 0x03030000;            // 盾 DS1 / 法球 OM1
            default -> family == 0x02010000 || family == 0x02050000             // 甲 DA1 / 法袍 DA2
                    || family == 0x02120000 || family == 0x02130000;            // DA3 / DA4
        };
    }

    /** 石头可被使用的位置（与 `ItemNetworkHandler.isUsableLocation` 同口径：背包，或药水快捷槽）。 */
    private static boolean isUsableStoneLocation(ItemInstance it) {
        int loc = it.getLocation();
        return loc == ItemLocations.BAG_PAGE
                || loc == ItemLocations.EQUIP && it.getSlot() >= ItemLocations.SLOT_POTION_1
                && it.getSlot() <= ItemLocations.SLOT_POTION_3;
    }

    // ------------------------------------------------------------------ 判定与落地

    private static AgeRoll.Result rollOnce(int level, int stoneType, AgeList row, int ageMax) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return AgeRoll.roll(level, stoneType, row, ageMax, false, rng.nextInt(100), rng.nextInt(100));
    }

    /** 把判定结果落到物品上（升级/降级/破坏），并落库。 */
    private void apply(Player player, ItemInstance target, int levelBefore, AgeRoll.Result rr, String how) {
        if (rr.broke()) {
            // 破坏：销毁（原版 `OnBrokeItemHandler` → 存库 + 记日志；播报交调用方，见类注释）
            player.getItems().byUidRemove(target.getId());
            target.setDeleted(true);
            storage.softDelete(target.getId());
            log.info("[Age] {} {} uid={}（{}）在 +{} 投石破坏 —— 已销毁", player.getName(), how,
                    target.getId(), target.name(), levelBefore);
            return;
        }
        int delta = rr.levelDelta();
        if (delta > 0) {
            // ⚠ 这里**不再**自己把属性加上去（那是"烘焙"）：只改锻造等级，属性在读取时按 N 重算
            //    （`ItemInstance.markDerivedDirty()` → `ItemDerivedStats.recompute`）。
            //    好处：① 改一次锻造规则，所有已存在的装备跟着变对；
            //          ② 掉级只要用 N−1 重算就精确回到原值，不需要原版那套反向减法。
            target.setAgingNum(levelBefore + delta);
            target.setKindCode(ItemKind.AGING);
            // ⚠ **不再把 +1 写进存储值**：需求等级改成**派生**（见 `effectiveReqLevel`），
            //   库里的 `req_level` 永远是**基底值**。原版 EU 有"`iLevel ≥ 88` 就不再涨"的豁免，
            //   **用户 2026-09-23 明确：我们没有这个机制** —— 所以这里也不实现它（不要再加回来）。
            target.setDurability(Math.max(0, target.getDurability() - 1));      // 成功 −1（EU UpdateIntegrity -1）
            target.setAgingExp(0);              // 照原版 `OnUpAge`：sMatureBar 清零
            target.setAgingExpMax(0);
            target.markDerivedDirty();
            log.info("[Age] {} {} uid={}（{}）→ +{}（{}）", player.getName(), how, target.getId(),
                    target.name(), target.getAgingNum(), rr);
        } else {
            int levels = -delta;
            // 掉级同样只是"改 N"——重算用 N−levels 走一遍即精确回到原值（**没有**反向函数）
            target.setAgingNum(Math.max(0, levelBefore - levels));
            int cost = levelBefore <= 9 ? 3 : 5;                                // EU：≤9 级 −3，>9 级 −5
            target.setDurability(Math.max(0, target.getDurability() - cost));
            target.markDerivedDirty();
            log.info("[Age] {} {} uid={}（{}）→ +{}（{}，耐久 -{}）", player.getName(), how, target.getId(),
                    target.name(), target.getAgingNum(), rr, cost);
        }
        storage.update(target);
        player.getItems().markDirty(target.getLocation(), target.getSlot(), target.getId());
    }

    /**
     * 养成要交的**金币**（EU `ItemServer::GetItemAgingPrice`，`itemserver.cpp:3984-4008`）：
     * <pre>price = round(物品售价 × (当前等级 + 1) / 2)</pre>
     * （原版还有 `EVENT_AGING_HALFPRICE` 减半与 Bless Castle 税，税那段在源码里被注释掉了 ⇒ 不实现。）
     *
     * <p>⚠ 目前只**算**不扣：扣款要走 `GoldService`（在 game-server 侧），接线还没做 —— 见待办。
     */
    public static int agingGoldPrice(ItemInstance it) {
        int price = it == null ? 0 : it.getPrice();
        return (int) Math.round((double) price * (it.getAgingNum() + 1) / 2.0);
    }

    /**
     * **生效的需求等级** = 存储的基底值 + `floor(锻造等级 / 2)`（每 2 级 +1）。
     *
     * <p>为什么不写进库：与属性同理（DB 只存基底 + 锻造等级，值读时算）——
     * 这样改规则能回溯到所有已存在的装备，也不会出现"存储值被 +1 过、再算一次就翻倍"的错。
     * <p>⚠ **没有 `iLevel ≥ 88` 豁免**（用户 2026-09-23："我早就说过我们没有 iLevel ≥ 88 豁免这个机制"）。
     */
    public static int effectiveReqLevel(ItemInstance it) {
        return it.getReqLevel() + Math.max(0, it.getAgingNum()) / 2;
    }

    // ------------------------------------------------------------------ 判据

    /** 是不是装备（锻造目标）：能装备的 classitem，且不是材料石（OS 族的 14 颗是"投进去的"，不是"被锻造的"）。 */
    private static boolean isEquipment(ItemInstance it) {
        if (it.getTemplate() == null || it.getTemplate().getClassItem() == null) {
            return false;
        }
        if (isMaterialStone(it.getItemCode())) {
            return false;
        }
        int c = it.getTemplate().getClassItem();
        return c == ItemClass.OFF_HAND || c == ItemClass.ONE_HAND_WEAPON || c == ItemClass.TWO_HAND_WEAPON
                || c == ItemClass.ARMOR || c == ItemClass.BOOTS || c == ItemClass.GLOVES
                || c == ItemClass.ARMLET || c == ItemClass.AMULET || c == ItemClass.RRING || c == ItemClass.LRING
                || c == ItemClass.SHELTOM || c == ItemClass.COSTUME;
    }

    /** 是不是材料石（OS 族 1..14 档）。 */
    static boolean isMaterialStone(Integer idCode) {
        if (idCode == null || (idCode & 0xFFFF0000) != STONE_FAMILY) {
            return false;
        }
        int tier = (idCode & 0xFFFF) >>> 8;
        return tier >= 1 && tier <= 14;
    }

    /**
     * 石头类型（EU 的 `iAgeStoneType`）。我们的对应物：
     * `Aging Stone`（`sinBI1|sin10` = `0x080B0A00`）→ {@link AgeRoll#STONE_AGING}；
     * `Copper Ore`（`sinBI1|sin11` = `0x080B0B00`）→ {@link AgeRoll#STONE_COPPER_ORE}；
     * 其它材料石（那 14 颗）→ 按普通石走（{@link AgeRoll#STONE_NONE}，有破坏风险）。
     */
    static int stoneTypeOf(Integer idCode) {
        if (idCode == null) {
            return AgeRoll.STONE_NONE;
        }
        if (idCode == 0x080B0A00) {
            return AgeRoll.STONE_AGING;
        }
        if (idCode == 0x080B0B00) {
            return AgeRoll.STONE_COPPER_ORE;
        }
        if (maxAgeKindOf(idCode) >= 0) {
            return AgeRoll.STONE_MAX_AGE;       // "一键拉满"那三颗（见 maxAgeKindOf）
        }
        return AgeRoll.STONE_NONE;
    }
}
