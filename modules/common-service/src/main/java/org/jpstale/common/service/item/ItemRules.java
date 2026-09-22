package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;

/**
 * 物品使用规则（**原版代码里的表**，不是我们发明的）。
 *
 * 权威出处：`NewSourcePT-2023/SrcGame/src/sinbaram/sinItem.cpp:64-72` 定义了**三张同源的姊妹表**：
 * <pre>
 *   NotSell_Item_{CODE,MASK,KIND}   // 不能卖给 NPC
 *   NotDrow_Item_{CODE,MASK,KIND}   // 不能丢到地面
 *   NotSet_Item_ {CODE,MASK,KIND}   // 不能摆摊/放进商店
 *   NotDrow_Item_CODE[] = { (sinQT1|sin07), (sinQT1|sin08), 0 };   // 0x07010007 / 0x07010008
 *   NotDrow_Item_MASK[] = { 0 };                                   // 家族掩码表为空
 *   NotDrow_Item_KIND[] = { ITEM_KIND_QUEST_WEAPON, 0 };           // 按 ItemKindCode：任务武器
 *   sinQT1 = 0x07010000
 * </pre>
 * 命中判据（`sinInvenTory1.cpp:5849-5862`）：三张表**任一命中就不许**。
 *
 * ⚠ **我们的差异**：第三张表按 `ItemKindCode` 判，而我们的 `gamedb.itemlist` **没有这一列**
 * → 用**任务家族**（`idCode & 0xFFFF0000 == 0x07010000`）近似。它比"具体两码"更宽，
 * 取舍是**宁可不让丢，也不误丢任务物品**（丢出去不可逆）。
 *
 * 客户端同一份表在 `src/game/itemRules.ts`（改一边要改另一边）。
 */
public final class ItemRules {

    private ItemRules() {
    }

    /** 原版 `sinITEM_MASK2`：idcode 的高 16 位（家族） */
    private static final int MASK2 = 0xFFFF0000;
    /** 原版 `sinQT1`：任务物品家族 */
    private static final int FAMILY_QUEST = 0x07010000;
    /** 原版 `sinGG1`：金币/经验掉落物家族（`sinGG1|sin01` = 金币 = 0x05010100）。掉落方也用这个常量建金币物。 */
    public static final int FAMILY_GOLD = 0x05010000;
    /**
     * `sinGG1 | sin01`：金币道具本身 —— `gamedb.itemlist` 里 Gold 行的 idcode。
     *
     * ⚠ 实测值 **83951872 = 0x05010100**（该行 `id=484, name=Gold, codeimg1=GG101`）。
     * 这里曾经写成 `FAMILY_GOLD | 0x00010000` = **0x05020000** —— 把子索引 `sinNN` 挪到了高 16 位，
     * 而它实际在 `0xNN00` 位（项目里的同一编码：`Steel Axe = 0x01010200`、`Skull Beads = 0x03030500`）
     * ⇒ 物品表里查不到这个 idcode ⇒ 金币**掷中了也造不出那枚金币**（只在日志留一条 error）
     * ⇒ 地上永远不掉钱（用户 2026-09-14 实测"服务端根本就不掉钱"）。
     * 回归：`ItemRulesGoldCodeTest`。
     */
    public static final int CODE_GOLD = FAMILY_GOLD | 0x00000100;

    /** `NotDrow_Item_CODE[]`：不能丢到地上的**具体码** */
    private static final int[] NOT_DROP_CODES = { 0x07010007, 0x07010008 };
    /** `NotSell_Item_CODE[]`：不能卖给 NPC（原版与禁丢同表） */
    private static final int[] NOT_SELL_CODES = { 0x07010007, 0x07010008 };

    private static boolean inFamily(int idCode) {
        return (idCode & MASK2) == FAMILY_QUEST;
    }

    /**
     * 是否是**金币掉落物**（原版 `sinGG1 | sin01` = `0x05010000`）。
     *
     * 依据：`itemlist` 的 Gold 行 `idcode = 83951872 = 0x05010100`；原版客户端拾取时按
     * `pItemInfo->CODE == (sinGG1 | sin01)` 走 `sinPlusMoney` + `SIN_SOUND_COIN` 并**直接 return**
     * （`sinInvenTory.cpp:7808`）—— 即金币**不入背包、不占格、不负重**。
     *
     * ⚠ 判据是"**家族 + 带金额**"两个条件，缺一不可（用户 2026-09-14 指出）：
     * 只看金额会把"带金额的其它家族物品"误当金币；只看家族会把"作为普通物品掉落的
     * 金币外观物（money=0）"误当金币。所以调用方必须写成
     * `isGoldDrop(code, money)`，而不是各自判一半。
     */
    public static boolean isGoldFamily(int idCode) {
        return idCode != 0 && (idCode & MASK2) == FAMILY_GOLD;
    }

