package org.jpstale.server.web.clan;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clan 接口统一响应：Code + Key=Value 集合，可格式化为原版文本（\r 分隔）或 JSON。
 * extraLines 用于多行同 key（如 CMem=name1, CMem=name2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClanResponse {

    private int code;
    private Map<String, String> data;
    private List<String> extraLines;

    public static ClanResponse of(int code) {
        return new ClanResponse(code, new LinkedHashMap<>(), new ArrayList<>());
    }

    public static ClanResponse of(int code, Map<String, String> data) {
        return new ClanResponse(code, data != null ? data : new LinkedHashMap<>(), new ArrayList<>());
    }

    public ClanResponse put(String key, String value) {
        if (data == null) data = new LinkedHashMap<>();
        data.put(key, value);
        return this;
    }

    public ClanResponse put(String key, Object value) {
        if (data == null) data = new LinkedHashMap<>();
        data.put(key, value != null ? value.toString() : "");
        return this;
    }

    /** 追加多行（如 CMem=角色名） */
    public ClanResponse addLine(String key, String value) {
        if (extraLines == null) extraLines = new ArrayList<>();
        extraLines.add(key + "=" + (value != null ? value : ""));
        return this;
    }

    /** 原版 .asp 文本格式：Key=Value，行分隔 \r */
    public String toAspText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Code=").append(code);
        if (data != null) {
            for (Map.Entry<String, String> e : data.entrySet()) {
                sb.append("\r").append(e.getKey()).append("=").append(e.getValue() != null ? e.getValue() : "");
            }
        }
        if (extraLines != null) {
            for (String line : extraLines) {
                sb.append("\r").append(line);
            }
        }
        return sb.toString();
    }

    /** SodScore index=1 使用 | 分隔的部分行 */
    public String toAspTextWithPipeSeparator() {
        StringBuilder sb = new StringBuilder();
        sb.append("Code=").append(code);
        if (data != null) {
            for (Map.Entry<String, String> e : data.entrySet()) {
                sb.append("|").append(e.getKey()).append("=").append(e.getValue() != null ? e.getValue() : "");
            }
        }
        return sb.toString();
    }
}
