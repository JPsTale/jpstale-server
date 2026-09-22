package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成（Mix）：把**材料石**投进**装备**，按配方一次性加上固定属性。
 *
 * <p>
 * 依据 `docs/锻造与合成-源码分析.md` §3.14（EU = 我们数据的血脉）：
 * <ol>
 *   <li>目标物品的基材类 {@link MixType#ofItemCode}（EU `GetTypeMixByItemCode`）；</li>
 *   <li>投入的石头**按档位计数**，与配方**逐个严格相等**（{@link MixRecipeService#find}）；</li>
 *   <li>逐槽应用效果：{@code peratributte = 1 → 直加}、{@code = 0 → 按当前值百分比}
 *       （EU `AddAttributeBonusToItem`，`MixHandler.cpp:1503-1529`）；</li>
 *   <li>位掩码按位或进物品的 `craft_mask`（EU 的 `eMixEffect |= iType` —— **效果类型值就是掩码位**）；</li>
 *   <li>`kind_code = ITEM_KIND_CRAFT(1)`（EU `eCraftType = ITEMCRAFTTYPE_Mixing`）、
 *       `aging_num2 = 配方 mixuniqueid`（EU 的 `sMixUniqueID1`）；</li>
 *   <li>消耗掉投入的石头（软删 + 从容器索引摘掉）。</li>
 * </ol>
 *
 * <p>
 * ⚠ 两处**我们定的**（方案 §2 记过）：
 * <ul>
 *   <li>**只接受背包里的目标与石头**（EU 那套是"合成窗口里的 12 格"，我们还没做那个 UI）；
 *       目标是装备槽里的也能合，但属性变更要调用方刷新面板 —— 这里只改实例，不动面板缓存；</li>
 *   <li>**配方 id 存 `aging_num2`**（我们库里没有 EU 的 `sMixUniqueID1` 列）。它是**反查**配方数值的唯一途径：
 *       例如"药水槽容量"这一位我们的实例没有字段（只有模板 `itemlist.potionspace`），
 *       读取侧用 {@link MixRecipeService#byUniqueId} 拿配方再取该槽的值。</li>
 * </ul>
 *
 * <p>
 * ⚠ 失败**必须**带原因回（协议侧用 key，见 {@link Reason#key()}）——不静默、不兜底成别的配方（纠错 #12/#24）。
 */
@Service
public class MixService {

    private static final Logger log = LoggerFactory.getLogger(MixService.class);

    /** 材料石家族（OS 族，`sinOS1 = 0x02350000`）。 */
    private static final int STONE_FAMILY = 0x02350000;

    private final MixRecipeService recipes;
    private final ItemStorageService storage;

    public MixService(MixRecipeService recipes, ItemStorageService storage) {
        this.recipes = recipes;
        this.storage = storage;
    }

    /** 失败原因（客户端按 {@link #key()} 前缀处理，不匹配文案）。 */
    public enum Reason {
        OK, TARGET_NOT_FOUND, TARGET_NOT_IN_BAG, NO_STONES, STONE_NOT_ALLOWED, STONE_NOT_FOUND, NOT_MIXABLE, NO_RECIPE;

        /** 协议用的原因 key（前缀 `item.op.`，与装备失败的约定一致；纠错 #24）。 */
        public String key() {
            return "item.op.mix." + name().toLowerCase().replace('_', '-');
        }
    }

    /** 合成结果。 */
    public static final class Result {
        public final Reason reason;
        public final MixRecipe recipe;
        public final ItemInstance target;
        public final List<Long> consumedStoneUids;

        private Result(Reason reason, MixRecipe recipe, ItemInstance target, List<Long> consumed) {
            this.reason = reason;
            this.recipe = recipe;
            this.target = target;
            this.consumedStoneUids = consumed;
        }

        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private static Result fail(Reason r) {
        return new Result(r, null, null, List.of());
    }

    /**
     * 执行一次合成。
     *
     * @param player    玩家
     * @param targetUid 目标装备（必须在**背包装备位/BAG 容器**里，见类注释）
     * @param stoneUids 投入的石头（必须都是 OS 族那 14 档）
     */
    public Result mix(Player player, long targetUid, List<Long> stoneUids) {
        Match m = match(player, targetUid, stoneUids);
        if (!m.ok) {
            return fail(m.reason);
        }
        PlayerItems items = player.getItems();   // match() 已确认非 null
        ItemInstance target = m.target;
        MixRecipe recipe = m.recipe;

        // ③ 只**记录**"合成了哪条配方"（+ 掩码），**不把属性写进物品** ——
        //    属性在读时由 `ItemDerivedStats` 按配方 id 重算（DB 只存基准值 + 配方 id）。
        //    好处：改配方 ⇒ 所有已合成过的装备跟着变对（用户 2026-09-22 定的口径）。
        // ⚠ 连 `craft_mask` 也不写：它是**派生值**（= 该配方效果位的并集），由 `aging_num2` 唯一确定，
        //    重算时算出来即可（`ItemInstance.getDerivedCraftMask()`）。原版存它只是为了工具提示少查一次表。
        target.setKindCode(ItemKind.CRAFT);
        target.setAgingNum2(recipe.uniqueId());
        target.markDerivedDirty();
        storage.update(target);
        items.markDirty(target.getLocation(), target.getSlot(), target.getId());

        // ④ 消耗石头
        for (ItemInstance st : m.stones) {
            items.byUidRemove(st.getId());
            st.setDeleted(true);
            storage.softDelete(st.getId());
        }

        log.info("[Mix] {} 用 {} 颗石头合成 uid={}（{}）→ 配方 {}「{}」（掩码是派生值，读时由配方 id 算）",
                player.getName(), m.stones.size(), target.getId(), target.name(),
                recipe.uniqueId(), recipe.description());
        return new Result(Reason.OK, recipe, target, stoneUids);
    }

    /**
     * 一份"校验 + 匹配"的结果 —— **唯一实现**，`mix()` 与 `preview()` 共用。
     *
     * <p>为什么必须共用：预览说"这些石头会得到 A"，真合成却按 B 落地，是最难发现的一类 bug
     * （客户端显示的数与物品实际变化不一致，而两边都不报错）。把匹配抽成一份以后，
     * 两者只在"**写不写进物品**"这一步分叉。
     */
    public static final class Match {
        public final Reason reason;
        public final MixRecipe recipe;
        public final ItemInstance target;
        public final List<ItemInstance> stones;
        private final boolean ok;

        private Match(Reason reason, MixRecipe recipe, ItemInstance target, List<ItemInstance> stones) {
            this.reason = reason;
            this.recipe = recipe;
            this.target = target;
            this.stones = stones;
            this.ok = reason == Reason.OK;
        }
    }

    /**
     * 校验目标与石头、并匹配配方（**不改任何东西、不落库**）。
     * 目标必须在**背包**里（鼠标位/装备位不算 —— 与 `mix()` 同一判据）。
     */
    Match match(Player player, long targetUid, List<Long> stoneUids) {
        PlayerItems items = player.getItems();
        if (items == null) {
            return new Match(Reason.TARGET_NOT_FOUND, null, null, List.of());
        }
        ItemInstance target = items.byUid(targetUid);
        if (target == null || target.isDeleted()) {
            return new Match(Reason.TARGET_NOT_FOUND, null, null, List.of());
        }
        if (target.getLocation() != ItemLocations.BAG_PAGE) {
            return new Match(Reason.TARGET_NOT_IN_BAG, null, null, List.of());
        }
        if (stoneUids == null || stoneUids.isEmpty()) {
            return new Match(Reason.NO_STONES, null, null, List.of());
        }
        // ① 石头：必须是 OS 族那 14 档，且按档位计数
        int[] counts = new int[MixRecipe.STONE_SLOTS];
        List<ItemInstance> stones = new ArrayList<>(stoneUids.size());
        for (Long uid : stoneUids) {
            if (uid == null) {
                return new Match(Reason.STONE_NOT_FOUND, null, null, List.of());
            }
            ItemInstance st = items.byUid(uid);
            if (st == null || st.isDeleted() || st.getLocation() != ItemLocations.BAG_PAGE) {
                return new Match(Reason.STONE_NOT_FOUND, null, null, List.of());
            }
            int index = stoneIndexOf(st.getItemCode());
            if (index < 0) {
                return new Match(Reason.STONE_NOT_ALLOWED, null, null, List.of());
            }
            counts[index]++;
            stones.add(st);
        }
        // ② 匹配（基材类 + 逐档严格相等）
        MixType type = MixType.ofItemCode(target.getItemCode());
        if (type == MixType.UNKNOWN) {
            return new Match(Reason.NOT_MIXABLE, null, null, List.of());
        }
        MixRecipe recipe = recipes.find(type, counts);
        if (recipe == null) {
            return new Match(Reason.NO_RECIPE, null, null, List.of());
        }
        return new Match(Reason.OK, recipe, target, stones);
    }

    /** 预览里的一项效果（**服务端算好再下发**，客户端不做任何算术）。 */
    /** 预览结果：命中哪些效果、每项从多少变成多少；未命中时带原因 key。 */
    public static final class Preview {
        public final Reason reason;
        public final String recipeName;
        public final List<MixEffect.Applied> effects;

        private Preview(Reason reason, String recipeName, List<MixEffect.Applied> effects) {
            this.reason = reason;
            this.recipeName = recipeName;
            this.effects = effects;
        }

        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    /**
     * **合成预览**：这批石头打在这件装备上会得到什么（改前/改后），**不改物品、不消费石头**。
     *
     * <p>与 {@link #mix} 共用 {@link #match} 与 {@link #applyEffect}（干跑），所以预览的数**就是**真加的数。
     * 客户端不持有配方表也不做算术 —— 它只把当前的 uid 列表报上来，数字由服务端下发。
     */
    public Preview preview(Player player, long targetUid, List<Long> stoneUids) {
        Match m = match(player, targetUid, stoneUids);
        if (!m.ok) {
            return new Preview(m.reason, null, List.of());
        }
        List<MixEffect.Applied> list = new ArrayList<>();
        for (MixRecipe.Slot slot : m.recipe.slots()) {
            if (slot.used()) {
                list.add(MixEffect.apply(m.target, slot, false));   // 干跑：只算不写
            }
        }
        return new Preview(Reason.OK, m.recipe.description(), list);
    }

    /** 石头档位（0-based）；非 OS 族或超出 1..14 档返回 -1。 */
    static int stoneIndexOf(Integer idCode) {
        if (idCode == null || (idCode & 0xFFFF0000) != STONE_FAMILY) {
            return -1;
        }
        int tier = (idCode & 0xFFFF) >>> 8;          // sin01..sin14 ⇒ 1..14
        return (tier >= 1 && tier <= MixRecipe.STONE_SLOTS) ? tier - 1 : -1;
    }
}
