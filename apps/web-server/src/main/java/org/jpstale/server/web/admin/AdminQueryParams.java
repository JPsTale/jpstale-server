package org.jpstale.server.web.admin;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 管理端列表接口的**筛选 / 排序 / 分页**参数基类（物品与怪物共用）。
 *
 * <p>
 * 为什么自己解析而不用 `@ModelAttribute`：Spring 的表单绑定按**属性名精确匹配**，
 * 不会把 `reqlevel_min` 绑到 `reqlevelMin`（放松绑定只对 `@ConfigurationProperties` 生效）。
 * 而把条件交给一张显式白名单，正好能实现"**未知键一律 400**"——
 * 静默忽略非法参数会让人把"没生效"当成"生效了"（本项目明令禁止的失败方式）。
 *
 * <p>
 * 两条口径：
 * <ul>
 *   <li>**值为空串 = 没填**，跳过（表单里没填的输入框就会这样提交）；**非空但非法** ⇒
 *       {@link IllegalArgumentException}（由 Controller 落成 400）。</li>
 *   <li>`page` / `size` 越界**夹紧**而不是报错（`page ≥ 1`、`1 ≤ size ≤ 200`）；
 *       夹紧同时是 `LIMIT` 的注入防线。</li>
 * </ul>
 *
 * <p>
 * 子类只需：在 {@code parse} 里对每个键调用 {@link #commonKey}（公共键），自己处理剩下的键
 * （**未知键必须抛异常**），最后调用 {@link #finish}。
 */
public abstract class AdminQueryParams {

    /** size 上限。也是 LIMIT 的注入防线（见 AdminEntityService 的分页实现）。 */
    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 50;

    /** 数据库列名；null 表示按主键升序（默认）。非法列名 ⇒ 400。 */
    private String sort;
    private boolean desc;
    private int page = 1;
    private int size = DEFAULT_SIZE;

    public String getSort() {
        return sort;
    }

    public boolean isDesc() {
        return desc;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    /** 公共键（`sort`/`order`/`page`/`size`）；返回 true 表示已被本方法处理。 */
    protected boolean commonKey(String key, String value) {
        switch (key) {
            case "sort" -> sort = value.trim();
            case "order" -> {
                String o = value.trim().toLowerCase();
                if (!o.equals("asc") && !o.equals("desc")) {
                    throw new IllegalArgumentException("order 只能是 asc 或 desc，收到：" + value);
                }
                desc = o.equals("desc");
            }
            case "page" -> page = intOf(key, value);
            case "size" -> size = intOf(key, value);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 收尾：`sort` 必须是库里的列名 + 分页夹紧。子类解析完自己的键后调用。 */
    protected void finish(ColumnRegistry registry) {
        if (sort != null && !registry.has(sort)) {
            throw new IllegalArgumentException("sort 必须是数据库列名，收到：" + sort);
        }
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
    }

    /** 实际排序的列：没指定就按**主键**升序（列名从注册表取，各表主键名可以不同）。 */
    public String sortColumnOrDefault(ColumnRegistry registry) {
        return sort != null ? sort : registry.primaryKeyName();
    }

    /** 遍历入参：**空串 = 没填**（表单里没填的输入框就会这样提交）。 */
    protected static void each(Map<String, String> raw, BiConsumer<String, String> handler) {
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            handler.accept(e.getKey(), v);
        }
    }

    protected static Integer intOf(String key, String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 不是整数：" + value);
        }
    }

    protected void checkNotReversed(String name, Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(name + " 区间倒置（min > max）：" + min + " > " + max);
        }
    }
}
