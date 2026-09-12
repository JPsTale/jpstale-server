package org.jpstale.server.game.service;

import org.jpstale.server.common.codec.GameConstants;
import org.jpstale.server.game.item.EquipSummary;
import org.jpstale.server.game.item.ItemClass;
import org.jpstale.server.game.item.ItemInstance;
import org.jpstale.server.game.item.ItemLocations;
import org.jpstale.server.game.model.Player;
import org.springframework.stereotype.Component;

/**
 * 玩家面板计算器 — 严格对齐 ex-machina 原版公式。
 * <p>
 * 派生属性（面板/移动/回复）计算结果缓存到 {@link Player#getStatsCache()}：
 * {@link #stats(Player)} 计算一次全量后供所有读数共享；升级 / 属性分配 / 装备变化时
 * 调用 {@link #invalidate(Player)} 失效，避免移动上报（~25Hz）等高频路径重复遍历装备。
 * <p>
 * 依据（代码来源）：
 * <ul>
 *   <li>1 级初始属性 99 点按职业固定分配：TempNewCharacterInit / MorNewCharacterInit</li>
 *   <li>属性点总量 = 99 + (Level-1)*5，每级 +5 自由点存 StatePoint：ReformCharStatePoint</li>
 *   <li>职业公式系数（Life/Mana/Stamina/DamageFunction）：JobDataBase / saCharacterClassData</li>
 *   <li>属性→面板公式：sinInvenTory.cpp / sinSubMain.cpp</li>
 *   <li>再生：*生命再生/*魔法再生/*耐力再生（每秒固定值）
 *   <li>回避：100 - sinGetPVPAccuracy（Accuracy_Table 区间取上界，自 vs 自等级修正为 0）</li>
 * </ul>
 */
@Component
public class PlayerStatCalculator {

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

    /** 近战伤害公式的 Strength 系数 F：DamageMelee 1→130, 2→150, 3→190 */
    public int meleeDamageFactor(int job) {
        int dm = jobFunction(job)[3];
        return switch (dm) {
            case 1 -> 130;
            case 2 -> 150;
            default -> 190;
        };
    }

    private int[] jobFunction(int job) {
        if (job < 1 || job >= JOB_FUNCTION.length) {
            return new int[]{1, 1, 1, 1, 2, 0};
        }
        return JOB_FUNCTION[job];
    }

    // ======== 派生属性缓存（一次全量计算，事件失效后重建） ========

    /** 单次计算快照：一次遍历装备 + 公式，供所有读数共享 */
    public static final class Stats {
        public int maxHp;
        public int maxMp;
        public int maxSp;
        public int attackRating;   // 命中率
        public int defense;        // 防御力（含装备掷点值）
        public int absorption;     // 吸收率
        public int[] baseAttack;   // {min, max}
        public int attackSpeed;    // 攻击速度（装备累加）
        public int critical;       // 暴击率（上限 50）
        public int block;          // 格挡率
        public int shootingRange;  // 射程
        public int maxWeight;      // 负重上限
        public int moveSpeed;      // 移动速度档位 1~51（对标 wartale；> exm 25 > 原版 9）
        public int avoid;          // 回避率 = 100 - 命中率（自 vs 自）
        public double walkSpeed;   // 世界单位/秒
        public double runSpeed;    // 世界单位/秒
        public double regenHp;     // 每秒固定值（精确 0.1）
        public double regenMp;
        public double regenStm;
        /** 装备聚合（掷点实例值），供战斗/负重/面板二次读数 */
        public EquipSummary equip = new EquipSummary();
        /** 负重上限（STR*2 + HEA*1.5 + LV*3 + 60） */
        public int maxWeightBase;
    }

    /** 读取（失败时惰性重算）。事件失效点：recalcPanel / 属性分配 / 装备装载 */
    public Stats stats(Player p) {
        Object cached = p.getStatsCache();
        if (cached instanceof Stats s) {
            return s;
        }
        Stats s = compute(p);
        p.setStatsCache(s);
        return s;
    }

    /** 失效派生属性缓存（升级/属性分配/装备变化后调用） */
    public void invalidate(Player p) {
        p.setStatsCache(null);
    }