    /** 金币掉落物的完整判据：家族命中 **且** 带金额（见 {@link #isGoldFamily} 的说明）。 */
    public static boolean isGoldDrop(int idCode, long money) {
        return money > 0 && isGoldFamily(idCode);
    }

    /** 是否属于**任务物品家族**（`sinQT1 = 0x07010000`）。禁丢清单与超重豁免共用这一份近似。 */
    public static boolean isQuestFamily(int idCode) {
        return idCode != 0 && inFamily(idCode);
    }

    /** 能否丢到地面（原版 `NotDrow_Item_*`）。 */
    public static boolean isDroppable(int idCode) {
        if (idCode == 0) {
            return true;
        }
        for (int c : NOT_DROP_CODES) {
            if (c == idCode) {
                return false;
            }
        }
        return !inFamily(idCode);   // 任务家族一律不许丢（原版还含 ITEM_KIND_QUEST_WEAPON，我们缺该列）
    }

    /** 能否卖给 NPC（原版 `NotSell_Item_*`；目前尚无出售入口，先备好判据）。 */
    public static boolean isSellable(int idCode) {
        if (idCode == 0) {
            return true;
        }
        for (int c : NOT_SELL_CODES) {
            if (c == idCode) {
                return false;
            }
        }
        return !inFamily(idCode);
    }

    // ================= 装备职业门（原版 `NotUseFlag` 的真正语义）=================
    //
    // 出处：`ex-machina/src/game/Legacy/Game/Interface/sinInvenTory.cpp:4341-4440`（`CharOnlySetItem`）
    // + `:4470-4520`（`CheckRequireItem` / `CheckRequireItemToSet`）。原版在放装备时按这些
    // **硬编码规则**置 `NotUseFlag`，命中就不许放进槽（`CheckSetOk` 里 `ItemPosition != 0
    // && NotUseFlag` → `MESSAGE_NO_USE_ITEM` 并拒绝）。家族码取自 `sinItem.h`。
    // 客户端同一份在 `src/game/itemRules.ts`（改一边要改另一边）。
    //
    // ⚠ 更权威的是服务端 OpenItem 的 `**특화`/`**특화랜덤` 字段（AGENTS 纠错 #8），
    //   但 `items-11job.json` 当初没保留那两列；在重扫之前，这里用**与原版客户端一致**的规则兜住。

    private static final int DA1 = 0x02010000;   // 铠甲（物理系）
    private static final int DA2 = 0x02050000;   // 法袍（法系）
    private static final int OM1 = 0x03030000;   // 法球（副手）
    private static final int WD1 = 0x010A0000;   // 匕首（刺客专属）
    private static final int WN1 = 0x01090000;   // 图腾（萨满专属）
    private static final int WV1 = 0x010B0000;   // 拳套（格斗家专属）
    /** 原版 `sinITEM_MASK3`（playmain.h:220）：低 16 位 —— 男/女外观变体就在这一层区分 */
    private static final int MASK3 = 0x0000FFFF;

    /**
     * 原版 `CharOnlySetItem` **第一分支**的 10 个甲码 —— **女性职业被这 10 个码拒绝**
     * ⇒ 它们是**男款**外观：`sin31 sin32 sin35 sin36 sin39 sin40 sin43 sin44 sin51 sin54`。
     * <p>
     * ⚠ 命名按**语义**（谁是这一款的主人）而不是按"源码里谁被判"：源码第一分支写的是
     * "PRIESTESS/ATALANTA/ARCHER 不可用"，所以那个列表是**男款**。搞反会让下一个人误判。
     * <p>
     * 同一批甲在男/女两套外观下是**两个不同的 idcode**（同名的两件）——实测我方数据：
     * `da151`/`da152` 都叫 Dark Gaia Armor、`da251`/`da252` 都叫 Dark Iria Robe。
     * 回归：`npm run verify-canuse`（会用"同名对"反向自证这个维度）。
     */
    private static final int[] MALE_VARIANT_CODES = {
            0x2F00, 0x3000, 0x3300, 0x3400, 0x3700, 0x3800, 0x3B00, 0x3C00, 0x4300, 0x4600
    };
    /** 原版 `CharOnlySetItem` **第二分支**的 10 个甲码 —— **其余职业被拒绝** ⇒ 它们是**女款**：`sin33 sin34 sin37 sin38 sin41 sin42 sin45 sin46 sin52 sin55` */
    private static final int[] FEMALE_VARIANT_CODES = {
            0x3100, 0x3200, 0x3500, 0x3600, 0x3900, 0x3A00, 0x3D00, 0x3E00, 0x4400, 0x4700
    };

