package org.jpstale.common.service.stat;

import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.MonsterStats;
import org.jpstale.common.service.model.Player;
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
     * 计算玩家对怪物的伤害。
     *
     * 怪物侧参数是 {@link MonsterStats} 快照而非实体：共享层不认 app 层的运行时实体。
     */
    public DamageResult calculatePlayerToMonster(Player player, MonsterStats monster, int skillDamage) {
        return calculatePlayerToMonster(player, monster, skillDamage, 0);
    }

    /**
     * **技能对判定的修正**（原版随技能包/技能码下发的那些量）。
     *
     * <p>为什么收成一个 record 而不是继续加 int 形参：两个相邻的 int 含义不同
     * （命中加成 vs 暴击加成），调用处 `f(x, y, 0, 30)` 读不出哪个是哪个 —— 这类"位置参数张冠李戴"
     * 不会编译错、只是数值悄悄不对。
     *
     * @param accuracyBonusPct 命中（`Attack_Rating`）加成百分比 —— Jumping Crash 的"施法前临时加命中"
     *                         （`SkillSub.cpp:1935-1943`，`Jumping_Crash_Attack_Rating[point]`）
     * @param critBonusPct     暴击**率**加成（加在暴击率上再掷，不是加伤害）—— Critical Hit 的
     *                         `Critical_Hit_Critical[point]`（`Svr_Damge.cpp` 的 `Critical[0] += 表值`）
     */
    public record SkillMods(int accuracyBonusPct, int critBonusPct) {
        public static final SkillMods NONE = new SkillMods(0, 0);
    }

    /**
     * 带技能修正的版本（命中加成 / 暴击率加成）。
     */
    public DamageResult calculatePlayerToMonster(Player player, MonsterStats monster, int skillDamage, SkillMods mods) {
        // 0. 命中判定（原版 Accuracy_Table：攻方命中+等级 对 防方等级+防御）
        //    必须在暴击/伤害之前：未命中就不掷暴击、不出伤害，否则会出现「MISS 却带暴击」的自相矛盾。
        //    B 方案下这一掷发生在**起手**，结果随 S2C_AttackPlan 下发，客户端在事件帧就能直接播挥空音。
        int hitPercent = statCalculator.accuracyPvp(player, monster.level(), monster.defense(),
                mods.accuracyBonusPct());
        if (ThreadLocalRandom.current().nextInt(100) >= hitPercent) {
            return DamageResult.miss();
        }
        return damageBody(player, monster, skillDamage, mods.critBonusPct());
    }

    /** 只要暴击率加成的版本（Critical Hit 等）。 */
    public DamageResult calculatePlayerToMonster(Player player, MonsterStats monster, int skillDamage, int critBonusPct) {
        return calculatePlayerToMonster(player, monster, skillDamage, new SkillMods(0, critBonusPct));
    }

    /**
     * **必中**版本：不做命中判定（原版 `dm_SelectRange(..., FALSE)` ⇒ `dmUseAccuracy = 0`，
     * `Damage.cpp:428/454`；Pike Wind 等"必中"技能用它）。
     *
     * <p>为什么单独一个入口而不是加个 boolean 形参：调用点读起来就是它的语义（`…AlwaysHit`），
     * 而 boolean 形参在调用处不可读（`true` 是什么意思要靠翻签名）。
     * 除命中判定外，其余（暴击/吸收/过度保护/最小 1 点）与普攻**同一条实现**（`damageBody`）。
     */
    public DamageResult calculatePlayerToMonsterAlwaysHit(Player player, MonsterStats monster, int skillDamage) {
        return damageBody(player, monster, skillDamage, 0);
    }

    /** 命中判定**之后**的那半段（伤害本体）：普攻与所有技能共用，保证"不同技能只在命中/加成上分岔"。 */
    private DamageResult damageBody(Player player, MonsterStats monster, int skillDamage, int critBonus) {
        DamageResult result = new DamageResult();

        // 1. 基础伤害 = 玩家攻击力 + 技能伤害
        int baseDamage = skillDamage > 0 ? skillDamage : calculatePlayerAttack(player);
        result.setRawDamage(baseDamage);

        // 2. 暴击判定
        int criticalRate = calculateCriticalRate(player.getLevel(), monster.level()) + critBonus;
        if (ThreadLocalRandom.current().nextInt(100) < criticalRate) {
            baseDamage = (baseDamage * 170) / 100; // 1.7x 暴击伤害
            result.setCritical(true);
        }

        // 3. 元素抗性减伤 (TODO: 实现元素系统)
        // baseDamage -= (elementalDamage * monster.getResistance(element)) / 100;

        // 4. 吸收减伤 — 对应原版 Absorption
        int absorption = monster.absorption();
        baseDamage = baseDamage * (100 - absorption) / 100;

        // 5. 怪物等级过高保护（对齐原版 Svr_Damge.cpp：用最大血量，非当前血量）
        if (monster.maxHp() / 4 < baseDamage && monster.level() > player.getLevel()) {
            baseDamage = ThreadLocalRandom.current().nextInt(monster.maxHp() / 8, monster.maxHp() / 4 + 1);
        }

        // 6. 最小伤害为1
        result.setFinalDamage(Math.max(1, baseDamage));
        return result;
    }

    /**
     * 计算怪物对玩家的伤害
     */
    public DamageResult calculateMonsterToPlayer(MonsterStats monster, Player player) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 0. 命中判定（原版 `sinGetMonsterAccuracy`：怪.attackRating 对 玩家.等级/防御，系数 ×2、等级修正 ×50）
        //    必须在伤害/格挡之前：未命中就不该有伤害，也不该触发受击硬直与受击音（客户端按 damage 判断）。
        int hitPercent = statCalculator.monsterAccuracyPvp(
            monster.level(), monster.attackRating(),
            player.getLevel(), calculatePlayerDefense(player));
        if (rnd.nextInt(100) >= hitPercent) {
            return DamageResult.miss();
        }

        DamageResult result = new DamageResult();

        // 1. 基础伤害 = 怪物攻击力
        int baseDamage = rnd.nextInt(monster.atkMax() - monster.atkMin()) + monster.atkMin() + 1;
        result.setRawDamage(baseDamage);

        // 2. 玩家吸收减伤
        int playerAbsorb = playerAbsorbValue(player);
        baseDamage = Math.max(1, baseDamage - playerAbsorb);

        // 3. 玩家格挡判定
        int blockRate = calculateBlockRate(player);
        if (rnd.nextInt(100) < blockRate) {
            return DamageResult.blocked(baseDamage);
        }

        result.setFinalDamage(baseDamage);
        return result;
    }

    /**
     * 计算**怪物对怪物**的伤害（召唤物打怪 / 怪打召唤物）。
     *
     * <p>
     * 与 {@link #calculateMonsterToPlayer} 的差别只有两处，都是照原版来的：
     * <ol>
     *   <li>**没有格挡** —— 格挡是玩家专有（原版的怪→怪路径里没有 block 判定）；</li>
     *   <li>减伤是**百分比**吸收，不是玩家那种明文减伤点数：
     *       `pw = pow; pw -= (pow * lpTargetChar->smCharInfo.Absorption) / 100;`
     *       （`character.cpp` 里召唤物的范围伤害分支即此写法，见
     *       `docs/召唤物系统-源码分析.md` §4.1 的引用）。</li>
     * </ol>
     *
     * <p>
     * 命中判定与 {@code calculateMonsterToPlayer} 共用 `statCalculator.monsterAccuracyPvp`
     * （原版怪→怪与怪→玩家走的是同一套 `sinGetMonsterAccuracy`）。
     * 两个入参都是 {@link MonsterStats} 快照 —— 共享层不认 app 层的实体，
     * 攻方与防方用同一个记录类型即可，**不要为它加字段**（那会波及所有取快照的调用点）。
     */
    public DamageResult calculateMonsterToMonster(MonsterStats attacker, MonsterStats defender) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 0. 命中判定（同怪→玩家：攻击方 等级/attackRating 对 防御方 等级/defense）
        int hitPercent = statCalculator.monsterAccuracyPvp(
            attacker.level(), attacker.attackRating(), defender.level(), defender.defense());
        if (rnd.nextInt(100) >= hitPercent) {
            return DamageResult.miss();
        }

        DamageResult result = new DamageResult();

        // 1. 基础伤害 = 攻方攻击力区间掷点（原版 `smCharInfo.Attack_Damage[0..1]`）。
        //    区间用 max(1, …) 兜住"上界不大于下界"的坏数据：`nextInt(0)` 会抛，
        //    而不是"打 0 伤害"—— 数据问题不该表现成崩溃。
        int baseDamage = rnd.nextInt(Math.max(1, attacker.atkMax() - attacker.atkMin()))
            + attacker.atkMin() + 1;
        result.setRawDamage(baseDamage);

        // 2. 吸收减伤 —— 百分比
        int absorption = defender.absorption();
        baseDamage = baseDamage * (100 - absorption) / 100;

        // 3. 最小伤害为 1（与原版一致：打中就是掉血）
        result.setFinalDamage(Math.max(1, baseDamage));
        return result;
    }

    /**
     * 计算玩家基础攻击力（对齐原版 sinInvenTory.cpp）
     * <p>
     * 徒手：按 DamageMelee 系数用属性公式；有武器时按武器伤害 * 属性系数。
     * 武器伤害用掷点实例值（`EquipSummary.damageMin/Max` = 每件装备 Damage 之和），非模板区间。
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
        return Math.clamp(rate, 0, 70); // 上限 70%
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
