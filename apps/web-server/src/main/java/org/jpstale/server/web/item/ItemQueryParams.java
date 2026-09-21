package org.jpstale.server.web.item;

import lombok.Getter;

import java.util.Map;

/**
 * 列表接口的筛选 / 排序 / 分页参数（设计文档 §5.2）—— 入参键用**数据库列名族**
 * （`name_like` / `reqlevel_min` / `weaponclass` …），与"对外用列名"的口径一致。
 *
 * <p>
 * 为什么自己解析而不用 `@ModelAttribute`：Spring 的表单绑定按**属性名精确匹配**，
 * 不会把 `reqlevel_min` 绑到 `reqlevelMin`（放松绑定只对 `@ConfigurationProperties` 生效）。
 * 而把这三个条件交给一张显式白名单，正好能实现"**未知键一律 400**"——
 * 静默忽略非法参数会让人把"没生效"当成"生效了"（本项目明令禁止的失败方式）。
 *
 * <p>
 * 两条口径：
 * <ul>
 *   <li>**值为空串 = 没填**，跳过（表单里没填的输入框就会这样提交）；**非空但非法** ⇒
 *       {@link IllegalArgumentException}（由 Controller 落成 400）。</li>
 *   <li>`page` / `size` 越界**夹紧**而不是报错（`page ≥ 1`、`1 ≤ size ≤ 200`）。</li>
 * </ul>
 */
@Getter
public final class ItemQueryParams {

    /** size 上限。也是 LIMIT 的注入防线（见 AdminItemService 的分页实现）。 */
    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 50;

    // ---- 身份 ----
    private String nameLike;
    private Integer idcode;
    private String category;

    // ---- 数值门槛 ----
    private Integer reqlevelMin;
    private Integer reqlevelMax;
    private Integer priceMin;
    private Integer priceMax;
    private Integer weightMin;
    private Integer weightMax;

    // ---- 装备语义（数据库原值，不翻译语义）----
    private Integer weaponclass;
    private Integer classitem;
    private Integer modelposition;

    // ---- 攻防数值：按"列对"做区间相交（见 AdminItemService）----
    private Integer defenseMin;
    private Integer defenseMax;
    private Integer atkpow1Min;
    private Integer atkpow1Max;
    private Integer atkpow2Min;
    private Integer atkpow2Max;
    private Integer atkratingMin;
    private Integer atkratingMax;
    private Integer absorbMin;
    private Integer absorbMax;
    private Integer blockMin;
    private Integer blockMax;

    // ---- 排序 / 分页 ----
    /** 数据库列名；null 表示按主键升序（默认）。非法列名 ⇒ 400。 */
    private String sort;
    private boolean desc;
    private int page = 1;
    private int size = DEFAULT_SIZE;

    private ItemQueryParams() {
    }

    /**
     * @throws IllegalArgumentException 未知键、非整数、区间倒置（min &gt; max）
     */
    public static ItemQueryParams parse(Map<String, String> raw) {
        ItemQueryParams q = new ItemQueryParams();
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (value == null || value.isBlank()) {
                    continue;   // 空串 = 没填
                }
                switch (key) {
                    case "name_like" -> q.nameLike = value.trim();
                    case "idcode" -> q.idcode = intOf(key, value);
                    case "category" -> q.category = value.trim();
                    case "reqlevel_min" -> q.reqlevelMin = intOf(key, value);
                    case "reqlevel_max" -> q.reqlevelMax = intOf(key, value);
                    case "price_min" -> q.priceMin = intOf(key, value);
                    case "price_max" -> q.priceMax = intOf(key, value);
                    case "weight_min" -> q.weightMin = intOf(key, value);
                    case "weight_max" -> q.weightMax = intOf(key, value);
                    case "weaponclass" -> q.weaponclass = intOf(key, value);
                    case "classitem" -> q.classitem = intOf(key, value);
                    case "modelposition" -> q.modelposition = intOf(key, value);
                    case "defense_min" -> q.defenseMin = intOf(key, value);
                    case "defense_max" -> q.defenseMax = intOf(key, value);
                    case "atkpow1_min" -> q.atkpow1Min = intOf(key, value);
                    case "atkpow1_max" -> q.atkpow1Max = intOf(key, value);
                    case "atkpow2_min" -> q.atkpow2Min = intOf(key, value);
                    case "atkpow2_max" -> q.atkpow2Max = intOf(key, value);
                    case "atkrating_min" -> q.atkratingMin = intOf(key, value);
                    case "atkrating_max" -> q.atkratingMax = intOf(key, value);
                    case "absorb_min" -> q.absorbMin = intOf(key, value);
                    case "absorb_max" -> q.absorbMax = intOf(key, value);
                    case "block_min" -> q.blockMin = intOf(key, value);
                    case "block_max" -> q.blockMax = intOf(key, value);
                    case "sort" -> q.sort = value.trim();
                    case "order" -> {
                        String o = value.trim().toLowerCase();
                        if (!o.equals("asc") && !o.equals("desc")) {
                            throw new IllegalArgumentException("order 只能是 asc 或 desc，收到：" + value);
                        }
                        q.desc = o.equals("desc");
                    }
                    case "page" -> q.page = intOf(key, value);
                    case "size" -> q.size = intOf(key, value);
                    default -> throw new IllegalArgumentException("未知的查询参数：" + key);
                }
            }
        }

        if (q.sort != null && !ItemColumnRegistry.has(q.sort)) {
            throw new IllegalArgumentException("sort 必须是数据库列名，收到：" + q.sort);
        }
        q.checkNotReversed("reqlevel", q.reqlevelMin, q.reqlevelMax);
        q.checkNotReversed("price", q.priceMin, q.priceMax);
        q.checkNotReversed("weight", q.weightMin, q.weightMax);
        q.checkNotReversed("defense", q.defenseMin, q.defenseMax);
        q.checkNotReversed("atkpow1", q.atkpow1Min, q.atkpow1Max);
        q.checkNotReversed("atkpow2", q.atkpow2Min, q.atkpow2Max);
        q.checkNotReversed("atkrating", q.atkratingMin, q.atkratingMax);
        q.checkNotReversed("absorb", q.absorbMin, q.absorbMax);
        q.checkNotReversed("block", q.blockMin, q.blockMax);

        // 分页夹紧（不是报错）：见设计文档 §5.2 —— 夹紧同时是 LIMIT 的注入防线
        if (q.page < 1) {
            q.page = 1;
        }
        if (q.size < 1) {
            q.size = DEFAULT_SIZE;
        }
        if (q.size > MAX_SIZE) {
            q.size = MAX_SIZE;
        }
        return q;
    }

    private static Integer intOf(String key, String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 不是整数：" + value);
        }
    }

    private void checkNotReversed(String name, Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(name + " 区间倒置（min > max）：" + min + " > " + max);
        }
    }

    /** 是否有任何筛选条件（用于日志/诊断）。 */
    public boolean hasFilter() {
        return nameLike != null || idcode != null || category != null
                || reqlevelMin != null || reqlevelMax != null
                || priceMin != null || priceMax != null
                || weightMin != null || weightMax != null
                || weaponclass != null || classitem != null || modelposition != null
                || defenseMin != null || defenseMax != null
                || atkpow1Min != null || atkpow1Max != null
                || atkpow2Min != null || atkpow2Max != null
                || atkratingMin != null || atkratingMax != null
                || absorbMin != null || absorbMax != null
                || blockMin != null || blockMax != null;
    }
}
