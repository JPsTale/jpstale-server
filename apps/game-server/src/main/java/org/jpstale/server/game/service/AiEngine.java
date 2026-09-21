package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterAnimData;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.S2C_Damage;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪物 AI(状态机决策驱动)。
 *
 * 按 docs/monster-ai-entity-design.md §2.1:由 MonsterSpawnService 仅在"邻近玩家(D3/D10)"时
 * 每 tick 调用 update(monster)。决策职责:
 *   - 已有目标:校验仍可锁(在视野/同图/存活)→ 否则丢;
 *   - 无目标:仅 Evil(nature=1)按 Real_Sight 扫描最近玩家;
 *   - 目标在攻击距离内 → ATTACK(按 attackSpeed 冷却结算伤害);
 *     否则 → CHASE(移动由 MovementService 执行);
 *   - 无目标:超出出生锚 leash → RETURN 归位;否则 IDLE 站立(无恒定巡逻, D4)。
 *
 * 只做决策+攻击结算,不直接位移(位移在 MovementService.updateMonster)。
 * 关键转移/攻击打关键日志,便于运行时调参。
 */
@Slf4j
@Component
public class AiEngine {

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private DamageCalculator damageCalculator;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private CombatService combatService;

    @Autowired
    private BattleLogService battleLogService;

    @Autowired
    private GameMessageSender messageSender;

    private final Map<Long, AiContext> monsterContexts = new ConcurrentHashMap<>();

    public void init() {
        log.info("[MonsterAI] init done");
    }

    /** 每 tick(仅活跃怪,由 MonsterSpawnService 门控)执行一次 AI 决策 */
    public void update(Monster monster) {
        if (!monster.isAlive()) {
            return;
        }
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        MonsterState prevState = monster.getState();

        PlayerEntity target = validateTarget(monster, context);

        // 纯决策(无副作用,可表驱动单测):目标可锁→攻击/追击;无目标→出生锚 leash 内待机/超界归位
        MonsterState next = decide(
            target != null,
            target != null && inAttackRange(monster, target),
            homeDistOf(monster), leashOf(monster));

        if (target != null) {
            // 刚出过刀 → **站完这一刀**：动画时长内不切状态（哪怕目标已移出攻击范围）。
            //
            // 原版服务端跑的是**同一份 `smCHAR::Main()`**，怪进入 ATTACK 后要等 `MotionInfo->EndFrame`
            // 播完才切别的状态；我们服务端不持有动画数据，只能按
            // `帧数(monster-attack-frames.json) ÷ 播放步进(DB attackspeed)` 算同样的时长。
            // 少了这一步：怪一刀刚出手、目标一挪出范围就立刻切 CHASE ⇒ **客户端的攻击动画被打断**，
            // 而玩家攻击时却被定身等动画播完 —— 两边不公平（用户 2026-09-16 实测）。
            long lockMs = monster.getAttackIntervalMs();
            if (monster.getState() == MonsterState.ATTACK && lockMs > 0
                    && System.currentTimeMillis() - monster.getLastAttackTime() < lockMs) {
                faceTarget(monster, target);   // 站桩也要面向目标
                return;
            }
            if (next == MonsterState.ATTACK) {
                if (monster.getState() != MonsterState.ATTACK) {
                    logState(monster, prevState, MonsterState.ATTACK, "lock target=" + targetName(target));
                    monster.setState(MonsterState.ATTACK);
                }
                faceTarget(monster, target);
                tryAttack(monster, target);
            } else {
                if (monster.getState() != MonsterState.CHASE) {
                    logState(monster, prevState, MonsterState.CHASE, "chase target=" + targetName(target));
                    monster.setState(MonsterState.CHASE);
                }
                context.setTargetX(target.getX());
                context.setTargetY(target.getY());
                context.setTargetZ(target.getZ());
            }
            return;
        }

        if (next == MonsterState.RETURN) {
            if (monster.getState() != MonsterState.RETURN) {
                logState(monster, prevState, MonsterState.RETURN,
                    "home=" + (int) monster.getSpawnX() + "," + (int) monster.getSpawnZ()
                        + " dist=" + (int) homeDistOf(monster) + " leash=" + (int) leashOf(monster));
                monster.setState(MonsterState.RETURN);
            }
        } else {
            if (monster.getState() != MonsterState.IDLE) {
                logState(monster, prevState, MonsterState.IDLE, "stand");
                monster.setState(MonsterState.IDLE);
            }
        }
    }

