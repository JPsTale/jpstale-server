package org.jpstale.server.common.redis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClanMessageData {
    private String clanName;
    private String userId;
    private String charName;
    private Integer charType;
    private Integer level;
    private String targetName;
    private Integer targetType;
    private Integer targetLevel;
    private String targetUserId;

    public static ClanMessageData create(String clanName, String userId, String charName, Integer charType, Integer level) {
        return new ClanMessageData(clanName, userId, charName, charType, level, null, null, null, null);
    }

    public static ClanMessageData simple(String clanName, String userId, String charName) {
        return new ClanMessageData(clanName, userId, charName, null, null, null, null, null, null);
    }
}
