package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.service.*;
import org.jpstale.server.game.model.AiContext;
import org.jpstale.server.game.model.BehaviorTree;
import org.jpstale.server.game.model.BehaviorNode;
import org.jpstale.server.game.model.NodeStatus;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.SelectorNode;
import org.jpstale.server.game.model.SequenceNode;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.network.PlayerSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 引擎
 * 管理怪物的 AI 行为
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

    private final Map<Long, AiContext> monsterContexts = new ConcurrentHashMap<>();
    private final Map<Integer, BehaviorTree> behaviorTrees = new ConcurrentHashMap<>();

    public void init() {
        // 为每种 AI 类型构建行为树
        behaviorTrees.put(0, buildDefaultTree()); // 默认：巡逻
        behaviorTrees.put(1, buildAggressiveTree()); // 攻击型
        behaviorTrees.put(2, buildDefensiveTree()); // 防御型
    }

    /**
     * 更新怪物 AI
     */
    public void update(Monster monster) {
        if (!monster.isAlive()) {
            return;
        }

        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        
        // 检查决策间隔
        if (!context.shouldDecide()) {
            return;
        }

        // 仇恨检测：无目标时寻找附近玩家，超出视野则放弃目标
        updateTarget(monster, context);

        // 获取行为树
        BehaviorTree tree = behaviorTrees.get(0); // 默认使用攻击型
        if (tree != null) {
            tree.tick(monster, context);
            context.updateDecisionTime();
        }
    }

    /**
     * 仇恨检测：
     * 1. 无目标：扫描视野范围内（viewsight）的玩家，主动型怪物（intelligence>0）锁定最近玩家
     * 2. 已有目标：玩家脱离视野（1.5 倍视野）则放弃目标，怪物回归巡逻/归位
     */
    private void updateTarget(Monster monster, AiContext context) {
        PlayerSession currentTarget = context.getTargetPlayer();
        if (currentTarget != null) {
            float dx = monster.getX() - currentTarget.getX();
            float dz = monster.getZ() - currentTarget.getZ();
            float distSq = dx * dx + dz * dz;
            // 追击丢失：目标跑出活动范围（MoveRange 1.5 倍视野）或跨地图 → 放弃并归位
            float loseRange = Math.max(monster.getMoveRange(), AOIManager.VIEW_RANGE_DISCONNECT);
            if (distSq > loseRange * loseRange || !currentTarget.isPlaying()
                    || currentTarget.getCurrentMapId() != monster.getMapId()) {
                clearTarget(monster);
            } else {
                // 刷新目标位置（玩家在移动）
                context.setTargetX(currentTarget.getX());
                context.setTargetY(currentTarget.getY());
                context.setTargetZ(currentTarget.getZ());
            }
            return;
        }

        // 无目标：仅 Evil（主动）怪扫描视野内玩家；Neutral/Good 不主动（Neutral 靠受击反击）
        if (monster.getNature() != 1 || monster.getViewsight() <= 0) {
            return;
        }

        // EU（OnSever.cpp）：扫描范围 = min(viewsight, CONNECT 1086)，且高度差 |rY| < 140
        float sight = Math.min(monster.getViewsight(), AOIManager.VIEW_RANGE);
        Set<PlayerSession> nearby = aoiManager.getNearbyPlayers(monster.getX(), monster.getZ(), sight);
        PlayerSession nearest = null;
        float nearestDistSq = Float.MAX_VALUE;
        for (PlayerSession session : nearby) {
            if (!session.isPlaying() || session.getCurrentMapId() != monster.getMapId()) {
                continue;
            }
            float dy = monster.getY() - session.getY();
            if (Math.abs(dy) > 140) {
                continue;
            }
            float dx = monster.getX() - session.getX();
            float dz = monster.getZ() - session.getZ();
            float distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = session;
            }
        }

        if (nearest != null) {
            context.setTargetPlayer(nearest);
            context.setTargetX(nearest.getX());
            context.setTargetY(nearest.getY());
            context.setTargetZ(nearest.getZ());
            monster.setTargetPlayerId(nearest.getCharacterId());
        }
    }

    /**
     * 设置怪物目标
     */
    public void setTarget(Monster monster, Long playerId, float targetX, float targetZ) {
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        // TODO: 从 SessionManager 获取 PlayerSession
        context.setTargetX(targetX);
        context.setTargetZ(targetZ);
        monster.setTargetPlayerId(playerId);
    }

    /**
     * 受击反击/仇恨指定：将怪物目标设为指定玩家（含 PlayerSession，使行为树可追击）
     */
    public void setTargetPlayer(Monster monster, PlayerSession session, float targetX, float targetZ) {
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        context.setTargetPlayer(session);
        context.setTargetX(targetX);
        context.setTargetZ(targetZ);
        monster.setTargetPlayerId(session.getCharacterId());
    }

    /**
     * 清除怪物目标
     */
    public void clearTarget(Monster monster) {
        AiContext context = monsterContexts.get(monster.getId());
        if (context != null) {
            context.setTargetPlayer(null);
        }
        monster.setTargetPlayerId(null);
    }

    /**
     * 移除怪物上下文
     */
    public void removeContext(long monsterId) {
        monsterContexts.remove(monsterId);
    }

    /**
     * 获取怪物 AI 上下文（MovementService 读取 targetX/targetZ/patrolX/patrolZ）
     */
    public AiContext getContext(long monsterId) {
        return monsterContexts.get(monsterId);
    }

    private BehaviorTree buildDefaultTree() {
        return new BehaviorTree(
            new SelectorNode(
                // 如果死了 → 失败
                new SequenceNode(
                    new IsDeadNode(),
                    new DeadActionNode()
                ),
                // 如果有目标 → 追击/攻击
                new SequenceNode(
                    new HasTargetNode(),
                    new SelectorNode(
                        new SequenceNode(
                            new InAttackRangeNode(),
                            new AttackNode(damageCalculator, playerService, combatService)
                        ),
                        new ChaseNode()
                    )
                ),
                // 否则 → 巡逻
                new PatrolNode()
            )
        );
    }

    private BehaviorTree buildAggressiveTree() {
        return buildDefaultTree(); // 与默认相同
    }

    private BehaviorTree buildDefensiveTree() {
        return buildDefaultTree(); // 与默认相同
    }

    /**
     * 动作节点：死亡处理
     */
    private static class DeadActionNode extends BehaviorNode {
        @Override
        public NodeStatus tick(Monster monster, AiContext context) {
            // 怪物死亡，什么都不做
            return NodeStatus.SUCCESS;
        }
    }
}
