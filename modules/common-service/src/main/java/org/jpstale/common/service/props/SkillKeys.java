package org.jpstale.common.service.props;

import org.jpstale.server.common.enums.skill.SkillIds;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 技能键的**唯一生成处** —— 别的任何地方不许手拼 `skill.*` 字符串。
 *
 * <p>键里的身份是**数字 `skillId`**（= `0x&lt;job&gt;&lt;tier&gt;&lt;slot&gt;`），键形
 * `skill.&lt;0xID&gt;.&lt;point|mastery&gt;`，例 `skill.0x040401.point`（pikeman 四转一槽）：
 * <ul>
 *   <li>**名字不进键**（显示名/常量名随生成物改，键不动 ⇒ 改名零迁移）；</li>
 *   <li>**小写 6 位 hex** —— ⚠ 生成物 `skillIdHex` 那列字母是**大写**（`0x0B0101`），
 *       照抄它拼键就会得到另一个键（读侧静默拿到 0）；键里的 hex 一律由这里格式化。</li>
 * </ul>
 *
 * <p>传参防线：`format(Object...)` 是弱类型 ⇒ 只能靠这里的白名单挡住坏 id
 * （判据 = 该 id 在 {@link SkillIds} 的 220 个常量里；`0`/负数/不存在的 id 当场抛）。
 */
public final class SkillKeys {

    /** 职业目录白名单（⚠ 是 `mecha`，不是源码的 `MECHANICIAN`；`martial` 是第 11 职业）。 */
    public static final List<String> CLASS_DIRS = List.of(
            "fighter", "mecha", "archer", "pikeman", "atalanta", "knight",
            "magician", "priestess", "assassin", "shaman", "martial");

    /** 职业号 1..11 → 目录名（下标 = 职业号，0 位占位）。 */
    private static final String[] JOB_CLASS_DIR = {
            null,
            "fighter", "mecha", "archer", "pikeman", "atalanta", "knight",
            "magician", "priestess", "assassin", "shaman", "martial",
    };

    /**
     * 合法的技能 id（= 生成物 `SkillIds` 的 220 个常量）。
     *
     * <p>它是键的判据、也是"两边生成物是否同代"的一处钉子 —— 单测断言它与
     * `skill-tables.json` 的 `skills` 段**逐 id 相等**，任一侧漏生成就在这里红。
     */
    private static final Set<Integer> KNOWN_IDS;

    static {
        Set<Integer> ids = new HashSet<>();
        for (SkillIds s : SkillIds.values()) {
            ids.add(s.id());
        }
        KNOWN_IDS = Set.copyOf(ids);
    }

    private SkillKeys() {
    }

    /** 职业号 → 目录名；1..11 之外 ⇒ 抛（不猜一个职业出来）。 */
    public static String classDirOfJob(int job) {
        if (job < 1 || job >= JOB_CLASS_DIR.length) {
            throw new IllegalArgumentException("职业号 " + job + " 没有技能目录名（有效范围 1.."
                    + (JOB_CLASS_DIR.length - 1) + "）");
        }
        return JOB_CLASS_DIR[job];
    }

    /** `skill.0x%06x.point`（技能等级 0..10）。 */
    public static String point(int skillId) {
        return SkillKey.POINT.format(hex(skillId));
    }

    /** `skill.0x%06x.mastery`（熟练度 0..10000）。 */
    public static String mastery(int skillId) {
        return SkillKey.MASTERY.format(hex(skillId));
    }

    /** `skill.0x%06x.usecount`（熟练度增长计数；原版 `UseSKillIncreCount`）。 */
    public static String useCount(int skillId) {
        return SkillKey.USECOUNT.format(hex(skillId));
    }

    /** 键里那一段：小写、6 位、带 `0x`（未知 id ⇒ 抛）。 */
    public static String hex(int skillId) {
        if (!KNOWN_IDS.contains(skillId)) {
            throw new IllegalArgumentException("技能 id " + describe(skillId) + " 不在 SkillIds 表里（共 "
                    + KNOWN_IDS.size() + " 个：1..11 职业 × 20 槽）");
        }
        return "0x" + String.format(Locale.ROOT, "%06x", skillId);
    }

    /**
     * 日志用的技能标识：小写 6 位 hex。
     *
     * <p>⚠ 与 {@link #hex} 的区别是**它不校验**（可能拿到一个来自网络的垃圾整数，正是要把它打出来）。
     */
    public static String describe(int skillId) {
        return skillId > 0 ? "0x" + String.format(Locale.ROOT, "%06x", skillId) : Integer.toString(skillId);
    }
}
