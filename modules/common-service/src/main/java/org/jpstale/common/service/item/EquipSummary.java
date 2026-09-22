package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.ItemList;

/**
 * 装备汇总：从玩家已装备实例（location=EQUIP 的掷点实例）聚合战斗/面板加成。
 * <p>
 * 取代旧的"遍历假 Equipment 模板平均"——本类用 {@code userdb.item} 行的掷点结果值
 * （defence/absorb/block_rating/damage_min/max 等具体实例值），而非模板 [min,max] 平均。
 * 供 PlayerStatCalculator（面板）与 DamageCalculator（战斗）共用，避免两处口径漂移。
 *
 * <p>
 * <b>累加口径（2026-09-22 重写，用户定："按原版逻辑逐件累加，不分部位"）。</b>
 * 原版 `SetItemToChar`（`NewSourcePT-2023/SrcGame/src/sinbaram/sinInvenTory.cpp:7380-7500`）
 * 对**每一件**已装备物品无条件累加同一组字段，不区分甲/靴/手/盾/饰品：
 * <pre>
 *   sinAttack_Rating += Attack_Rating;   sinAttack_Damage[0/1] += Damage[0/1];
 *   sinCritical += Critical_Hit;         sinDefense += Defence;
 *   sinBlock_Rate += fBlock_Rating;      sinMoveSpeed += fSpeed;
 *   sinWeaponSpeed += Attack_Speed;      sinShooting_Range += Shooting_Range;
 *   sinfRegen{Life,Mana,Stamina} += ...; sinfIncre{Life,Mana,Stamina} += ...;
 *   sinfResistance[8] += Resistance[8]                              （内层循环，同样无部位门槛）
 * </pre>
 * 旧实现自造了"只有甲/靴/手算 defence、只有主手算攻速/射程"的分部位规则，导致
 * **盾牌的 46 点躲避、臂环的 5 点躲避从未计入角色属性**（用户 2026-09-22 实测：
 * 装/卸盾面板躲避恒为 121 = 基础 46 + 仅躯干三件的 28+32+15）。
 *
 * <p>
 * <b>职业特效（原版 `sITEM_SPECIAL JobItem`）也在这里累加</b>，门槛是原版那一句
 * `if (sinChar->JobBitMask & sItemInfo.JobCodeMask)`（同一份实例掩码，掷点时写入）。
 * 逐字段公式照抄 `sinInvenTory.cpp:7446-7492`：
 * <ul>
 *   <li>直接加：`Add_Defence` / `Add_fAbsorb` / `Add_fSpeed` / `Add_fBlock_Rating` /
 *       `Add_Attack_Speed` / `Add_Critical_Hit` / `Add_Shooting_Range` / `Add_fMagic_Mastery`</li>
 *   <li>`Lev_*` 族（等级档）：**整除**`等级 / v`，v=0 视为无此项 ——
 *       `Lev_Life`/`Lev_Mana`/`Lev_Attack_Rating`/`Lev_Damage[1]`</li>
 *   <li>`Per_*_Regen`：`v / 2.0`（原版 `sinPer_*_Regen += JobItem.Per_*_Regen / 2.0f`）</li>
 *   <li>抗性：`Add_Resistance[8]` 直加；`Lev_Attack_Resistance[8]` 只加 `等级 / v`
 *       （用户 2026-09-22 定：原版那句 `(int)v + 等级/v` 里把除数自己也加进去了，不采）</li>
 * </ul>
 * ⚠ 吸收按原版**逐件**取整到 0.1（`(int)(v*10.000001f)/10.0f`），不是最后对总和取整。
 * ⚠ 唯一有意差异：基础 `fMagic_Mastery` 原版写的是**赋值**（`sinAdd_fMagic_Mastery = ...`，
 * 后一件覆盖前一件、与装备顺序有关），我们改累加；同文件特效侧的写法就是 `+=`。
 */
