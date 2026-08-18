package org.jpstale.server.game.service;

import org.jpstale.server.game.service.ItemCache;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.ItemTemplate;
import org.jpstale.server.game.model.ItemStack;
import org.jpstale.server.game.model.Equipment;
import org.jpstale.server.game.model.EquipmentSlotType;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.Player;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 伤害计算器 — 对应原版 Svr_Damge.cpp
 */
@Component
public class DamageCalculator {

    @Autowired
    private ItemCache itemCache;

    /**
     * 计算玩家对怪物的伤害
     */
    public DamageResult calculatePlayerToMonster(Player player, Monster monster, int skillDamage) {
        DamageResult result = new DamageResult();

        // 1. 基础伤害 = 玩家攻击力 + 技能伤害
        int baseDamage = skillDamage > 0 ? skillDamage : calculatePlayerAttack(player);
        result.setRawDamage(baseDamage);

        // 2. 暴击判定
        int criticalRate = calculateCriticalRate(player.getLevel(), monster.getLevel());
        if (ThreadLocalRandom.current().nextInt(100) < criticalRate) {
            baseDamage = (baseDamage * 170) / 100; // 1.7x 暴击伤害
            result.setCritical(true);
        }

        // 3. 元素抗性减伤 (TODO: 实现元素系统)
        // baseDamage -= (elementalDamage * monster.getResistance(element)) / 100;

        // 4. 吸收减伤 — 对应原版 Absorption
        int absorption = monster.getAbsorption();
        baseDamage = baseDamage * (100 - absorption) / 100;

        // 5. 怪物等级过高保护
        if (monster.getHp() / 4 < baseDamage && monster.getLevel() > player.getLevel()) {
            baseDamage = ThreadLocalRandom.current().nextInt(monster.getHp() / 8, monster.getHp() / 4 + 1);
        }

        // 6. 最小伤害为1
        result.setFinalDamage(Math.max(1, baseDamage));
        return result;
    }

    /**
     * 计算怪物对玩家的伤害
     */
    public DamageResult calculateMonsterToPlayer(Monster monster, Player player) {
        DamageResult result = new DamageResult();

        // 1. 基础伤害 = 怪物攻击力
        int baseDamage = monster.getAttack();
        result.setRawDamage(baseDamage);

        // 2. 玩家吸收减伤
        int playerAbsorb = calculatePlayerAbsorption(player);
        baseDamage = baseDamage * (100 - playerAbsorb) / 100;

        // 3. 玩家格挡判定
        int blockRate = calculateBlockRate(player);
        if (ThreadLocalRandom.current().nextInt(100) < blockRate) {
            return DamageResult.blocked(baseDamage);
        }

        // 4. 玩家防御力减伤 (固定值)
        int defense = calculatePlayerDefense(player);
        baseDamage = Math.max(1, baseDamage - defense);

        result.setFinalDamage(Math.max(1, baseDamage));
        return result;
    }

    /**
     * 计算玩家基础攻击力
     */
    private int calculatePlayerAttack(Player player) {
        int baseAttack = 5; // 默认拳头伤害

        // 装备武器伤害
        Equipment equip = player.getEquipment();
        if (equip != null) {
            var weapon = equip.getEquipped(EquipmentSlotType.WEAPON);
            if (weapon != null) {
                ItemTemplate template = itemCache.getTemplate(weapon.getItemId());
                if (template != null) {
                    int min = template.getAtkPow1Min();
                    int max = template.getAtkPow1Max();
                    baseAttack = ThreadLocalRandom.current().nextInt(min, max + 1);
                }
            }
        }

        // 等级加成
        baseAttack += player.getLevel() * 2;

        return baseAttack;
    }

    /**
     * 计算暴击率 — 对应原版 GetCritical
     */
    private int calculateCriticalRate(int attackerLevel, int targetLevel) {
        int rate = 5; // 基础暴击率 5%
        rate += ((attackerLevel - targetLevel) * 25) / 100;
        return Math.min(70, Math.max(0, rate)); // 上限 70%
    }

    /**
     * 计算玩家吸收率
     */
    private int calculatePlayerAbsorption(Player player) {
        int absorption = 0;

        // 从装备获取吸收值
        Equipment equip = player.getEquipment();
        if (equip != null) {
            for (var slot : equip.getSlots().values()) {
                ItemTemplate template = itemCache.getTemplate(slot.getItemId());
                if (template != null) {
                    absorption += (int) ((template.getAbsorbMin() + template.getAbsorbMax()) / 2);
                }
            }
        }

        return Math.min(80, absorption); // 上限 80%
    }

    /**
     * 计算玩家格挡率 — 对应原版 sinGetBlockRating
     */
    private int calculateBlockRate(Player player) {
        int blockRate = 0;

        // 从装备获取格挡率
        Equipment equip = player.getEquipment();
        if (equip != null) {
            for (var slot : equip.getSlots().values()) {
                ItemTemplate template = itemCache.getTemplate(slot.getItemId());
                if (template != null) {
                    blockRate += (int) ((template.getBlockMin() + template.getBlockMax()) / 2);
                }
            }
        }

        return Math.min(50, blockRate); // 上限 50%
    }

    /**
     * 计算玩家防御力
     */
    private int calculatePlayerDefense(Player player) {
        int defense = 0;

        // 从装备获取防御力
        Equipment equip = player.getEquipment();
        if (equip != null) {
            for (var slot : equip.getSlots().values()) {
                ItemTemplate template = itemCache.getTemplate(slot.getItemId());
                if (template != null) {
                    defense += (template.getDefenseMin() + template.getDefenseMax()) / 2;
                }
            }
        }

        // 等级加成
        defense += player.getLevel();

        return defense;
    }
}
