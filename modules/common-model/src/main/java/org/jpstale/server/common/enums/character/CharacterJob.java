package org.jpstale.server.common.enums.character;

import java.util.ArrayList;
import java.util.List;

/**
 * 职业目录（原版 `ECharacterClass`）：**1..11 的名字与短名，唯一来源**。
 *
 * <p>
 * 为什么补这个枚举：这些名字原先散在至少三处各写一份 ——
 * {@link CharacterRace} 的 javadoc、{@code ItemRules} 的注释、
 * 以及客户端的 {@code static/js/item-effects.js} 里的 {@code SPEC_JOBS}。
 * 物品的 `primaryspec` / `addspecclass1..12` 存的就是这些编号，接口要把它翻成名字，
 * 就不能再新增第四份。
 *
 * <p>
 * 依据：工作区 {@code AGENTS.md} 纠错 #13（"职业编号 = ECharacterClass（packets.h L160-171）：
 * 1=Fighter..10=Shaman, 11=Brawler"）；短名沿用客户端 {@code SPEC_JOBS}（FS/MS/AS/PS/ATS/KS/MGS/PRS/ASS/SS/BS）。
 *
 * <p>
 * ⚠ **第 11 个职业的名字在本仓里有三种写法**：本枚举用 {@code Brawler}（同 AGENTS #13 与客户端），
 * 而 {@link CharacterRace} 的注释写 {@code MartialArtist}、{@code ItemRules} 的注释写 {@code 格斗家}。
 * 三处指同一个职业，此处按多数来源取 {@code Brawler}。
 *
 * <p>
 * ⚠ 编号 **12 没有对应职业**：`addspecclass12` 是第 12 个槽位，库里实测有 1 件物品置位。
 * 本枚举只到 11；{@link #fromNumber} 对 12 返回 null，调用方须自己决定怎么显示（不要猜一个职业出来）。
 */
public enum CharacterJob {

    FIGHTER(1, "Fighter", "FS"),
    MECHANICIAN(2, "Mechanician", "MS"),
    ARCHER(3, "Archer", "AS"),
    PIKEMAN(4, "Pikeman", "PS"),
    ATALANTA(5, "Atalanta", "ATS"),
    KNIGHT(6, "Knight", "KS"),
    MAGICIAN(7, "Magician", "MGS"),
    PRIESTESS(8, "Priestess", "PRS"),
    ASSASSIN(9, "Assassin", "ASS"),
    SHAMAN(10, "Shaman", "SS"),
    BRAWLER(11, "Brawler", "BS");

    /** 职业号上限（也是 `addspecclass` 的槽位数）。 */
    public static final int MAX_NUMBER = 11;

    /** `addspecclass` 的槽位数（12 个：第 12 个没有对应职业）。 */
    public static final int SPEC_SLOT_COUNT = 12;

    private final int number;
    private final String fullName;
    private final String shortName;

    CharacterJob(int number, String fullName, String shortName) {
        this.number = number;
        this.fullName = fullName;
        this.shortName = shortName;
    }

    public int getNumber() {
        return number;
    }

    public String getFullName() {
        return fullName;
    }

    public String getShortName() {
        return shortName;
    }

    /** 展示用："Knight (KS)"。 */
    public String label() {
        return fullName + " (" + shortName + ")";
    }

    /** 职业号 → 枚举；未知（含 0 与 12）返回 null —— 不猜。 */
    public static CharacterJob fromNumber(Integer number) {
        if (number == null) {
            return null;
        }
        for (CharacterJob j : values()) {
            if (j.number == number) {
                return j;
            }
        }
        return null;
    }

    /** 展示名；未知号返回 null（调用方自行处理，别填一个假职业）。 */
    public static String labelOf(Integer number) {
        CharacterJob j = fromNumber(number);
        return j == null ? null : j.label();
    }

    /** 全部职业的展示名，按职业号排序。 */
    public static List<String> allLabels() {
        List<String> out = new ArrayList<>(values().length);
        for (CharacterJob j : values()) {
            out.add(j.label());
        }
        return out;
    }
}