public final class EquipSummary {

    public int attackRating;     // 命中（每件装备 attack_rating 掷点值之和）
    public int defense;          // 躲避（每件装备 defence + 职业特效 spec_defence）
    public double absorb;        // 吸收（每件装备 absorb + spec_absorb，逐件截到 0.1）
    public double block;         // 抵挡率（每件装备 block_rating + spec_block_rating）
    public int attackSpeed;      // 攻速档（每件装备 attack_speed + spec_attack_speed）
    public int critical;         // 暴击（每件装备 critical + spec_critical）
    public int range;            // 射程（每件装备 shooting_range + spec_shooting_range）
    public int magicMastery;     // 魔法精通（原版此列写在 JobItem/fMagic_Mastery；我们目前无消费方）
    /** 元素抗性 8 元素，顺序 = 原版 EElementID：0生物 1大地 2火 3冰 4雷 5毒 6水 7风 */
    public int[] res = new int[8];
    /** 主手武器的 `classitem`（4=单手 / 6=双手）。近战攻击距离按它区分（用户 2026-09-14 定）。 */
    public int weaponClassItem;
    /** 移速加成：每件装备掷点 speed + 职业特效 spec_speed（原版 `sinMoveSpeed += fSpeed`，不限靴子）。 */
    public double moveSpeedBonus;
    public double regenHp, regenMp, regenStm;  // 回复（含特效 `Per_*_Regen/2`）
    public int increaseLife, increaseMana, increaseStamina; // 上限提升
    /** 特效「等级档」（`Lev_*`）：由读取方在对应读数的**加数位置**加上（不可并入上层基数）。 */
    public int specLevLife, specLevMana, specLevAttackRating, specLevDamage;
    public boolean hasWeapon;    // 是否装备主手武器
    /** 装备伤害之和（原版 `sinAttack_Damage[0/1] += sItemInfo.Damage[0/1]`，逐件累加）。 */
    public int damageMin, damageMax;
    public int weight;           // 装备总重（按模板 weight 求和；**负重权威在 PlayerStatCalculator.currentWeight**）