    /**
     * 女性职业集合 = 客户端 `JOB_DATA[].gender === 'f'`（3 弓手 / 5 女战神 / 8 祭司 / 9 刺客 / 11 格斗家）。
     * <p>
     * 原版 `CharOnlySetItem` 里写死的是 `PRIESTESS || ATALANTA || ARCHER` —— 8 职业时代那 3 个女性职业，
     * 与我们的 `gender` 表**完全吻合**。新增的 9/10/11 在原版源码里没有（那个时代还没有），
     * 故按**体型性别**外推：刺客(m6)/格斗家(m8) 用的是女性体型（`bipMeshPrefix 'tfb'`）→ 归女性；
     * 萨满(m7) 用男性体型 → 归男性。依据记在此处，不是照抄。
     */
    private static boolean isFemaleJob(int job) {
        return job == 3 || job == 5 || job == 8 || job == 9 || job == 11;
    }

    /**
     * **需求门（原版 `NotUseFlag` 的属性侧）**：等级 + 5 属性。出处 `sinInvenTory.cpp:6971`
     * `CheckRequireItem`（Level/Dexterity/Strength/Talent/Spirit/Health 任一不足即置 `NotUseFlag`）。
     *
     * 两个用途，必须是同一份判据：
     *  - **能不能穿上**（`ItemService.equipFromBag` 的门槛）；
     *  - **已装备的件还算不算数** —— 原版 `SetItemToChar` 在累加属性时
     *    `if (InvenItem[i].sItemInfo.NotUseFlag) continue;`（`sinInvenTory.cpp:7355`），
     *    即属性不满足的装备**属性不生效**（但外观照旧、负重照算），且背包格/装备槽画**红底**提示
     *    （`:944`，`sinInvenColor[2]` = 255,0,0,128）。
     */
    public static boolean meetsRequirements(Player player, ItemInstance it) {
        if (it == null) {
            return false;
        }
        return player.getLevel() >= AgeService.effectiveReqLevel(it)
                && player.getStrength() >= it.getReqStrength()
                && player.getSpirit() >= it.getReqSpirit()
                && player.getTalent() >= it.getReqTalent()
                && player.getAgility() >= it.getReqAgility()
                && player.getHealth() >= it.getReqHealth();
    }

    /**
     * 该职业能否使用这件装备 —— **逐条照抄原版客户端的判定**（顺序与条件一一对应）。
     *
     * @param job   角色职业号（1..11；3 弓手 / 5 女战神 / 7 法师 / 8 祭司 / 9 刺客 / 10 萨满 / 11 格斗家）
     * @param idCode 物品 idcode
     */
    public static boolean canUse(int job, int idCode) {
        if (idCode == 0) {
            return true;
        }
        int f = idCode & MASK2;
        // ① 甲（DA1/DA2）按**男/女外观码**分派：只能穿自己那一款（女性 → 男款不可用，反之亦然）
        if (f == DA1 || f == DA2) {
            int m3 = idCode & MASK3;
            int[] foreign = isFemaleJob(job) ? MALE_VARIANT_CODES : FEMALE_VARIANT_CODES;
            for (int c : foreign) {
                if (c == m3) {
                    return false;
                }
            }
        }
        // ② 铠甲(DA1) 法系穿不了；法袍(DA2)、法球(OM1) 非魔法职业用不了
        boolean magicJob = job == 7 || job == 8 || job == 10;
        if (magicJob ? (f == DA1) : (f == DA2 || f == OM1)) {
            return false;
        }
        // ③ **全族一致**的职业锁（来源：服务端 OpenItem 的 `**특화`/`**특화랜덤`，AGENTS 纠错 #8）。
        //    只收录"整个族 100% 一致"的族 —— 这种族才敢当**族级**规则用：
        //    OM 法球 = Priestess/Magician 25/25、WD 匕首 = Assassin 33/33、
        //    WN 图腾 = Shaman 33/33、WV 拳套 = MartialArtist 34/34。
        //    （WA/WP/WS/WT/WC/WH 是 `**특화` **单职业且只覆盖一部分**，不能推成族规则 → 不收。）
        //    ⚠ 更细的权威是逐件的 JobCodeMask（`**특화랜덤` 在掉落时挑一个写回），
        //      那一列还没扫进 `items-11job.json`；在那之前用这 4 条族规则兜住。
        if (f == OM1) {
            return job == 7 || job == 8;
        }
        if (f == WD1) {
            return job == 9;
        }
        if (f == WN1) {
            return job == 10;
        }
        if (f == WV1) {
            return job == 11;
        }
        return true;
    }
}
