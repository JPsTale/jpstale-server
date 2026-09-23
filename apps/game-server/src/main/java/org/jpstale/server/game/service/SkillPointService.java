package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.skill.SkillBindRules;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.skill.SkillRules;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.LearnedSkill;
import org.jpstale.server.proto.base.S2C_SkillBindings;
import org.jpstale.server.proto.base.S2C_SkillList;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.stereotype.Service;

/**
 * 技能点的**唯一求值入口**（两个池）+ 学技能/洗点的**唯一内存写入处** + **技能表/绑定表的下发处**。
 *
 * <p>技能点是**派生量**（等级 + 任务位 − 已花），不落库；落库的只有各技能等级/熟练度与任务位。
 * 于是升级路径零写入、任务位一写就自动生效、洗点只要把技能键清零。
 *
 * <p>技能绑定（拳位/F1~F8）也在这一层下发：它和技能表是**同一批时机**（登录/选角、学技能后、
 * 洗点后），配成 {@link #sendSkillTables} 一处发出，免得某条路径只发了技能表。
 * 绑定的判定与读写另在 {@link SkillBindRules}（common-service，纯规则）。
 *
 * <p>技能的标识是**数字 `skillId`**（`SkillDataRegistry.Skill.skillId()`）：池归属看该行的
 * `slotInJob`、等级看键 `skill.<0xID>.point`。**不看宏名**（60 行没有宏）。
 *
 * <p>职责边界（为什么扣钱与落库不在这里）：判定与写入必须能与"扣钱 + 落库"分开 —— 后者要走
 * `GoldService` / `PlayerService`，一碰就需要 DB，而这一层要能离线单测。所以本类只做
 * 求值 + 改内存 + 拼包，扣钱与落库由 `SkillNetworkHandler` 按既有通道完成（钱只走金库那一道）。
 */
@Slf4j
@Service
public class SkillPointService {

    /** 点池：1–3 转池 / 4 转池。 */
    public enum Pool { ONE, FOUR }

    /**
     * 池的槽位段（`slotInJob` 是 **0 基**；原版数的是 1 基的「槽 1..12 / 13..16」）。
     * ⚠ 差一位就静默算错（少算/多算一整档的点数），故只此一处。
     */
    private static final int ONE_POOL_FIRST = 0;
    private static final int ONE_POOL_LAST = 11;
    private static final int FOUR_POOL_FIRST = 12;
    /** 池的上界与价目表的上界是同一个数：16~19（5 转）**没有价、也不属任何池**。 */
    private static final int FOUR_POOL_LAST = 15;

    /** 两个池各自的等级门（门槛之下是 0，不是负数）。 */
    private static final int ONE_POOL_MIN_LEVEL = 10;
    private static final int FOUR_POOL_MIN_LEVEL = 60;

    private final SkillDataRegistry skillData;

    public SkillPointService(SkillDataRegistry skillData) {
        this.skillData = skillData;
    }

    /* ─────────────── 求值（唯一实现） ─────────────── */

    /** 该池的剩余点数（等级不够 ⇒ 0）。 */
    public int free(Player p, Pool pool) {
        if (p == null) {
            throw new IllegalArgumentException("算技能点需要一个已装载的 Player（null 不是「没有点数」）");
        }
        if (pool == null) {
            // 槽位 16..19（5 转预留）不属任何池：静默按 4 转池算就会扣错池，宁可当场炸
            throw new IllegalArgumentException("该槽位不属于任何点池（原版只有 1–3 转池与 4 转池）");
        }
        if (!skillData.hasJob(p.getJob())) {
            return 0;   // 职业号不在 1..11（存档坏了）：显示一堆花不出去的点没有意义
        }
        return Math.max(0, gained(p, pool) - spent(p, pool));
    }

    /**
     * 载入自检：**给的 < 花的** ⇒ 存档非法，返回非法池的个数（每个池一条 error 日志）。
     *
     * <p>不能用 {@link #free} 判（它按原版口径夹到 0），所以在这里比原始值。**只报错、不改数据**：
     * 静默修正会把"存档坏了"演成"正常"，事后查不出来。
     */
    public int checkLoaded(Player p) {
        int bad = 0;
        for (Pool pool : Pool.values()) {
            int over = spent(p, pool) - gained(p, pool);
            if (over > 0) {
                bad++;
                log.error("[Skill] 角色 {} 的技能点存档非法：{} 池已花比所得多 {} 点（等级 {}）—— 不改数据，待人工处理",
                        p.getName(), pool, over, p.getLevel());
            }
        }
        return bad;
    }

