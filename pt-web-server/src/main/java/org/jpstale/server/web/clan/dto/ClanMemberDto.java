package org.jpstale.server.web.clan.dto;

import lombok.Data;

@Data
public class ClanMemberDto {
    private String charName;
    private String userId;
    private Integer charType;
    private Integer charLevel;
    private String permission;
    private String joinDate;
}
