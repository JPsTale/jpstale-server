package org.jpstale.common.service.skill;

import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.common.service.props.PlayerProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillBindRules} 的特征测试：绑定身份（本职业 / `useCode` 位置许可）+ props 读写往返。
 *
 * <p>数据用**真**的 {@link SkillDataRegistry}：哪个技能是 `RIGHT`/`ALL`/`NOT` 从生成物里**现查**
 * （不手打 useCode，否则"断言里的值"与"数据里的值"各错一半也能过）。
 */
class SkillBindRulesTest {

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

    /** pikeman 里第一个 `useCode` 等于给定值的槽。 */
    private static SkillDataRegistry.Skill firstWith(String useCode) {
        return data.ofJob(4).stream().filter(s -> useCode.equals(s.useCode())).findFirst()
                .orElseThrow(() -> new IllegalStateException("pikeman 没有 useCode=" + useCode + " 的技能（数据变了）"));
    }

    /** 别的职业（fighter）第一个槽 —— 用来验"异职业 id 必须被拒"。 */
    private static SkillDataRegistry.Skill foreign() {
        return data.ofJob(1).get(0);
    }

    /* ─────────────── 合法：绑定 / 解绑 ─────────────── */

    @Test
    void 绑右拳_写进props的右拳键() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        SkillBindRules.Judged j = SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST,
                SkillBindRules.INDEX_RIGHT, right.skillId());
        assertTrue(j.ok(), "同职业 + RIGHT 技能绑右拳应当通过：" + j.reason());

        SkillBindRules.apply(p, j.kind(), j.index(), j.skillId());
        assertEquals(right.skillId(), p.getPropInt(PlayerKey.fistBind(PlayerKey.Fist.RIGHT)));
        assertEquals(0, p.getPropInt(PlayerKey.fistBind(PlayerKey.Fist.LEFT)), "左拳没动");
    }

    @Test
    void 绑快捷栏_写进对应的F键键() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        SkillBindRules.Judged j = SkillBindRules.judge(data, p, SkillBindRules.KIND_QUICK, 3, right.skillId());
        assertTrue(j.ok(), String.valueOf(j.reason()));

        SkillBindRules.apply(p, j.kind(), j.index(), j.skillId());
        assertEquals(right.skillId(), p.getPropInt(PlayerKey.quickBind(3)), "F3");
        assertEquals(0, p.getPropInt(PlayerKey.quickBind(4)), "F4 没动");
    }

    @Test
    void 解绑只写0_不校验技能身份() {
        Player p = pikeman(20);
        SkillBindRules.Judged j = SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST,
                SkillBindRules.INDEX_LEFT, SkillBindRules.UNBOUND);
        assertTrue(j.ok(), "解绑不需要技能身份：" + j.reason());

        SkillBindRules.apply(p, j.kind(), j.index(), j.skillId());
        assertEquals(0, p.getPropInt(PlayerKey.fistBind(PlayerKey.Fist.LEFT)), "0 = 未绑（普通攻击拳）");
    }

    /* ─────────────── 拒绝：身份 / 位置 / 结构 ─────────────── */

    @Test
    void 异职业的id被拒() {
        Player p = pikeman(20);
        SkillBindRules.Judged j = SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST,
                SkillBindRules.INDEX_RIGHT, foreign().skillId());
        assertEquals(SkillBindRules.Reason.WRONG_JOB, j.reason(), "异职业绑定正是「HUD 画出别职业图标」的来源");
        assertEquals("wrongJob", j.reason().key());
    }

    @Test
    void 表里没有的id被拒() {
        Player p = pikeman(20);
        SkillBindRules.Judged j = SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST,
                SkillBindRules.INDEX_RIGHT, 0x7F7F7F);
        assertEquals(SkillBindRules.Reason.UNKNOWN_SKILL, j.reason());
    }

    @Test
    void useCode不允许位置被拒() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill notSkill = firstWith("NOT");
        assertEquals(SkillBindRules.Reason.NOT_BINDABLE,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_QUICK, 1, notSkill.skillId()).reason(),
                "NOT（被动一类）不能绑快捷栏");
        assertEquals(SkillBindRules.Reason.NOT_BINDABLE,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_LEFT,
                        notSkill.skillId()).reason(),
                "NOT 也不能绑拳位");
    }

    @Test
    void 只能绑右键的技能绑左拳被拒() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        assertEquals(SkillBindRules.Reason.WRONG_FIST,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_LEFT,
                        right.skillId()).reason(),
                "RIGHT 技能不能绑左拳（源语 LButtonUp 判 LEFT|ALL）");
        // 同一技能绑右拳是合法的（对照组，证明拒绝的是"位置"不是"技能"）
        assertTrue(SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_RIGHT,
                right.skillId()).ok());
    }

    @Test
    void ALL技能左右拳都能绑() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill all = firstWith("ALL");
        assertTrue(SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST,
                SkillBindRules.INDEX_LEFT, all.skillId()).ok(), "LEFT|ALL");
        assertTrue(SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST,
                SkillBindRules.INDEX_RIGHT, all.skillId()).ok(), "RIGHT|ALL");
    }

    @Test
    void 未知kind与越界index被拒() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        assertEquals(SkillBindRules.Reason.BAD_KIND,
                SkillBindRules.judge(data, p, 9, 1, right.skillId()).reason());
        assertEquals(SkillBindRules.Reason.BAD_KIND,
                SkillBindRules.judge(data, p, 0, 1, right.skillId()).reason());
        assertEquals(SkillBindRules.Reason.BAD_INDEX,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_FIST, 3, right.skillId()).reason(),
                "拳位只有 1=左 / 2=右");
        assertEquals(SkillBindRules.Reason.BAD_INDEX,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_QUICK, 0, right.skillId()).reason());
        assertEquals(SkillBindRules.Reason.BAD_INDEX,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_QUICK,
                        PlayerKey.QUICK_BIND_COUNT + 1, right.skillId()).reason(), "F9 不存在");
        // 解绑也要过结构门（不允许用一个坏 index 去写任意键）
        assertEquals(SkillBindRules.Reason.BAD_INDEX,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_QUICK, 99, SkillBindRules.UNBOUND).reason());
    }

    @Test
    void 职业号不在1到11时给noSkillTree() {
        Player p = new Player(0);
        p.setJob(99);
        assertEquals(SkillBindRules.Reason.NO_SKILL_TREE,
                SkillBindRules.judge(data, p, SkillBindRules.KIND_QUICK, 1, 0x040101).reason());
    }

    @Test
    void 读绑定表_键默认0() {
        SkillBindRules.Bindings b = SkillBindRules.read(pikeman(20));
        assertEquals(0, b.fistLeft());
        assertEquals(0, b.fistRight());
        assertEquals(PlayerKey.QUICK_BIND_COUNT, b.quick().length, "定长 8（F1~F8）");
        for (int v : b.quick()) {
            assertEquals(0, v, "没绑过 = 0");
        }
    }

    /* ─────────────── 持久化：写 props → 序列化 → 读回 ─────────────── */

    @Test
    void 落库往返_绑定一致() {
        Player saved = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        SkillBindRules.apply(saved, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_RIGHT, right.skillId());
        SkillBindRules.apply(saved, SkillBindRules.KIND_QUICK, 5, right.skillId());
        SkillBindRules.apply(saved, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_LEFT, SkillBindRules.UNBOUND);

        // 走真实落库路径的两端：PlayerService.persistStats 的 serialize + load 的 parse/setProps
        String json = PlayerProperties.serialize(saved.getProps());
        Player reloaded = pikeman(20);
        reloaded.setProps(PlayerProperties.parse(json));

        // ⚠ `Bindings` 带 `int[]`，记录的 `equals` 按引用比数组 ⇒ 这里**逐字段**比，不比对象
        SkillBindRules.Bindings before = SkillBindRules.read(saved);
        SkillBindRules.Bindings after = SkillBindRules.read(reloaded);
        assertEquals(before.fistLeft(), after.fistLeft(), "左拳");
        assertEquals(before.fistRight(), after.fistRight(), "右拳");
        assertEquals(java.util.Arrays.toString(before.quick()), java.util.Arrays.toString(after.quick()),
                "F1~F8");
    }

    /** ⚠ `quick()` 返回副本：改它不该动内部状态。 */
    @Test
    void 绑定快照的数组不外露() {
        Player a = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        SkillBindRules.apply(a, SkillBindRules.KIND_QUICK, 2, right.skillId());
        SkillBindRules.Bindings b = SkillBindRules.read(a);
        int[] copy = b.quick();
        copy[1] = 0;
        assertEquals(right.skillId(), b.quick()[1], "改副本不影响快照（F2 还在）");
    }

    /* ─────────────── props 键的登记（键只在 PlayerKey 一处写） ─────────────── */

    @Test
    void 绑定键在props注册表里() {
        assertTrue(PlayerProperties.isKnown(PlayerKey.fistBind(PlayerKey.Fist.LEFT)));
        assertTrue(PlayerProperties.isKnown(PlayerKey.fistBind(PlayerKey.Fist.RIGHT)));
        for (int i = 1; i <= PlayerKey.QUICK_BIND_COUNT; i++) {
            assertTrue(PlayerProperties.isKnown(PlayerKey.quickBind(i)), "bind.quick." + i);
        }
        assertEquals(0, PlayerProperties.defaultOf(PlayerKey.fistBind(PlayerKey.Fist.LEFT)), "默认 0 = 未绑");
        assertEquals(0, PlayerProperties.defaultOf(PlayerKey.quickBind(8)));
        assertFalse(PlayerProperties.isKnown("bind.quick.9"), "F9 不是注册键");
        assertFalse(PlayerProperties.isKnown("bind.fist.up"));
    }

    @Test
    void 越界下标即抛_不许拼出错键() {
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.quickBind(0));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.quickBind(PlayerKey.QUICK_BIND_COUNT + 1));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.fistBind(null));
    }

    @Test
    void 写入的入口自己也要挡越界_不许静默落错键() {
        Player p = pikeman(20);
        assertThrows(IllegalArgumentException.class,
                () -> SkillBindRules.apply(p, SkillBindRules.KIND_FIST, 3, 0x040101),
                "拳位 index 3 越界：静默写右拳会让读侧以为「玩家自己绑的」");
        assertThrows(IllegalArgumentException.class,
                () -> SkillBindRules.apply(p, 7, 1, 0x040101), "未知 kind");
        assertThrows(IllegalArgumentException.class,
                () -> SkillBindRules.apply(p, SkillBindRules.KIND_QUICK, 0, 0x040101));
        assertEquals(0, p.getProps().size(), "被拒的写入不许落进包里");
    }

    @Test
    void 绑定键的键形() {
        assertEquals("bind.fist.left", PlayerKey.fistBind(PlayerKey.Fist.LEFT));
        assertEquals("bind.fist.right", PlayerKey.fistBind(PlayerKey.Fist.RIGHT));
        assertEquals("bind.quick.1", PlayerKey.quickBind(1));
        assertEquals("bind.quick.8", PlayerKey.quickBind(8));
    }

    @Test
    void 绑定键不是烂键_unknownPropKeys不报() {
        Player p = pikeman(20);
        SkillDataRegistry.Skill right = firstWith("RIGHT");
        SkillBindRules.apply(p, SkillBindRules.KIND_FIST, SkillBindRules.INDEX_RIGHT, right.skillId());
        SkillBindRules.apply(p, SkillBindRules.KIND_QUICK, 8, right.skillId());
        assertTrue(p.unknownPropKeys().isEmpty(), "绑定键必须在注册表里：" + p.unknownPropKeys());
    }
}