    /**
     * 状态机决策函数(纯函数,无依赖/副作用)——表驱动单测的唯一断言面。
     *
     * 语义对齐 docs/monster-ai-entity-design.md §2.1:
     *   - 有可锁目标:在攻击距内 → ATTACK,否则 → CHASE;
     *   - 无目标:超出出生锚 leash → RETURN,否则 → IDLE。
     *
     * @param hasValidTarget 当前是否持有可锁目标(已经 validateTarget 校验)
     * @param inAttackRange  目标是否在近战攻击距离内(含高度差)
     * @param homeDist       距出生锚的 XZ 距离
     * @param leash          归位半径(出生锚容忍度)
     */
    static MonsterState decide(boolean hasValidTarget, boolean inAttackRange, double homeDist, double leash) {
        if (hasValidTarget) {
            return inAttackRange ? MonsterState.ATTACK : MonsterState.CHASE;
        }
        return homeDist > leash ? MonsterState.RETURN : MonsterState.IDLE;
    }

    /** 距出生锚 XZ 距离 */
    private static double homeDistOf(Monster monster) {
        double dx = monster.getSpawnX() - monster.getX();
        double dz = monster.getSpawnZ() - monster.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double leashOf(Monster monster) {
        if (monster.getMoveRange() > 0) {
            return monster.getMoveRange();
        }
        return 0.0;
    }

    // ======== 目标管理 ========

    /** 校验当前目标是否仍可锁;不可锁则清空并尝试按视野补一个(Evil) */
    private PlayerEntity validateTarget(Monster monster, AiContext context) {
        PlayerEntity target = context.getTargetPlayer();

        // 若已锁一个目标,先校验它是否仍有效
        if (target != null) {
            double lose = loseRangeOf(monster);
            if (!target.isTargetable()
                || target.getMapId() != monster.getMapId()
                || distXZ(monster, target) > lose) {
                log.info("[MonsterAI] {}#{} lost target {} (out of range/area)",
                    monster.getName(), monster.getId(), targetName(target));
                context.setTargetPlayer(null);
                monster.setTargetPlayerId(null);
                target = null;
            } else {
                context.setTargetX(target.getX());
                context.setTargetY(target.getY());
                context.setTargetZ(target.getZ());
                return target;
            }
        }

        // 无目标:仅 Evil(主动)扫描视野内玩家
        PlayerEntity found = scanTarget(monster);
        if (found != null) {
            context.setTargetPlayer(found);
            context.setTargetX(found.getX());
            context.setTargetY(found.getY());
            context.setTargetZ(found.getZ());
            monster.setTargetPlayerId(found.getCharId());
            log.info("[MonsterAI] {}#{} acquire target {} at ({},{})",
                monster.getName(), monster.getId(), targetName(found),
                (int) found.getX(), (int) found.getZ());
        } else {
            monster.setTargetPlayerId(null);
        }
        return found;
    }

    /** 视野内找最近玩家实体(仅 Evil;高度差 <140) */
    private PlayerEntity scanTarget(Monster monster) {
        if (monster.getNature() != 1 || monster.getViewsight() <= 0) {
            return null;
        }
        double sight = Math.min(monster.getViewsight(), AOIManager.VIEW_RANGE);
        Set<PlayerEntity> nearby = aoiManager.getNearbyPlayers(monster.getX(), monster.getZ(), (float) sight);
        PlayerEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (PlayerEntity entity : nearby) {
            if (entity == null || !entity.isTargetable() || entity.getMapId() != monster.getMapId()) {
                continue;
            }
            double dy = monster.getY() - entity.getY();
            if (Math.abs(dy) > AIConstants.SCAN_HEIGHT_DIFF) {
                continue;
            }
            double d = distXZ(monster, entity);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = entity;
            }
        }
        return nearest;
    }

    /** 受击反击/仇恨指定:把目标设为指定玩家实体(供 CombatService 受击调用) */
    public void setTargetPlayer(Monster monster, PlayerEntity target, double targetX, double targetZ) {
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        if (target == null || !target.isTargetable()) {
            return;
        }
        context.setTargetPlayer(target);
        context.setTargetX(targetX);
        context.setTargetZ(targetZ);
        monster.setTargetPlayerId(target.getCharId());
        log.info("[MonsterAI] {}#{} retaliate target {}", monster.getName(), monster.getId(),
            targetName(target));
    }

