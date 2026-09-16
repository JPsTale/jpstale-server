package org.jpstale.server.game.service;

import io.netty.channel.embedded.EmbeddedChannel;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.item.GroundItemManager;
import org.jpstale.server.game.item.ItemInstance;
import org.jpstale.server.game.item.ItemRules;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.SessionState;
import org.jpstale.server.proto.base.MessageProto;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 地面物品的两条回归（用户 2026-09-16 联机实测）：
 *
 * ① **重进看不到掉落物** —— `GroundItemAOI` 漏了 `clearVisible`（怪/NPC 早就有）。
 *    可见集以 charId 为键**跨会话保留**，重进时 `visible.add(id)` 恒 false ⇒ 一条 Appear 都不发。
 * ② **看不到别人的掉落物** —— 可见性曾被写成"`ownerId != pid` 就永久跳过"，
 *    而原版是**私有窗口 5 秒**：窗口内只有归属者看得见，窗口一过就是公共掉落
 *    （依据见 `GroundItem.privateUntil` 与 `GroundItemManager.PRIVATE_WINDOW_MS`）。
 *
 * 断言 ①②③ 分别钉住"清空后能重发"、"窗口内非归属者看不见"、"窗口的边界"。
 */
public class GroundItemAoiVisibilityTest {

    private static final long PID = 7L;
    private static final long OTHER = 9L;
    private static final int MAP = 1;
    private static final double NEAR_X = 100;
    private static final double FAR_X = 5000;

