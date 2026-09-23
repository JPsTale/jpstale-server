package org.jpstale.common.service.skill;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;

/**
 * 学技能 / 升级技能的**唯一判定处**：四道门 + 两条成本。纯判定，不写任何状态。
 *
 * <p>身份是**数字 `skillId`**（`0x&lt;job&gt;&lt;tier&gt;&lt;slot&gt;`）：职业门、槽位、前置、等级、价目
 * 全部由它在 {@link SkillDataRegistry} 里查到的那一行决定，**不再看宏名**（60 行本来就没有宏）。
 *
 * <p>判定顺序（先判先返回）：职业门 → 槽位开放 → 前置槽 → 等级 → 上限 → 技能点 → 钱。
 * 失败一律给**具体**原因（{@link Reason}），客户端按 `skill.op.*` 前缀决定是否回滚乐观 UI。
 *
 * <p>两个边界（看错一位就静默算错）：
 * <ul>
 *   <li>`slotInJob` 是 **0 基 0..19**（原版的"槽"是 1 基；0..15 = 1–4 转、16..19 = 5 转）——
 *       只有本类与 {@code SkillPointService} 的池边界注释碰这两个口径。</li>
 *   <li>钱的"等级"是**升级前**的等级（0..9）：升到第 1 级收基数价，第 2 级起按增量加价；
 *       价目表只有 16 项 ⇒ 5 转（编号 17..20）**没有价**，这类槽一律按未开放拒。</li>
 * </ul>
 *
 * <p>技能点不在这里求值：它是派生量，唯一求值入口是 {@code SkillPointService.free}，
 * 由调用方按槽位所属的池算好后传进来（{@link #judge}）。
 */
public final class SkillRules {

    /** 技能等级上限（到这一级就不再升）。 */
    public static final int MAX_POINT = 10;

    /** 每转职档 4 个槽。 */
    private static final int TIER_SLOTS = 4;

    /** 槽位开放数：下标 = rank（1 转 5 / 2 转 9 / 3 转 13 / 4 转 17）。rank 更高按 4 转算。 */
    private static final int[] OPEN_SLOTS = {5, 9, 13, 17, 17};

    /** 学习费基数（16 项，下标 = 技能编号 − 1）。 */
    private static final int[] MONEY = {
            500, 1000, 1500, 2000, 3000, 5000, 7000, 9000,
            10000, 12000, 15000, 20000, 25000, 30000, 35000, 40000};

    /** 学习费每级增量（16 项，下标 = 技能编号 − 1）。 */
    private static final int[] PLUS_MONEY = {
            500, 600, 700, 800, 1000, 1200, 1400, 1600,
            2000, 2500, 3000, 4000, 5000, 6000, 7000, 8000};

    /** 拒绝原因；{@link #key()} 是 i18n 后缀（`skill.op.` + key），OK 没有 key。 */
    public enum Reason {
        OK(null),
        /** 这个 id 不在 skills 表里（客户端发了垃圾/过期标识） */
        UNKNOWN_SKILL("unknownSkill"),
        /** 该职业在 skills 表里没有技能行（职业号不在 1..11） */
        NO_SKILL_TREE("noSkillTree"),
        /** 技能不属于该职业 */
        WRONG_JOB("wrongJob"),
        /** 槽位还没开放（按转职档；含 5 转那 4 格 —— 原版没有 5 转，未实现） */
        SLOT_LOCKED("slotLocked"),
        /** 上一槽还没学 */
        PREV_NOT_LEARNED("prevNotLearned"),
        /** 等级不够（需求等级 + 当前等级 ×2） */
        LEVEL_TOO_LOW("levelTooLow"),
        /** 已经满级（10） */
        MAX_POINT("maxPoint"),
        /** 技能点不够（该池） */
        NO_SKILL_POINT("noSkillPoint"),
        /** 钱不够 */
        NO_GOLD("noGold"),
        /** 本次登录已经洗过点 */
        RESET_USED("resetUsed"),
        ;

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** i18n key 后缀（`skill.op.` + 它）；OK 返回 null。 */
        public String key() {
            return key;
        }
    }

