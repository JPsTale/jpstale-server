package org.jpstale.server.web.npc;

import org.jpstale.server.web.admin.ColumnSemantics;
import org.jpstale.server.web.admin.ColumnSemantics.Semantics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `gamedb.npclist` 的列取值语义（行名；`pvp` 之类的布尔不在本表）。
 *
 * <p>
 * 命名依据（**不猜**）：全部按**列名本身的含义**起名（`weaponshop`→武器店清单、`message1`→对话 1 …）。
 * `eventtype`/`eventparam`/`teleportid`/`skillquests`/`quest*` 这一批**只有编号含义**的列，
 * 原版源码里也没有对照表（2026-09-22 在 `NewSourcePT-2023` 里找过，没有），
 * 所以**照样给"列名含义"的名字**（事件类型 / 事件参数 / 传送 ID …），但**不给候选值** ——
 * 谁的编号是多少由数据说了算，界面按数字显示与编辑。
 */
public final class NpcColumnSemantics {

    private static final Semantics NUMBER = ColumnSemantics.NUMBER;

    private static final Map<String, Semantics> BY_COLUMN = new LinkedHashMap<>();

    static {
        named("name", "npc.name");
        named("gamefile", "npc.gamefile");
        named("teleportid", "npc.teleportId");
        named("message1", "npc.message1");
        named("message2", "npc.message2");
        named("message3", "npc.message3");
        named("message4", "npc.message4");
        named("eventtype", "npc.eventType");
        named("eventparam", "npc.eventParam");
        named("skillquests", "npc.skillQuests");
        named("questid", "npc.questId");
        named("questtypeid", "npc.questTypeId");
        named("questtypesubid", "npc.questTypeSubId");
        named("weaponshop", "npc.weaponShop");
        named("defenseshop", "npc.defenseShop");
        named("miscshop", "npc.miscShop");
    }

    private NpcColumnSemantics() {
    }

    /** 该列的语义；未登记的列返回 NUMBER。 */
    public static Semantics of(String column) {
        return BY_COLUMN.getOrDefault(column, NUMBER);
    }

    private static void named(String column, String rowLabelKey) {
        Semantics old = BY_COLUMN.getOrDefault(column, NUMBER);
        BY_COLUMN.put(column, new Semantics(old.kind(), old.options(), old.textOptions(), old.bits(),
                rowLabelKey, 0, old.unit()));
    }
}
