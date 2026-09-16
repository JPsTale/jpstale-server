package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClanDetailRequest {
    @NotBlank
    private String charName;
}
