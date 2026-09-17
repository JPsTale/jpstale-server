package org.jpstale.server.game.service;

import io.netty.channel.embedded.EmbeddedChannel;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.ServerMessage;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * `NpcAOI.clearVisible` 回归 —— 它修的是「刚进图/换图看不到 NPC」（用户 2026-09-15）。
 *
 * 可见集以 characterId 为 key **跨会话保留**；不清空的话，重进时 `reconcile` 里
 * `visible.add(eid)` 恒 false ⇒ 一条 Appear 都不发。清空还必须**逐条补 Disappear**：
 * 跨图传送时客户端不清场，旧图 NPC 仍在场景里，只清集合不通知就会变幽灵。
 */
public class NpcAoiClearVisibleTest {

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, Set<Long>> visibleMap(NpcAOI aoi) throws Exception {
        Field f = NpcAOI.class.getDeclaredField("visibleByPlayer");
        f.setAccessible(true);
        return (ConcurrentHashMap<Long, Set<Long>>) f.get(aoi);
    }

    /** 展开合批信封（多条会被包成 S2C_Batch） */
    private static List<ServerMessage> drain(EmbeddedChannel ch) {
        List<ServerMessage> out = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            ServerMessage m = (ServerMessage) o;
            if (m.hasBatch()) {
                out.addAll(m.getBatch().getMessagesList());
            } else {
                out.add(m);
            }
        }
        return out;
    }

    @Test
    public void clearVisibleNotifiesEachVisibleThenEmptiesSet() throws Exception {
        NpcAOI aoi = new NpcAOI();
        Set<Long> visible = ConcurrentHashMap.newKeySet();
        visible.add(1001L);
        visible.add(1002L);
        visibleMap(aoi).put(7L, visible);

        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession session = new PlayerSession(ch);
        session.setCharacterId(7L);

        aoi.clearVisible(session);

        // 清空 = 下一 tick reconcile 能重新 add → 重发 Appear（这正是"看不到 NPC"的修复）
        assertTrue("清空后可见集必须为空", visible.isEmpty());

        session.flushPending();
        List<ServerMessage> msgs = drain(ch);
        assertEquals("每个曾可见的 NPC 都要补一条 Disappear", 2, msgs.size());
        for (ServerMessage m : msgs) {
            assertTrue("必须是 NpcDisappear", m.hasNpcDisappear());
            long eid = m.getNpcDisappear().getEntityId();
            assertTrue("entity_id 必须来自被清的集合", eid == 1001L || eid == 1002L);
        }
    }

    @Test
    public void clearVisibleForUnknownCharacterIsNoOp() {
        NpcAOI aoi = new NpcAOI();
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSession session = new PlayerSession(ch);
        session.setCharacterId(999L);

        aoi.clearVisible(session);   // 不抛、不误发

        session.flushPending();
        assertTrue("没有可见集就不该发任何消息", drain(ch).isEmpty());
    }
}
