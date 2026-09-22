package org.jpstale.server.web.admin;

import java.util.List;

/**
 * 列的**取值语义**类型（枚举 / 布尔 / 位掩码 / 成对区间）—— 各表自己登记取值表，
 * 类型定义在**这里共用**（物品与怪物的语义形状一模一样，抄第二份只会漂）。
 *
 * <p>
 * ⚠ 本层**只产出 translate key，不产出任何可显示文案**（与 {@code Result.msg} 的既有约定一致：
 * 服务端给 key、客户端翻译）。文案表在页面侧：`static/i18n/{zh,en}.json`。
 *
 * @see org.jpstale.server.web.item.ItemColumnSemantics
 * @see org.jpstale.server.web.monster.MonsterColumnSemantics
 */
public final class ColumnSemantics {

    /** 值的种类：界面据此决定显示与编辑控件。 */
    public enum Kind {
        /** 数字（默认） */
        NUMBER,
        /** 文本 */
        TEXT,
        /** 0/1 布尔 */
        BOOL,
        /** 有限取值集合：显示翻名字、编辑给下拉 */
        ENUM
    }

    /**
     * 一个候选值。
     *
     * @param value    数据库原值
     * @param labelKey 文案 key（客户端翻译）；**永不**是文案本身
     */
    public record Option(int value, String labelKey) {
    }

    /** 位掩码的一位：位值 + 文案 key（客户端用它拼出任意组合值）。 */
    public record Bit(int value, String labelKey) {
    }

    /**
     * **字符串取值**的候选（文本枚举）。
     *
     * <p>
     * 为什么需要它：有些列的值是**文本**而不是数字 —— 怪物表的 `monstertype`（`Good`/`Normal`/
     * `Neutral`/`Evil`）与 `propertymon`（`Demon`/`Normal`/`Machine`/`Mutant`/`Undead`）。
     * 没有候选，编辑时只能手打字符串，一个拼写错误就是一条脏数据，而且**看不出来**。
     *
     * @param value    数据库原值（**逐字**，大小写敏感）
     * @param labelKey 文案 key；**永不**是文案本身
     */
    public record TextOption(String value, String labelKey) {
    }

    /** 一列的语义。`rowLabelKey`/`rowPart` 见"成对区间"；无行名的列为 (null, 0)。 */
    public record Semantics(Kind kind, List<Option> options, List<TextOption> textOptions, List<Bit> bits,
                            String rowLabelKey, int rowPart, String unit) {

        public static Semantics plain(Kind kind) {
            return new Semantics(kind, List.of(), List.of(), List.of(), null, 0, null);
        }

        /** 文本枚举：候选来自 {@link TextOption}。 */
        public static Semantics textEnum(List<TextOption> options) {
            return new Semantics(Kind.ENUM, List.of(), options, List.of(), null, 0, null);
        }

        /** 复制一份并加上行名（区间行用）。 */
        public Semantics withRow(String labelKey, int part) {
            return new Semantics(kind, options, textOptions, bits, labelKey, part, unit);
        }

        /** 复制一份并加上单位（单位是**数据**、不是文案，故不涉及 i18n）。 */
        public Semantics withUnit(String u) {
            return new Semantics(kind, options, textOptions, bits, rowLabelKey, rowPart, u);
        }
    }

    /** 无任何语义的数字列。 */
    public static final Semantics NUMBER = Semantics.plain(Kind.NUMBER);

    private ColumnSemantics() {
    }
}
