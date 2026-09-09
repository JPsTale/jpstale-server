package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.DamageResult;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.MonsterState;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.PlayerSession;
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
            if (next == MonsterState.ATTACK) {
                if (monster.getState() != MonsterState.ATTACK) {
                    logState(monster, prevState, MonsterState.ATTACK, "lock target=" + targetName(target));
                    monster.setState(MonsterState.ATTACK);
                }
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

    /** 归位半径:优先 moveRange,缺省 viewsight×1.5 且不下于 100 */
    private static double leashOf(Monster monster) {
        if (monster.getMoveRange() > 0) {
            return monster.getMoveRange();
        }
        return Math.max(monster.getViewsight() * 1.5f, 100.0f);
    }

    // ======== 目标管理 ========

    /** 校验当前目标是否仍可锁;不可锁则清空并尝试按视野补一个(Evil) */
    private PlayerEntity validateTarget(Monster monster, AiContext context) {
        PlayerEntity target = context.getTargetPlayer();

        // 若已锁一个目标,先校验它是否仍有效
        if (target != null) {
            double lose = loseRangeOf(monster);
            if (!target.isPlaying()
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
            if (entity == null || !entity.isPlaying() || entity.getMapId() != monster.getMapId()) {
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
        if (target == null || !target.isPlaying()) {
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
        long interval = (long) monster.getAttackSpeed();
        if (interval <= 0) interval = 1000;
        if (now - monster.getLastAttackTime() < interval) {
            return;
        }
        monster.setLastAttackTime(now);

        Player player = target.getPlayer();
        if (player == null) {
            return;
        }
        DamageResult result = damageCalculator.calculateMonsterToPlayer(monster, player);
        int newHp = Math.max(0, player.getHp() - result.getFinalDamage());
        player.setHp(newHp);

        log.info("[MonsterAI] {}#{} ATK {} dmg={} ({}->{}), interval={}ms",
            monster.getName(), monster.getId(), targetName(target),
            result.getFinalDamage(), newHp + result.getFinalDamage(), newHp, interval);

        // 战斗日志：玩家受击（进聊天窗"系统"tab）
        battleLogService.playerHurt(player.getSession(), monster.getName(), result.getFinalDamage());

        // 推送玩家最新状态（HUD 血条 + 角色信息面板）：客户端据 S2C_PlayerState/S2C_CharacterStatus 刷新
        playerService.sendPlayerStatus(player.getSession(), player);

        // 强制下一轮攻击广播重发(客户端每刀都能看到攻击动作)
        monster.setLastBroadcastAnim(-1);

        if (newHp <= 0) {
            combatService.respawnPlayer(player);
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
