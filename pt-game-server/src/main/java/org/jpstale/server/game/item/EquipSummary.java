package org.jpstale.server.game.item;

import org.jpstale.server.game.model.Player;

/**
 * 装备汇总：从玩家已装备实例（location=EQUIP 的掷点实例）聚合战斗/面板加成。
 * <p>
 * 取代旧的"遍历假 Equipment 模板平均"——本类用 {@code userdb.item} 行的掷点结果值
 * （defence/absorb/block_rating/damage_min/max 等具体实例值），而非模板 [min,max] 平均。
 * 供 PlayerStatCalculator（面板）与 DamageCalculator（战斗）共用，避免两处口径漂移。
 */
public final class EquipSummary {

    public int defense;          // 装备防御（防具 defence 掷点值）
    public double absorb;        // 装备吸收（absorb 掷点值）
    public double block;         // 装备格挡（block_rating 掷点值）
    public int attackSpeed;      // 攻速（模板 atkSpeed，非掷点）
    public int critical;         // 暴击（模板 critical，非掷点）
    public int range;            // 射程（模板 range）
    public double bootsSpeed;    // 移速加成：靴子掷点 speed + 职业特效 spec_speed（按职业掩码）
    public double regenHp, regenMp, regenStm;  // 回复
    public int increaseLife, increaseMana, increaseStamina; // 上限提升
    public boolean hasWeapon;    // 是否装备主手武器
    public int weaponDamageMin, weaponDamageMax; // 武器伤害掷点值
    public int weight;           // 装备总重（负重用）

    /** 从玩家已装备掷点实例聚合。 */
    public static EquipSummary of(Player player) {
        EquipSummary s = new EquipSummary();
        PlayerItems items = player.getItems();
        if (items == null) {
            return s;
        }
        int job = player.getJob();
        for (ItemInstance it : items.itemsIn(ItemLocations.EQUIP)) {
            if (it.isDeleted()) {
                continue;
            }
            var def = it.getTemplate();
            // 上限提升（掷点实例值）
            s.increaseLife += (int) it.getIncreaseLife();
            s.increaseMana += (int) it.getIncreaseMana();
            s.increaseStamina += (int) it.getIncreaseStamina();
            // 回复（掷点实例值）
            s.regenHp += it.getLifeRegen();
            s.regenMp += it.getManaRegen();
            s.regenStm += it.getStaminaRegen();
            // 负重
            if (def != null && def.getWeight() != null) {
                s.weight += def.getWeight();
            }
            // 主手武器：攻速/射程/伤害只在此计入一次（下方 else 分支不再重复累加攻速/射程）
            boolean mainHand = it.getSlot() == ItemLocations.SLOT_MAIN_HAND;
            if (mainHand) {
                s.hasWeapon = true;
                s.weaponDamageMin = it.getDamageMin();
                s.weaponDamageMax = it.getDamageMax();
                s.attackSpeed += def != null && def.getAtkSpeed() != null ? def.getAtkSpeed() : 0;
                s.range = def != null && def.getRange() != null ? def.getRange() : 0;
            }
            if (def != null) {
                int c = def.getClassItem() == null ? 0 : def.getClassItem();
                if (ItemClass.isBodyGear(c)) {
                    // 防具系（甲/靴/手）：防御 + 可能格挡/移速
                    s.defense += it.getDefence();
                    s.block += it.getBlockRating();
                    if (c == ItemClass.BOOTS) {
                        // 靴子基础移速用「实例掷点 speed」，非模板 runSpeedMin（模板 min 常<1，取整会变 0）
                        s.bootsSpeed += it.getSpeed();
                    }
                    s.absorb += it.getAbsorb();
                    s.critical += def.getCritical() == null ? 0 : def.getCritical();
                } else {
                    // 其余装备（含盾 class2/饰品/宝石等）：吸收/格挡/暴击取自实例或模板
                    s.absorb += it.getAbsorb();
                    s.block += it.getBlockRating();
                    s.critical += def.getCritical() == null ? 0 : def.getCritical();
                    if (!mainHand) {
                        // 主手武器的攻速/射程已在上方计入 → 避免重复累加（曾致攻速翻倍）
                        s.attackSpeed += def.getAtkSpeed() == null ? 0 : def.getAtkSpeed();
                        s.range = Math.max(s.range, def.getRange() == null ? 0 : def.getRange());
                    }
                }
                // 职业特效移速：仅当装备职业掩码包含本职业时生效（对齐客户端 ItemInfo 特效显示）
                s.bootsSpeed += specIfJob(job, it.getJobCodeMask(), it.getSpecSpeed());
            }
        }
        return s;
    }

    /** 职业特效：装备 jobCodeMask 含该职业位 → 返回特效值，否则 0（掩码 0 视为无特效） */
    private static double specIfJob(int job, int jobCodeMask, double value) {
        if (value == 0 || jobCodeMask == 0 || job < 1 || job > 12) {
            return 0;
        }
        int bit = 1 << (job - 1);
        return (jobCodeMask & bit) != 0 ? value : 0;
    }
}
