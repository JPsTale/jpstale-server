package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_MonsterAppear;
import org.jpstale.server.proto.base.S2C_MonsterDeath;
import org.jpstale.server.proto.base.S2C_MonsterDisappear;
import org.jpstale.server.proto.base.S2C_MonsterMove;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪物 AOI：把服务端权威的怪物状态（出现/移动/死亡/消失）推送给视野内玩家。
 *
 * 可见性距离与玩家 AOI 共用同一对常量（现为 1000 进入 / 1600 离开，见 AOIManager）：
 * 进入 CONNECT → Appear；超出 DISCONNECT → Disappear。所有集合以玩家 characterId 为 key 持久化。
 *
 * **尸体是正常可见实体**：怪物死后不在这里清出可见集 —— 死掉的怪就是"动作态 = DEAD、
 * 动画冻在末帧"的同一个实体，服务端照常按距离同步它，直到 `Monster.decayTime` 到点后
 * `onMonsterRemoved` 才发 Disappear。所以"死"（Death 事件）与"消失"（Disappear）是**两条独立事件**：
 * Death 只发给死亡当刻在场的观察者，中途进场的人靠 `S2C_MonsterAppear.dead` 认出尸体。
 * 若死亡时就清出可见集，中途进场的玩家会看到"尸体凭空不存在"，与在场玩家画面不一致
 * （原版同样如此：`OnSever.cpp` 里死怪仍被 `MakeTransPlayData` 同步，直到 `FrameCounter > 400`
 * 才 `Close()` + `DeleteMonTable`）。
 *
 * 线程模型：syncSessions/broadcastMove 在主循环线程（MonsterSpawnService.tick）调用；
 * onMonsterDeath 可能在 Netty IO 线程（玩家击杀）调用 —— 内部用 CHM 集合，弱一致即可。
 */
@Slf4j
@Component
public class MonsterAOI {

    private static final float CONNECT = AOIManager.VIEW_RANGE;
    private static final float DISCONNECT = AOIManager.VIEW_RANGE_DISCONNECT;

    /** 角度差归一化到 (-π, π]：跨 ±π 的转身不该被当成"转了 6 弧度" */
    private static double wrapAngle(double d) {
        return Math.atan2(Math.sin(d), Math.cos(d));
    }

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private EntityRegistry entityRegistry;

    /** 观察者 playerId → 当前可见的怪物 id 集合（持久化，双阈值升降级状态） */
    private final ConcurrentHashMap<Long, Set<Long>> visibleByPlayer = new ConcurrentHashMap<>();

    /**
     * 玩家**进入世界**时清空他的怪物可见集
     *（用户 2026-09-14 实测：进游戏看不到怪、却一直在挨打）。
     *
     * 为什么必须清：可见集以 characterId 为 key **跨会话保留**，而重连（自动续传）时
     * 旧会话与新会话是同一个 charId —— 若旧会话那轮 `removeIf` 还没跑到，
     * 集合里就留着上一次的怪物 id，于是 `reconcile` 里 `visible.add(mid)` 恒为 false
     * ⇒ **一条 Appear 都不发**，而服务端 AI 照常把他当目标打（服务端日志全是
     * `[MonsterAI] ... ATK aglob`，客户端一条"怪物出现"都没有）。
     *
     * 对照：玩家 AOI 在 `AOIManager.onPlayerEnter` 里本来就 `visible.clear()` —— 怪物这边漏了。
     * 清空后下一 tick `reconcile` 会按当前位置把该看见的**全部重发** —— 但**必须先补发 Disappear**
     * （见方法内注释）：客户端换图时不清场，只重发会出现"旧图的怪 + 新图的怪"同时挂着。
     */
    public void clearVisible(long characterId) {
        Set<Long> visible = visibleByPlayer.get(characterId);
        if (visible == null) {
            return;
        }
        PlayerSession session = sessionManager.getSessionByCharacterId(characterId);
        synchronized (visible) {
            // ⚠ 清空前**逐条补发 Disappear** —— 与 `NpcAOI.clearVisible` 同一个原因（那份先修对了）：
            //   换图 / 传送时客户端**不清场**（`applyTeleport` 不调 `clearWorldActors`），旧图的怪仍在
            //   场景里；只清服务端集合而不补通知，它们就成了**删不掉的幽灵**
            //   （用户 2026-09-16 实测："走到另一个图，怪物一直都在"）。
            //   （首次进场时客户端本就 `clearWorldActors()` 清过，补发对未知 id 是无害的 no-op。）
            if (session != null) {
                for (long mid : visible) {
                    session.send(buildDisappear(mid));
                }
            }
            visible.clear();
        }
    }

