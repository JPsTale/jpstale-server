package org.jpstale.server.web.item;

import lombok.Getter;
import org.jpstale.server.web.admin.AdminQueryParams;

import java.util.Map;

/**
 * 物品列表接口的筛选参数（设计文档 §5.2）—— 入参键用**数据库列名族**
 * （`name_like` / `reqlevel_min` / `weaponclass` …），与"对外用列名"的口径一致。
 *
 * <p>
 * 公共部分（`sort`/`order`/`page`/`size`、空串=没填、分页夹紧、未知键 400）在
 * {@link AdminQueryParams} 里 —— 物品与怪物共用同一套；这里只列**物品的四组条件**。
 */
@Getter
public final class ItemQueryParams extends AdminQueryParams {

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

    private ItemQueryParams() {
    }

    /**
     * @throws IllegalArgumentException 未知键、非整数、区间倒置（min &gt; max）
     */
    public static ItemQueryParams parse(Map<String, String> raw) {
        ItemQueryParams q = new ItemQueryParams();
        each(raw, (key, value) -> {
            if (q.commonKey(key, value)) {
                return;     // sort / order / page / size
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
                default -> throw new IllegalArgumentException("未知的查询参数：" + key);
            }
        });
        q.checkNotReversed("reqlevel", q.reqlevelMin, q.reqlevelMax);
        q.checkNotReversed("price", q.priceMin, q.priceMax);
        q.checkNotReversed("weight", q.weightMin, q.weightMax);
        q.checkNotReversed("defense", q.defenseMin, q.defenseMax);
        q.checkNotReversed("atkpow1", q.atkpow1Min, q.atkpow1Max);
        q.checkNotReversed("atkpow2", q.atkpow2Min, q.atkpow2Max);
        q.checkNotReversed("atkrating", q.atkratingMin, q.atkratingMax);
        q.checkNotReversed("absorb", q.absorbMin, q.absorbMax);
        q.checkNotReversed("block", q.blockMin, q.blockMax);
        // sort 白名单 + 分页夹紧（夹紧同时是 LIMIT 的注入防线）
        q.finish(ItemColumnRegistry.REGISTRY);
        return q;
    }
}