    /** 该池一共给了多少点（等级门没过 ⇒ 0，**不是负数**；否则自检会把没过门的池误报成"超花"）。 */
    private int gained(Player p, Pool pool) {
        int level = p.getLevel();
        if (pool == Pool.ONE && level < ONE_POOL_MIN_LEVEL) {
            return 0;
        }
        if (pool == Pool.FOUR && level < FOUR_POOL_MIN_LEVEL) {
            return 0;
        }
        return base(pool, level) + questPoints(p, pool);
    }

    /** 该池已花的点数 = 该职业该池各技能的 `point` 之和。 */
    private int spent(Player p, Pool pool) {
        if (!skillData.hasJob(p.getJob())) {
            return 0;
        }
        int sum = 0;
        for (SkillDataRegistry.Skill s : skillData.ofJob(p.getJob())) {
            if (pool != poolOfSlot(s.slotInJob())) {
                continue;
            }
            sum += p.getPropInt(SkillKeys.point(s.skillId()));
        }
        return sum;
    }

    /** 等级给的底数（整数除法）。 */
    private static int base(Pool pool, int level) {
        return pool == Pool.ONE ? (level - 8) / 2 : (level - 58) / 2;
    }

    /** 任务位给的技能点（只有 1–3 转池有；80_2/90_2 是属性点，不在这里）。 */
    private static int questPoints(Player p, Pool pool) {
        if (pool != Pool.ONE) {
            return 0;
        }
        return p.getPropInt(PlayerKey.QUEST_LEVEL_55.getKey())
                + p.getPropInt(PlayerKey.QUEST_LEVEL_70.getKey())
                + p.getPropInt(PlayerKey.QUEST_LEVEL_80.getKey());
    }

    /** 槽位属于哪个池；16..19（5 转）没有池 ⇒ null（调用方按"不属于任何池"处理）。 */
    private static Pool poolOfSlot(int slotInJob) {
        if (slotInJob >= ONE_POOL_FIRST && slotInJob <= ONE_POOL_LAST) {
            return Pool.ONE;
        }
        if (slotInJob >= FOUR_POOL_FIRST && slotInJob <= FOUR_POOL_LAST) {
            return Pool.FOUR;
        }
        return null;
    }

    /* ─────────────── 学 / 升级 ─────────────── */

    /** 学/升级的结果：OK 时 `cost` 是这次该收的钱（调用方走金库通道扣）。 */
    public record LearnResult(SkillRules.Reason reason, long cost) {
        public boolean ok() {
            return reason == SkillRules.Reason.OK;
        }
    }

    /**
     * 学/升级**一级**：判定（`SkillRules`）通过后把该技能的等级写进 props（内存）。
     *
     * <p>**不扣钱、不落库**：钱要走唯一金库通道（它顺带整行落库 + 回推状态），由调用方接着做；
     * 万一那一步被拒，用 {@link #undoLearn} 把刚写的这一级退回去（否则玩家白得一级）。
     */
    public LearnResult learn(Player p, int skillId) {
        SkillRules.Learn learn = SkillRules.resolve(p, skillData, skillId);
        if (!learn.ok()) {
            return new LearnResult(learn.reason(), 0);
        }
        int free = free(p, poolOfSlot(learn.slotInJob()));
        SkillRules.Reason reason = SkillRules.judge(p, learn, free);
        if (reason != SkillRules.Reason.OK) {
            log.debug("[Skill] {} 学 {}（{}）被拒：{}（等级 {} / 当前 {} 级 / 剩余点 {} / 钱 {}）",
                    p.getName(), learn.constName() == null ? "?" : learn.constName(),
                    SkillKeys.describe(skillId), reason, p.getLevel(), learn.currentPoint(), free, p.getGold());
            return new LearnResult(reason, learn.cost());
        }
        p.setPropInt(SkillKeys.point(skillId), learn.newPoint());
        return new LearnResult(SkillRules.Reason.OK, learn.cost());
    }

    /** 退回刚学的那一级（只在"扣钱被拒"时用；按当前等级减 1，不会低于 0）。 */
    public void undoLearn(Player p, int skillId) {
        SkillRules.Learn learn = SkillRules.resolve(p, skillData, skillId);
        if (!learn.ok() || learn.currentPoint() <= 0) {
            return;
        }
        p.setPropInt(SkillKeys.point(skillId), learn.currentPoint() - 1);
    }

