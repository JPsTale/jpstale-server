package org.jpstale.common.service.skill;

import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.item.ItemStorageService;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 技能熟练度（`UseSkillCount`）的**写入唯一实现** —— 两个入口共用：
 * <ol>
 *   <li><b>道具「Skill Master(1st/2nd/3rd)」</b>：原版 `cHaPremiumItem::UseSkillMaster(int ItemKind)`
 *       （`ScrServer/src/sinbaram/HaPremiumItem.cpp:1877-1930`，`SrcGame` 同文件 `:2734-2785`）
 *       —— 一整档（4 个槽）的**已学**技能计数直接 `+= 10000` ⇒ 派生熟练度触顶 = 满；</li>
 *   <li><b>GM 命令 `/@skill_mastery &lt;1..100&gt;`</b>：把已学技能设成指定的百分比（我们自己的命令，见下）。</li>
 * </ol>
 *
 * <h3>道具这条链的逐字依据</h3>
 * <pre>
 *   // sinInvenTory.cpp:2425-2456（入口：按物品码分派）
 *   if (CODE == (sinBI1 | sin39)) { if (cSkill.CheckMaturedSkill(1) == FALSE)
 *                                       { ShowMessage(MESSAGE_HAVE_NOT_MATURESKILL); return; }
 *                                   chaPremiumitem.UsePremiumItem(76); }   // → UseSkillMaster(1)
 *   if (CODE == (sinBI1 | sin40)) { … CheckMaturedSkill(2) … UsePremiumItem(77); }   // 2 转档
 *   if (CODE == (sinBI1 | sin41)) { … CheckMaturedSkill(3) … UsePremiumItem(78); }   // 3 转档
 *
 *   // HaPremiumItem.cpp:1877（效果）
 *   for (int j = 1; j &lt; 5; j++)                       // 档内 4 个槽（2 档 5..8、3 档 9..12）
 *       if (UseSkillCount &lt; 10000)                    // 没满才加
 *           if (Flag &amp;&amp; Point)                       // **已学**（Flag=这一档已开、Point=学过）
 *               UseSkillCount += 10000;                // ⇒ 派生值被 10000 上限截住 = 满熟练度
 *   … ReformSkillMasteryForm(0, j)                     // 立刻重算派生值（我们读时重算，不需要这一步）
 *   ShowMessage(MESSAGE_SKILL_MATURE_SUCCESS); sinPlaySound(SIN_SOUND_EAT_POTION2); sinEffect_Agony(lpCurPlayer);
 *
 *   // 门槛 CheckMaturedSkill（sinSkill.cpp:7687-7735）：该档里
 *   //   `UseSkillCount &lt; 10000 &amp;&amp; USECODE != SIN_SKILL_USE_NOT &amp;&amp; UseSkillMastery != 0` 的个数 ∈ [1,4]
 *   //   ⇒ 一个可提升的都没有（未学/被动/已满）⇒ 返回 FALSE ⇒ 回 MESSAGE_HAVE_NOT_MATURESKILL 且**不消耗石头**
 * </pre>
 *
 * <h3>物品码怎么定的（我们库里的码位）</h3>
 * 11 职业客户端的 `OpenItem/BI139..BI141.txt` 三个文件名就是
 * <b>「스킬 마스터(1차/2차/3차)」/「Skill Master(1st/2nd/3rd)」</b>，
 * 而 `sinInvenTory.cpp` 里分派用的正是 `sinBI1 | sin39/40/41` = `0x080B3700/3800/3900`
 * （`sin39 = 0x3700`…，见 `sinbaram/sinItem.h`）—— 与紧邻的「Aging Master(A/B/C)」
 * （`sinBI1 | sin36/37/38` = `0x080B3400/3500/3600`，即 `AgeService.maxAgeKindOf` 那三颗）**连号**，
 * 三处（文件名 / 分派码 / 连号）互证。
 * ⚠ 我们 `gamedb.itemlist` 里那三行（id 755 / 701 / 698，`codeimg1` = bi139/bi140/bi141）的
 * **`name` 列是 EU 时代的旧名**（Ruby/Emerald/Sapphire Stone），与本册不符 —— 显示名以 OpenItem 为准，
 * 改名需另行确认（见 AGENTS #16 与 docs 的记录）。
 */
@Service
public class SkillMasteryService {

    private static final Logger log = LoggerFactory.getLogger(SkillMasteryService.class);

    /** 石头 idcode → 转职档 1/2/3（`sinBI1 | sin39/40/41`）。 */
    private static final int STONE_TIER_1 = 0x080B3700;
    private static final int STONE_TIER_2 = 0x080B3800;
    private static final int STONE_TIER_3 = 0x080B3900;

    /** 每档 4 个槽（`SkillRules.slotInJob` 的 0..19 每 4 个一档）。 */
    private static final int SLOTS_PER_TIER = 4;

    /** 计数上限（`sinSkill.cpp:2069` 的 `if (UseSkillMastery >= 10000)`；也是每次 +10000 的来源）。 */
    private static final int COUNT_MAX = SkillRules.MASTERY_MAX;

