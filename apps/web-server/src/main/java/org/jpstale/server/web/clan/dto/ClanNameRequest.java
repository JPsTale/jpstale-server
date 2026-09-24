package org.jpstale.server.web.clan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jpstale.common.service.clan.ClanManager;

@Data
public class ClanNameRequest {
    /**
     * ⚠ 上限引用 {@link ClanManager#MAX_NAME_LENGTH}，**不要在这里另写一个数** ——
     * 否则会出现"查重说有 50 字可用、建会却只收 20 字"这种自相矛盾（原先是硬编码 50，
     * 与建会侧毫无关联）。
     */
    @NotBlank @Size(min = 1, max = ClanManager.MAX_NAME_LENGTH)
    private String clanName;
}
