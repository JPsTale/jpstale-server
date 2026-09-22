package org.jpstale.server.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理端的**列元信息**（`GET /api/admin/{items,monsters}/columns` 的条目）。
 *
 * <p>
 * `column` 是**数据库列名**，也是这些接口对外的唯一口径（物品设计文档 §二）。
 * 由 {@code ColumnRegistry} 对实体反射生成 —— 加列不用改这里，也不用改页面。
 *
 * <p>
 * `kind` 与 `options` 来自各实体自己的语义表（{@code ItemColumnSemantics} /
 * {@code MonsterColumnSemantics}）：让界面能把裸数字翻成名字、并用下拉编辑
 * （用户 2026-09-21 反馈"纯数字没有语义"）。
 *
 * <p>
 * ⚠ 物品与怪物**共用本类**：形状一模一样，抄第二份只会让两边慢慢漂
 * （AGENTS #15：同一个判定在仓库里出现第二份就是 bug 的种子）。
 */
@Data
public class AdminColumn {

    /** 数据库列名。 */
    private String column;

    /** 实体字段类型名：Integer / Double / String / Boolean（供前端选控件、供后端做写入类型校验）。 */
    private String javaType;

    /** 所属段 id（各表自己定义，如 Identity / Combat / Spawn…；漏归类的落 Unassigned）。 */
    private String section;

    /** 段名的**文案 key**（不是文案本身 —— 文案在页面侧 `static/i18n/*.json`，服务端不发可显示文本）。 */
    private String sectionLabelKey;

    /** 是否主键。 */
    private boolean primaryKey;

    /** 是否允许通过 `POST /{id}` 修改。 */
    private boolean editable;

    /** 是否参与筛选（各表自己的固定筛选组）。 */
    private boolean filterable;

    /**
     * 值的种类：`NUMBER` / `TEXT` / `BOOL` / `ENUM`。
     * 界面据此选控件：`ENUM` → 下拉、`BOOL` → 勾选、其余 → 文本框。
     */
    private String kind;

    /** `ENUM` 的候选值（value + 文案 key）；其他 kind 为空列表。 */
    private List<Option> options;

    /**
     * `ENUM` 里**取值为文本**的那类列（怪物表的 `monstertype`/`propertymon`）的候选：
     * 数据库原值（逐字、大小写敏感）+ 文案 key。
     *
     * <p>
     * 数字枚举走 {@link #options}，文本枚举走这里 —— 两者的 `value` 类型不同，
     * 合成一个字段就得把数字也当字符串发，改了既有接口的含义（不如多一个显式字段）。
     */
    private List<TextOption> textOptions;

    /**
     * 位掩码列的**位表**（目前只有物品的 `classitem`）：每位一个 value + 文案 key。
     * 界面用它拼出**不在候选里**的组合值（服务端不拼串，避免把文案拼进接口）。
     */
    private List<Bit> bits;

    /**
     * 游戏内展示用的**行名 key**（与客户端串表 `itemtip.*` 同名同义）；没有名字的列为 null
     * （界面回退显示数据库列名 —— **不编一个名字出来**）。
     */
    private String rowLabelKey;

    /**
     * 在**行内**的位置：`0` = 单值一行；`1`/`2` = 同一 `rowLabelKey` 下的第 1/2 个值
     * （界面把它渲染成 `值1 - 值2` 的区间）。
     *
     * <p>
     * ⚠ 配对不一定是"同一列的 min/max"：物品的 `atkpow1min` 与 **`atkpow2min`** 才是同一行
     * （「攻击力(小) 16-19」），`atkpow1max` 与 `atkpow2max` 是另一行（「攻击力(大) 26-30」）。
     * 界面按 `rowLabelKey` 分组、按 `rowPart` 排序即可，不需要知道是哪种配法。
     */
    private int rowPart;

    /**
     * 值的**单位**（如 `%`），没有则为 null。
     * 单位是**数据**、不是文案（不参与 i18n）；由服务端给，界面只是接上去。
     */
    private String unit;

    /** 一个候选值：数据库原值 + 文案 key。 */
    @Data
    public static class Option {
        private Integer value;
        /** 文案 key（客户端翻译）；**永不**是文案本身。 */
        private String labelKey;
    }

    /** 位掩码的一位：位值 + 文案 key。 */
    @Data
    public static class Bit {
        private Integer value;
        private String labelKey;
    }

    /** 文本枚举的一个候选：数据库原值（逐字）+ 文案 key。 */
    @Data
    public static class TextOption {
        private String value;
        /** 文案 key（客户端翻译）；**永不**是文案本身。 */
        private String labelKey;
    }
}
