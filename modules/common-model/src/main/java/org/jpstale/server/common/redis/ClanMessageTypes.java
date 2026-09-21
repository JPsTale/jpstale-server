package org.jpstale.server.common.redis;

public final class ClanMessageTypes {
    public static final String CLAN_CREATE = "CLAN_CREATE";
    public static final String CLAN_DISSOLVE = "CLAN_DISSOLVE";
    public static final String CLAN_INVITE = "CLAN_INVITE";
    public static final String CLAN_KICK = "CLAN_KICK";
    public static final String CLAN_LEAVE = "CLAN_LEAVE";
    public static final String CLAN_TRANSFER_LEADER = "CLAN_TRANSFER_LEADER";
    public static final String CLAN_SET_SUB_LEADER = "CLAN_SET_SUB_LEADER";
    public static final String CLAN_RELEASE_SUB_LEADER = "CLAN_RELEASE_SUB_LEADER";
    private ClanMessageTypes() {}
}
