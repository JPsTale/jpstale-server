package org.jpstale.server.game.clan;

import java.util.Map;

/**
 * **哪个 NPC 提供公会服务** —— 照 {@link org.jpstale.server.game.item.NpcCraftTable} 的模式：
 * 判据是 **`npclist.eventtype`**（我们库自己的字段），不按名字/模型猜。
 *
 * <p><b>依据（2026-09-25 实测）</b>：`gamedb.npclist` 里 **`eventtype = 8` 恰好只有 2 个 NPC**，
 * 名字全是 {@code *_clan_master}：
 * <ul>
 *   <li>{@code phillai_clan_master}（id=72）—— Phillai 站</li>
 *   <li>{@code ricarten_clan_master}（id=93）—— Ricarten 站</li>
 * </ul>
 * 且客户端 i18n（`src/locales/*.json` 的 {@code npc.*_clan_master.name}）已经把这两个内部名
 * 显示为「公会管理员」—— 数据、名字、文案三处一致。
 * 其余 {@code *_master} 各占别的 eventtype（4=力量/6=锻造/10=技能/…），互不冲突。
 *
 * <p>不在表里的 NPC **不提供**公会服务（不兜底、不猜，AGENTS #12）。
 */
public final class NpcClanTable {

    private NpcClanTable() {
    }

    /** 公会服务档位。目前只有"开公会菜单"一档；将来「攻城报名/SOD」若也要 NPC 入口再扩。 */
    public enum Mode {
        /** 公会菜单（建会/查会/排名） */
        CLAN;
    }

    /** `eventtype` → 公会服务。 */
    private static final Map<Integer, Mode> BY_EVENT_TYPE = Map.of(
            8, Mode.CLAN);

    /** 该 NPC 是否提供公会服务；不在表里返回 {@code null}（= 不提供，不兜底）。 */
    public static Mode modeOfEventType(int eventType) {
        return BY_EVENT_TYPE.get(eventType);
    }
}
