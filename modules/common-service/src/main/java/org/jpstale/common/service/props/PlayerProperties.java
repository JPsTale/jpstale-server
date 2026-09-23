package org.jpstale.common.service.props;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `characterinfo.props` 的**唯一**解析/序列化实现，外加键注册表的默认值查询。
 *
 * <p>存储只放 `int`（技能等级 ≤10、熟练度 ≤10000、点数/标志位都是小整数）：类型越少越安全 ——
 * `getLong` 读一个 int 写的键、`getBool` 读一个用 2 写的键，这类**跨类型读**是键值包最典型的静默 bug。
 *
 * <p>纪律：读到非整数 / 值不是 int ⇒ **抛**（消息带键名），不静默当 0 —— 静默会把"数据坏了"
 * 伪装成"这个角色没学过这个技能"。
 */
public final class PlayerProperties {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 已知**具名**键的默认值（{@link PlayerKey} 的显式成员；模板成员与技能键见 {@link #defaultOf}）。 */
    private static final Map<String, Integer> NAMED_DEFAULTS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (PlayerKey k : PlayerKey.values()) {
            m.put(k.getKey(), k.getDefaultValue());
        }
        NAMED_DEFAULTS = Map.copyOf(m);
    }

    private PlayerProperties() {
    }

    /** jsonb 文本 → 键值包。null/空白 ⇒ 空包（列有 DEFAULT '{}'，缺值是常态）。 */
    public static Map<String, Integer> parse(String json) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("props 不是合法 JSON：" + json, e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("props 期望 JSON 对象（键 → int），实得 " + json);
        }
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            // ⚠ 必须判 `isIntegralNumber`：Jackson 的 `canConvertToInt()` 对 1.5 **返回 true**（asInt 截断成 1），
            //   光靠它会把小数静默截断，与"只存 int、读不到就抛"的口径相反。
            if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
                throw new IllegalArgumentException("props 键 '" + e.getKey()
                        + "' 的值必须是 int，实得 " + (v == null ? "缺值" : v.toString()));
            }
            out.put(e.getKey(), v.asInt());
        }
        return out;
    }

    /** 键值包 → jsonb 文本（null ⇒ `{}`）。 */
    public static String serialize(Map<String, Integer> props) {
        try {
            return MAPPER.writeValueAsString(props == null ? Map.of() : props);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("props 序列化失败：" + props, e);
        }
    }

    /** 键的注册表默认值：具名键 → 其声明值；技能键 → 对应模板的默认值；绑定键 → 0；未注册 → 0。 */
    public static int defaultOf(String key) {
        Integer named = key == null ? null : NAMED_DEFAULTS.get(key);
        if (named != null) {
            return named;
        }
        if (PlayerKey.isBindKey(key)) {
            return 0;   // 绑定键的默认值就是「未绑」（拳位 0 = 普通攻击拳）
        }
        SkillKey skillKey = skillKeyOf(key);
        return skillKey != null ? skillKey.getDefaultValue() : 0;
    }

    /** 键是否在注册表里（具名成员，或 `skill.*` / `bind.*` 的键形之一）——`unknownKeys` 的判据。 */
    public static boolean isKnown(String key) {
        return key != null
                && (NAMED_DEFAULTS.containsKey(key) || PlayerKey.isBindKey(key) || skillKeyOf(key) != null);
    }

    /** 技能键 → 模板枚举；不是技能键（前缀不对、字段名不是 point/mastery）⇒ null。 */
    private static SkillKey skillKeyOf(String key) {
        if (key == null || !key.startsWith("skill.")) {
            return null;
        }
        if (key.endsWith(".point")) {
            return SkillKey.POINT;
        }
        return key.endsWith(".mastery") ? SkillKey.MASTERY : null;
    }
}
