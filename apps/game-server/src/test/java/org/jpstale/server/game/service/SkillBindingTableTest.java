package org.jpstale.server.game.service;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.common.service.skill.SkillBindRules;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.server.proto.base.S2C_SkillBindings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `S2C_SkillBindings` 的**同源性**测试：发出去的那几个数必须**逐字来自 props**
 * （`SkillBindRules.read`），不许多算一位、不许补默认值。
 *
 * <p>为什么值得单测：客户端**不做乐观更新**（改绑定只发包，界面等这一条回推），所以界面对不对
 * 完全押在这条消息上；而它和"存到 props"是**两个**读路径（一个读内存包、一个读键），
 * 键拼错一位就是"绑定改了但界面不变"（本项目最擅长的那类静默失效）。
 */
class SkillBindingTableTest {

    private static SkillDataRegistry data;

    @BeforeAll
    static void load() {
        data = new SkillDataRegistry();
        data.load();
    }

    private static Player pikeman(int level) {
        Player p = new Player(0);
        p.setJob(4);
        p.setLevel(level);
        return p;
    }

    /** pikeman 里第一个 `useCode` 等于给定值的技能 id。 */
    private static int idWith(String useCode) {
        return data.ofJob(4).stream().filter(s -> useCode.equals(s.useCode())).findFirst()
                .orElseThrow(() -> new IllegalStateException("pikeman 没有 useCode=" + useCode + " 的技能")).skillId();
    }

    private final SkillPointService svc = new SkillPointService(data);

    @Test
    void 没绑过时三处都是0_且quick定长8() {
        S2C_SkillBindings m = svc.buildSkillBindings(pikeman(20));
        assertEquals(0, m.getFistLeft());
        assertEquals(0, m.getFistRight());
        assertEquals(PlayerKey.QUICK_BIND_COUNT, m.getQuickCount(), "定长 8（下标 0 = F1）");
        for (int v : m.getQuickList()) {
            assertEquals(0, v, "0 = 未绑");
        }
    }

    @Test
    void 绑定表逐字来自props() {
        Player p = pikeman(20);
        int right = idWith("RIGHT");
        int all = idWith("ALL");
        SkillBindRules.apply(p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_RIGHT, right);
        SkillBindRules.apply(p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_LEFT, all);
        SkillBindRules.apply(p, SkillBindRules.KIND_QUICK, 1, right);
        SkillBindRules.apply(p, SkillBindRules.KIND_QUICK, 8, all);

        SkillBindRules.Bindings props = SkillBindRules.read(p);
        S2C_SkillBindings m = svc.buildSkillBindings(p);
        assertEquals(props.fistLeft(), m.getFistLeft(), "左拳 = props 的值（不是别的推导）");
        assertEquals(props.fistRight(), m.getFistRight(), "右拳");
        assertEquals(all, m.getFistLeft());
        for (int i = 0; i < PlayerKey.QUICK_BIND_COUNT; i++) {
            assertEquals(props.quick()[i], m.getQuick(i),
                    "F" + (i + 1) + " 与 props 同源（同一个键、同一个值）");
        }
        assertEquals(0, m.getQuick(4), "没绑的 F5 就是 0（不补默认技能）");
    }

    @Test
    void 解绑后在消息里回到0() {
        Player p = pikeman(20);
        int right = idWith("RIGHT");
        SkillBindRules.apply(p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_RIGHT, right);
        assertEquals(right, svc.buildSkillBindings(p).getFistRight());

        SkillBindRules.apply(p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_RIGHT, SkillBindRules.UNBOUND);
        assertEquals(0, svc.buildSkillBindings(p).getFistRight(), "解绑后是 0（拳位回到普通攻击）");
    }

    @Test
    void 两个角色的绑定互不串() {
        Player a = pikeman(20);
        Player b = pikeman(20);
        SkillBindRules.apply(a, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_LEFT, idWith("ALL"));
        assertEquals(0, svc.buildSkillBindings(b).getFistLeft(), "另一角色的绑定表是空的（按角色读 props）");
        assertTrue(a.getProps().containsKey(PlayerKey.fistBind(PlayerKey.Fist.LEFT)));
        assertTrue(b.getProps().isEmpty(), "b 一个键都没被写过");
    }
}
