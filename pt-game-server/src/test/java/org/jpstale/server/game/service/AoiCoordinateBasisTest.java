package org.jpstale.server.game.service;

import io.netty.channel.embedded.EmbeddedChannel;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.item.GroundItemManager;
import org.jpstale.server.game.item.ItemInstance;
import org.jpstale.server.game.item.ItemRules;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.Npc;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.SessionState;
import org.jpstale.server.proto.base.MessageProto;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * **可见性只看坐标，不看"哪张图"** —— 回归（用户 2026-09-16 实测）：
 * "我在村庄门口能看到门外的玩家在战斗，但看不到她在跟谁战斗。"
 *
 * 地图是**人为切分**的（同 AGENTS #18 对碰撞的结论）：一张图只包含它负责的那部分几何/实体，
 * 所以"边界另一侧的怪/NPC/掉落物离我只有几步"是**切分的正常结果**，不是"它们不存在"。
 * 按 `mapId` 取实体 = 用切分结果当空间判据 ⇒ 边界两侧互相看不见。
 *
 * 玩家 AOI 本来就是坐标口径（`AOIManager.getNearbyPlayers(x,z)`），原版也是
 * （ex-machina `srTransPlayData` 逐只 `dist² < DIST_TRANSLEVEL_CONNECT`）。
 * 本测试把 怪/NPC/掉落物 三类也钉在同一口径上 —— 三个断言各自对应一类。
 */
public class AoiCoordinateBasisTest {

    private static final long PID = 7L;
    private static final int MY_MAP = 1;
    private static final int OTHER_MAP = 2;   // 边界另一侧
    private static final double MY_X = 100;
    private static final double NEAR_X = 0;   // 距我 100 < CONNECT(1000)
    private static final double FAR_X = 5000; // 远超 DISCONNECT(1600)

