package org.jpstale.common.service.skill;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.common.service.props.SkillKeys;

/**
 * 技能绑定（拳位 / F1~F8）的**唯一判定处** + 读写 props 的唯一实现。纯判定 + 存取，不碰会话/协议。
 *
 * <h3>存哪</h3>
 * 角色的 `characterinfo.props`：`bind.fist.left|right` 与 `bind.quick.1..8`，值 = **数字 `skillId`**
 * （键由 {@link PlayerKey#fistBind} / {@link PlayerKey#quickBind} 生成，别处不许手拼）。
 * **0 = 未绑**：拳位 0 就是原版"这一击退化为普通攻击"的状态（`pLeftSkill/pRightSkill == NULL`，
 * 退化点见 `playmain.cpp:2316-2317`），快捷 0 = 该 F 键没绑东西。**不存图标名、不存职业目录**。
 *
 * <h3>为什么这里要校验（这是"HUD 画出别的职业技能图标"的结构性根治）</h3>
 * 绑定一旦越界（别的职业的 id），客户端只能反查到**异职业的图标/动画**，且没有任何一层会拦住它。
 * 三道门（顺序即拒绝优先级）：
 * <ol>
 *   <li>{@link Reason#BAD_KIND} / {@link Reason#BAD_INDEX}：kind 只能是 1/2，index 拳位 1/2、快捷 1..8；</li>
 *   <li>{@link Reason#UNKNOWN_SKILL} / {@link Reason#NO_SKILL_TREE} / {@link Reason#WRONG_JOB}：
 *       id 必须在技能身份表里，且是该角色 `job` 的那 20 行之一（{@link SkillDataRegistry#ofJob}）；</li>
 *   <li>该行的 `useCode` 必须**允许这个位置**：先看能不能绑（`NOT` = 被动一类，任何位置都不行），
 *       再看这一只拳 —— 源码 `LButtonUp` 判 `LEFT|ALL` → `pLeftSkill`（`sinSkill.cpp:1349-1351`）、
 *       `RButtonUp` 判 `RIGHT|ALL` → `pRightSkill`（`:1430-1432`）；
 *       快捷栏按「能不能绑到鼠标位」这条（`SIN_SKILL_USE_LEFT/RIGHT/ALL`，`Language/English/e_sinSkill_Info.h:3-5`）
 *       —— 记录 F 键的源码分支（`sinSkill.cpp:1447/1460`）也只在 `LEFT`/`RIGHT`/`ALL` 三种下成立，
 *       这里只做「必须可绑」，不额外造限制。⚠ 源码里**没有**「F 键专属的 useCode 条件」这一说法。
 * </ol>
 *
 * <p>⚠ 服务端**不判**"学没学过"：原版记录绑定的分支要求 `Point != 0`（`sinSkill.cpp:1450`），
 * 但那是面板入口的守卫，我们这轮只做上面的位置/身份校验（客户端面板仍按已学才允许点）。
 *
 * <p>越界一律**拒绝 + 回原因码**（`skill.bind.*`，见 `SkillNetworkHandler`），**绝不静默改成 0**
 * —— 静默写 0 会把"客户端发了垃圾"演成"玩家自己解绑了"。
 */
public final class SkillBindRules {

    /** `C2S_SetSkillBinding.kind`：拳位。 */
    public static final int KIND_FIST = 1;
    /** `C2S_SetSkillBinding.kind`：快捷栏。 */
    public static final int KIND_QUICK = 2;

    /** 拳位的 `index`：左键（左拳位）。 */
    public static final int INDEX_LEFT = 1;
    /** 拳位的 `index`：右键（右拳位）。 */
    public static final int INDEX_RIGHT = 2;

    /** `skill_id` 为 0 = **解绑**（显式清掉这个位置，不是"未知值"）。 */
    public static final int UNBOUND = 0;

    /** 拒绝原因；{@link #key()} 是 i18n 后缀（`skill.bind.` + key），OK 没有 key。 */
    public enum Reason {
        OK(null),
        /** kind 不是 1/2 */
        BAD_KIND("badKind"),
        /** index 越界（拳位必须 1/2，快捷必须 1..8） */
        BAD_INDEX("badIndex"),
        /** 该职业在技能身份表里没有行（职业号不在 1..11，存档坏了） */
        NO_SKILL_TREE("noSkillTree"),
        /** id 不在技能身份表里（客户端发了垃圾/过期标识） */
        UNKNOWN_SKILL("unknownSkill"),
        /** 不是本职业的技能 —— **异职业图标就是这么进来的**，必须拒 */
        WRONG_JOB("wrongJob"),
        /** 该技能的 `useCode` 不允许绑到鼠标位（`NOT`：被动一类） */
        NOT_BINDABLE("notBindable"),
        /** `useCode` 不允许绑到**这一只**拳（只能右键的技能绑左拳，反之亦然） */
        WRONG_FIST("wrongFist"),
        ;

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** i18n key 后缀（`skill.bind.` + 它）；OK 返回 null。 */
        public String key() {
            return key;
        }
    }