    /**
     * 一次学/升级请求的判定结果。`reason != OK` 时**只有 reason 与标识字段有效**。
     *
     * @param reason      判定结果
     * @param skillId     技能数字身份（= 目标那一行；未知 id 时就是请求里的原值）
     * @param constName   枚举常量名（日志用；未解析出行时为 `null`）
     * @param macro       源码宏名（日志用；可能为 `null`，60 行没有宏）
     * @param classId     该行所属职业 1..11（未解析出行时为 0）
     * @param slotInJob   本职业内槽序 0..19（0 基）
     * @param tier        转职档 1..5
     * @param slotInTier  档内槽 1..4
     * @param skillNum    技能编号 1..20（= (转职档 − 1) × 4 + 档内槽），钱表下标用它
     * @param prevSkillId 上一槽的 id；**0 = 本槽就是第 0 槽（无前置）**（0 不是合法 id）
     * @param currentPoint 升级**前**的技能等级（钱的"等级"就是它）
     * @param newPoint    升级后的等级（= currentPoint + 1）
     * @param requireLevel 该技能的需求等级（该行数据）
     * @param cost        这次该收的钱
     */
    public record Learn(Reason reason, int skillId, String constName, String macro, int classId,
                        int slotInJob, int tier, int slotInTier, int skillNum, int prevSkillId,
                        int currentPoint, int newPoint, int requireLevel, long cost) {

        public boolean ok() {
            return reason == Reason.OK;
        }

        /** 有前置槽需要已经学过（第 0 槽没有）。 */
        public boolean hasPrev() {
            return prevSkillId != 0;
        }

        private static Learn bad(Reason reason, int skillId) {
            return new Learn(reason, skillId, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        private static Learn bad(Reason reason, SkillDataRegistry.Skill row) {
            return new Learn(reason, row.skillId(), row.constName(), row.macro(), row.classId(),
                    row.slotInJob(), row.tier(), row.slotInTier(), 0, 0, 0, 0, row.reqLv(), 0);
        }
    }

    private SkillRules() {
    }

    /**
     * 解析请求：职业门 + 槽位/成本（**不判**点数与钱，也不写任何东西）。
     *
     * <p>职业门 = 该行数据的 `classId` 是否等于角色 `job`（数据说话，不是代码里的名单）。
     * 未知 id 返回 {@link Reason#UNKNOWN_SKILL} 而**不是抛**：它来自网络。
     */
    public static Learn resolve(Player p, SkillDataRegistry data, int skillId) {
        int job = p.getJob();
        if (!data.hasJob(job)) {
            return Learn.bad(Reason.NO_SKILL_TREE, skillId);
        }
        if (!data.hasId(skillId)) {
            return Learn.bad(Reason.UNKNOWN_SKILL, skillId);
        }
        SkillDataRegistry.Skill row = data.byId(skillId);
        if (row.classId() != job) {
            return Learn.bad(Reason.WRONG_JOB, row);
        }
        int skillNum = skillNum(row.tier(), row.slotInTier());
        if (!hasPrice(skillNum)) {
            // 5 转（槽 16..19）：没有价目表、也不属任何点池 ⇒ 与"槽没开"同样处理（先于 judge 挡掉，
            // 免得往下算出一个 0 元的价）
            return Learn.bad(Reason.SLOT_LOCKED, row);
        }
        int prevSkillId = row.slotInJob() == 0 ? 0 : data.bySlot(job, row.slotInJob() - 1).skillId();
        int point = p.getPropInt(SkillKeys.point(skillId));
        return new Learn(Reason.OK, skillId, row.constName(), row.macro(), row.classId(), row.slotInJob(),
                row.tier(), row.slotInTier(), skillNum, prevSkillId, point, point + 1, row.reqLv(),
                cost(skillNum, point));
    }

    /**
     * 四道门 + 点数 + 钱。
     *
     * @param freePoints 该槽**所属池**的剩余技能点，由唯一求值入口 `SkillPointService.free` 给出
     */
    public static Reason judge(Player p, Learn learn, int freePoints) {
        if (!learn.ok()) {
            return learn.reason();
        }
        if (learn.slotInJob() >= openSlots(p.getRank())) {
            return Reason.SLOT_LOCKED;
        }
        if (learn.hasPrev() && p.getPropInt(SkillKeys.point(learn.prevSkillId())) <= 0) {
            return Reason.PREV_NOT_LEARNED;
        }
        if (learn.requireLevel() + learn.currentPoint() * 2 > p.getLevel()) {
            return Reason.LEVEL_TOO_LOW;
        }
        if (learn.currentPoint() >= MAX_POINT) {
            return Reason.MAX_POINT;
        }
        if (freePoints < 1) {
            return Reason.NO_SKILL_POINT;
        }
        if (p.getGold() < learn.cost()) {
            return Reason.NO_GOLD;
        }
        return Reason.OK;
    }

    /** 该转职档开放几个槽（rank 越高开得越多）。 */
    public static int openSlots(int rank) {
        if (rank <= 0) {
            return OPEN_SLOTS[0];
        }
        return OPEN_SLOTS[Math.min(rank, OPEN_SLOTS.length - 1)];
    }

    /** 学习/升级费 = 基数 + 每级增量 × 升级**前**的等级。 */
    public static long cost(int skillNum, int currentPoint) {
        if (!hasPrice(skillNum)) {
            throw new IllegalArgumentException("技能编号 " + skillNum + " 超范围（1.." + MONEY.length + "）");
        }
        return (long) MONEY[skillNum - 1] + (long) PLUS_MONEY[skillNum - 1] * currentPoint;
    }

    /** 该编号有没有价目表（5 转的 17..20 没有 —— 原版只有 16 项）。 */
    public static boolean hasPrice(int skillNum) {
        return skillNum >= 1 && skillNum <= MONEY.length;
    }

    /** 技能编号 1..20 = (转职档 − 1) × 4 + 档内槽。 */
    public static int skillNum(int tier, int slotInTier) {
        return (tier - 1) * TIER_SLOTS + slotInTier;
    }

    /** 本职业内序号 0..19 = (转职档 − 1) × 4 + (档内槽 − 1)；= 技能编号 − 1。 */
    public static int slotInJob(int tier, int slotInTier) {
        return skillNum(tier, slotInTier) - 1;
    }
}
