package org.jpstale.server.web.clan.dto;

import lombok.Data;

@Data
public class ClanRankDto {
    private Integer clanId;
    private String clanName;
    private String leader;
    private Integer memberCount;
    private Integer iconId;
    private Integer cPoint;
    private Long clanMoney;
}
