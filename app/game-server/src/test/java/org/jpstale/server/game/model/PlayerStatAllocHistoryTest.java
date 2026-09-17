package org.jpstale.server.game.model;

import org.jpstale.server.game.network.PlayerSession;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PlayerStatAllocHistoryTest {

    private Player newPlayer() {
        return new Player(new PlayerSession(null), 0);
    }

    @Test
    public void emptyHistoryPollsNull() {
        Player p = newPlayer();
        assertNull(p.pollLastStatAlloc());
    }

    @Test
    public void pushPopsInLifoOrder() {
        Player p = newPlayer();
        p.pushStatAlloc("strength");
        p.pushStatAlloc("agility");
        assertEquals("agility", p.pollLastStatAlloc());
        assertEquals("strength", p.pollLastStatAlloc());
        assertNull(p.pollLastStatAlloc());
    }

    @Test
    public void historyRemovesOldestBeyondFive() {
        Player p = newPlayer();
        for (int i = 0; i < 6; i++) {
            p.pushStatAlloc("s" + i);
        }
        assertEquals("s5", p.pollLastStatAlloc());
        assertEquals("s4", p.pollLastStatAlloc());
        assertEquals("s3", p.pollLastStatAlloc());
        assertEquals("s2", p.pollLastStatAlloc());
        assertEquals("s1", p.pollLastStatAlloc());
        assertNull(p.pollLastStatAlloc());
    }

    @Test
    public void clearEmptiesHistory() {
        Player p = newPlayer();
        p.pushStatAlloc("strength");
        p.clearStatAllocHistory();
        assertNull(p.pollLastStatAlloc());
    }
}