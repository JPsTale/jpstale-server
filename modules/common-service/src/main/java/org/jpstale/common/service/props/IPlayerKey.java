package org.jpstale.common.service.props;

/**
 * 角色属性包（`characterinfo.props`）的键：模板 + 默认值 + 说明。
 *
 * <p>无参键的模板就是键本身；带参键把变化的部分写成 `%s`，用 {@link #format} 填
 * （"模板 + 枚举"两形态合一的写法见 {@link PlayerKey} 与 {@link SkillKey}）。
 */
public interface IPlayerKey {

    /** 键模板，如 `skill.%s.%s.point`；无参键就是键本身。 */
    String getKey();

    /** 从未写过该键时的值（`getInt` / `getIntOrDefault` 的兜底）。 */
    int getDefaultValue();

    /** 人读说明（短句）。 */
    String getDesc();

    /**
     * 用模板拼键。
     *
     * <p>必须 `Locale.ROOT`：默认 locale 会改写格式化结果（如阿拉伯语 locale 把数字写成阿拉伯数字），
     * 那样拼出来的键**与库里存的不是同一个字符串**，读回默认值、且不报错。
     */
    default String format(Object... args) {
        return String.format(java.util.Locale.ROOT, getKey(), args);
    }
}