    private Stats compute(Player p) {
        Stats s = new Stats();
        s.equip = EquipSummary.of(p);
        EquipSummary e = s.equip;
        s.maxHp = maxHpOf(p) + e.increaseLife;
        s.maxMp = maxMpOf(p) + e.increaseMana;
        s.maxSp = maxSpOf(p) + e.increaseStamina;
        s.attackRating = attackRatingOf(p) + equipAttackRating(p, e);
        s.defense = defenseOf(p) + e.defense;
        s.absorption = absorptionOf(p) + (int) e.absorb;
        s.baseAttack = baseAttackOf(p);
        s.attackSpeed = attackSpeedOf(p, e);
        s.critical = Math.min(50, criticalOf(p, e));
        s.block = blockOf(p, e);
        s.shootingRange = shootingRangeOf(p, e);
        s.maxWeightBase = maxWeightOf(p);
        s.maxWeight = s.maxWeightBase;
        s.moveSpeed = moveSpeedStatOf(p, e);
        s.walkSpeed = GameConstants.playerWalkSpeedWorldPerSec(s.moveSpeed);
        s.runSpeed = GameConstants.playerRunSpeedWorldPerSec(s.moveSpeed);
        // 每秒恢复（原版 sinSetRegen）：
        //  HP = ((Lv + STR/2 + HEA)/180 + 装备再生 再生Life_Regen)/1.5
        //  MP = (Lv + SPR*1.2 + HEA/2)/115 + 装备再生 Mana_Regen
        //  STM = (Lv + HEA)/100 + 装备再生 Stamina_Regen
        double hpEquip = e.regenHp;
        double mpEquip = e.regenMp;
        double stmEquip = e.regenStm;
        s.regenHp = ((p.getLevel() + p.getStrength() / 2.0 + p.getHealth()) / 180.0 + hpEquip) / 1.5;
        s.regenMp = (p.getLevel() + p.getSpirit() * 1.2 + p.getHealth() / 2.0) / 115.0 + mpEquip;
        s.regenStm = (p.getLevel() + p.getHealth()) / 100.0 + stmEquip;
        s.avoid = avoidOf(s.attackRating, s.defense);
        return s;
    }

    // ======== 面板计算（原版公式） ========

    private int maxHpOf(Player p) {
        int f = jobFunction(p.getJob())[0];
        double v = switch (f) {
            case 1 -> p.getLevel() * 2.1 + (p.getHealth() * 2.4 + p.getStrength() * 0.8) - 10;
            case 2 -> p.getLevel() * 2.1 + (p.getHealth() * 2.2 + p.getStrength() * 0.6) - 5;
            case 3 -> p.getLevel() * 1.8 + (p.getHealth() * 2.1 + p.getStrength() * 0.3);
            case 4 -> p.getLevel() * 1.5 + p.getHealth() * 2.1;
            default -> p.getLevel() * 1.5 + p.getHealth() * 1.9;
        };
        return (int) v;
    }

    private int maxMpOf(Player p) {
        int f = jobFunction(p.getJob())[1];
        double v = switch (f) {
            case 1 -> p.getLevel() * 1.5 + p.getSpirit() * 3.8;
            case 2 -> p.getLevel() * 0.9 + p.getSpirit() * 2.7;
            default -> p.getLevel() * 0.6 + p.getSpirit() * 2.2;
        };
        return (int) v;
    }

    private int maxSpOf(Player p) {
        return (int) (p.getHealth() * 1.4 + (double) (p.getStrength() + p.getTalent()) / 2
            + p.getLevel() * 2.3 + 80 + p.getSpirit());
    }

    /** 命中率：DEX*3.1 + LV*1.9 + TAL*1.5 */
    private int attackRatingOf(Player p) {
        return (int) (p.getAgility() * 3.1 + p.getLevel() * 1.9 + p.getTalent() * 1.5);
    }

    /** 防御力：DEX/2 + TAL/4 + LV*1.4 */
    private int defenseOf(Player p) {
        return (int) ((double) p.getAgility() / 2 + (double) p.getTalent() / 4 + p.getLevel() * 1.4);
    }

