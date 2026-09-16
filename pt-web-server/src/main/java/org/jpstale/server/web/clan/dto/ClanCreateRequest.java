package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClanCreateRequest {
    @NotBlank @Size(min = 1, max = 50)
    private String clanName;
    @NotBlank @Size(min = 1, max = 50)
    private String charName;
    private Integer charType;
    private Integer level;
}
