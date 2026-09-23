package org.jpstale.common.service.props;

/**
 * 技能键：**只 2 个模板常量**（不是 440 个）。
 *
 * <p>键形 `skill.&lt;0xID&gt;.&lt;point|mastery&gt;`（`0xID` = 数字 `skillId` 的小写 6 位 hex），
 * 由 {@link SkillKeys} 生成；别处不许手拼 `skill.*` 字符串。
 */
public enum SkillKey implements IPlayerKey {

    POINT("skill.%s.point", 0, "技能等级"),
    MASTERY("skill.%s.mastery", 0, "熟练度"),
    ;

    private final String key;
    private final int defaultValue;
    private final String desc;

    SkillKey(String key, int defaultValue, String desc) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.desc = desc;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public int getDefaultValue() {
        return defaultValue;
    }

    @Override
    public String getDesc() {
        return desc;
    }
}
