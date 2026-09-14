package org.jpstale.server.game.service;

import org.jpstale.server.game.model.DamageResult;
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
    private PlayerStatCalculator statCalculator;

    /**
     * 计算玩家对怪物的伤害
     */
    public DamageResult calculatePlayerToMonster(Player player, Monster monster, int skillDamage) {
        // 0. 命中判定（原版 Accuracy_Table：攻方命中+等级 对 防方等级+防御）
        //    必须在暴击/伤害之前：未命中就不掷暴击、不出伤害，否则会出现「MISS 却带暴击」的自相矛盾。
        //    B 方案下这一掷发生在**起手**，结果随 S2C_AttackPlan 下发，客户端在事件帧就能直接播挥空音。
        int hitPercent = statCalculator.accuracyPvp(player, monster.getLevel(), monster.getDefense());
        if (ThreadLocalRandom.current().nextInt(100) >= hitPercent) {
            return DamageResult.miss();
        }

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

        // 5. 怪物等级过高保护（对齐原版 Svr_Damge.cpp：用最大血量，非当前血量）
        if (monster.getMaxHp() / 4 < baseDamage && monster.getLevel() > player.getLevel()) {
            baseDamage = ThreadLocalRandom.current().nextInt(monster.getMaxHp() / 8, monster.getMaxHp() / 4 + 1);
        }

        // 6. 最小伤害为1
        result.setFinalDamage(Math.max(1, baseDamage));
        return result;
    }

    /**
     * 计算怪物对玩家的伤害
     */
    public DamageResult calculateMonsterToPlayer(Monster monster, Player player) {
        // 0. 命中判定（原版 `sinGetMonsterAccuracy`：怪.attackRating 对 玩家.等级/防御，系数 ×2、等级修正 ×50）
        //    必须在伤害/格挡之前：未命中就不该有伤害，也不该触发受击硬直与受击音（客户端按 damage 判断）。
        int hitPercent = statCalculator.monsterAccuracyPvp(
            monster.getLevel(), monster.getAttackRating(),
            player.getLevel(), calculatePlayerDefense(player));
        if (ThreadLocalRandom.current().nextInt(100) >= hitPercent) {
            return DamageResult.miss();
        }

        DamageResult result = new DamageResult();

        // 1. 基础伤害 = 怪物攻击力
        int baseDamage = monster.getAttack();
        result.setRawDamage(baseDamage);

        // 2. 玩家吸收减伤 —— **明文减伤，不是百分比**（用户 2026-09-14 纠正）。
        //    怪攻 3、吸收 1 ⇒ 伤害 3-1=2。与"怪物吸收"（百分比减伤，见 calculatePlayerToMonster）不同。
        //    原版佐证：技能描述写的是 "Physical Absorption's **fixed absorb value**"、
        //    `Metal Armor ... chains ... fixed absorb value by 200%`（skillData.ts:58），
        //    且 `sinTempAbsorb` 累加的是 `P_Absorb = {{3,4},...}` 这类**明文值**。
        int playerAbsorb = playerAbsorbValue(player);
        baseDamage = Math.max(1, baseDamage - playerAbsorb);

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
     * 计算玩家基础攻击力（对齐原版 sinInvenTory.cpp）
     * <p>
     * 徒手：按 DamageMelee 系数用属性公式；有武器时按武器伤害 * 属性系数。
     * 武器伤害用掷点实例值（EquipSummary.weaponDamageMin/Max），非模板区间。
     */
    private int calculatePlayerAttack(Player player) {
        // 与面板同源：含武器伤害的攻击力区间（statCalculator.attackPower）
        int[] ap = statCalculator.attackPower(player);
        int min = ap[0];
        int max = ap[1];
        return ThreadLocalRandom.current().nextInt(Math.max(1, min), Math.max(2, max + 1));
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
     * 玩家吸收的**明文减伤点数**（用户 2026-09-14：玩家的 absorb 是纯数字直接减伤，不是百分比）。
     *
     * 来源 = `PlayerStatCalculator` 的 `absorption`（公式值 + 装备 absorb 掷点值）。
     * ⚠ 这里**不再**有 `Math.min(80, ...)` 的百分比上限 —— 那个 80 属于"当百分比用"的旧口径。
     */
    private int playerAbsorbValue(Player player) {
        return statCalculator.absorption(player);
    }

    /**
     * 计算玩家格挡率（statCalculator.blockChance 已含装备格挡掷点值；上限 50）
     */
    private int calculateBlockRate(Player player) {
        return Math.min(50, statCalculator.blockChance(player));
    }

    /**
     * 计算玩家防御力（statCalculator.defense 已含装备防御掷点值）
     */
    private int calculatePlayerDefense(Player player) {
        return statCalculator.defense(player);
    }
}
