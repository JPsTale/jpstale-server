package org.jpstale.server.common.enums.character;

/**
 * 角色种族（对应 packets.h 中 ECharacterRace）。
 */
public enum CharacterRace {
    Tempskron(0),
    Morion(1);

    private final int value;

    CharacterRace(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * 坦普族（Tempskron）职业集合 —— **唯一判据，别再各处写 `job <= 4`**。
     *
     * 1 Fighter / 2 Mechanician / 3 Archer / 4 Pikeman / **9 Assassin** / **11 MartialArtist**。
     * 其余（5 Atalanta / 6 Knight / 7 Magician / 8 Priestess / 10 Shaman）属魔灵族（Morion）。
     *
     * 依据：用户 2026-09-13 实测指出"刺客、格斗家的 jobId 大于 4"，原先各处的 `job <= 4`
     * 会把它们误判成魔灵族（复活/回城会被送去菲拉而不是理查登）；
     * `AccountService` 原有注释也已是 {1,2,3,4,9}，此处补上 11 并收口。
     */
    private static final int[] TEMPSKRON_JOBS = {1, 2, 3, 4, 9, 11};

    /** 该职业是否属坦普族。 */
    public static boolean isTempskron(int job) {
        for (int j : TEMPSKRON_JOBS) {
            if (j == job) return true;
        }
        return false;
    }

    /** 该职业的族。 */
    public static CharacterRace ofJob(int job) {
        return isTempskron(job) ? Tempskron : Morion;
    }

    public static CharacterRace fromValue(int value) {
        for (CharacterRace r : values()) {
            if (r.value == value) return r;
        }
        return Tempskron;
    }
}
