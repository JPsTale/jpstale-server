package org.jpstale.server.game.service;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转职接口的特征测试（**任务驱动**，用户 2026-09-24 裁定；设计文档 D4/§2.3）。
 *
 * <p>钉住的事实：
 * <ul>
 *   <li>转职门 = 角色 props 里的任务位 {@code quest.level_20/40/60} == 1（任务系统写，本模块读）——
 *       <b>不看等级</b>：等级门槛由任务自身的接取条件承担；</li>
 *   <li>任务位未完成 ⇒ QUEST_NOT_DONE，且<b>什么都不做</b>（不落库、不广播、不清洗点守卫）；</li>
 *   <li>已 4 转（rank 3）⇒ MAX_RANK，同样什么都不做；</li>
 *   <li>推进 = rank+1（一次一档）+ <b>放开洗点守卫</b>（原版转职清 {@code wVersion[1]}，
 *       {@code sinMessageBox.cpp:1964/2267}）；</li>
 *   <li>任务键与 {@code PlayerKey.QUEST_ID} 模板落在同一个键上（写侧 format / 读侧 getKey 的不变量）。</li>
 * </ul>
 * {@link JobService#tryPromote}（判定 + 内存语义）与 {@code advance} 的拒绝路径
 * 不触碰持久化/广播依赖，可无桩直测；advance OK 路径的落库/广播/推送编排由
 * 任务系统的真实调用链覆盖（任务系统落地时补集成验证）。
 */
class JobServiceTest {

    private final JobService service = new JobService();

    @Test
    void 任务键与模板落在同一个键上() {
        // 写侧 QUEST_ID.format("level_20") 与读侧 QUEST_LEVEL_20.getKey() 必须是同一个键——
        // 不一致时任务系统写的完成标识，读侧永远读不到（静默拿默认 0，转职永远被拒）
        assertEquals(PlayerKey.QUEST_ID.format("level_20"), PlayerKey.QUEST_LEVEL_20.getKey());
        assertEquals(PlayerKey.QUEST_ID.format("level_40"), PlayerKey.QUEST_LEVEL_40.getKey());
        assertEquals(PlayerKey.QUEST_ID.format("level_60"), PlayerKey.QUEST_LEVEL_60.getKey());
    }

    @Test
    void 下一档的任务键按当前rank取() {
        assertEquals("quest.level_20", JobService.questKeyFor(0));
        assertEquals("quest.level_40", JobService.questKeyFor(1));
        assertEquals("quest.level_60", JobService.questKeyFor(2));
        assertThrows(IllegalArgumentException.class, () -> JobService.questKeyFor(3),
                "rank 3 已是 4 转，没有下一档（防调用方用错档位静默读错键）");
    }

    @Test
    void 任务未完成时拒绝且什么都不做() {
        Player p = pikemanAt(0);
        p.setSkillResetUsed(true);
        assertEquals(JobService.Reason.QUEST_NOT_DONE, service.advance(p), "没写任务位 ⇒ 拒绝");
        assertEquals(0, p.getRank(), "rank 不变");
        assertTrue(p.isSkillResetUsed(), "没推进就不许清洗点守卫（原版只在转职时放开）");

        // 任务位写成 0（任务系统置过又清零的形状）同样拒绝
        Player zero = pikemanAt(1);
        zero.setPropInt(PlayerKey.QUEST_LEVEL_40.getKey(), 0);
        zero.setSkillResetUsed(true);
        assertEquals(JobService.Reason.QUEST_NOT_DONE, service.advance(zero));
        assertEquals(1, zero.getRank());
        assertTrue(zero.isSkillResetUsed());
    }

    @Test
    void 已到四转上限时拒绝() {
        Player p = pikemanAt(3);
        // 就算任务位全写 1 也不推进（上限不看任务位）
        p.setPropInt(PlayerKey.QUEST_LEVEL_60.getKey(), 1);
        assertEquals(JobService.Reason.MAX_RANK, service.advance(p));
        assertEquals(3, p.getRank());
    }

    @Test
    void 任务完成后推进一档并放开洗点守卫() {
        Player p = pikemanAt(1);                       // rank 1（已 2 转）
        p.setPropInt(PlayerKey.QUEST_LEVEL_40.getKey(), 1);   // 任务系统写完成标识
        p.setSkillResetUsed(true);

        assertEquals(JobService.Reason.OK, service.tryPromote(p));
        assertEquals(2, p.getRank(), "一次只推一档");
        assertFalse(p.isSkillResetUsed(), "转职放开洗点（原版 wVersion[1]=0）");
    }

    @Test
    void 一次调用只推一档_连升由任务系统按序调两次() {
        Player p = pikemanAt(0);
        // 两个任务位都完成了（极端形状）：advance 只看"下一档"的键
        p.setPropInt(PlayerKey.QUEST_LEVEL_20.getKey(), 1);
        p.setPropInt(PlayerKey.QUEST_LEVEL_40.getKey(), 1);
        assertEquals(JobService.Reason.OK, service.tryPromote(p));
        assertEquals(1, p.getRank(), "每次调用最多一档");
        assertEquals(JobService.Reason.OK, service.tryPromote(p));
        assertEquals(2, p.getRank(), "再调一次才到下一档");
        assertEquals(JobService.Reason.QUEST_NOT_DONE, service.advance(p));
        assertEquals(2, p.getRank(), "第三档的任务位没完成 ⇒ 停在 2 转");
    }

    @Test
    void null玩家返回拒绝() {
        assertEquals(JobService.Reason.NO_PLAYER, service.tryPromote(null));
    }

    @Test
    void gm设值范围0到4_越界拒绝且不改值() {
        // GM 上限比玩法上限宽：4 = 5 转预留档（客户端头模有 d 档），玩法推进仍封顶 3
        assertTrue(JobService.GM_MAX_RANK > JobService.MAX_RANK, "GM 可设到 5 转预留档");
        Player over = pikemanAt(0);
        assertEquals(JobService.Reason.BAD_RANK, service.setRank(over, 5), "5 超出 GM 上限");
        assertEquals(0, over.getRank(), "拒绝后 rank 不变");
        Player under = pikemanAt(2);
        assertEquals(JobService.Reason.BAD_RANK, service.setRank(under, -1), "负数拒绝");
        assertEquals(2, under.getRank());
        assertEquals(JobService.Reason.NO_PLAYER, service.setRank(null, 2));
    }

    private static Player pikemanAt(int rank) {
        Player p = new Player(0);
        p.setName("测试枪兵");
        p.setRank(rank);
        return p;
    }
}