    /** 清除怪物目标 */
    public void clearTarget(Monster monster) {
        AiContext context = monsterContexts.get(monster.getId());
        if (context != null) {
            context.setTargetPlayer(null);
        }
        monster.setTargetPlayerId(null);
    }

    /** 移除怪物上下文 */
    public void removeContext(long monsterId) {
        monsterContexts.remove(monsterId);
    }

    /** 获取怪物 AI 上下文(MovementService 读取 target/patrol) */
    public AiContext getContext(long monsterId) {
        return monsterContexts.get(monsterId);
    }

    // ======== 攻击 ========

    /**
     * 站桩攻击时面朝目标。
     *
     * 原先**只有** `MovementService.moveToward`（追击移动）会写 `monster.angle`，所以一旦进入攻击距离
     * 停下不动，朝向就停在上一次移动的方向 —— 从侧面/背面靠近或被人从背后打时，
     * 怪会**背对玩家挥击**（用户 2026-09-12 实测发现）。
     * 转向写成 monster.angle 后由 MonsterAOI.broadcastMove 下发（那里也已把朝向纳入"变化"判定）。
     */
    private void faceTarget(Monster monster, PlayerEntity target) {
        double dx = target.getX() - monster.getX();
        double dz = target.getZ() - monster.getZ();
        if (dx * dx + dz * dz < 0.0001) {
            return;
        }
        double angle = Math.atan2(dx, dz);   // 与 moveToward 同一约定：0 = +Z
        double diff = angle - monster.getAngle();
        diff = Math.atan2(Math.sin(diff), Math.cos(diff));   // 归一化到 (-π, π]，避免跨 ±π 误判
        if (Math.abs(diff) < 0.02) {
            return;   // 已朝向目标，不必每 tick 都写
        }
        monster.setAngle(angle);
    }

    private boolean inAttackRange(Monster monster, PlayerEntity target) {
        double range = monster.getAttackRange() > 0 ? monster.getAttackRange() : 2.0;
        if (distXZ(monster, target) > range) {
            return false;
        }
        double dy = monster.getY() - target.getY();
        return Math.abs(dy) < AIConstants.ATTACK_HEIGHT_DIFF;
    }

