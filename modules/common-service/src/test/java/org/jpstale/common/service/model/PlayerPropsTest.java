package org.jpstale.common.service.model;

import org.jpstale.common.service.props.PlayerKey;
import org.jpstale.common.service.props.PlayerProperties;
import org.jpstale.common.service.props.SkillKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Player} 上属性包访问器（`getPropInt` / `setPropInt` / bool 糖 / 未注册键）的特征测试。
 *
 * <p>这些访问器是 `characterinfo.props` 的**唯一读写口**（落库走整行 UPDATE）。断言里最要紧的是
 * "包里没有" 与 "包里写了 0" 必须区分开：前者退默认值、后者就是 0。
 */
class PlayerPropsTest {

    private static Player pikeman() {
        Player p = new Player(0);
        p.setJob(4);
        p.setLevel(20);
        return p;
    }

    @Test
    void int往返() {
        Player p = pikeman();
        String key = SkillKeys.point(0x040101);
        p.setPropInt(key, 7);
        assertEquals(7, p.getPropInt(key));
        assertEquals(7, p.getProps().get(key), "落在同一个包里（落库序列化的就是它）");
    }

    @Test
    void 包里没有该键就是注册表默认值() {
        Player p = pikeman();
        assertEquals(PlayerKey.QUEST_LEVEL_80.getDefaultValue(),
                p.getPropInt(PlayerKey.QUEST_LEVEL_80.getKey()));
        assertEquals(0, p.getPropInt("没人注册的键"));
    }

    @Test
    void 调用方的默认值优先于注册表() {
        Player p = pikeman();
        String key = PlayerKey.QUEST_LEVEL_55.getKey();
        assertEquals(5, p.getPropIntOrDefault(key, 5), "包里没有 ⇒ 用调用方的 def");

        p.setPropInt(key, 2);
        assertEquals(2, p.getPropIntOrDefault(key, 5), "包里写了 ⇒ 用包里的");

        p.setPropInt(key, 0);
        assertEquals(0, p.getPropIntOrDefault(key, 5), "显式写了 0 也算有值（不退到 def）");
    }

    @Test
    void bool只是0和1的糖() {
        Player p = pikeman();
        String key = PlayerKey.QUEST_LEVEL_70.getKey();

        p.setPropBool(key, true);
        assertEquals(1, p.getPropInt(key), "true 落成 1");
        assertTrue(p.getPropBool(key));

        p.setPropBool(key, false);
        assertEquals(0, p.getPropInt(key), "false 落成 0");
        assertFalse(p.getPropBool(key));

        // 没写过的键 ⇒ 用调用方的 def
        assertTrue(p.getPropBoolOrDefault("没写过的键", true));
        assertFalse(p.getPropBoolOrDefault("没写过的键", false));

        // 非 0 即真（不是只认 1）
        p.setPropInt(key, 2);
        assertTrue(p.getPropBool(key));
    }

    @Test
    void 一次取多个键() {
        Player p = pikeman();
        p.setPropInt("quest.level_55", 1);
        Map<String, Integer> m = p.getPropMany(List.of("quest.level_55", "quest.level_70"));
        assertEquals(1, m.get("quest.level_55"));
        assertEquals(0, m.get("quest.level_70"), "没写过的键填默认值");
        assertEquals(List.of("quest.level_55", "quest.level_70"), List.copyOf(m.keySet()), "保持入参顺序");
        assertEquals(Map.of(), p.getPropMany(null));
    }

    @Test
    void 未注册的键会被列出来() {
        Player p = pikeman();
        p.setPropInt("zeta.qux", 2);
        p.setPropInt("foo.bar", 1);
        p.setPropInt(PlayerKey.QUEST_LEVEL_55.getKey(), 1);
        p.setPropInt(SkillKeys.point(0x040101), 3);

        assertEquals(List.of("foo.bar", "zeta.qux"), p.unknownPropKeys(),
                "升序；已注册的键与 skill.* 都不是「未注册」");
    }

    @Test
    void 键不能是null或空白() {
        Player p = pikeman();
        assertThrows(IllegalArgumentException.class, () -> p.setPropInt("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> p.getPropInt(null));
        assertThrows(IllegalArgumentException.class, () -> p.getPropIntOrDefault(null, 0));
        assertEquals(0, p.getProps().size(), "被拒的写入不许落进包里");
    }

    @Test
    void 载入脏JSON当场抛且不留半包() {
        Player p = pikeman();
        assertThrows(IllegalArgumentException.class,
                () -> p.setProps(PlayerProperties.parse("{\"quest.level_55\":\"x\"}")));
        assertEquals(0, p.getProps().size(), "抛在半路 ⇒ 包保持原样（装载路径 PlayerService.load 同理）");

        // 干净的 JSON 当然能进
        p.setProps(PlayerProperties.parse("{\"quest.level_55\":1}"));
        assertEquals(1, p.getPropInt(PlayerKey.QUEST_LEVEL_55.getKey()));
    }
}
