package org.jpstale.server.common.enums.party;

/**
 * 对应 C++ party.h 中 EPartyAction，4 字节（int）。
 * DISBAND_RAID(5) 与 RaidState 已按用户 2026-09-24 裁定移除（组队参考 EU 逻辑但不做 Raid），
 * CHANGE_MODE 保留 C++ 原值 6，5 留空位。
 */
public enum PartyAction {
    NONE(0),
    LEAVE(1),
    KICK(2),
    DELEGATE(3),
    DISBAND_PARTY(4),
    CHANGE_MODE(6);

    private final int value;

    PartyAction(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static PartyAction fromValue(int value) {
        for (PartyAction a : values()) {
            if (a.value == value) return a;
        }
        return NONE;
    }
}
