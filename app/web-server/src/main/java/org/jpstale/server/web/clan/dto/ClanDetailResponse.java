package org.jpstale.server.web.clan.dto;

import lombok.Data;
import java.util.List;

@Data
public class ClanDetailResponse {
    private Integer clanId;
    private String clanName;
    private String leader;
    private String subLeader;
    private String note;
    private Integer memberCount;
    private Integer iconId;
    private String regiDate;
    private String limitDate;
    private Long clanMoney;
    private Integer cPoint;
    private Integer rank;
    private boolean amLeader;
    private boolean amSubLeader;
    private List<ClanMemberDto> members;
}
