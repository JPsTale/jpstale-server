package org.jpstale.server.game.skill.job;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.game.skill.CastContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Healing 的**玩家目标解析**（`PriestessSkills.resolvePlayerTarget`）—— 2026-09-27 用户实测
 * "我点玩家释放 healing，**服务端日志却写（自己）**"后补的特征测试。
 *
 * <p>这个 bug 的本质是**两个 id 空间搞混**（协议面一律 charId，实体运行时 id 只用于 AOI 网格）：
 * 修前用 `entityByRuntimeId(targetId)` 解析客户端上报的 charId ⇒ **永远查不到** ⇒ 悄悄回自己，
 * 于是"点玩家加血"被伪装成了"自疗"（日志 `→ prist（自己）`），看日志也发现不了。
 *
 * <p>钉四条（每条都能红）：
 * <ol>
 *   <li>`targetId == 0` ⇒ 治自己（原版自疗分支 `SkillSub.cpp:537` 的 `!lpCharSelPlayer` 守卫）；</li>
 *   <li>`targetId == 自己 charId` ⇒ 治自己；</li>
 *   <li>`targetId == 别人 charId` ⇒ **返回那个玩家**（本次修的）；</li>
 *   <li>`targetId == 别人的**运行时** id`（修复前的错法）或未知 id ⇒ **null = 什么都不做**
 *       （对齐原版 `rsPlayHealing` 找不到 serial 时的 `return FALSE`；**不许**改成治自己 —— AGENTS #12）。</li>
 * </ol>
 *
 * <p>治疗量的结算（`Life[0] += WParam`、广播飘字）依赖会话/广播层，由真机验收覆盖。
 */
class PriestessHealingTargetTest {

    private static SkillDataRegistry data;
    private static PriestessSkills priestess;
    private static PlayerService playerService;

    private static final long HEALER_CHAR = 100L;
    private static final long OTHER_CHAR = 200L;
    /** 别人（OTHER_CHAR）的**运行时实体 id** —— 与 charId 解耦（`EntityIdSource`），故意取一个不相等的值 */
    private static final long OTHER_RUNTIME = 555L;

    @BeforeAll
    static void setUp() throws ReflectiveOperationException {
        data = new SkillDataRegistry();
        data.load();
        priestess = new PriestessSkills();
        setField(priestess, "skillData", data);

        // PlayerService：只需两个内部 map（byId / entityOf 读它们），其余依赖都不用
        playerService = new PlayerService();
        Map<Long, Player> players = mapOf(playerService, "players");
        Map<Long, PlayerEntity> entities = mapOf(playerService, "entities");

        Player healer = player(HEALER_CHAR, "施法者", 100, 100);
        Player other = player(OTHER_CHAR, "被治疗者", 50, 200);
        players.put(HEALER_CHAR, healer);
        players.put(OTHER_CHAR, other);
        entities.put(HEALER_CHAR, new PlayerEntity(444L, HEALER_CHAR, healer, null));
        entities.put(OTHER_CHAR, new PlayerEntity(OTHER_RUNTIME, OTHER_CHAR, other, null));

        setField(priestess, "playerService", playerService);
    }

    private static Player player(long charId, String name, int hp, int maxHp) {
        Player p = new Player(0);
        p.setCharacterId(charId);
        p.setName(name);
        p.setHp(hp);
        p.setMaxHp(maxHp);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<Long, T> mapOf(Object bean, String field) throws ReflectiveOperationException {
        Field f = bean.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return (Map<Long, T>) f.get(bean);
    }

    private static void setField(Object bean, String field, Object value) throws ReflectiveOperationException {
        Field f = bean.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(bean, value);
    }

    /** 造一个"施法者是 healer、目标是 targetId"的上下文（治疗路径只读 player/self/targetId/表）。 */
    private static CastContext ctx(long targetId) throws ReflectiveOperationException {
        Player healer = playerService.byId(HEALER_CHAR);
        PlayerEntity self = playerService.entityOf(healer);
        return new CastContext(healer, self, 0x080101, 1, targetId, data, null);
    }

    private static Player resolve(long targetId) throws ReflectiveOperationException {
        var m = PriestessSkills.class.getDeclaredMethod("resolvePlayerTarget", CastContext.class);
        m.setAccessible(true);
        return (Player) m.invoke(priestess, ctx(targetId));
    }

    @Test
    void 无目标_治自己() throws Exception {
        assertSame(playerService.byId(HEALER_CHAR), resolve(0L), "targetId=0 ⇒ 自疗分支");
    }

    @Test
    void 目标是自己的charId_治自己() throws Exception {
        assertSame(playerService.byId(HEALER_CHAR), resolve(HEALER_CHAR), "点自己 ⇒ 治自己");
    }

    @Test
    void 目标是别人的charId_治那个人() throws Exception {
        Player target = resolve(OTHER_CHAR);
        assertSame(playerService.byId(OTHER_CHAR), target,
                "**协议面一律 charId**（S2C_EnterGame/PlayerAppear 都是它）⇒ 按 charId 解析");
        assertEquals("被治疗者", target.getName());
    }

    @Test
    void 目标用运行时id上报_认不出_不放也不治自己() throws Exception {
        assertNull(resolve(OTHER_RUNTIME),
                "运行时实体 id 不是协议面 id 空间（修复前就错在这里：永远查不到 ⇒ 悄悄自疗）");
    }

    @Test
    void 目标id未知_返回null而不是治自己() throws Exception {
        assertNull(resolve(999_999L), "认不出 ⇒ 显式的没有（原版 rsPlayHealing 找不到 serial 时 return FALSE）");
    }
}
