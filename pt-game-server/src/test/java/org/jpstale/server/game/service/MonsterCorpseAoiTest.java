package org.jpstale.server.game.service;

import io.netty.channel.embedded.EmbeddedChannel;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.SessionState;
import org.jpstale.server.proto.base.MessageProto;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 怪物**尸体**的 AOI 行为回归（用户 2026-09-16："怪物死亡后不会留下尸体"）。
 *
 * 原版形状（ex-machina 与 NewSourcePT-2023 两棵树一致）：死掉的怪不是"生成一具新尸体"，
 * 而是**同一个对象**动作态变 `CHRMOTION_STATE_DEAD(0x120)`、动画帧冻在末帧、照常按距离同步，
 * 直到 `FrameCounter > 400` 才 `Close()` + `DeleteMonTable`。
 * 也就是 **"死"（Death 事件）与"消失"（Disappear）是两条独立事件**。本次改动就照这个形状：
 * <ol>
 *   <li>`onMonsterDeath` 只发 Death，**不清出可见集**（尸体留在里面，之后照常按距离同步）；</li>
 *   <li>尸体在 `reconcile` 里与活怪同规则 ⇒ 中途进场的观察者收到 `Appear(dead=true)` 而不是空场景；</li>
 *   <li>`Monster.decayTime` 到点才 `onMonsterRemoved` → Disappear。</li>
 * </ol>
 * 下面四条断言分别钉住这三句；任何一条被改回旧行为（死亡即清出、Death + Disappear 成对发）
 * 都会在这里变红。
 */
public class MonsterCorpseAoiTest {

    private static final long PID = 7L;
    private static final int MAP = 1;
    /** 视野内（< VIEW_RANGE 1000）与视野外（> VIEW_RANGE_DISCONNECT 1600）各取一个安全距离 */
    private static final double NEAR_X = 100;
    private static final double FAR_X = 5000;

    // ==================== 工具 ====================

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, Set<Long>> visibleMap(MonsterAOI aoi) throws Exception {
        Field f = MonsterAOI.class.getDeclaredField("visibleByPlayer");
        f.setAccessible(true);
        return (ConcurrentHashMap<Long, Set<Long>>) f.get(aoi);
    }

    /** 让 `MonsterSpawnService.getMonstersByMap(MAP)` 返回给定列表（直接塞它的世界表） */
    @SuppressWarnings("unchecked")
    private static MonsterSpawnService spawnServiceWith(List<Monster> monsters) throws Exception {
        MonsterSpawnService svc = new MonsterSpawnService();
        Field f = MonsterSpawnService.class.getDeclaredField("monstersByMap");
        f.setAccessible(true);
        ((Map<Integer, List<Monster>>) f.get(svc)).put(MAP, monsters);
        return svc;
    }

    /** 展开合批信封（多条会被包成 S2C_Batch） */
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

    private static Monster monster(int hp, double x) {
        Monster m = new Monster();
        m.setName("testmob");
        m.setTemplateId(1);
        m.setModelFile("char/monster/test/test.inx");
        m.setMaxHp(hp);
        m.setHp(hp);
        m.setX(x);
        m.setY(0);
        m.setZ(0);
        m.setMapId(MAP);
        return m;
    }

    /**
     * 建一个 PLAYING 且在 (x,0) 的观察者会话，并绑定实体（syncSessions 从 session.getEntity() 取）。
     *
     * ⚠ 必须走 `bindCharacterId`：`SessionManager.sessionsByCharacterId` 只在它里面登记，
     * 而 `MonsterAOI.reconcile` / `onMonsterDeath` 都是用 `getSessionByCharacterId` 找会话 ——
     * 直接 `setCharacterId` 的话会话不在索引里，表现就是"一条 Appear/Death 都发不出去"。
     * 它会把状态置成 CHARACTER_SELECTED，故 PLAYING 要在之后设。
     */
    private static PlayerSession observer(SessionManager sm, EmbeddedChannel ch, long pid, double x) {
        PlayerSession s = sm.createSession(ch);
        sm.bindCharacterId(ch, pid, "obs" + pid);
        s.setState(SessionState.PLAYING);
        PlayerEntity e = new PlayerEntity(pid, pid, null, s);
        e.setMapId(MAP);
        e.setX(x);
        e.setY(0);
        e.setZ(0);
        s.setEntity(e);
        return s;
    }

    private static MonsterAOI aoiWith(SessionManager sm, MonsterSpawnService spawn) throws Exception {
        MonsterAOI aoi = new MonsterAOI();
        inject(aoi, "sessionManager", sm);
        inject(aoi, "monsterSpawnService", spawn);
        return aoi;
    }

    // ==================== ① 死亡只发 Death，尸体留在可见集 ====================

    @Test
    public void deathSendsDeathOnlyAndKeepsCorpseVisible() throws Exception {
        Monster m = monster(100, 0);
        SessionManager sm = new SessionManager();
        MonsterAOI aoi = aoiWith(sm, spawnServiceWith(new ArrayList<>(List.of(m))));
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, PID, NEAR_X);

