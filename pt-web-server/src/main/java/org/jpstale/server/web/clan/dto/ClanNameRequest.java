package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClanNameRequest {
    @NotBlank @Size(min = 1, max = 50)
    private String clanName;
}