    // ==================== 工具 ====================

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, Set<Long>> visibleMap(GroundItemAOI aoi) throws Exception {
        Field f = GroundItemAOI.class.getDeclaredField("visibleByPlayer");
        f.setAccessible(true);
        return (ConcurrentHashMap<Long, Set<Long>>) f.get(aoi);
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

    /** 一个最小可用的地面物（金币），只为驱动 AOI 判定 */
    private static ItemInstance item(int code) {
        ItemList t = new ItemList();
        t.setId(484);
        t.setName("Gold");
        t.setIdCode(code);
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
        it.setItemCode(code);
        it.setCount(1);
        return it;
    }

    /**
     * 建一个 PLAYING 且在 (x,0) 的观察者。
     * ⚠ 必须走 `bindCharacterId`：`sessionsByCharacterId` 只在它里面登记，
     * 而 `syncSessions`/`reconcile` 是用 charId 找会话的（直接 setCharacterId 会"一条都不发"）。
     */
    private static void observer(SessionManager sm, EmbeddedChannel ch, long pid, double x) {
        PlayerSession s = sm.createSession(ch);
        sm.bindCharacterId(ch, pid, "obs" + pid);
        s.setState(SessionState.PLAYING);
        PlayerEntity e = new PlayerEntity(pid, pid, null, s);
        e.setMapId(MAP);
        e.setX(x);
        e.setY(0);
        e.setZ(0);
        s.setEntity(e);
    }

    private static GroundItemAOI aoiWith(SessionManager sm, GroundItemManager items) throws Exception {
        GroundItemAOI aoi = new GroundItemAOI();
        inject(aoi, "sessionManager", sm);
        inject(aoi, "groundItems", items);
        return aoi;
    }

    // ==================== ① 清空后能重发（重进看不到掉落物的根因） ====================

    @Test
    public void clearVisibleNotifiesEachVisibleThenEmptiesSet() throws Exception {
        GroundItemAOI aoi = new GroundItemAOI();
        Set<Long> visible = ConcurrentHashMap.newKeySet();
        visible.add(501L);
        visible.add(502L);
        visibleMap(aoi).put(PID, visible);

        EmbeddedChannel ch = new EmbeddedChannel();
        SessionManager sm = new SessionManager();
        PlayerSession s = sm.createSession(ch);
        sm.bindCharacterId(ch, PID, "obs");
        s.setCharacterId(PID);

        aoi.clearVisible(s);

        assertTrue("清空后才能重新 add → 重发 Appear（这正是'重进看不到掉落物'的修复）", visible.isEmpty());
        s.flushPending();
        List<MessageProto.ServerMessage> msgs = drain(ch);
        assertEquals("每个曾可见的地面物都要补一条 Disappear（换图时客户端不清场）", 2, msgs.size());
        for (MessageProto.ServerMessage m : msgs) {
            assertTrue("必须是 GroundItemDisappear", m.hasGroundItemDisappear());
        }
    }

    // ==================== ② 私有窗口内：非归属者看不见 ====================

    @Test
    public void privateItemInvisibleToOthersInsideWindow() throws Exception {
        GroundItemManager items = new GroundItemManager();
        // ownerId = OTHER（别人打怪掉的/丢的），我们就站在旁边
        items.add(item(ItemRules.CODE_GOLD), MAP, 0, 0, 0, OTHER, 60_000);

        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        GroundItemAOI aoi = aoiWith(sm, items);
        observer(sm, ch, PID, NEAR_X);

        aoi.syncSessions();
        PlayerSession s = sm.getSessionByCharacterId(PID);
        s.flushPending();
        assertTrue("私有窗口内，非归属者不该收到 Appear", drain(ch).isEmpty());

        // 走远也一样：不该有任何 Disappear（它从没进过可见集）
        assertFalse("私有窗口内的物品不该出现在别人的可见集里",
            visibleMap(aoi).getOrDefault(PID, Set.of()).contains(1L));
    }

    // ==================== ③ 窗口边界：5 秒后就是公共掉落 ====================

    @Test
    public void privateWindowExpiresAfterFiveSeconds() {
        GroundItemManager items = new GroundItemManager();
        GroundItemManager.GroundItem gi = items.add(item(ItemRules.CODE_GOLD), MAP, 0, 0, 0, OTHER, 60_000);
        long now = System.currentTimeMillis();

        assertTrue("刚掉落：私有（只有归属者看得见）", gi.isPrivateAt(now));
        assertTrue("不到 5 秒仍是私有", gi.isPrivateAt(gi.privateUntil - 1));
        assertFalse("到点即公共（原版：dwCreateTime += 5000，之后随批量补发对所有人可见）",
            gi.isPrivateAt(gi.privateUntil));
        assertFalse("私有窗口就是 5 秒（GroundItemManager.PRIVATE_WINDOW_MS 是唯一定义）",
            gi.privateUntil - now > GroundItemManager.PRIVATE_WINDOW_MS);
    }

    @Test
    public void playerDroppedItemIsImmediatelyPublic() {
        GroundItemManager items = new GroundItemManager();
        // 玩家主动丢弃走的是 ownerId = 0（原版那条分支直接 SendStgItemToNearUsers、无 +5000、无归属）
        GroundItemManager.GroundItem gi = items.add(item(ItemRules.CODE_GOLD), MAP, 0, 0, 0, 0L, 60_000);
        long now = System.currentTimeMillis();
        assertFalse("玩家丢到地上的东西**立即**对所有人可见（用户 2026-09-16 纠正：原版没有 5 秒限制）",
            gi.isPrivateAt(now));
        assertFalse("公共掉落对任何人都不私有", gi.isPrivateAt(now + 1));
    }

    /** 怪物掉落的公共部分（`dropispublic=1`）同样立即公开 */
    @Test
    public void publicMonsterDropIsNeverPrivate() {
        GroundItemManager items = new GroundItemManager();
        GroundItemManager.GroundItem gi = items.add(item(ItemRules.CODE_GOLD), MAP, 0, 0, 0, 0L, 60_000);
        assertFalse(gi.isPrivateAt(System.currentTimeMillis()));
    }

    // ==================== ④ 视野外不打扰 ====================

    @Test
    public void outOfRangeItemDoesNotAppear() throws Exception {
        GroundItemManager items = new GroundItemManager();
        items.add(item(ItemRules.CODE_GOLD), MAP, 0, 0, 0, 0L, 60_000);

        SessionManager sm = new SessionManager();
        EmbeddedChannel ch = new EmbeddedChannel();
        GroundItemAOI aoi = aoiWith(sm, items);
        observer(sm, ch, PID, FAR_X);

        aoi.syncSessions();
        sm.getSessionByCharacterId(PID).flushPending();
        assertTrue("超出 CONNECT 距离不该收到 Appear", drain(ch).isEmpty());
    }
}