    /** 每 tick 由 MonsterSpawnService.tick() 驱动：同步所有 playing 会话的怪物可见集 */
    public void syncSessions() {
        Set<Long> active = ConcurrentHashMap.newKeySet();
        for (PlayerSession session : sessionManager.getAllSessions()) {
            PlayerEntity e = session != null ? session.getEntity() : null;
            if (e == null || !session.isPlaying() || e.getMapId() < 0) {
                continue;
            }
            Long pid = session.getCharacterId();
            if (pid == null) {
                continue;
            }
            active.add(pid);
            // 按坐标同步，不按图（地图边界是人为切分的）：判定只看距离，与来自哪张图无关。
            reconcile(e, entityRegistry.allMonsters());
        }
        // 清理已离线/未 playing 会话的残留可见集
        visibleByPlayer.keySet().removeIf(pid -> !active.contains(pid));
    }

    private void reconcile(PlayerEntity player, java.util.Collection<Monster> monsters) {
        Long pid = player.getSession() != null ? player.getSession().getCharacterId() : null;
        if (pid == null) {
            return;
        }
        double sx = player.getX();
        double sz = player.getZ();
        Set<Long> visible = visibleByPlayer.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());
        double connectSq = (double) CONNECT * CONNECT;
        double disconnectSq = (double) DISCONNECT * DISCONNECT;
        PlayerSession session = player.getSession();