    private final SkillDataRegistry data;
    private final PlayerStatCalculator stats;
    private final ItemStorageService storage;

    public SkillMasteryService(SkillDataRegistry data, PlayerStatCalculator stats, ItemStorageService storage) {
        this.data = data;
        this.stats = stats;
        this.storage = storage;
    }

    /** 拒绝原因（协议按 {@link #key()} 走文案，不匹配中文）。 */
    public enum Reason {
        OK(null),
        /** 不是这种石头 */
        NOT_A_STONE("not-a-stone"),
        /** 石头不在可用位置（背包 / 药水快捷槽） */
        STONE_NOT_IN_BAG("stone-not-in-bag"),
        /** 该职业没有技能树（存档坏了） */
        NO_SKILL_TREE("no-skill-tree"),
        /** 该档没有可提升的技能（未学 / 全是被动 / 都已满）—— 原版 `MESSAGE_HAVE_NOT_MATURESKILL` */
        NOTHING_TO_MATURE("nothing-to-mature"),
        /** GM 命令参数越界（1..100） */
        BAD_PERCENT("bad-percent");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** i18n key 后缀（`item.op.skill-master.` + 它）；{@link #OK} 返回 null。 */
        public String key() {
            return key == null ? null : "item.op.skill-master." + key;
        }
    }

    /**
     * 一次写入的结果。
     *
     * @param reason      见 {@link Reason}
     * @param tier        石头档位 1..3；GM 命令报 0
     * @param changed     计数**真的被改**的技能数（未学的不算）
     * @param elementFull 其中派生值恒满（`Element[0] != 0`）的数量 —— 这些写了也不改变显示值
     * @param effectivePct 写入后普通技能的**实际**百分比（GM 命令用；石头恒 100）
     */
    public record Result(Reason reason, int tier, int changed, int elementFull, int effectivePct) {

        public boolean ok() {
            return reason == Reason.OK;
        }

        private static Result fail(Reason r) {
            return new Result(r, 0, 0, 0, 0);
        }
    }

    /**
     * idcode → 转职档（1..3）；不是这三颗石头 ⇒ 0。
     * 调用方据此判"要不要走这条路"（与 `AgeService.maxAgeKindOf` 同款）。
     */
    public static int tierIndexOf(Integer idCode) {
        if (idCode == null) {
            return 0;
        }
        return switch (idCode) {
            case STONE_TIER_1 -> 1;
            case STONE_TIER_2 -> 2;
            case STONE_TIER_3 -> 3;
            default -> 0;
        };
    }

    /* ─────────────── ① 道具：拉满一整档 ─────────────── */

    /**
     * 使用一颗「Skill Master(n)」：把**该档已学**技能的熟练度计数拉到上限（派生值随即变 10000）。
     *
     * <p>扣石头的时机照 `ItemService.consumeFromBag` 的约定：**先确认效果能落地**（门槛过了），
     * 再消耗 —— 所以门槛不过时石头**不消耗**（与原版 `return` 在 `UsePremiumItem` 之前一致）。
     *
     * @return 结果；`changed` = 本次被改计数的技能数（0 且 `reason=NOTHING_TO_MATURE` 时石头未消耗）
     */
    public Result matureTier(Player p, long stoneUid) {
        if (p == null || p.getItems() == null) {
            return Result.fail(Reason.STONE_NOT_IN_BAG);
        }
        ItemInstance stone = p.getItems().byUid(stoneUid);
        if (stone == null || stone.isDeleted() || !isUsableStoneLocation(stone)) {
            return Result.fail(Reason.STONE_NOT_IN_BAG);
        }
        int tier = tierIndexOf(stone.getItemCode());
        if (tier == 0) {
            return Result.fail(Reason.NOT_A_STONE);
        }
        if (!data.hasJob(p.getJob())) {
            return Result.fail(Reason.NO_SKILL_TREE);
        }
        int magic = stats.magicMastery(p);
        // 门槛（原版 CheckMaturedSkill）：还有"没满、非被动、派生值非 0"的技能才允许用
        int pending = 0;
        for (SkillDataRegistry.Skill s : tierSlots(p, tier)) {
            int raw = p.getPropInt(SkillKeys.mastery(s.skillId()));
            if (raw >= COUNT_MAX) {
                continue;
            }
            if (SkillRules.useSkillMastery(p, data, s.skillId(), magic) != 0) {
                pending++;
            }
        }
        if (pending == 0) {
            log.info("[SkillMaster] {} 用 {} 档石头被拒：该档没有可提升的技能（未学/被动/已满）",
                    p.getName(), tier);
            return Result.fail(Reason.NOTHING_TO_MATURE);
        }
        // 消耗石头（与 AgeService.useMaxAgeStone 同款：内存 + 落库软删）
        p.getItems().byUidRemove(stone.getId());
        stone.setDeleted(true);
        storage.softDelete(stone.getId());

        int changed = 0;
        int elementFull = 0;
        for (SkillDataRegistry.Skill s : tierSlots(p, tier)) {
            int raw = p.getPropInt(SkillKeys.mastery(s.skillId()));
            if (raw >= COUNT_MAX) {
                continue;
            }
            // 原版 `UseSkillCount += 10000`（配合上限 10000 截断 = 拉满）。Element[0] 的技能派生值本来
            // 就恒为 10000，这里照原版**一样写**（写的是同一个计数），但单独计数出来给上层报明。
            if (s.element0() != 0) {
                elementFull++;
            }
            p.setPropInt(SkillKeys.mastery(s.skillId()), Math.min(COUNT_MAX, raw + COUNT_MAX));
            changed++;
        }
        log.info("[SkillMaster] {} 使用 {} 档技能熟练度石：{} 个技能拉满（其中元素/高阶 {} 个本来就是恒满）",
                p.getName(), tier, changed, elementFull);
        return new Result(Reason.OK, tier, changed, elementFull, 100);
    }

