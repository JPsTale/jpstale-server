package org.jpstale.common.service.account;

/**
 * 游戏管理员（GM）判定 —— **唯一实现**。
 *
 * <p>
 * 判据取自 `userdb.userinfo` 的原版两列 `gamemastertype` / `gamemasterlevel`
 * （EU 侧叫 `GameMasterType` / `GameMasterLevel`）。
 *
 * <p>
 * <b>判据为什么是「与」</b>：<br>
 * 收编前仓库里已有两份各写一份、且判据不一致的 `isGm`：
 * <ul>
 *   <li>{@code game-server NpcShopHandler#isGm}：{@code type != 0 && level > 0}（与）</li>
 *   <li>{@code game-server ChatService#isGm}：{@code level > 0 || type != 0}（或）</li>
 * </ul>
 * 活库（2026-09-21 实测）只有两种组合：{@code (type=1,level=4)} 78 个账号、{@code (type=0,level=0)} 1 个，
 * 两种判据当前放行的是**同一批 78 个**账号 —— 分歧只在将来出现"单边非零"时才显现。
 * 本类取**更严的「与」**（fail-closed）：它同时被当作 **Web 管理端的权限门**（见
 * {@code StpInterfaceImpl}），门这种东西宁可保守。
 *
 * <p>
 * ⚠ 若本意是「或」，改这一处即可（两个 game-server 调用点尚未改为委托本类，见下）。<br>
 * ⚠ 活库里 79 个账号有 78 个命中本判据（测试账号全量灌过 GM 字段）—— 所以拿它当 Web 管理端
 * 权限门，等于"知道这些测试账号密码的人都是管理员"。这是选定的方案，不是漏洞。
 */
public final class GameMasterRule {

    private GameMasterRule() {
    }

    /**
     * 是否游戏管理员。
     *
     * <p>
     * 判据 = {@code type != 0 && level > 0}。两点说明：
     * <ul>
     *   <li>{@code type} 按"**有没有类别**"看（{@code != 0}），不按量级看 —— 它是类别不是等级；
     *       两处既有实现（{@code NpcShopHandler} / {@code ChatService}）也都写 {@code != 0}，
     *       本类照抄以保持一致。库中该列实际只有 0/1，负数不可出现，故不为负数特判。</li>
     *   <li>真正 fail-closed 的是 {@code level > 0}（0 与负数都不算）。</li>
     * </ul>
     *
     * @param type  {@code userinfo.gamemastertype}，可为 null
     * @param level {@code userinfo.gamemasterlevel}，可为 null
     * @return type 非 0 且 level 大于 0；任一为 null 一律 false
     */
    public static boolean isGameMaster(Integer type, Integer level) {
        return type != null && type != 0 && level != null && level > 0;
    }
}
