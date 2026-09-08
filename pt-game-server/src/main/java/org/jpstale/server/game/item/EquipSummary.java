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
    public int bootsSpeed;       // 靴子移速（模板 runSpeedMin 最大）
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
            // 主手武器
            if (it.getSlot() == ItemLocations.SLOT_MAIN_HAND) {
                s.hasWeapon = true;
                s.weaponDamageMin = it.getDamageMin();
                s.weaponDamageMax = it.getDamageMax();
                s.attackSpeed += def != null && def.getAtkSpeed() != null ? def.getAtkSpeed() : 0;
                s.range = def != null && def.getRange() != null ? def.getRange() : 0;
            }
            // 副手盾/法球：不参与攻击/攻速，格挡在此
            if (def != null) {
                int c = def.getClassItem() == null ? 0 : def.getClassItem();
                if (c == 8 || c == 16 || c == 32) {
                    // 防具系（甲/靴/手）：防御 + 可能格挡/移速
                    s.defense += it.getDefence();
                    s.block += it.getBlockRating();
                    if (c == 16) {
                        int bs = def.getRunSpeedMin() == null ? 0 : (int) (double) def.getRunSpeedMin();
                        s.bootsSpeed = Math.max(s.bootsSpeed, bs);
                    }
                    s.absorb += it.getAbsorb();
                    s.critical += def.getCritical() == null ? 0 : def.getCritical();
                } else {
                    // 其余装备（含盾 class2/饰品/宝石等）：吸收/格挡/暴击/攻速/射程取自实例或模板
                    s.absorb += it.getAbsorb();
                    s.block += it.getBlockRating();
                    s.critical += def.getCritical() == null ? 0 : def.getCritical();
                    s.attackSpeed += def.getAtkSpeed() == null ? 0 : def.getAtkSpeed();
                    s.range = Math.max(s.range, def.getRange() == null ? 0 : def.getRange());
                }
            }
        }
        return s;
    }
}