        for (Monster m : monsters) {
            long mid = m.getId();
            double dx = sx - m.getX();
            double dz = sz - m.getZ();
            double distSq = dx * dx + dz * dz;
            // 不变式自检：`state == DEAD` ⇒ `deathInfo != null`（唯一致死入口见 Monster.onDeath）。
            // 违反它说明有新的致死路径绕过了结算 —— 那种怪没有 Death 事件，客户端只会看到它站着不动
            // 然后凭空消失（2026-09-14 修过一次的同一个症状）。**不静默**：报一次，不刷屏。
            if (!m.isAlive() && m.getDeathInfo() == null && !m.isMissingDeathPayloadLogged()) {
                m.setMissingDeathPayloadLogged(true);
                log.error("[AOI] 怪物 {}#{} 处于 DEAD 却没有死亡负载（deathInfo=null）"
                    + " → 客户端收不到死亡事件，这条尸体会表现为'站着不动然后消失'",
                    m.getName(), m.getId());
            }
            // 与 onMonsterDeath 互斥（同锁）：死亡当刻的 Death 广播与这里的 Appear/Disappear 不能交错，
            // 否则会出现"刚发完 Death 又发 Appear"或反过来的乱序（客户端会先播死亡再站回去）。
            synchronized (visible) {
                // 生与死在这里**同一套距离规则** —— 尸体是正常可见实体，不单独排除。
                // 死怪被算进可见集是刻意的：中途进场/重连的观察者靠 Appear(dead=true) 看见尸体，
                // 与在场玩家画面一致（见类头注释）。
                if (distSq > disconnectSq) {
                    if (visible.remove(mid)) {
                        sendDisappear(session, mid);
                    }
                } else if (distSq <= connectSq) {
                    if (visible.add(mid)) {
                        sendAppear(session, m);
                    }
                }
            }
        }

    }

    /** 怪物位移后广播（由 MonsterSpawnService 主循环每步调用）：位置/朝向/动画任一变化才下发 */
    public void broadcastMove(Monster m) {
        if (!m.isAlive()) {
            return;
        }
        int anim = animOf(m);
        double mx = m.getX();
        double mz = m.getZ();
        boolean posChanged = Double.isNaN(m.getLastBroadcastX())
            || Math.abs(mx - m.getLastBroadcastX()) > 0.01
            || Math.abs(mz - m.getLastBroadcastZ()) > 0.01;
        // 朝向也要参与"变化"判定：站桩攻击的怪只转身不移动（AiEngine.faceTarget），
        // 只看位置/动画会把这次转身节流掉 → 客户端永不转向，怪背对玩家挥击（用户实测发现）
        boolean angleChanged = Double.isNaN(m.getLastBroadcastAngle())
            || Math.abs(wrapAngle(m.getAngle() - m.getLastBroadcastAngle())) > 0.02;
        if (!posChanged && !angleChanged && anim == m.getLastBroadcastAnim()) {
            return;
        }
        m.setLastBroadcastX(mx);
        m.setLastBroadcastZ(mz);
        m.setLastBroadcastAngle(m.getAngle());
        m.setLastBroadcastAnim(anim);

        ServerMessage moveMsg = ServerMessage.newBuilder()
            .setMonsterMove(S2C_MonsterMove.newBuilder()
                .setMonsterId(m.getId())
                .setPosition(CommonProto.Position.newBuilder()
                    .setX((float) m.getX())
                    .setY((float) m.getY())
                    .setZ((float) m.getZ())
                    .build())
                .setAngle((float) m.getAngle())
                .setAnimState(anim)
                // 攻击时带上**服务端选定的条目索引** —— 客户端直接播这一条（与玩家 anim_index 同一语义）。
                // 非攻击状态没有服务端选择的变体，留 0（客户端按自己的状态机匹配）。
                .setAnimIndex(anim == 0x0100 ? m.getAttackAnimIndex() : 0)
                .build())
            .build();
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            if (e.getValue().contains(m.getId())) {
                PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                if (s != null) {
                    s.send(moveMsg);
                }
            }
        }
    }

    /**
     * 怪物死亡：写入死亡负载 → 通知**当前可见的**观察者发 Death 事件。
     *
     * **尸体不清出可见集**（这是本次改动的核心）：死怪留在可见集里，之后由主循环按距离继续
     * 同步（超出 DISCONNECT 才 Disappear），直到 `Monster.decayTime` 到点后 `onMonsterRemoved`
     * 才真正移除。"死"与"消失"从此是两条独立事件。
     *
     * 代价与好处都很明确：中途进场/重连的观察者拿不到 Death（它不在可见集里、没赶上），
     * 但会在 reconcile 里收到 `Appear(dead=true)` —— 看见尸体，而不是"这里什么都没有"。
     * 不这样做的话，同一具尸体在在场玩家屏幕上有、在后来者屏幕上没有。
     */
    public void onMonsterDeath(Monster m, long killerId, long exp, int gold) {
        // 负载**先落**：Appear(dead) 与 Death 都从这份负载取"谁杀的/给多少经验"，
        // 且 `state==DEAD ⇒ deathInfo!=null` 是 AOI 自检依赖的不变式（见 reconcile）
        m.setDeathInfo(new Monster.DeathInfo(killerId, exp, gold));
        long mid = m.getId();
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            Set<Long> set = e.getValue();
            // 与 reconcile 互斥（同锁）：保证这条 Death 不会被并发的 Appear/Disappear 反超
            synchronized (set) {
                if (!set.contains(mid)) {
                    continue;   // 该玩家当时看不见这只怪 → 不通知（他进场时靠 Appear(dead) 认出尸体）
                }
                PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                if (s == null) {
                    continue;
                }
                sendDeath(s, e.getKey(), m);
            }
        }
    }

    /**
     * 单个观察者的死亡事件：**只发 Death，不发 Disappear**（尸体还在，由 decay 到点后发 Disappear）。
     *
     * 经验/金币只给击杀者（原版 `rsOpen_MonsterItemExp` 只结算给击杀者）；其余观察者收的是
     * 同一个 Death 事件，只是不带 exp/gold —— 他们据此播死亡动画。
     */
    private void sendDeath(PlayerSession session, long pid, Monster m) {
        Monster.DeathInfo di = m.getDeathInfo();
        if (di == null) {
            // 不变式被破坏（有致死路径绕过结算）→ 不能静默：这条 Death 缺 killer/exp/gold，
            // 击杀者会拿不到经验飘字，且现象与"怪凭空消失"难以区分
            log.warn("[AOI] 怪物 {}#{} 发死亡事件时没有死亡负载（未走 CombatService.handleMonsterDeath）"
                + " → 本次 Death 缺 killer/exp/gold", m.getName(), m.getId());
        }
        S2C_MonsterDeath.Builder death = S2C_MonsterDeath.newBuilder()
            .setMonsterId(m.getId());
        if (di != null) {
            death.setKillerId(di.killerId());
            if (pid == di.killerId()) {
                death.setExp((int) di.exp());
                death.setGold(di.gold());
            }
        }
        session.send(ServerMessage.newBuilder().setMonsterDeath(death.build()).build());
    }

    /** 怪物被移除（decay 到点 / 无交互清理）：向仍可见的观察者广播 Disappear（这是"尸体消失"那一半） */
    public void onMonsterRemoved(Monster m) {
        long mid = m.getId();
        for (Map.Entry<Long, Set<Long>> e : visibleByPlayer.entrySet()) {
            Set<Long> set = e.getValue();
            synchronized (set) {
                if (set.remove(mid)) {
                    PlayerSession s = sessionManager.getSessionByCharacterId(e.getKey());
                    if (s != null) {
                        s.send(buildDisappear(mid));
                    }
                }
            }
        }
    }

    private void sendAppear(PlayerSession session, Monster m) {
        S2C_MonsterAppear.Builder appear = S2C_MonsterAppear.newBuilder()
            .setMonsterId(m.getId())
            .setTemplateId(m.getTemplateId())
            .setName(m.getName() != null ? m.getName() : "")
            .setLevel(m.getLevel())
            .setPosition(CommonProto.Position.newBuilder()
                .setX((float) m.getX())
                .setY((float) m.getY())
                .setZ((float) m.getZ())
                .build())
            .setHp(Math.max(0, m.getHp()))
            .setMaxHp(m.getMaxHp())
            .setAngle((float) m.getAngle());
        if (m.getModelFile() != null) {
            appear.setModelFile(m.getModelFile());
        }
        // 尸体：**必须显式下发**。中途进场/重连的观察者拿不到 Death 事件，
        // 只能靠这个标记把"尸体"和"活怪"分开（否则会看到一具站着的尸体）。
        // 不要改由客户端从 hp == 0 推 —— 那是隐式信号（见 proto 该字段注释）。
        appear.setDead(!m.isAlive());
        appear.setMonsterEffectId(m.getMonsterEffectId());
        // 动画播放速率：服务端持有 attackspeed 档位（客户端没有），算好下发 —— 客户端直接当 animRate 用。
        // 与 `Monster.getAttackIntervalMs()`（服务端等动画播完的时长）同源，两边时间才对得上。
        appear.setAnimRate(m.getAnimRate());
        session.send(ServerMessage.newBuilder().setMonsterAppear(appear.build()).build());
    }

    private void sendDisappear(PlayerSession session, long monsterId) {
        session.send(buildDisappear(monsterId));
    }

    private ServerMessage buildDisappear(long monsterId) {
        return ServerMessage.newBuilder()
            .setMonsterDisappear(S2C_MonsterDisappear.newBuilder()
                .setMonsterId(monsterId)
                .build())
            .build();
    }

    /** MonsterState → 客户端动画 token（对齐 S2C_MonsterMove.anim_state 注释）
     *  追逐:能跑(RUN 0x60)才发跑,否则走(WALK 0x50)——与 MovementService 速度档同源 */
    public static int animOf(Monster m) {
        MonsterState state = m.getState();
        return switch (state) {
            case CHASE -> m.isCanRun() ? 0x0060 : 0x0050;
            case PATROL, RETURN -> 0x0050;
            case ATTACK -> 0x0100;
            default -> 0x0040;
        };
    }
}
