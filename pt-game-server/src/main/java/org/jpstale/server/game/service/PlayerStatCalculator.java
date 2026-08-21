package org.jpstale.server.game.service;

import org.jpstale.server.common.codec.GameConstants;
import org.jpstale.server.game.model.ItemTemplate;
import org.jpstale.server.game.model.Player;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 玩家面板计算器 — 严格对齐 ex-machina 原版公式
 * <p>
 * 依据（代码来源）：
 * <ul>
 *   <li>1 级初始属性 99 点按职业固定分配：TempNewCharacterInit / MorNewCharacterInit</li>
 *   <li>属性点总量 = 99 + (Level-1)*5，每级 +5 自由点存 StatePoint：ReformCharStatePoint</li>
 *   <li>职业公式系数（Life/Mana/Stamina/DamageFunction）：JobDataBase / saCharacterClassData</li>
 *   <li>属性→面板公式：sinInvenTory.cpp / sinSubMain.cpp</li>
 * </ul>
 */
@Component
public class PlayerStatCalculator {

    @Autowired
    private ItemCache itemCache;

    /** 每职业公式系数：jobcode 1-10 → {LifeFunction, ManaFunction, StaminaFunction, DamageMelee, DamageRange, DamageMagic} */
    private static final int[][] JOB_FUNCTION = {
        /* 0 占位 */ null,
        /* 1 Fighter      */ {1, 3, 1, 1, 2, 0},
        /* 2 Mechanician  */ {2, 2, 2, 2, 2, 0},
        /* 3 Archer       */ {3, 3, 2, 3, 1, 0},
        /* 4 Pikeman      */ {1, 3, 1, 1, 2, 0},
        /* 5 Atalanta     */ {2, 2, 2, 2, 1, 0},
        /* 6 Knight       */ {2, 2, 2, 1, 2, 0},
        /* 7 Magician     */ {5, 1, 3, 3, 2, 1},
        /* 8 Priestess    */ {4, 1, 3, 3, 2, 2},
        /* 9 Assassin     */ {3, 3, 2, 3, 1, 0},   // = Archer 系
        /* 10 Shaman      */ {5, 1, 3, 3, 2, 1},   // = Magician 系
    };

    /** 1 级初始属性（99 点按职业固定分配）：jobcode 1-10 → {STR, SPI, TAL, DEX, HEA} */
    private static final int[][] INITIAL_STATS = {
        /* 0 占位 */ null,
        /* 1 Fighter      */ {28, 6, 21, 17, 27},
        /* 2 Mechanician  */ {24, 8, 25, 18, 24},
        /* 3 Archer       */ {17, 11, 21, 27, 23},
        /* 4 Pikeman      */ {26, 9, 20, 19, 25},
        /* 5 Atalanta     */ {23, 15, 19, 19, 23},
        /* 6 Knight       */ {26, 13, 17, 19, 24},
        /* 7 Magician     */ {16, 29, 19, 14, 21},
        /* 8 Priestess    */ {15, 28, 21, 15, 20},
        /* 9 Assassin     */ {17, 11, 21, 27, 23}, // = Archer
        /* 10 Shaman      */ {16, 29, 19, 14, 21}, // = Magician
    };

    /**
     * 属性点总量：99 + (Level-1)*5（对齐 ReformCharStatePoint NewState）
     */
    public static int totalStatPoints(int level) {
        return 99 + (level - 1) * 5;
    }

    public int[] getInitialStats(int job) {
        if (job < 1 || job >= INITIAL_STATS.length) {
            return new int[]{10, 10, 10, 10, 10};
        }
        return INITIAL_STATS[job];
    }

    private int[] jobFunction(int job) {
        if (job < 1 || job >= JOB_FUNCTION.length) {
            return new int[]{1, 1, 1, 1, 2, 0};
        }
        return JOB_FUNCTION[job];
    }

    /** 近战伤害公式的 Strength 系数 F：DamageMelee 1→130, 2→150, 3→190 */
    public int meleeDamageFactor(int job) {
        int dm = jobFunction(job)[3];
        return switch (dm) {
            case 1 -> 130;
            case 2 -> 150;
            default -> 190;
        };
    }

    // ======== 面板计算（原版公式） ========

    public int maxHp(Player p) {
        int f = jobFunction(p.getJob())[0];
        double v;
        switch (f) {
            case 1: v = p.getLevel() * 2.1 + (p.getHealth() * 2.4 + p.getStrength() * 0.8) - 10; break;
            case 2: v = p.getLevel() * 2.1 + (p.getHealth() * 2.2 + p.getStrength() * 0.6) - 5; break;
            case 3: v = p.getLevel() * 1.8 + (p.getHealth() * 2.1 + p.getStrength() * 0.3); break;
            case 4: v = p.getLevel() * 1.5 + p.getHealth() * 2.1; break;
            default: v = p.getLevel() * 1.5 + p.getHealth() * 1.9; break;
        }
        return (int) v;
    }

    public int maxMp(Player p) {
        int f = jobFunction(p.getJob())[1];
        double v;
        switch (f) {
            case 1: v = p.getLevel() * 1.5 + p.getSpirit() * 3.8; break;
            case 2: v = p.getLevel() * 0.9 + p.getSpirit() * 2.7; break;
            default: v = p.getLevel() * 0.6 + p.getSpirit() * 2.2; break;
        }
        return (int) v;
    }

    public int maxSp(Player p) {
        return (int) (p.getHealth() * 1.4 + (p.getStrength() + p.getTalent()) / 2
            + p.getLevel() * 2.3 + 80 + p.getSpirit());
    }