    // ==================== 工具 ====================

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static List<MessageProto.ServerMessage> drain(EmbeddedChannel ch) {
        List<MessageProto.ServerMessage> out = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            MessageProto.ServerMessage m = (MessageProto.ServerMessage) o;
            if (m.hasBatch()) {
                out.addAll(m.getBatch().getMessagesList());
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /** PLAYING 且在 (x,0) 的观察者，所在图 = MY_MAP */
    private static PlayerSession observer(SessionManager sm, EmbeddedChannel ch, double x) {
        PlayerSession s = sm.createSession(ch);
        sm.bindCharacterId(ch, PID, "obs");   // sessionsByCharacterId 只在这里登记
        s.setState(SessionState.PLAYING);
        PlayerEntity e = new PlayerEntity(PID, PID, null, s);
        e.setMapId(MY_MAP);
        e.setX(x);
        e.setY(0);
        e.setZ(0);
        s.setEntity(e);
        return s;
    }

    private static Monster monster(double x, int mapId) {
        Monster m = new Monster();
        m.setName("cross-boundary-mob");
        m.setMaxHp(100);
        m.setHp(100);
        m.setX(x);
        m.setY(0);
        m.setZ(0);
        m.setMapId(mapId);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static MonsterSpawnService spawnWith(List<Monster> monsters, int mapId) throws Exception {
        MonsterSpawnService svc = new MonsterSpawnService();
        Field f = MonsterSpawnService.class.getDeclaredField("monstersByMap");
        f.setAccessible(true);
        ((Map<Integer, List<Monster>>) f.get(svc)).put(mapId, monsters);
        return svc;
    }

    private static ItemInstance item() {
        ItemList t = new ItemList();
        t.setId(484);
        t.setName("Gold");
        t.setIdCode(ItemRules.CODE_GOLD);
        t.setCodeImg1("GG101");
        t.setCodeImg2("DRCOIN");
        t.setWidth(22);
        t.setHeight(22);
        t.setWeight(1);
        ItemInstance it = new ItemInstance();
        it.setId(1L);
        it.setCharacterId(1);
        it.setTemplate(t);
        it.setItemListId(484);
        it.setItemCode(ItemRules.CODE_GOLD);
        it.setCount(1);
        return it;
    }

    // ==================== ① 怪：边界另一侧、就在旁边 → 必须看得见 ====================

    @Test
    public void monsterAcrossMapBoundaryIsVisibleWhenNearby() throws Exception {
        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, MY_X);

        // 怪在**另一张图**，但离我只有 100 单位
        Monster m = monster(NEAR_X, OTHER_MAP);
        MonsterAOI aoi = new MonsterAOI();
        inject(aoi, "sessionManager", sm);
        inject(aoi, "monsterSpawnService", spawnWith(new ArrayList<>(List.of(m)), OTHER_MAP));

        aoi.syncSessions();
        s.flushPending();
        List<MessageProto.ServerMessage> msgs = drain(ch);
        assertEquals("就在边界另一侧的怪必须出现在我眼前（用户 2026-09-16 报的正是这个）", 1, msgs.size());
        assertTrue(msgs.get(0).hasMonsterAppear());
        assertEquals(m.getId(), msgs.get(0).getMonsterAppear().getMonsterId());
    }

    /** 反向：距离仍然要起作用（坐标口径不等于"全图广播"） */
    @Test
    public void monsterAcrossMapBoundaryButFarIsNotVisible() throws Exception {
        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, MY_X);

        Monster m = monster(FAR_X, OTHER_MAP);
        MonsterAOI aoi = new MonsterAOI();
        inject(aoi, "sessionManager", sm);
        inject(aoi, "monsterSpawnService", spawnWith(new ArrayList<>(List.of(m)), OTHER_MAP));

        aoi.syncSessions();
        s.flushPending();
        assertTrue("再远也不该发（距离仍是门槛）", drain(ch).isEmpty());
    }

    // ==================== ② NPC：同一口径 ====================

    @Test
    public void npcAcrossMapBoundaryIsVisibleWhenNearby() throws Exception {
        Npc npc = new Npc();
        npc.setNameKey("cross_boundary_npc");
        npc.setModelFile("char/npc/x/x.inx");
        npc.setX(NEAR_X);
        npc.setY(0);
        npc.setZ(0);
        npc.setMapId(OTHER_MAP);

        NpcSpawnService npcSvc = new NpcSpawnService();
        @SuppressWarnings("unchecked")
        Map<Integer, List<Npc>> byMap = (Map<Integer, List<Npc>>) field(NpcSpawnService.class, npcSvc, "npcsByMap");
        byMap.put(OTHER_MAP, new ArrayList<>(List.of(npc)));

        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, MY_X);

        NpcAOI aoi = new NpcAOI();
        inject(aoi, "sessionManager", sm);
        inject(aoi, "npcSpawnService", npcSvc);

        aoi.syncSessions();
        s.flushPending();
        List<MessageProto.ServerMessage> msgs = drain(ch);
        assertEquals("边界另一侧的 NPC 同样应当可见", 1, msgs.size());
        assertTrue(msgs.get(0).hasNpcAppear());
    }

    // ==================== ③ 掉落物：同一口径 ====================

    @Test
    public void groundItemAcrossMapBoundaryIsVisibleWhenNearby() throws Exception {
        GroundItemManager items = new GroundItemManager();
        items.add(item(), OTHER_MAP, NEAR_X, 0, 0, 0L, 60_000);

        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, MY_X);

        GroundItemAOI aoi = new GroundItemAOI();
        inject(aoi, "sessionManager", sm);
        inject(aoi, "groundItems", items);

        aoi.syncSessions();
        s.flushPending();
        List<MessageProto.ServerMessage> msgs = drain(ch);
        assertEquals("边界另一侧的掉落物同样应当可见", 1, msgs.size());
        assertTrue(msgs.get(0).hasGroundItemAppear());
    }

    /** 反射取字段（只为把测试数据塞进图分表） */
    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
