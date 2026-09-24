package org.jpstale.common.service.props;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 手写的标志位类键（任务位等；技能等级/熟练度走 {@link SkillKeys}）。
 *
 * <p>⚠ 一个枚举里**两种成员混用**：
 * <ul>
 *   <li>{@link #QUEST_ID} / {@link #BIND_FIST} / {@link #BIND_QUICK} 是**模板**成员，
 *       写侧不给字符串、走下面的静态方法拼键；</li>
 *   <li>{@code QUEST_LEVEL_*} 是**显式**成员，读侧的点数公式要按名字取。</li>
 * </ul>
 *
 * <p>⚠ 显式成员的 `getKey()` 必须是**完整存储键**，不能写成 id 片段（如 `("level_55", …)`）——
 * 那样 `getKey()` 不是存储键，`player.getPropInt(PlayerKey.QUEST_LEVEL_55.getKey())` 会去读一个不存在的键，
 * 静默拿到默认值 0（点数少给、零报错）。
 */
public enum PlayerKey implements IPlayerKey {

    /** 任务位模板：写侧用 `QUEST_ID.format(任务id)`。 */
    QUEST_ID("quest.%s", 0, "任务位"),

    QUEST_LEVEL_55("quest.level_55", 0, "技能点 +1"),
    QUEST_LEVEL_70("quest.level_70", 0, "技能点 +1"),
    QUEST_LEVEL_80("quest.level_80", 0, "技能点 +2"),
    QUEST_LEVEL_80_2("quest.level_80_2", 0, "属性点 +2"),
    QUEST_LEVEL_90_2("quest.level_90_2", 0, "属性点 +3"),

    // —— 转职任务位（**转职的唯一门**；用户 2026-09-24 裁定：转职走任务流程）——
    // 任务系统完成对应任务后置 1（`setPropInt(key, 1)`），`JobService` 据它给 rank+1。
    // ⚠ 我方决定，与源码不同：原版 A 根是按等级自动转（sinQuest.cpp:293-306，任务流程整段被注释
    // ——那是私服没有任务系统的表现，不代表原版设计不需要任务）。
    QUEST_LEVEL_20("quest.level_20", 0, "转职任务位：完成 ⇒ rank 可升至 1"),
    QUEST_LEVEL_40("quest.level_40", 0, "转职任务位：完成 ⇒ rank 可升至 2"),
    QUEST_LEVEL_60("quest.level_60", 0, "转职任务位：完成 ⇒ rank 可升至 3"),

    /**
     * 拳位绑定的技能（模板：`%s` = {@link Fist#key()}）。
     *
     * <p>值 = 数字 `skillId`，**0 = 未绑**（= 普通攻击拳 —— 就是原版 `pLeftSkill/pRightSkill == NULL`
     * 时那一击退化为普通攻击的那个状态，`playmain.cpp:2316-2317`）。
     * 键里**只有 skillId**：图标名/职业目录是客户端资产命名（AGENTS #24），不进键也不进值。
     */
    BIND_FIST("bind.fist.%s", 0, "拳位绑定的技能 id（0=未绑/普攻）"),

    /** F1~F8 绑定的技能（模板：`%d` = 1..{@link #QUICK_BIND_COUNT}）。值 = 数字 `skillId`，0 = 该键未绑。 */
    BIND_QUICK("bind.quick.%d", 0, "F1~F8 绑定的技能 id（0=未绑）"),
    ;

    /** 快捷绑定的槽数（F1..F8）—— 键的取值范围就是 1..{@link #QUICK_BIND_COUNT}。 */
    public static final int QUICK_BIND_COUNT = 8;

    /** 拳位（键里那一段 `left`/`right`）。⚠ 与协议里的 `index`（1=左 2=右）**各有一套编码**，别混。 */
    public enum Fist {
        LEFT("left"),
        RIGHT("right"),
        ;

        private final String key;

        Fist(String key) {
            this.key = key;
        }

        /** 键里那一段（`bind.fist.left` 的 `left`）。 */
        public String key() {
            return key;
        }
    }

    private final String key;
    private final int defaultValue;
    private final String desc;

    PlayerKey(String key, int defaultValue, String desc) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.desc = desc;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public int getDefaultValue() {
        return defaultValue;
    }

    @Override
    public String getDesc() {
        return desc;
    }

    /* ─────────── 绑定键的**唯一生成处**（别处不许手拼 `bind.*` 字符串） ─────────── */

    /** 拳位键：`bind.fist.left` / `bind.fist.right`。 */
    public static String fistBind(Fist fist) {
        if (fist == null) {
            throw new IllegalArgumentException("拳位不能为 null（要「没有绑定」就写 0 到左/右拳的键上）");
        }
        return BIND_FIST.format(fist.key());
    }

    /** 快捷栏键：`bind.quick.1` … `bind.quick.8`（越界即抛 —— 拼错的键读回默认值 0，静默）。 */
    public static String quickBind(int index) {
        if (index < 1 || index > QUICK_BIND_COUNT) {
            throw new IllegalArgumentException("快捷栏下标 " + index + " 越界（1.." + QUICK_BIND_COUNT + " = F1..F8）");
        }
        return BIND_QUICK.format(index);
    }

    /** 全部绑定键（拳位 2 + 快捷 8）—— `PlayerProperties` 据它登记默认值与"是否已知"。 */
    private static final Set<String> BIND_KEYS;

    static {
        Set<String> keys = new LinkedHashSet<>();
        for (Fist f : Fist.values()) {
            keys.add(fistBind(f));
        }
        for (int i = 1; i <= QUICK_BIND_COUNT; i++) {
            keys.add(quickBind(i));
        }
        BIND_KEYS = Set.copyOf(keys);
    }

    /** 这个键是不是绑定键（`bind.fist.*` / `bind.quick.*`）。 */
    public static boolean isBindKey(String key) {
        return key != null && BIND_KEYS.contains(key);
    }
}