    /**
     * 一次绑定请求的判定结果。`reason != OK` 时只有 `kind/index/skillId/reason` 有效。
     *
     * @param reason    判定结果
     * @param kind      请求里的 kind（原样回带，便于日志/回错）
     * @param index     请求里的 index（原样回带）
     * @param skillId   请求里的 skillId（`0` = 解绑）
     * @param constName 该 id 在技能身份表里的常量名（日志用；解绑/未解析出行时为 null）
     * @param useCode   该行的 `useCode`（日志用；同上为 null）
     */
    public record Judged(Reason reason, int kind, int index, int skillId, String constName, String useCode) {

        public boolean ok() {
            return reason == Reason.OK;
        }

        private static Judged bad(Reason reason, int kind, int index, int skillId) {
            return new Judged(reason, kind, index, skillId, null, null);
        }
    }

    /**
     * 绑定表的**只读快照**（= `S2C_SkillBindings` 的内容）。
     *
     * @param fistLeft  左拳位的 skillId；**0 = 未绑（普通攻击拳）**
     * @param fistRight 右拳位
     * @param quick     F1~F8，**定长 {@link PlayerKey#QUICK_BIND_COUNT}、下标 0 = F1**；0 = 未绑
     */
    public record Bindings(int fistLeft, int fistRight, int[] quick) {

        public Bindings {
            quick = quick.clone();   // 记录不可变（数组引用不外露）
        }

        @Override
        public int[] quick() {
            return quick.clone();
        }

        /** 该拳位的 skillId（0 = 未绑）。 */
        public int fist(PlayerKey.Fist fist) {
            return fist == PlayerKey.Fist.LEFT ? fistLeft : fistRight;
        }
    }

    private SkillBindRules() {
    }

    /**
     * 判定一次绑定/解绑请求（**不写任何状态**）。
     *
     * <p>先判 kind/index（结构），再判身份（表里、本职业），最后判位置的 `useCode` 许可。
     * 解绑（`skillId == 0`）只需 kind/index 合法：解绑不涉及技能身份。
     */
    public static Judged judge(SkillDataRegistry data, Player p, int kind, int index, int skillId) {
        if (data == null || p == null) {
            throw new IllegalArgumentException("判绑定请求需要已装载的 Player 与技能身份表（null 不是「没绑」）");
        }
        if (kind != KIND_FIST && kind != KIND_QUICK) {
            return Judged.bad(Reason.BAD_KIND, kind, index, skillId);
        }
        if (kind == KIND_FIST && index != INDEX_LEFT && index != INDEX_RIGHT) {
            return Judged.bad(Reason.BAD_INDEX, kind, index, skillId);
        }
        if (kind == KIND_QUICK && (index < 1 || index > PlayerKey.QUICK_BIND_COUNT)) {
            return Judged.bad(Reason.BAD_INDEX, kind, index, skillId);
        }
        if (skillId == UNBOUND) {
            return new Judged(Reason.OK, kind, index, skillId, null, null);   // 解绑：位置合法即可
        }
        if (!data.hasJob(p.getJob())) {
            return Judged.bad(Reason.NO_SKILL_TREE, kind, index, skillId);
        }
        if (!data.hasId(skillId)) {
            return Judged.bad(Reason.UNKNOWN_SKILL, kind, index, skillId);
        }
        SkillDataRegistry.Skill row = data.byId(skillId);
        if (row.classId() != p.getJob()) {
            return new Judged(Reason.WRONG_JOB, kind, index, skillId, row.constName(), row.useCode());
        }
        if (!isBindable(row.useCode())) {
            // `NOT`（被动一类）任何位置都进不去 —— 先报"不可绑"，别报成"这只拳不行"（那是另一回事）
            return new Judged(Reason.NOT_BINDABLE, kind, index, skillId, row.constName(), row.useCode());
        }
        if (kind == KIND_FIST) {
            boolean left = index == INDEX_LEFT;
            if (!allowsFist(row.useCode(), left)) {
                return new Judged(Reason.WRONG_FIST, kind, index, skillId, row.constName(), row.useCode());
            }
        }
        return new Judged(Reason.OK, kind, index, skillId, row.constName(), row.useCode());
    }