        // 活着时先进入视野
        aoi.syncSessions();
        s.flushPending();   // send() 只是进合批队列，必须 flush 才写进 channel
        List<MessageProto.ServerMessage> appear = drain(ch);
        assertEquals("首次同步应发一条 Appear", 1, appear.size());
        assertTrue(appear.get(0).hasMonsterAppear());
        assertFalse("活怪不带尸体标记", appear.get(0).getMonsterAppear().getDead());

        // 打死
        m.onDeath();
        aoi.onMonsterDeath(m, PID, 42, 7);
        s.flushPending();
        List<MessageProto.ServerMessage> onDeath = drain(ch);
        assertEquals("击杀者只应收到 Death 一条（不再跟一条 Disappear）", 1, onDeath.size());
        assertTrue("必须是 MonsterDeath", onDeath.get(0).hasMonsterDeath());
        assertEquals("击杀者带 exp", 42, onDeath.get(0).getMonsterDeath().getExp());
        assertEquals("击杀者带 gold", 7, onDeath.get(0).getMonsterDeath().getGold());
        assertEquals(m.getId(), onDeath.get(0).getMonsterDeath().getMonsterId());

        // 尸体仍在可见集里 ⇒ reconcile 不会把它当"已清出"而重复通知/重新 Appear
        assertTrue("尸体必须留在可见集内（这正是本次改动的核心）",
            visibleMap(aoi).get(PID).contains(m.getId()));
        aoi.syncSessions();
        s.flushPending();
        assertTrue("尸体在视野内且已在可见集 → 后续同步不应再发任何消息（不重发 Appear、不发 Disappear）",
            drain(ch).isEmpty());
    }

    // ==================== ② 中途进场的观察者看见尸体（Appear dead=true） ====================

    @Test
    public void latecomerSeesCorpseViaAppearWithDeadFlag() throws Exception {
        Monster m = monster(100, 0);
        m.onDeath();   // 在任何人看见它之前就死了
        SessionManager sm = new SessionManager();
        MonsterAOI aoi = aoiWith(sm, spawnServiceWith(new ArrayList<>(List.of(m))));
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, PID, NEAR_X);

        aoi.syncSessions();
        s.flushPending();
        List<MessageProto.ServerMessage> msgs = drain(ch);
        assertEquals("迟到者应收到 Appear（而不是空场景）", 1, msgs.size());
        assertTrue(msgs.get(0).hasMonsterAppear());
        MessageProto.S2C_MonsterAppear a = msgs.get(0).getMonsterAppear();
        assertTrue("必须显式带 dead=true —— 客户端靠它把尸体和活怪分开（它收不到 Death）", a.getDead());
        assertEquals("尸体的 hp 为 0", 0, a.getHp());
    }

    // ==================== ③ decay 到点才 Disappear ====================

    @Test
    public void decayRemovalSendsDisappearAndClearsVisible() throws Exception {
        Monster m = monster(100, 0);
        SessionManager sm = new SessionManager();
        MonsterAOI aoi = aoiWith(sm, spawnServiceWith(new ArrayList<>(List.of(m))));
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, PID, NEAR_X);

        aoi.syncSessions();
        drain(ch);
        m.onDeath();
        aoi.onMonsterDeath(m, PID, 1, 0);
        s.flushPending();
        drain(ch);

        // 服务端主循环在 Monster.decayTime 到点后调它（见 MonsterSpawnService 的 removeIf 分支）
        aoi.onMonsterRemoved(m);
        s.flushPending();
        List<MessageProto.ServerMessage> msgs = drain(ch);
        assertEquals("尸体消失只发一条 Disappear", 1, msgs.size());
        assertTrue(msgs.get(0).hasMonsterDisappear());
        assertEquals(m.getId(), msgs.get(0).getMonsterDisappear().getMonsterId());
        assertFalse("消失后必须从可见集摘掉，否则下次进场不会再 Appear",
            visibleMap(aoi).get(PID).contains(m.getId()));
    }

    // ==================== ④ 看不见它的观察者不该收到 Death ====================

    @Test
    public void noDeathEventForObserverOutOfRange() throws Exception {
        Monster m = monster(100, 0);
        SessionManager sm = new SessionManager();
        MonsterAOI aoi = aoiWith(sm, spawnServiceWith(new ArrayList<>(List.of(m))));
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession s = observer(sm, ch, PID, FAR_X);

        aoi.syncSessions();   // 太远 → 连 Appear 都没有
        s.flushPending();
        assertTrue("视野外不该收到 Appear", drain(ch).isEmpty());

        m.onDeath();
        aoi.onMonsterDeath(m, PID, 42, 7);
        s.flushPending();
        assertTrue("当时看不见这只怪的观察者不该收到 Death（他进场时靠 Appear(dead) 认出尸体）",
            drain(ch).isEmpty());
    }
}
