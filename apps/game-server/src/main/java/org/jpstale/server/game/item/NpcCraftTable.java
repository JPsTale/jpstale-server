package org.jpstale.server.game.item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **哪个 NPC 提供哪几种打造服务** —— 一张显式的代码表（不建表、不猜）。
 *
 * <p><b>依据（2026-09-22 用户确认为准）</b>：判据是 **`npclist.eventtype`** —— 我们库自己的字段，
 * 且这些取值在 204 个 NPC 里**唯一可辨**（实测）：
 * <ul>
 *   <li>{@code eventtype = 4} → **力量大师**（力量石/炼金）：全库 5 个，名字全是 {@code *force_master}</li>
 *   <li>{@code eventtype = 11} → **合成大师**：全库唯一 = {@code mixing_craftsman_morald}</li>
 *   <li>{@code eventtype = 6} → **锻造大师**（Aging）：全库唯一 = {@code arcane_moriph}</li>
 *   <li>{@code eventtype = 9} → **传送 NPC**（用户澄清）—— **不是**打造 NPC，别再往表里放</li>
 * </ul>
 * ⚠ 这份表**唯一还站得住的证据就是上面这条**（我们自己的数据 + 用户确认）。
 * 我先前写的"名字/模型/关键字四处一致"是从 **11 职业私服**的 `.npc` 脚本跨血统比出来的
 * —— 那只能说明"像"，说明不了"是同一个 NPC"✗。据此我把 {@code clever_newter}（eventtype=9，
 * 其实是**传送** NPC）错当成了"代理锻造大师" ✗，用户 2026-09-22 纠正后已从表里删除。
 *
 * <p><b>还不如直接从 eventtype 判</b>：表里记 id 只是权宜；更稳的做法是按 NPC 的
 * {@code eventtype} 直接映射服务（新 NPC 只要 eventtype 对就自动生效）—— 待做。
 *
 * <p><b>为什么不用数据库的商店列推</b>：商店（{@code weaponshop/defenseshop/miscshop}）与打造是
 * 两回事，原版里也是**互相独立**的标志（见 `Svr_Damge.cpp` 的判据各自成段）。
 * 未登记的 NPC 一律**不提供**服务（不兜底、不猜）。
 */
public final class NpcCraftTable {

    private NpcCraftTable() {
    }

    /** 打造窗口的服务档位（值就是协议 `S2C_CraftOpen.modes` 里的取值）。 */
    public enum Mode {
        /** 合成（组合）：投 1~12 颗材料石 → 按配方一次性加固定属性。EV = 原版 `*아이템조합` */
        MIX(1),
        /** 锻造：投石升 +N，可能失败/降级/破坏。EV = 原版 `*아이템에이징` */
        AGE(2),
        /** 力量石（炼金）：材料石 → 力量石，吃下获得攻击 buff。EV = 原版 `*아이템연금`（ItemMix=200） */
        FORCE(3);

        public final int wire;

        Mode(int wire) {
            this.wire = wire;
        }
    }

    /**
     * **`eventtype` → 服务**（用户 2026-09-22 确认的判据；实测这些取值在 204 个 NPC 里唯一可辨）。
     * 不再按 NPC id 硬编码 —— 之前那 8 行是我拿另一条血统的脚本名/模型猜的，还错了一行
     * （把 `eventtype=9` 的**传送** NPC 当成了锻造大师）。
     */
    private static final Map<Integer, Mode> BY_EVENT_TYPE = new LinkedHashMap<>();

    static {
        BY_EVENT_TYPE.put(4, Mode.FORCE);    // 力量大师（力量石/炼金）—— 全库 5 个，名字全是 *force_master
        BY_EVENT_TYPE.put(6, Mode.AGE);      // 锻造大师（Aging）—— 全库唯一 = arcane_moriph
        BY_EVENT_TYPE.put(11, Mode.MIX);     // 合成大师 —— 全库唯一 = mixing_craftsman_morald
        // eventtype=9 是**传送 NPC**，不在这里（用户澄清）
    }

    /** 该 NPC 提供的服务；`eventtype` 不在表里返回 {@code null}（= 不提供，不兜底）。 */
    public static Mode modeOfEventType(int eventType) {
        return BY_EVENT_TYPE.get(eventType);
    }

}
