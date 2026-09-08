package org.jpstale.server.game.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import lombok.Data;

/**
 * 物品实例（内存态）：掷点结果 + 归属位置 + 模板引用。
 * <p>
 * 与 userdb.item 行一一对应；uid 即 DB id（未落库前为 null）。
 * 位置用 (location, slot) 表达（见 {@link ItemLocations}）。
 */
@Data
public class ItemInstance {

    /** DB 主键 uid；未 INSERT 前为 null */
    private Long id;

    // ---- 归属位置 ----
    private int characterId;
    private int location;
    private int slot;

    /** 堆叠数量（可堆叠物 >1；装备恒 1） */
    private int count = 1;

    // ---- 定义引用 ----
    private Integer itemListId;
    private Integer itemCode;
    /** 模板（来自 gamedb.itemlist），供占格/分类/需求校验；不参与持久化 */
    private transient ItemList template;

    // ---- 掷点结果（对应 userdb.item 列）----
    private int durability;
    private int durabilityMax;
    private int resBionic, resEarth, resFire, resIce, resLighting, resPoison, resWater, resWind;
    private int damageMin, damageMax;
    private int attackRating;
    private double absorb;
    private int defence;
    private double blockRating;
    private double speed;
    private double manaRegen, lifeRegen, staminaRegen;
    private double increaseLife, increaseMana, increaseStamina;

    // ---- 需求（掷点时已做职业修正）----
    private int reqLevel, reqStrength, reqSpirit, reqTalent, reqAgility, reqHealth;
    private int price;
    private int jobCodeMask;

    // ---- 职业特效增益 ----
    private double specAbsorb;
    private int specDefence;
    private double specSpeed;
    private double specPerManaRegen;
    private int specLevAttackRating;

    // ---- 锻造/合成状态（本轮仅存储，无操作逻辑）----
    private int agingNum;
    private int agingNum2;
    private int agingExp;
    private int agingExpMax;

    /** 软删标记（丢弃/扫地销毁后 true，DB 行带 delete_time） */
    private boolean deleted;

    // ------------------------------------------------------------------
    // 便捷查询（依赖模板）
    // ------------------------------------------------------------------

    /** 占格宽（格数，模板 width/22；模板缺失按 1） */
    public int gridW() {
        if (template == null || template.getWidth() == null) {
            return 1;
        }
        return Math.max(1, template.getWidth() / 22);
    }

    /** 占格高（格数，模板 height/22） */
    public int gridH() {
        if (template == null || template.getHeight() == null) {
            return 1;
        }
        return Math.max(1, template.getHeight() / 22);
    }

    /**
     * 是否可堆叠（消耗/材料/药水等 count 语义）；装备不可堆叠恒 1。
     * <p>
     * DB classitem 即原版 INVENTORY_POS 位值：2副手/4单/6双手/8甲/16靴/32手/
     * 192戒/256宝石/512项链/2048护腕/8192药水/16384时装 均为装备位；
     * classitem=1 与 0 无装备位（消耗/材料/任务等）→ 可堆叠。
     */
    public boolean stackable() {
        if (template == null || template.getClassItem() == null) {
            return true;
        }
        int c = template.getClassItem();
        return c == 0 || c == 1;
    }

    /** 物品名（模板）。 */
    public String name() {
        return template == null ? "?" : template.getName();
    }

    /** 判断该槽位是否允许放本物品（装备槽位校验，后续由 EquipService 细化）。 */
    public int slotIndex() {
        return slot;
    }
}