    /** 命中率：DEX*3.1 + LV*1.9 + TAL*1.5 */
    public int attackRating(Player p) {
        return (int) (p.getAgility() * 3.1 + p.getLevel() * 1.9 + p.getTalent() * 1.5);
    }

    /** 防御力：DEX/2 + TAL/4 + LV*1.4 */
    public int defense(Player p) {
        return (int) (p.getAgility() / 2 + p.getTalent() / 4 + p.getLevel() * 1.4);
    }

    /** 吸收率：Def/100 + LV/10 + (STR+TAL)/40 + 1（上限由调用方限制） */
    public int absorption(Player p) {
        return defense(p) / 100 + p.getLevel() / 10 + (p.getStrength() + p.getTalent()) / 40 + 1;
    }

    /** 负重上限：STR*2 + HEA*1.5 + LV*3 + 60 */
    public int maxWeight(Player p) {
        return (int) (p.getStrength() * 2 + p.getHealth() * 1.5 + p.getLevel() * 3 + 60);
    }

    /**
     * 徒手/基础攻击力（DamageFunction 的近战系数）：{min, max}
     * <p>
     * DamageMelee==1: min=1+(STR+130)/130+(TAL+DEX)/40  max=2+(STR+130)/130+(TAL+DEX)/35
     * 其他:            min=1+(STR+200)/200+(TAL+DEX)/50  max=2+(STR+200)/200+(TAL+DEX)/45
     */
    public int[] baseAttack(Player p) {
        int dm = jobFunction(p.getJob())[3];
        int min, max;
        if (dm == 1) {
            min = 1 + (p.getStrength() + 130) / 130 + (p.getTalent() + p.getAgility()) / 40;
            max = 2 + (p.getStrength() + 130) / 130 + (p.getTalent() + p.getAgility()) / 35;
        } else {
            min = 1 + (p.getStrength() + 200) / 200 + (p.getTalent() + p.getAgility()) / 50;
            max = 2 + (p.getStrength() + 200) / 200 + (p.getTalent() + p.getAgility()) / 45;
        }
        // 原版最后 +1 修正
        return new int[]{min + 1, max + 1};
    }

    // ======== 装备聚合的面板字段（原版 sinInvenTory：攻击速度/暴击/格挡/射程/移动速度） ========

    private java.util.List<ItemTemplate> equippedTemplates(Player p) {
        java.util.List<ItemTemplate> list = new java.util.ArrayList<>();
        var equip = p.getEquipment();
        if (equip == null) {
            return list;
        }
        for (var slot : equip.getSlots().values()) {
            ItemTemplate t = itemCache.getTemplate(slot.getItemId());
            if (t != null) {
                list.add(t);
            }
        }
        return list;
    }

    /** 攻击速度（装备累加） */
    public int attackSpeed(Player p) {
        int sum = 0;
        for (ItemTemplate t : equippedTemplates(p)) {
            sum += t.getAtkSpeed();
        }
        return sum;
    }

    /** 暴击率（装备累加，上限 50，对齐原版 sinChar->Critical_Hit） */
    public int criticalHit(Player p) {
        int sum = 0;
        for (ItemTemplate t : equippedTemplates(p)) {
            sum += t.getCritical();
        }
        return Math.min(50, sum);
    }

    /** 格挡率（装备累加，对齐原版 Chance_Block） */
    public int blockChance(Player p) {
        int sum = 0;
        for (ItemTemplate t : equippedTemplates(p)) {
            sum += (int) ((t.getBlockMin() + t.getBlockMax()) / 2);
        }
        return sum;
    }

    /** 射程（装备累加，对齐原版 Shooting_Range） */
    public int shootingRange(Player p) {
        int sum = 0;
        for (ItemTemplate t : equippedTemplates(p)) {
            sum += t.getRange();
        }
        return sum;
    }

    /**
     * 移动速度点数（对齐 exm sinInvenTory.cpp:5478-5482）
     * <p>
     * 公式：int((TAL+HEA+LV+60)/150.0 - weightRatio + bootsSpeed) + 1
     * 范围：1~9（关键：最后 +1）
     */
    public int moveSpeedStat(Player p) {
        int equipSpeed = 0;
        for (ItemTemplate t : equippedTemplates(p)) {
            int s = (int) t.getRunSpeedMin();
            if (s > equipSpeed) {
                equipSpeed = s;
            }
        }
        double weightRatio = 0.0; // 负重系统未实现
        int ms = (int) ((p.getTalent() + p.getHealth() + p.getLevel() + 60) / 150.0
                - weightRatio + equipSpeed) + 1;
        return Math.max(1, Math.min(9, ms));
    }

    /**
     * 走路速度（游戏单位/秒）
     * 走 m/s = 0.4375 × Move_Speed，换算游戏单位/秒 ×256。
     * Move_Speed=1 → 0.44 m/s = 112 单位/秒（5.6 单位/tick @20tick/s）。
     */
    public double walkSpeed(Player p) {
        return GameConstants.PLAYER_WALK_SPEED_PER_POINT * moveSpeedStat(p) * GameConstants.POSITION_SCALE;
    }

    /**
     * 跑步速度（游戏单位/秒）
     * 跑 m/s = 1.2109 × Move_Speed，换算游戏单位/秒 ×256。
     * Move_Speed=1 → 1.21 m/s = 310 单位/秒（15.5 单位/tick @20tick/s）。
     */
    public double runSpeed(Player p) {
        return GameConstants.PLAYER_RUN_SPEED_PER_POINT * moveSpeedStat(p) * GameConstants.POSITION_SCALE;
    }
}