    /** 按攻击冷却结算一次伤害(对齐原版:站桩出刀,帧外由 tick 决定出手节奏) */
    private void tryAttack(Monster monster, PlayerEntity target) {
        long now = System.currentTimeMillis();
        // 两刀间隔 = 攻击动画时长 —— 唯一判据在 Monster.getAttackIntervalMs()
        // （原版服务端跑同一份 smCHAR::Main()，动画没播完不能出下一刀，等价于这个时长）。
        // 恒 > 0（没有攻击动画的模型由 `NO_ANIM_ATTACK_FRAMES` 推一个间隔，不会变成"永不出刀"）
        long interval = monster.getAttackIntervalMs();
        if (now - monster.getLastAttackTime() < interval) {
            return;
        }
        monster.setLastAttackTime(now);

        // 选本刀要播的攻击动画变体 —— **服务端权威**（所有客户端必须看到同一条）。
        // 与玩家那条链同一套：服务端持有动画数据 → 选 → 把条目索引随 `S2C_MonsterMove.anim_index`
        // 下发给客户端（客户端直接 `playMotion(该条目)`，不再各自 `deriveAnimSeed` 派生）。
        // 每刀都重选（原版 `SetMotionFromCode(ATTACK)` 每刀都随机）；选完强制重广播。
        monster.setAttackAnim(MonsterAnimData.get().pick(monster.getModelFile(), "attack"));
        monster.setLastBroadcastAnim(-1);

        Player player = target.getPlayer();
        if (player == null) {
            return;
        }
        DamageResult result = damageCalculator.calculateMonsterToPlayer(monster.combatStats(), player);

        // 未命中（原版 sinGetMonsterAccuracy）：不扣血、不写战斗日志、不触发受击硬直，
        // 只广播一条 missed 让受害者头顶飘 MISS —— 低等级怪打高等级玩家常常打空，正是靠这条体现。
        if (result.isMissed()) {
            log.info("[MonsterAI] {}#{} ATK {} -> MISS, interval={}ms",
                monster.getName(), monster.getId(), targetName(target), interval);
            battleLogService.monsterMissed(playerService.sessionOf(player), monster.getName());
            messageSender.broadcastToArea(target.getMapId(),
                (float) target.getX(), (float) target.getZ(), 50,
                ServerMessage.newBuilder()
                    .setDamage(S2C_Damage.newBuilder()
                        .setTargetId(player.getId())
                        .setDamage(0)
                        .setCurrentHp(player.getHp())
                        .setMissed(true)
                        .build())
                    .build());
            monster.setLastBroadcastAnim(-1);   // 下一刀仍广播攻击动作，玩家看得到挥空
            return;
        }

        // 被格挡（`DamageCalculator` 的格挡判定通过）：伤害为 0、不扣血、不触发受击硬直/受击音，
        // 只广播一条 blocked 让受害者头顶飘 "Blocked" + 客户端随机播 impact/block{1,2,3}.wav。
        // 与 missed 分开：格挡有音、miss 没有（用户 2026-09-16）。
        if (result.isBlocked()) {
            log.info("[MonsterAI] {}#{} ATK {} -> BLOCKED, interval={}ms",
                monster.getName(), monster.getId(), targetName(target), interval);
            messageSender.broadcastToArea(target.getMapId(),
                (float) target.getX(), (float) target.getZ(), 50,
                ServerMessage.newBuilder()
                    .setDamage(S2C_Damage.newBuilder()
                        .setTargetId(player.getId())
                        .setDamage(0)
                        .setCurrentHp(player.getHp())
                        .setBlocked(true)
                        .build())
                    .build());
            monster.setLastBroadcastAnim(-1);   // 下一刀仍广播攻击动作
            return;
        }

        int newHp = Math.max(0, player.getHp() - result.getFinalDamage());
        player.setHp(newHp);

        log.info("[MonsterAI] {}#{} ATK {} dmg={} ({}->{}), interval={}ms",
            monster.getName(), monster.getId(), targetName(target),
            result.getFinalDamage(), newHp + result.getFinalDamage(), newHp, interval);

        // 战斗日志：玩家受击（进聊天窗"系统"tab）
        battleLogService.playerHurt(playerService.sessionOf(player), monster.getName(), result.getFinalDamage());

        // 飘字：怪→玩家伤害广播给附近玩家（S2C_Damage 带权威 currentHp，客户端自机/远端头顶飘红字）
        messageSender.broadcastToArea(target.getMapId(),
            (float) target.getX(), (float) target.getZ(), 50,
            ServerMessage.newBuilder()
                .setDamage(S2C_Damage.newBuilder()
                    .setTargetId(player.getId())
                    .setDamage(result.getFinalDamage())
                    .setCurrentHp(newHp)
                    .build())
                .build());

        // 推送玩家最新状态（HUD 血条 + 角色信息面板）：客户端据 S2C_PlayerState/S2C_CharacterStatus 刷新
        playerService.sendPlayerStatus(playerService.sessionOf(player), player);

        // 强制下一轮攻击广播重发(客户端每刀都能看到攻击动作)
        monster.setLastBroadcastAnim(-1);

        if (newHp <= 0) {
            // 不再立刻复活：进入死亡态躺下，由玩家在三个选项里选、或 1 分钟后被强制送回村庄。
            // 客户端 HUD 由上面那条 status（hp=0）与 enterDeath 广播的 S2C_PlayerDeath 一起刷新。
            combatService.enterDeath(player);
        }
    }

    // ======== 工具 ========

    private double loseRangeOf(Monster monster) {
        double sight = monster.getViewsight();
        return Math.max(sight <= 0 ? AIConstants.MIN_LOSE_RANGE : sight, AIConstants.MIN_LOSE_RANGE);
    }

    private double distXZ(Monster monster, PlayerEntity entity) {
        double dx = monster.getX() - entity.getX();
        double dz = monster.getZ() - entity.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private String targetName(PlayerEntity entity) {
        if (entity == null) return "?";
        Player p = entity.getPlayer();
        if (p != null && p.getName() != null) return p.getName();
        PlayerSession s = entity.getSession();
        return s != null && s.getCharacterName() != null ? s.getCharacterName() : String.valueOf(entity.getCharId());
    }

    private void logState(Monster monster, MonsterState from, MonsterState to, String reason) {
        log.info("[MonsterAI] {}#{} state {} -> {} ({}) at ({},{})",
            monster.getName(), monster.getId(), from, to, reason,
            (int) monster.getX(), (int) monster.getZ());
    }
}
