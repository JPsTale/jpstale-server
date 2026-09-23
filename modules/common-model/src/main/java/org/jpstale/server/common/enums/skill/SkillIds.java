package org.jpstale.server.common.enums.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * 我方技能身份（220 个 = 11 职业 x 20 槽）—— <b>生成物，勿手改</b>。
 * 由客户端仓库脚本按其生成器口径产出，与 skilldata/skill-tables.json 的 skills 段逐行对应。
 *
 * <p>编号规则 {0x}{job}{tier}{slot}：每段一字节、十进制值、在 hex 里直读
 * （fighter 一转一槽 = 0x010101，pikeman 五转四槽 = 0x040504）。job 用我方 job 号 1..11，
 * 与 characterinfo.job_code 同一套；tier 1..5 = 转职档，slot 1..4 = 该档内的槽。
 *
 * <p>常量名只作可读性（日志/表/代码引用）：有源码的用源码宏名，无源码的用客户端显示名。
 * <b>名字不进协议、不当判据</b>；判据一律用 {@link #id()}。
 */
public enum SkillIds {

    // 1 fighter
    MELEE_MASTERY(0x010101, "Melee Mastery - fighter T1-1, reqLv 10"),
    FIRE_ATTRIBUTE(0x010102, "Fire Attribute - fighter T1-2, reqLv 12"),
    RAVING(0x010103, "Raving - fighter T1-3, reqLv 14"),
    IMPACT(0x010104, "Impact - fighter T1-4, reqLv 17"),
    TRIPLE_IMPACT(0x010201, "Triple Impact - fighter T2-1, reqLv 20"),
    BRUTAL_SWING(0x010202, "Brutal Swing - fighter T2-2, reqLv 23"),
    ROAR(0x010203, "Roar - fighter T2-3, reqLv 26"),
    RAGE_OF_ZECRAM(0x010204, "Rage of Zecram - fighter T2-4, reqLv 30"),
    CONCENTRATION(0x010301, "Concentration - fighter T3-1, reqLv 40"),
    AVANGING_CRASH(0x010302, "Avenging Crash - fighter T3-2, reqLv 43"),
    SWIFT_AXE(0x010303, "Swiftness - fighter T3-3, reqLv 46"),
    BONE_CRASH(0x010304, "Bone Crash - fighter T3-4, reqLv 50"),
    DETORYER(0x010401, "Destroyer - fighter T4-1, reqLv 60"),
    BERSERKER(0x010402, "Berserker - fighter T4-2, reqLv 63"),
    CYCLONE_STRIKE(0x010403, "Cyclone Strike - fighter T4-3, reqLv 66"),
    BOOST_HEALTH(0x010404, "Boost Health - fighter T4-4, reqLv 70"),
    CHARGE(0x010501, "Charge - fighter T5-1, reqLv 80"),
    INNER_SOUL(0x010502, "Inner Soul - fighter T5-2, reqLv 83"),
    HELLION(0x010503, "Hellion - fighter T5-3, reqLv 86"),
    FLAME_VORTEX(0x010504, "Flame Vortex - fighter T5-4, reqLv 90"),

    // 2 mecha
    EXTREME_SHIELD(0x020101, "Extreme Shield - mecha T1-1, reqLv 10"),
    MECHANIC_BOMB(0x020102, "Mechanic Bomb - mecha T1-2, reqLv 12"),
    POISON_ATTRIBUTE(0x020103, "Reverse Engineering - mecha T1-3, reqLv 14"),
    PHYSICAL_ABSORB(0x020104, "Physical Absorption - mecha T1-4, reqLv 17"),
    GREAT_SMASH(0x020201, "Great Smash - mecha T2-1, reqLv 20"),
    MAXIMIZE(0x020202, "Maximize - mecha T2-2, reqLv 23"),
    AUTOMATION(0x020203, "Automation - mecha T2-3, reqLv 26"),
    SPARK(0x020204, "Spark - mecha T2-4, reqLv 30"),
    METAL_ARMOR(0x020301, "Metal Armor - mecha T3-1, reqLv 40"),
    GRAND_SMASH(0x020302, "Grand Smash - mecha T3-2, reqLv 43"),
    MECHANIC_WEAPON(0x020303, "Mechanic Weapon Mastery - mecha T3-3, reqLv 46"),
    SPARK_SHIELD(0x020304, "Spark Shield - mecha T3-4, reqLv 50"),
    IMPULSION(0x020401, "Impulsion - mecha T4-1, reqLv 60"),
    COMPULSION(0x020402, "Compulsion - mecha T4-2, reqLv 63"),
    MAGNETIC_SPHERE(0x020403, "Magnetic Sphere - mecha T4-3, reqLv 66"),
    METAL_GOLEM(0x020404, "Metal Golem - mecha T4-4, reqLv 70"),
    PRECISION(0x020501, "Precision - mecha T5-1, reqLv 80"),
    TRINE_SHIELD(0x020502, "Trine Shield - mecha T5-2, reqLv 83"),
    GRAVITATION(0x020503, "Gravitation - mecha T5-3, reqLv 86"),
    OBLITERATE(0x020504, "Obliterate - mecha T5-4, reqLv 90"),

    // 3 archer
    SCOUT_HAWK(0x030101, "Scout Hawk - archer T1-1, reqLv 10"),
    SHOOTING_MASTERY(0x030102, "Shooting Mastery - archer T1-2, reqLv 12"),
    WIND_ARROW(0x030103, "Wind Arrow - archer T1-3, reqLv 14"),
    PERFECT_AIM(0x030104, "Perfect Aim - archer T1-4, reqLv 17"),
    DIONS_EYE(0x030201, "Dion's Eye - archer T2-1, reqLv 20"),
    FALCON(0x030202, "Falcon - archer T2-2, reqLv 23"),
    ARROW_OF_RAGE(0x030203, "Arrow of Rage - archer T2-3, reqLv 26"),
    AVALANCHE(0x030204, "Avalanche - archer T2-4, reqLv 30"),
    ELEMENTAL_SHOT(0x030301, "Elemental Shot - archer T3-1, reqLv 40"),
    GOLDEN_FALCON(0x030302, "Golden Falcon - archer T3-2, reqLv 43"),
    BOMB_SHOT(0x030303, "Bomb Shot - archer T3-3, reqLv 46"),
    PERFORATION(0x030304, "Perforation - archer T3-4, reqLv 50"),
    RECALL_WOLVERIN(0x030401, "Wolverine - archer T4-1, reqLv 60"),
    EVASION_MASTERY(0x030402, "Evasion Mastery - archer T4-2, reqLv 63"),
    PHOENIX_SHOT(0x030403, "Phoenix Shot - archer T4-3, reqLv 66"),
    FORCE_OF_NATURE(0x030404, "Force of Nature - archer T4-4, reqLv 70"),
    STUN_ARROW(0x030501, "Stun Arrow - archer T5-1, reqLv 80"),
    PHOENIX_SPEED(0x030502, "Phoenix Speed - archer T5-2, reqLv 83"),
    LEAP_SHOT(0x030503, "Leap Shot - archer T5-3, reqLv 86"),
    SOLAR_ARROW(0x030504, "Solar Arrow - archer T5-4, reqLv 90"),

    // 4 pikeman
    PIKE_WIND(0x040101, "Pike Wind - pikeman T1-1, reqLv 10"),
    ICE_ATTRIBUTE(0x040102, "Ice Attribute - pikeman T1-2, reqLv 12"),
    CRITICAL_HIT(0x040103, "Critical Hit - pikeman T1-3, reqLv 14"),
    JUMPING_CRASH(0x040104, "Jumping Crash - pikeman T1-4, reqLv 17"),
    GROUND_PIKE(0x040201, "Ground Pike - pikeman T2-1, reqLv 20"),
    TORNADO(0x040202, "Tornado - pikeman T2-2, reqLv 23"),
    WEAPONE_DEFENCE_MASTERY(0x040203, "Weapon Defense Mastery - pikeman T2-3, reqLv 26"),
    EXPANSION(0x040204, "Expansion - pikeman T2-4, reqLv 30"),
    VENOM_SPEAR(0x040301, "Venom Spear - pikeman T3-1, reqLv 40"),
    VANISH(0x040302, "Vanish - pikeman T3-2, reqLv 43"),
    CRITICAL_MASTERY(0x040303, "Critical Mastery - pikeman T3-3, reqLv 46"),
    CHAIN_LANCE(0x040304, "Chain Lancer - pikeman T3-4, reqLv 50"),
    ASSASSIN_EYE(0x040401, "Assassin's Eye - pikeman T4-1, reqLv 60"),
    CHARGING_STRIKE(0x040402, "Charging Strike - pikeman T4-2, reqLv 63"),
    VAGUE(0x040403, "Vague - pikeman T4-3, reqLv 66"),
    SHADOW_MASTER(0x040404, "Shadow Master - pikeman T4-4, reqLv 70"),
    ATTACK_RATING_MASTERY(0x040501, "Attack Rating Mastery - pikeman T5-1, reqLv 80"),
    LETHAL_STRIKE(0x040502, "Lethal Strike - pikeman T5-2, reqLv 83"),
    DODGE(0x040503, "Dodge - pikeman T5-3, reqLv 86"),
    VORPAL_DIVE(0x040504, "Vorpal Dive - pikeman T5-4, reqLv 90"),

    // 5 atalanta
    SHIELD_STRIKE(0x050101, "Shield Strike - atalanta T1-1, reqLv 10"),
    FARINA(0x050102, "Farina - atalanta T1-2, reqLv 12"),
    THROWING_MASTERY(0x050103, "Throwing Mastery - atalanta T1-3, reqLv 14"),
    VIGOR_SPEAR(0x050104, "Vigor Spear - atalanta T1-4, reqLv 17"),
    WINDY(0x050201, "Windy - atalanta T2-1, reqLv 20"),
    TWIST_JAVELIN(0x050202, "Twist Javelin - atalanta T2-2, reqLv 23"),
    SOUL_SUCKER(0x050203, "Soul Sucker - atalanta T2-3, reqLv 26"),
    FIRE_JAVELIN(0x050204, "Fire Javelin - atalanta T2-4, reqLv 30"),
    SPLIT_JAVELIN(0x050301, "Split Javelin - atalanta T3-1, reqLv 40"),
    TRIUMPH_OF_VALHALLA(0x050302, "Triumph of Valhalla - atalanta T3-2, reqLv 43"),
    LIGHTNING_JAVELIN(0x050303, "Lightning Javelin - atalanta T3-3, reqLv 46"),
    STORM_JAVELIN(0x050304, "Storm Javelin - atalanta T3-4, reqLv 50"),
    HALL_OF_VALHALLA(0x050401, "Hall of Valhalla - atalanta T4-1, reqLv 60"),
    X_RAGE(0x050402, "Extreme Rage - atalanta T4-2, reqLv 63"),
    FROST_JAVELIN(0x050403, "Frost Javelin - atalanta T4-3, reqLv 66"),
    VENGEANCE(0x050404, "Vengeance - atalanta T4-4, reqLv 70"),
    CHIMERA_OF_LIGHT(0x050501, "Chimera of Light - atalanta T5-1, reqLv 80"),
    POISON_JAVELIN(0x050502, "Poison Javelin - atalanta T5-2, reqLv 83"),
    AMAZON_RAGE(0x050503, "Amazon Rage - atalanta T5-3, reqLv 86"),
    JAVELIN_MASTERY(0x050504, "Javelin Mastery - atalanta T5-4, reqLv 90"),

    // 6 knight
    SWORD_BLAST(0x060101, "Sword Blast - knight T1-1, reqLv 10"),
    HOLY_BODY(0x060102, "Holy Body - knight T1-2, reqLv 12"),
    PHYSICAL_TRANING(0x060103, "Physical Training - knight T1-3, reqLv 14"),
    DOUBLE_CRASH(0x060104, "Double Crash - knight T1-4, reqLv 17"),
    HOLY_VALOR(0x060201, "Holy Valor - knight T2-1, reqLv 20"),
    BRANDISH(0x060202, "Brandish - knight T2-2, reqLv 23"),
    PIERCING(0x060203, "Piercing - knight T2-3, reqLv 26"),
    DRASTIC_SPIRIT(0x060204, "Drastic Spirit - knight T2-4, reqLv 30"),
    SWORD_MASTERY(0x060301, "Sword Mastery - knight T3-1, reqLv 40"),
    DIVINE_INHALATION(0x060302, "Divine Shield - knight T3-2, reqLv 43"),
    HOLY_INCANTATION(0x060303, "Holy Incantation - knight T3-3, reqLv 46"),
    GRAND_CROSS(0x060304, "Grand Cross - knight T3-4, reqLv 50"),
    SWORD_OF_JUSTICE(0x060401, "Sword of Justice - knight T4-1, reqLv 60"),
    GODLY_SHIELD(0x060402, "Godly Shield - knight T4-2, reqLv 63"),
    GOD_BLESS(0x060403, "God's Blessing - knight T4-3, reqLv 66"),
    DIVINE_PIERCING(0x060404, "Divine Piercing - knight T4-4, reqLv 70"),
    LIGHTNING_SWORD(0x060501, "Lightning Sword - knight T5-1, reqLv 80"),
    UNDEAD_BANE(0x060502, "Undead Bane - knight T5-2, reqLv 83"),
    ZEALOUS_REACH(0x060503, "Zealous Reach - knight T5-3, reqLv 86"),
    HOLY_AURA(0x060504, "Holy Aura - knight T5-4, reqLv 90"),

    // 7 magician
    AGONY(0x070101, "Agony - magician T1-1, reqLv 10"),
    FIRE_BOLT(0x070102, "Fire Bolt - magician T1-2, reqLv 12"),
    ZENITH(0x070103, "Zenith - magician T1-3, reqLv 14"),
    FIRE_BALL(0x070104, "Fire Ball - magician T1-4, reqLv 17"),
    MENTAL_MASTERY(0x070201, "Mental Mastery - magician T2-1, reqLv 20"),
    WATORNADO(0x070202, "Watornado - magician T2-2, reqLv 23"),
    ENCHANT_WEAPON(0x070203, "Enchant Weapon - magician T2-3, reqLv 26"),
    DEAD_RAY(0x070204, "Death Ray - magician T2-4, reqLv 30"),
    ENERGY_SHIELD(0x070301, "Energy Shield - magician T3-1, reqLv 40"),
    DIASTROPHISM(0x070302, "Diastrophism - magician T3-2, reqLv 43"),
    SPIRIT_ELEMENTAL(0x070303, "Spirit Elemental - magician T3-3, reqLv 46"),
    DANCING_SWORD(0x070304, "Dancing Sword - magician T3-4, reqLv 50"),
    FIRE_ELEMENTAL(0x070401, "Fire Elemental - magician T4-1, reqLv 60"),
    FLAME_WAVE(0x070402, "Flame Wave - magician T4-2, reqLv 63"),
    DISTORTION(0x070403, "Distortion - magician T4-3, reqLv 66"),
    M_METEO(0x070404, "Meteorite - magician T4-4, reqLv 70"),
    MAGIC_SOURCE(0x070501, "Magic Source - magician T5-1, reqLv 80"),
    AMPLIFY(0x070502, "Amplify - magician T5-2, reqLv 83"),
    STONE_SPIKE(0x070503, "Stone Spike - magician T5-3, reqLv 86"),
    DANCE_OF_CALAMITY(0x070504, "Dance of Calamity - magician T5-4, reqLv 90"),

    // 8 priestess
    HEALING(0x080101, "Healing - priestess T1-1, reqLv 10"),
    HOLY_BOLT(0x080102, "Holy Bolt - priestess T1-2, reqLv 12"),
    MULTISPARK(0x080103, "Multi Spark - priestess T1-3, reqLv 14"),
    HOLY_MIND(0x080104, "Holy Mind - priestess T1-4, reqLv 17"),
    MEDITATION(0x080201, "Meditation - priestess T2-1, reqLv 20"),
    DIVINE_LIGHTNING(0x080202, "Divine Lightning - priestess T2-2, reqLv 23"),
    HOLY_REFLECTION(0x080203, "Holy Reflection - priestess T2-3, reqLv 26"),
    GRAND_HEALING(0x080204, "Grand Healing - priestess T2-4, reqLv 30"),
    VIGOR_BALL(0x080301, "Vigor Ball - priestess T3-1, reqLv 40"),
    RESURRECTION(0x080302, "Resurrection - priestess T3-2, reqLv 43"),
    EXTINCTION(0x080303, "Extinction - priestess T3-3, reqLv 46"),
    VIRTUAL_LIFE(0x080304, "Virtual Life - priestess T3-4, reqLv 50"),
    GLACIAL_SPIKE(0x080401, "Glacial Spike - priestess T4-1, reqLv 60"),
    REGENERATION_FIELD(0x080402, "Regeneration Field - priestess T4-2, reqLv 63"),
    CHAIN_LIGHTNING(0x080403, "Chain Lightning - priestess T4-3, reqLv 66"),
    SUMMON_MUSPELL(0x080404, "Summon Muspell - priestess T4-4, reqLv 70"),
    DIVINE_FORCE(0x080501, "Divine Force - priestess T5-1, reqLv 80"),
    ICE_METEORITE(0x080502, "Ice Meteorite - priestess T5-2, reqLv 83"),
    THUNDERSTORM(0x080503, "Thunderstorm - priestess T5-3, reqLv 86"),
    DIVINE_CLEANSING(0x080504, "Divine Cleansing - priestess T5-4, reqLv 90"),

    // 9 assassin
    STINGGER(0x090101, "Stinger - assassin T1-1, reqLv 10"),
    R_HIT(0x090102, "Double Blow - assassin T1-2, reqLv 12"),
    D_MASTERY(0x090103, "Dual Wield Mastery - assassin T1-3, reqLv 14"),
    WISP(0x090104, "Wisp - assassin T1-4, reqLv 17"),
    V_THRONE(0x090201, "Venom Thorn - assassin T2-1, reqLv 20"),
    ALAS(0x090202, "Alas - assassin T2-2, reqLv 23"),
    S_SHOCK(0x090203, "Soul Shock - assassin T2-3, reqLv 26"),
    A_MASTERY(0x090204, "Blade Mastery - assassin T2-4, reqLv 30"),
    S_SWORD(0x090301, "Finishing Blow - assassin T3-1, reqLv 40"),
    B_UP(0x090302, "Gust Slash - assassin T3-2, reqLv 43"),
    INPES(0x090303, "Inpes - assassin T3-3, reqLv 46"),
    BLIND(0x090304, "Deception - assassin T3-4, reqLv 50"),
    F_WIND(0x090401, "Frost Wind - assassin T4-1, reqLv 60"),
    F_MASTERY(0x090402, "Fatal Mastery - assassin T4-2, reqLv 63"),
    POLLUTED(0x090403, "Pollute - assassin T4-3, reqLv 66"),
    P_SHADOW(0x090404, "Ninja Shadow - assassin T4-4, reqLv 70"),
    SHADOW_BOMB(0x090501, "Shadow Bomb - assassin T5-1, reqLv 80"),
    RISING_SLASH(0x090502, "Rising Slash - assassin T5-2, reqLv 83"),
    VIOLENT_STAB(0x090503, "Violent Stab - assassin T5-3, reqLv 86"),
    SHADOW_STORM(0x090504, "Shadow Storm - assassin T5-4, reqLv 90"),

    // 10 shaman
    DARKBOLT(0x0A0101, "Dark Bolt - shaman T1-1, reqLv 10"),
    DARKWAVE(0x0A0102, "Dark Wave - shaman T1-2, reqLv 12"),
    CURSELAZY(0x0A0103, "Inertia - shaman T1-3, reqLv 14"),
    L_PEACE(0x0A0104, "Inner Peace - shaman T1-4, reqLv 17"),
    S_FLARE(0x0A0201, "Spiritual Flare - shaman T2-1, reqLv 20"),
    S_MANACLE(0x0A0202, "Spiritual Manacle - shaman T2-2, reqLv 23"),
    C_HUNT(0x0A0203, "Chasing Hunt - shaman T2-3, reqLv 26"),
    A_MIGAL(0x0A0204, "Advent Migal - shaman T2-4, reqLv 30"),
    R_MAKER(0x0A0301, "Rainmaker - shaman T3-1, reqLv 40"),
    L_GHOST(0x0A0302, "Phantom Call - shaman T3-2, reqLv 43"),
    HAUNT(0x0A0303, "Haunt - shaman T3-3, reqLv 46"),
    SCRATCH(0x0A0304, "Scratch - shaman T3-4, reqLv 50"),
    R_KNIGHT(0x0A0401, "Crimson Knight - shaman T4-1, reqLv 60"),
    JUDGE(0x0A0402, "Judgement - shaman T4-2, reqLv 63"),
    A_MIDRANDA(0x0A0403, "Advent Midranda - shaman T4-3, reqLv 66"),
    M_PRAY(0x0A0404, "Mourning Pray - shaman T4-4, reqLv 70"),
    CREED(0x0A0501, "Creed - shaman T5-1, reqLv 80"),
    PRESS_DEITY(0x0A0502, "Press Deity - shaman T5-2, reqLv 83"),
    PHANTOM_NAIL(0x0A0503, "Phantom Nail - shaman T5-3, reqLv 86"),
    OCCULT_LIFE(0x0A0504, "Occult Life - shaman T5-4, reqLv 90"),

    // 11 martial
    LOWKICK(0x0B0101, "Lowkick - martial T1-1, reqLv 10"),
    SPIRIT_MASTERY(0x0B0102, "Spirit Mastery - martial T1-2, reqLv 12"),
    DOUBLE_BLOW(0x0B0103, "Double Blow - martial T1-3, reqLv 14"),
    HALF_STRAIGHT(0x0B0104, "Half Straight - martial T1-4, reqLv 17"),
    RAGE_UP(0x0B0201, "Rage Up - martial T2-1, reqLv 20"),
    PATRIOT(0x0B0202, "Patriot - martial T2-2, reqLv 23"),
    ROLLING_ELBOW(0x0B0203, "Rolling Elbow - martial T2-3, reqLv 26"),
    SPEED_MASTERY(0x0B0204, "Speed Mastery - martial T2-4, reqLv 30"),
    IRON_BULKUP(0x0B0301, "Iron Bulkup - martial T3-1, reqLv 40"),
    TIGER_CANNON(0x0B0302, "Tiger Cannon - martial T3-2, reqLv 43"),
    WAR_CRY(0x0B0303, "War Cry - martial T3-3, reqLv 46"),
    JUMP_HEELKICK(0x0B0304, "Jump Heelkick - martial T3-4, reqLv 50"),
    COMBINATION(0x0B0401, "Combination - martial T4-1, reqLv 60"),
    STEELERS(0x0B0402, "Steelers - martial T4-2, reqLv 63"),
    BODY_CHECK(0x0B0403, "Body Check - martial T4-3, reqLv 66"),
    TYPHOON(0x0B0404, "Typhoon - martial T4-4, reqLv 70"),
    DEFENCE_MASTERY(0x0B0501, "Defence Mastery - martial T5-1, reqLv 80"),
    HUNTING_HAWK(0x0B0502, "Hunting Hawk - martial T5-2, reqLv 83"),
    LEG_BREAKING(0x0B0503, "Leg Breaking - martial T5-3, reqLv 86"),
    HAWK_TRAINING(0x0B0504, "Hawk Training - martial T5-4, reqLv 90"),
    ;

    private final int id;
    private final String desc;

    private static final Map<Integer, SkillIds> BY_ID = new HashMap<>();

    static {
        for (SkillIds s : values()) {
            if (BY_ID.put(s.id, s) != null) {
                throw new IllegalStateException("SkillIds 有重复 id：0x" + Integer.toHexString(s.id));
            }
        }
    }

    SkillIds(int id, String desc) {
        this.id = id;
        this.desc = desc;
    }

    /** 技能身份（数字 id）；协议、props 键、服务端判据都用它。 */
    public int id() {
        return id;
    }

    /** 供日志用的一句短语（显示名 + 职业/槽 + 需求等级）。 */
    public String desc() {
        return desc;
    }

    /** id 里的职业段（1..11），与 characterinfo.job_code 同一套。 */
    public int job() {
        return (id >> 16) & 0xFF;
    }

    /** id 里的转职档（1..5）。 */
    public int tier() {
        return (id >> 8) & 0xFF;
    }

    /** id 里的档内槽位（1..4）。 */
    public int slotInTier() {
        return id & 0xFF;
    }

    /** 数字 id -> 常量；未知 id 直接抛（不返回 null、不给占位值）。 */
    public static SkillIds ofId(int id) {
        SkillIds s = BY_ID.get(id);
        if (s == null) {
            throw new IllegalArgumentException("未知技能 id：0x" + Integer.toHexString(id));
        }
        return s;
    }
}