    /** 该档的槽（只含**已学**且**非被动**的：原版 `Flag && Point` + `USECODE != SIN_SKILL_USE_NOT`）。 */
    private java.util.List<SkillDataRegistry.Skill> tierSlots(Player p, int tier) {
        int lo = (tier - 1) * SLOTS_PER_TIER;
        int hi = tier * SLOTS_PER_TIER - 1;
        java.util.List<SkillDataRegistry.Skill> out = new java.util.ArrayList<>(SLOTS_PER_TIER);
        for (SkillDataRegistry.Skill s : data.ofJob(p.getJob())) {
            if (s.slotInJob() < lo || s.slotInJob() > hi) {
                continue;
            }
            if (p.getPropInt(SkillKeys.point(s.skillId())) <= 0) {
                continue;                    // 未学：原版 `Point` 为 0 直接跳过
            }
            if ("NOT".equals(s.useCode())) {
                continue;                    // 被动（`SIN_SKILL_USE_NOT`）：原版也不会给它加计数
            }
            out.add(s);
        }
        return out;
    }

    /** 石头可被使用的位置（与 `ItemNetworkHandler.isUsableLocation` 同口径：背包，或药水快捷槽）。 */
    private static boolean isUsableStoneLocation(ItemInstance it) {
        int loc = it.getLocation();
        return loc == ItemLocations.BAG_PAGE
                || loc == ItemLocations.EQUIP && it.getSlot() >= ItemLocations.SLOT_POTION_1
                && it.getSlot() <= ItemLocations.SLOT_POTION_3;
    }

    /* ─────────────── ② GM：整体设为某个百分比 ─────────────── */

    /**
     * 把**已学**技能的熟练度设为"派生值 = pct%"（`/@skill_mastery &lt;1..100&gt;`）。
     *
     * <p>写的是计数（`skill.&lt;id&gt;.mastery`）：派生值 = `min(50, Talent/3 + fMagic_Mastery)×100 + 计数`
     * （见 {@link SkillRules#useSkillMastery}）⇒ 计数 = `目标 − 才能项`，并夹到 `[0, 10000]`。
     * 于是有两种"要不到"的情形，都必须**如实回报**而不是假装做到了（AGENTS #12）：
     * <ul>
     *   <li><b>才能/装备给的下限高于目标</b>（计数最小只能到 0）⇒ 实际百分比 = 下限，`effectivePct` 会更大；</li>
     *   <li><b>`Element[0] != 0` 的技能</b>（高转职段）派生值**恒为 10000** ⇒ 它们的计数**不动**
     *       （写下去只会抹掉真实的修炼记录而显示值分毫不变），只回个数量。</li>
     * </ul>
     * 未学的技能**不建键**（不写 0 进去 —— 键不存在才是"没学"）。
     */
    public Result setAllPercent(Player p, int pct) {
        if (p == null) {
            return Result.fail(Reason.NO_SKILL_TREE);
        }
        if (pct < 1 || pct > 100) {
            return Result.fail(Reason.BAD_PERCENT);
        }
        if (!data.hasJob(p.getJob())) {
            return Result.fail(Reason.NO_SKILL_TREE);
        }
        int target = pct * 100;                                    // 派生值 0..10000
        int magic = stats.magicMastery(p);
        int floor = Math.min(SkillRules.TALENT_TERM_MAX,
                Math.max(0, p.getTalent() / 3) + Math.max(0, magic)) * 100;
        int raw = Math.max(0, Math.min(COUNT_MAX, target - floor));
        int changed = 0;
        int elementFull = 0;
        for (SkillDataRegistry.Skill s : data.ofJob(p.getJob())) {
            if (p.getPropInt(SkillKeys.point(s.skillId())) <= 0) {
                continue;                                          // 未学：不建键
            }
            if (s.element0() != 0) {
                elementFull++;
                continue;                                          // 恒满：见 javadoc
            }
            p.setPropInt(SkillKeys.mastery(s.skillId()), raw);
            changed++;
        }
        int effective = Math.min(100, (floor + raw) / 100);
        log.info("[SkillMaster] [GM] {} 熟练度设为 {}% ⇒ 计数={}（才能/装备下限 {}%），改了 {} 个技能，"
                        + "{} 个元素/高阶技能恒满不受影响",
                p.getName(), pct, raw, floor / 100, changed, elementFull);
        return new Result(Reason.OK, 0, changed, elementFull, effective);
    }
}