    /* ─────────────── 洗点 ─────────────── */

    /**
     * 洗点：把本职业所有技能等级/熟练度清零，并置"本次登录已洗"标记（会话内存标记，不落库）。
     *
     * <p>不需要"退点"逻辑 —— 剩余点是派生量，清键即退点。守卫照原版的实际行为：**登录/转职清零**，
     * 不是"每转职一次"（原版那个标志服务端既不读也不存）。
     */
    public SkillRules.Reason reset(Player p) {
        if (p.isSkillResetUsed()) {
            return SkillRules.Reason.RESET_USED;
        }
        if (!skillData.hasJob(p.getJob())) {
            return SkillRules.Reason.NO_SKILL_TREE;
        }
        for (SkillDataRegistry.Skill s : skillData.ofJob(p.getJob())) {
            clearIfPresent(p, SkillKeys.point(s.skillId()));
            clearIfPresent(p, SkillKeys.mastery(s.skillId()));
        }
        p.setSkillResetUsed(true);
        log.info("[Skill] {} 洗点完成（等级 {}）", p.getName(), p.getLevel());
        return SkillRules.Reason.OK;
    }

    /** 只清**已经存在**的键：未学 = 键不存在，写一堆 0 进去只是噪声。 */
    private static void clearIfPresent(Player p, String key) {
        if (p.getProps().containsKey(key)) {
            p.setPropInt(key, 0);
        }
    }

    /* ─────────────── 下发 ─────────────── */

    /**
     * 技能表。**只含已学的**（`point > 0`）：未学 = 键不存在 = 等级 0，客户端不需要为此多收 20 条。
     *
     * <p>标识发**数字 `skillId`**（客户端据此反查自己的图标/动画下标）。
     */
    public S2C_SkillList buildSkillList(Player p) {
        S2C_SkillList.Builder b = S2C_SkillList.newBuilder()
                .setSkillPoint(free(p, Pool.ONE))
                .setSpecialSkillPoint(free(p, Pool.FOUR));
        if (!skillData.hasJob(p.getJob())) {
            return b.build();   // 职业号不在 1..11：只有点数为 0 的空表
        }
        for (SkillDataRegistry.Skill s : skillData.ofJob(p.getJob())) {
            int point = p.getPropInt(SkillKeys.point(s.skillId()));
            if (point <= 0) {
                continue;
            }
            b.addSkills(LearnedSkill.newBuilder()
                    .setSkillId(s.skillId())
                    .setPoint(point)
                    .setMastery(p.getPropInt(SkillKeys.mastery(s.skillId())))
                    .build());
        }
        return b.build();
    }

    /** 发给某个会话（无会话/未登录时静默跳过，与 `PlayerService.sendPlayerStatus` 同约定）。 */
    public void sendSkillList(PlayerSession session, Player p) {
        if (session == null || !session.isLoggedIn() || p == null) {
            return;
        }
        session.send(ServerMessage.newBuilder().setSkillList(buildSkillList(p)).build());
    }

    /* ─────────────── 技能绑定表（拳位 / F1~F8） ─────────────── */

    /** 绑定表。**定长 8 个 `quick`**（下标 0 = F1，0 = 未绑）—— 客户端据此按下标取值，不猜长度。 */
    public S2C_SkillBindings buildSkillBindings(Player p) {
        SkillBindRules.Bindings b = SkillBindRules.read(p);
        S2C_SkillBindings.Builder out = S2C_SkillBindings.newBuilder()
                .setFistLeft(b.fistLeft())
                .setFistRight(b.fistRight());
        for (int v : b.quick()) {
            out.addQuick(v);
        }
        return out.build();
    }

    /** 只发绑定表（改绑定之后：客户端不做乐观更新，界面等这一条回推）。 */
    public void sendSkillBindings(PlayerSession session, Player p) {
        if (session == null || !session.isLoggedIn() || p == null) {
            return;
        }
        session.send(ServerMessage.newBuilder().setSkillBindings(buildSkillBindings(p)).build());
    }

    /**
     * 技能表 + 绑定表（**同一批时机**：登录/选角、学技能后、洗点后）。
     *
     * <p>两个 send 只在这里配对 —— 调用点不必记得发两次，也就不会有"新加一处调用只发了技能表"
     * 这种静默缺口（绑定表没到 = 客户端显式显示"未知"，见 AGENTS #12）。
     */
    public void sendSkillTables(PlayerSession session, Player p) {
        sendSkillList(session, p);
        sendSkillBindings(session, p);
    }
}