    /** 吸收率：Def/100 + LV/10 + (STR+TAL)/40 + 1（上限由调用方限制） */
    private int absorptionOf(Player p) {
        return defenseOf(p) / 100 + p.getLevel() / 10 + (p.getStrength() + p.getTalent()) / 40 + 1;
    }

    /** 负重上限：STR*2 + HEA*1.5 + LV*3 + 60 */
    private int maxWeightOf(Player p) {
        return (int) (p.getStrength() * 2 + p.getHealth() * 1.5 + p.getLevel() * 3 + 60);
    }

    /** 徒手/基础攻击力（DamageFunction 的近战系数）：{min, max} */
    private int[] baseAttackOf(Player p) {
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

    /** 装备命中加成：主手武器/部分装备 attack_rating 掷点值（面板用） */
    private int equipAttackRating(Player p, EquipSummary e) {
        int sum = 0;
        org.jpstale.server.game.item.PlayerItems items = p.getItems();
        if (items == null) {
            return 0;
        }
        for (ItemInstance it : items.itemsIn(ItemLocations.EQUIP)) {
            if (it.isDeleted()) {
                continue;
            }
            sum += it.getAttackRating();
        }
        return sum;
    }

    /** 攻击速度（装备累加） */
    private int attackSpeedOf(Player p, EquipSummary e) {
        return e.attackSpeed;
    }

    /** 暴击累计（不截断，compute 处统一 cap 50） */
    private int criticalOf(Player p, EquipSummary e) {
        return e.critical;
    }

    /** 格挡率（装备累加，对齐原版 Chance_Block） */
    private int blockOf(Player p, EquipSummary e) {
        return (int) e.block;
    }

    /** 射程（装备累加，对齐原版 Shooting_Range） */
    private int shootingRangeOf(Player p, EquipSummary e) {
        return e.range;
    }

    /**
     * 移动速度档位（对齐 exm sinInvenTory.cpp:5478-5482）
     * 公式：int((TAL+HEA+LV+60)/150.0 - weightRatio + bootsSpeed) + 1，范围 1~51（对标 wartale）
     */
    private int moveSpeedStatOf(Player p, EquipSummary e) {
        double weightRatio = 0.0; // 负重系统未实现（背包负重暂无结算）
        int ms = (int) ((p.getTalent() + p.getHealth() + p.getLevel() + 60) / 150.0
                - weightRatio + e.bootsSpeed) + 1;
        return Math.clamp(ms, GameConstants.MOVE_SPEED_MIN, GameConstants.MOVE_SPEED_MAX);
    }

    // ======== 回避率（原版 Accuracy_Table / sinGetPVPAccuracy 补集） ========
    // 原版没有独立"躲避"字段：命中率 = sinGetPVPAccuracy(攻方LV/命中, 防方LV/防御)，
    // 区间取表上界，等级修正 = ((DesLevel-MyLevel)/100)*28，clamp [30,95]。
    // 面板"回避" = 100 - 命中率（自 vs 自，等级修正为 0）。

    private static final int[] ACC_AC = {
        -380, -360, -340, -320, -300, -280, -260, -240, -220, -200,
        -180, -160, -140, -120, -100, -80, -60, -40, -20, 0,
        10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
        110, 120, 150, 190, 240, 300, 370, 450, 540, 640,
        750, 950, 1300, 1600, 2000, 2500
    };
    private static final int[] ACC_PCT = {
        50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
        60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
        70, 71, 72, 73, 74, 75, 76, 77, 78, 79,
        80, 81, 82, 83, 84, 85, 86, 87, 88, 89,
        90, 91, 92, 93, 94, 95
    };

    /** 玩家命中率（原版 sinGetPVPAccuracy，等级修正 ×28，clamp 30~95） */
    public int accuracyPvp(Player p, int desLevel, int desDefense) {
        Stats s = stats(p);
        double ac = (s.attackRating - desDefense) * 1.4;
        int real = 50;
        if (ac < -190) {
            real = 50;
        } else if (ac > 2100) {
            real = 95;
        } else {
            for (int i = 0; i < ACC_AC.length - 1; i++) {
                if (ac > ACC_AC[i] && ac <= ACC_AC[i + 1]) {
                    real = ACC_PCT[i + 1];
                    break;
                }
            }
        }
        int result = (int) (real - ((desLevel - p.getLevel()) / 100.0) * 28);
        return Math.clamp(result, 30, 95);
    }

    /**
     * 怪物命中率 —— 原版 `sinGetMonsterAccuracy(MonsterLV, MonsterAttack_Rating)` 逐行照抄
     * （ex-machina `src/game/Legacy/Game/Interface/sinSubMain.cpp:1025`）：
     * <pre>
     *   AC_R = (怪.attackRating − 玩家.Defence) * 2        ← 系数 2（玩家互打/打怪是 1.4）
     *   查同一张 Accuracy_Table
     *   Result = RealAC − ((玩家等级 − 怪等级) / 100) * 50  ← 等级修正 ×50（玩家侧是 ×28）
     *   clamp 30..95
     * </pre>
     * ⚠ 与另两个函数的唯一差别：原版这个函数**没有** `AC_R < -190 → 50` / `> 2100 → 95` 两处提前返回，
     * 表外会读到上一次调用遗留的 RealAC（未初始化静态量，属原版缺陷）。这里按同族函数的约定补上
     * 两端收敛（低于表首 → 50、高于表尾 → 95），避免把它照抄成未定义行为。
     */
    public int monsterAccuracyPvp(int monsterLevel, int monsterAttackRating, int playerLevel, int playerDefense) {
        double ac = (monsterAttackRating - playerDefense) * 2.0;
        int real;
        if (ac <= ACC_AC[0]) {
            real = 50;
        } else if (ac >= ACC_AC[ACC_AC.length - 1]) {
            real = 95;
        } else {
            real = 50;
            for (int i = 0; i < ACC_AC.length - 1; i++) {
                if (ac > ACC_AC[i] && ac <= ACC_AC[i + 1]) {
                    real = ACC_PCT[i + 1];
                    break;
                }
            }
        }
        int result = (int) (real - ((playerLevel - monsterLevel) / 100.0) * 50);
        return Math.clamp(result, 30, 95);
    }

    private int avoidOf(int attackRating, int defense) {
        double ac = (attackRating - defense) * 1.4;
        int real;
        if (ac < -190) {
            real = 50;
        } else if (ac > 2100) {
            real = 95;
        } else {
            real = 50;
            for (int i = 0; i < ACC_AC.length - 1; i++) {
                if (ac > ACC_AC[i] && ac <= ACC_AC[i + 1]) {
                    real = ACC_PCT[i + 1];
                    break;
                }
            }
        }
        return 100 - real;
    }

    // ======== 对外读数（全部走缓存） ========

    public int maxHp(Player p) { return stats(p).maxHp; }
    public int maxMp(Player p) { return stats(p).maxMp; }
    public int maxSp(Player p) { return stats(p).maxSp; }
    public int attackRating(Player p) { return stats(p).attackRating; }
    public int defense(Player p) { return stats(p).defense; }
    public int absorption(Player p) { return stats(p).absorption; }
    public int[] baseAttack(Player p) { return stats(p).baseAttack; }

    /**
     * 攻击力区间（面板显示 + 伤害掷点共用）：含主手武器伤害。
     * 徒手用 baseAttack；有武器：min=1 + wMin*(STR+F)/F + (TAL+AGI)/40，max=3 + wMax*(STR+F)/F + (TAL+AGI)/40
     * （对齐原版 sinInvenTory.cpp；F=meleeDamageFactor）。
     */
    public int[] attackPower(Player p) {
        EquipSummary e = stats(p).equip;
        int[] base = baseAttack(p);
        int min = base[0];
        int max = base[1];
        if (e.hasWeapon) {
            int str = p.getStrength();
            int dmg = meleeDamageFactor(p.getJob());
            int talAgi = p.getTalent() + p.getAgility();
            min = 1 + e.weaponDamageMin * (str + dmg) / dmg + talAgi / 40;
            max = 3 + e.weaponDamageMax * (str + dmg) / dmg + talAgi / 40;
        }
        return new int[]{min, max};
    }

    public int attackSpeed(Player p) { return stats(p).attackSpeed; }
    public int criticalHit(Player p) { return stats(p).critical; }
    public int blockChance(Player p) { return stats(p).block; }
    public int shootingRange(Player p) { return stats(p).shootingRange; }
    public int maxWeight(Player p) { return stats(p).maxWeight; }
    public int moveSpeedStat(Player p) { return stats(p).moveSpeed; }
    public double walkSpeed(Player p) { return stats(p).walkSpeed; }
    public double runSpeed(Player p) { return stats(p).runSpeed; }
    public double regenHp(Player p) { return stats(p).regenHp; }
    public double regenMp(Player p) { return stats(p).regenMp; }
    public double regenStm(Player p) { return stats(p).regenStm; }
    public int avoidChance(Player p) { return stats(p).avoid; }

    /**
     * 跑步每秒耐力消耗（JPT2018 sinUseStamina）：
     * DeCreaSTM = (1000 + 当前负重) / (最大负重 + STR/2 + 500) + 0.4
     */
    public double staminaUsePerSec(Player p) {
        int maxWeight = maxWeightOf(p);
        return (1000.0 + currentWeight(p)) / (maxWeight + p.getStrength() / 2.0 + 500.0) + 0.4;
    }

    /**
     * 当前负重（对齐原版 cINVENTORY::CheckWeight）：
     * 遍历 背包+当前装备套；药水按瓶数(count)计，其它累加模板 weight（weight<0 忽略）。
     * <p>
     * 用户拍板：副装备栏（备用套，未激活）不计负重，W 切换激活后才计入（让玩家更爽）。
     */
    public int currentWeight(Player p) {
        int w = 0;
        java.util.List<ItemInstance> all = new java.util.ArrayList<>();
        all.addAll(p.getItems().itemsIn(ItemLocations.BAG_PAGE));
        all.addAll(p.getItems().itemsIn(ItemLocations.EQUIP));
        for (ItemInstance it : all) {
            if (it == null || it.isDeleted()) {
                continue;
            }
            Integer ci = it.getTemplate() != null ? it.getTemplate().getClassItem() : null;
            if (ci != null && ItemClass.isPotion(ci)) { // 药水：每瓶 1 单位
                w += it.getCount();
                continue;
            }
            Integer wt = it.getTemplate() != null ? it.getTemplate().getWeight() : null;
            if (wt != null && wt >= 0) {
                w += wt;
            }
        }
        return w;
    }

    /**
     * 拾取/入包超重预检：把 fresh 计入后是否超过负重上限。
     * 对齐原版 Weight[0] > Weight[1] 语义。药水按瓶数计入。
     */
    public boolean isOverWeight(Player p, ItemInstance fresh) {
        int w = 0;
        java.util.List<ItemInstance> all = new java.util.ArrayList<>();
        all.addAll(p.getItems().itemsIn(ItemLocations.BAG_PAGE));
        all.addAll(p.getItems().itemsIn(ItemLocations.EQUIP));
        for (ItemInstance it : all) {
            if (it == null || it.isDeleted()) {
                continue;
            }
            Integer ci = it.getTemplate() != null ? it.getTemplate().getClassItem() : null;
            if (ci != null && ItemClass.isPotion(ci)) {
                w += it.getCount();
                continue;
            }
            Integer wt = it.getTemplate() != null ? it.getTemplate().getWeight() : null;
            if (wt != null && wt >= 0) {
                w += wt;
            }
        }
        if (fresh == null) {
            return false;
        }
        Integer ci = fresh.getTemplate() != null ? fresh.getTemplate().getClassItem() : null;
        if (ci != null && ItemClass.isPotion(ci)) {
            w += fresh.getCount();
        } else {
            Integer wt = fresh.getTemplate() != null ? fresh.getTemplate().getWeight() : null;
            if (wt != null && wt >= 0) {
                w += wt;
            }
        }
        int max = maxWeightOf(p);
        return w > max;
    }
}