    /** 该 `useCode` 允许绑到这只拳吗（左要 `LEFT|ALL`，右要 `RIGHT|ALL`）。 */
    public static boolean allowsFist(String useCode, boolean left) {
        return left ? isLeft(useCode) : isRight(useCode);
    }

    /** 该 `useCode` 能不能绑到鼠标位（`NOT` = 被动一类，不能）。 */
    public static boolean isBindable(String useCode) {
        return isLeft(useCode) || isRight(useCode);
    }

    private static boolean isLeft(String useCode) {
        return "LEFT".equals(useCode) || "ALL".equals(useCode);
    }

    private static boolean isRight(String useCode) {
        return "RIGHT".equals(useCode) || "ALL".equals(useCode);
    }

    /**
     * 把一个**已判定合法**的请求写进 props（内存）。
     *
     * <p>⚠ 只写内存：**落库由调用方走 `PlayerService.persistStats`**（整行 UPDATE 是 props 唯一的落库路径
     * —— `PlayerItems.markDirty` 那套在本项目没有兑现方，见 AGENTS #25）。
     */
    public static void apply(Player p, int kind, int index, int skillId) {
        if (kind == KIND_FIST) {
            if (index != INDEX_LEFT && index != INDEX_RIGHT) {
                // 越界就写右拳 = 静默落错键（读侧还会以为"玩家自己绑的"）—— 宁可当场炸
                throw new IllegalArgumentException("拳位 index " + index + " 越界（只有 "
                        + INDEX_LEFT + " 左 / " + INDEX_RIGHT + " 右）");
            }
            PlayerKey.Fist side = index == INDEX_LEFT ? PlayerKey.Fist.LEFT : PlayerKey.Fist.RIGHT;
            p.setPropInt(PlayerKey.fistBind(side), skillId);
            // **一个技能只在一只拳上**（原版模型：每个技能只有一个 `MousePosi`，`record.cpp:530` 存的是
            // `ShortKey | (MousePosi << 4)`）⇒ 绑到这一侧时，把**另一侧的同名技能清掉**。
            // ⚠ 不清的后果（用户 2026-09-24 实测）：技能可同时挂左右两拳，而客户端显示取左（先查 left）
            // ⇒ "绑了右键却显示 L"（看起来像 R 被 L 覆盖）。
            if (skillId != UNBOUND) {
                PlayerKey.Fist other = side == PlayerKey.Fist.LEFT ? PlayerKey.Fist.RIGHT : PlayerKey.Fist.LEFT;
                if (p.getPropInt(PlayerKey.fistBind(other)) == skillId) {
                    p.setPropInt(PlayerKey.fistBind(other), UNBOUND);
                }
            }
            return;
        }
        if (kind == KIND_QUICK) {
            p.setPropInt(PlayerKey.quickBind(index), skillId);
            return;
        }
        throw new IllegalArgumentException("未知的绑定 kind " + kind + "（合法值只有 "
                + KIND_FIST + " 拳位 / " + KIND_QUICK + " 快捷栏）");
    }

    /** 读绑定表（未写过的键 → 默认值 0 = 未绑）。 */
    public static Bindings read(Player p) {
        if (p == null) {
            throw new IllegalArgumentException("读绑定表需要已装载的 Player");
        }
        int[] quick = new int[PlayerKey.QUICK_BIND_COUNT];
        for (int i = 1; i <= PlayerKey.QUICK_BIND_COUNT; i++) {
            quick[i - 1] = p.getPropInt(PlayerKey.quickBind(i));
        }
        return new Bindings(
                p.getPropInt(PlayerKey.fistBind(PlayerKey.Fist.LEFT)),
                p.getPropInt(PlayerKey.fistBind(PlayerKey.Fist.RIGHT)),
                quick);
    }

    /** 日志用的绑定描述（`左=0x040401 右=未绑 F1=未绑 …`）；只打**已绑**的那些，避免刷屏。 */
    public static String describe(int kind, int index, int skillId) {
        String where = kind == KIND_FIST
                ? (index == INDEX_LEFT ? "左拳" : index == INDEX_RIGHT ? "右拳" : "拳位#" + index)
                : "F" + index;
        return where + "=" + (skillId == UNBOUND ? "未绑" : SkillKeys.describe(skillId));
    }
}