    /** 从玩家已装备掷点实例聚合。 */
    public static EquipSummary of(Player player) {
        EquipSummary s = new EquipSummary();
        PlayerItems items = player.getItems();
        if (items == null) {
            return s;
        }
        int job = player.getJob();
        int level = player.getLevel();
        for (ItemInstance it : items.equippedItems()) {   // 排除鼠标位（slot=-1，拿了还没放下的那件不算装备）
            if (it.isDeleted()) {
                continue;
            }
            // **需求不满足的装备不算数**：原版 `SetItemToChar` 里
            // `if (InvenItem[i].sItemInfo.NotUseFlag) continue;`（sinInvenTory.cpp:7355）——
            // 洗点/降级后属性掉下来的装备**属性不生效**（外观照旧、负重照算；客户端会把它标红）。
            if (!ItemRules.meetsRequirements(player, it)) {
                continue;
            }
            ItemList def = it.getTemplate();

            // ---- 基础掷点值：对每一件装备无条件累加（原版 sinInvenTory.cpp:7388-7430）----
            s.attackRating += it.getAttackRating();
            s.damageMin += it.getDamageMin();
            s.damageMax += it.getDamageMax();
            s.critical += it.getCritical();
            s.defense += it.getDefence();
            s.block += it.getBlockRating();
            s.moveSpeedBonus += it.getSpeed();
            s.attackSpeed += it.getAttackSpeed();
            s.range += it.getShootingRange();
            s.absorb += truncTenth(it.getAbsorb());
            s.regenHp += it.getLifeRegen();
            s.regenMp += it.getManaRegen();
            s.regenStm += it.getStaminaRegen();
            s.increaseLife += (int) it.getIncreaseLife();
            s.increaseMana += (int) it.getIncreaseMana();
            s.increaseStamina += (int) it.getIncreaseStamina();
            s.res[0] += it.getResBionic();
            s.res[1] += it.getResEarth();
            s.res[2] += it.getResFire();
            s.res[3] += it.getResIce();
            s.res[4] += it.getResLighting();
            s.res[5] += it.getResPoison();
            s.res[6] += it.getResWater();
            s.res[7] += it.getResWind();

            // 主手武器：只有"是不是武器""武器手别"是槽位相关的（原版按 sInven[] 槽取），其余一律不分部位
            if (it.getSlot() == ItemLocations.SLOT_MAIN_HAND) {
                s.hasWeapon = true;
                if (def != null && def.getClassItem() != null) {
                    s.weaponClassItem = def.getClassItem();
                }
            }
            // 负重（聚合值仅供引用；真实负重走 PlayerStatCalculator.currentWeight）
            if (def != null && def.getWeight() != null) {
                s.weight += def.getWeight();
            }

            // ---- 职业特效：门 = 装备掩码含本职业位（原版 `if (sinChar->JobBitMask & JobCodeMask)`）----
            if (!specActive(job, it.getJobCodeMask())) {
                continue;
            }
            s.absorb += truncTenth(it.getSpecAbsorb());
            s.defense += it.getSpecDefence();
            s.moveSpeedBonus += it.getSpecSpeed();
            s.block += it.getSpecBlockRating();
            s.attackSpeed += it.getSpecAttackSpeed();
            s.critical += it.getSpecCritical();
            s.range += it.getSpecShootingRange();
            s.magicMastery += (int) it.getSpecMagicMastery();
            s.specLevLife += levTerm(level, it.getSpecLevLife());
            s.specLevMana += levTerm(level, it.getSpecLevMana());
            s.specLevAttackRating += levTerm(level, it.getSpecLevAttackRating());
            s.specLevDamage += levTerm(level, it.getSpecLevDamageMax());
            s.regenHp += it.getSpecPerLifeRegen() / 2.0;
            s.regenMp += it.getSpecPerManaRegen() / 2.0;
            s.regenStm += it.getSpecPerStaminaRegen() / 2.0;
            s.res[0] += it.getSpecResBionic() + levTerm(level, it.getSpecLevResBionic());
            s.res[1] += it.getSpecResEarth() + levTerm(level, it.getSpecLevResEarth());
            s.res[2] += it.getSpecResFire() + levTerm(level, it.getSpecLevResFire());
            s.res[3] += it.getSpecResIce() + levTerm(level, it.getSpecLevResIce());
            s.res[4] += it.getSpecResLighting() + levTerm(level, it.getSpecLevResLighting());
            s.res[5] += it.getSpecResPoison() + levTerm(level, it.getSpecLevResPoison());
            s.res[6] += it.getSpecResWater() + levTerm(level, it.getSpecLevResWater());
            s.res[7] += it.getSpecResWind() + levTerm(level, it.getSpecLevResWind());
        }
        return s;
    }

    /** 职业特效是否生效：装备掩码含该职业位（掩码 0 视为无特效）。与掷点侧的位定义同一套。 */
    private static boolean specActive(int job, int jobCodeMask) {
        if (jobCodeMask == 0 || job < 1 || job > 12) {
            return false;
        }
        return (jobCodeMask & (1 << (job - 1))) != 0;
    }

    /** 特效「等级档」项：`等级 / v`（整除），v=0 表示无此项（原版 `if (x) sinLev_x += 等级 / x;`）。 */
    private static int levTerm(int level, int v) {
        return v == 0 ? 0 : level / v;
    }

    /**
     * 吸收取整：原版 `sinTempAbsorption = (int)(v * 10.000001f); ... / 10.0f + 0.000001f`
     * —— **逐件**截到 0.1 再累加（不是对总和取整）。
     */
    private static double truncTenth(double v) {
        return Math.floor(v * 10.0 + 1e-6) / 10.0;
    }
}
