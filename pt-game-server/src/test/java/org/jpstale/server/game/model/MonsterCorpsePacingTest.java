package org.jpstale.server.game.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 尸体停留时长 与 刷新节奏 **是两个独立时钟** —— 回归（用户 2026-09-16）。
 *
 * 背景：改动前两者是同一个时钟。尸体在 `Monster.respawnTime`(30s) 之后才被移除，
 * 而出生点名额是在"移除那一刻"才释放 —— 于是"尸体躺多久"顺带决定了"多久补怪"。
 * 把尸体停留改成 `decayTime`(6s) 之后，如果不给出生点补一个**独立**的击杀冷却，
 * 名额会提前 24 秒释放 ⇒ **补怪速度静默变成约 4 倍**（没有任何报错，只是怪变多了）。
 *
 * 本测试钉住：
 * <ul>
 *   <li>`Monster.decayTime` 的判据（6000ms）与"尸体 ≠ 活怪"；</li>
 *   <li>名额释放**不**加速补怪：冷却从**死亡时刻**起算，而不是从"尸体消失时刻"；</li>
 *   <li>非死亡的离场（D10 无玩家临近回收）只释放名额，**不**启动击杀冷却。</li>
 * </ul>
 */
public class MonsterCorpsePacingTest {

    @Test
    public void decayTimeIsSixSecondsFromDeath() {
        Monster m = new Monster();
        m.setMaxHp(100);
        m.setHp(100);
        assertEquals("默认尸体停留 6000ms（依据见 Monster.DEFAULT_DECAY_MS 的推导注释）",
            Monster.DEFAULT_DECAY_MS, m.getDecayTime());

        long deathAt = System.currentTimeMillis();
        m.onDeath();

        assertFalse("活怪不是尸体", m.isAlive());
        assertEquals("死亡即置 hp=0（否则 isAlive() 与 state==DEAD 会互相矛盾）", 0, m.getHp());
        assertFalse("死亡不足 decayTime → 尸体还在（不能移除）",
            m.isDecayed(deathAt + Monster.DEFAULT_DECAY_MS - 1));
        assertTrue("到点 → 可以移除",
            m.isDecayed(deathAt + Monster.DEFAULT_DECAY_MS));
    }

    /**
     * 最要紧的一条：尸体停留 6s 之后释放名额，**补怪时刻不变**（仍是死亡后 30s）。
     * 旧实现（名额释放即放开刷新）在这条上会红 —— 那正是"怪变多了"的静默 bug。
     */
    @Test
    public void releasingSlotAtCorpseDecayDoesNotSpeedUpRespawn() {
        long now = System.currentTimeMillis();
        SpawnPoint sp = new SpawnPoint(1, "test", 0, 0, 0, 10);
        sp.setActive(true);
        // 隔离：出生点另有一个"两次刷怪之间"的常规冷却（cooldownMs，默认 1s）。本测试只关心
        // **击杀冷却**这一个时钟，故把它关掉 —— 否则断言会被"刚刷过怪"挡住，看不出真正的因果。
        sp.setCooldownMs(0);
        assertTrue("基准：新出生点、名额未占、无击杀冷却 → 可刷", sp.canSpawn());

        sp.onMonsterSpawn();
        sp.onMonsterSpawn();
        sp.onMonsterSpawn();
        assertFalse("名额已满（3/3）→ 不可刷", sp.canSpawn());

        // 尸体在死亡 6s 后消失：主循环此时才释放名额（MonsterSpawnService 的 removeIf 分支）
        sp.onMonsterDeath(now - 6000, 30000);
        assertFalse("尸体消失了，但击杀冷却是从**死亡时刻**起算的 30s → 还要再等 24s，"
            + "绝不能因为尸体提前消失就提前补怪", sp.canSpawn());

        // 另两只死在更早
        sp.onMonsterDeath(now - 31000, 30000);
        assertTrue("死亡已超过刷新冷却 → 可刷", sp.canSpawn());
    }

    /** 非死亡的离场不该启动击杀冷却（那不是玩家打死的，刷新位没理由多等 30s） */
    @Test
    public void nonDeathRemovalDoesNotStartRespawnCooldown() {
        SpawnPoint sp = new SpawnPoint(2, "test", 0, 0, 0, 10);
        sp.setActive(true);
        // 隔离：出生点另有一个"两次刷怪之间"的常规冷却（cooldownMs，默认 1s）。本测试只关心
        // **击杀冷却**这一个时钟，故把它关掉 —— 否则断言会被"刚刷过怪"挡住，看不出真正的因果。
        sp.setCooldownMs(0);
        sp.onMonsterSpawn();
        sp.onMonsterSpawn();
        sp.onMonsterSpawn();
        assertFalse("名额已满", sp.canSpawn());

        sp.onMonsterRemoved();   // D10：无玩家临近回收
        assertTrue("只释放名额、不启动击杀冷却 → 立即可刷", sp.canSpawn());
    }
}
