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

    /** 锻造：战斗养熟练度（格挡喂盾、被击喂五件防具）—— 照 EU `character.cpp:10619/10876`。 */
    @Autowired
    private org.jpstale.common.service.item.AgeService ageService;

    /** 锻造升级的广播（原版 `smCOMMNAD_USER_AGINGUP`） */
    @Autowired
    private AgeEffectBroadcaster ageEffectBroadcaster;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private CombatService combatService;

    @Autowired
    private BattleLogService battleLogService;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private org.jpstale.server.game.entity.EntityRegistry entityRegistry;

    @Autowired
    private org.jpstale.server.game.network.SessionManager sessionManager;

    @Autowired
    private MapRegionService mapRegionService;

    private final Map<Long, AiContext> monsterContexts = new ConcurrentHashMap<>();

    // ======== 召唤物（怪物水晶）的牵引 ========
    //
    // 逐条照抄原版 `character.cpp:5780-5830`。原版在这里做三件事：把距离换算成"格"
    // （`>> FLOATNS`）再比三个半径、太远就瞬移到主人脚下、没地面就直接死。
    // 单位可以直接用：我们的协议明确"world 坐标（服务端已 /256）"，即我们的 1 单位 = 原版 1 格。
    //
    //   ≥ 500 —— 瞬移到主人脚下（原版还有一支：主人所在处**没有地面**（掉出世界）→ 召唤物死，
    //            靠 `lpStage->GetHeight(...) < 0` 判定；我们用
    //            `MapRegionService.getFloorHeightOrNull` 表达同一件事 —— 这也是它存在的理由）
    //   ≥ 300（我们另加"无目标时 ≥ leash"，见下）—— 跑回主人身边并清目标
    private static final double SUMMON_TELEPORT_DIST = 500.0;

    public void init() {
        log.info("[MonsterAI] init done");
    }

    /** 每 tick(仅活跃怪,由 MonsterSpawnService 门控)执行一次 AI 决策 */
    public void update(Monster monster) {
        if (!monster.isAlive()) {
            return;
        }
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());

        // 召唤物走**另一条链**：目标是怪而不是玩家，牵引基准是主人而不是出生锚。
        // 判定只有一处 —— `Monster.isSummon()`（原版对应 `Brood == smCHAR_MONSTER_USER`）。
        // 为什么要分叉而不是把目标泛型化：两条链的**结算方式**不同（对玩家有格挡/受击硬直/
        // 死亡流程，对怪是百分比吸收），共用的部分已经抽出来了 —— 目标校验的"该不该丢"
        // 与出刀节奏在下面共用，位移与坐标在 AiContext 里共用。
        if (monster.isSummon()) {
            updateSummon(monster, context);
            return;
        }

        MonsterState prevState = monster.getState();

        ResolvedTarget target = validateTarget(monster, context);

        // 纯决策(无副作用,可表驱动单测):目标可锁→攻击/追击;无目标→出生锚 leash 内待机/超界归位
        MonsterState next = decide(
            target.present(),
            target.present() && inAttackRange(monster, target),
            homeDistOf(monster), leashOf(monster));

        if (target.present()) {
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
                    logState(monster, prevState, MonsterState.ATTACK, "lock target=" + target.label());
                    monster.setState(MonsterState.ATTACK);
                }
                faceTarget(monster, target);
                tryAttack(monster, target);
            } else {
                if (monster.getState() != MonsterState.CHASE) {
                    logState(monster, prevState, MonsterState.CHASE, "chase target=" + target.label());
                    monster.setState(MonsterState.CHASE);
                }
                target.writeInto(context);
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

    /**
     * 一个已解析的目标：**玩家**或**另一只怪（召唤物）**，二者最多一个非空。
     *
     * <p>
     * 只在两处问"是哪一类"：`update` 的公共尾巴只看 `present()/label()/writeInto()/`坐标，
     * 结算分派集中在 {@code tryAttack} 一处。这样"目标多了一种"这件事不会渗透到
     * 状态机、面向、追击坐标里去。
     */
    private record ResolvedTarget(PlayerEntity player, Monster monster) {
        static ResolvedTarget of(PlayerEntity p) {
            return new ResolvedTarget(p, null);
        }

        static ResolvedTarget of(Monster m) {
            return new ResolvedTarget(null, m);
        }

        static ResolvedTarget none() {
            return new ResolvedTarget(null, null);
        }

        boolean present() {
            return player != null || monster != null;
        }

        double x() {
            return player != null ? player.getX() : monster.getX();
        }

        double y() {
            return player != null ? player.getY() : monster.getY();
        }

        double z() {
            return player != null ? player.getZ() : monster.getZ();
        }

        String label() {
            return player != null ? targetName(player) : labelOf(monster);
        }

        void writeInto(AiContext c) {
            c.setTargetX(x());
            c.setTargetY(y());
            c.setTargetZ(z());
        }
    }

    /** 校验当前目标是否仍可锁;不可锁则清空并尝试按视野补一个(Evil) */
    private ResolvedTarget validateTarget(Monster monster, AiContext context) {
        PlayerEntity player = context.getTargetPlayer();
        Monster summon = context.getTargetMonster();

        // 若已锁一个目标,先校验它是否仍有效
        if (player != null || summon != null) {
            double lose = loseRangeOf(monster);
            boolean stillOk = player != null
                ? player.isTargetable()
                    && player.getMapId() == monster.getMapId()
                    && distXZ(monster, player) <= lose
                : summon.isAlive()
                    && summon.getMapId() == monster.getMapId()
                    && distXZ(monster, summon) <= lose;
            if (!stillOk) {
                log.debug("[MonsterAI] {}#{} lost target {} (out of range/area)",
                    monster.getName(), monster.getId(),
                    player != null ? targetName(player) : labelOf(summon));
                context.setTargetPlayer(null);
                context.setTargetMonster(null);
                monster.setTargetPlayerId(null);
                monster.setTargetMonsterId(null);
                player = null;
                summon = null;
            } else {
                ResolvedTarget kept = player != null ? ResolvedTarget.of(player) : ResolvedTarget.of(summon);
                kept.writeInto(context);
                return kept;
            }
        }

        // 无目标:仅 Evil(主动)扫描视野内「玩家 ∪ 召唤物」
        ResolvedTarget found = scanTarget(monster);
        if (found.present()) {
            if (found.player() != null) {
                context.setTargetPlayer(found.player());
                monster.setTargetPlayerId(found.player().getCharId());
            } else {
                context.setTargetMonster(found.monster());
                monster.setTargetMonsterId(found.monster().getId());
            }
            found.writeInto(context);
            log.info("[MonsterAI] {}#{} acquire target {} at ({},{})",
                monster.getName(), monster.getId(), found.label(),
                (int) found.x(), (int) found.z());
        } else {
            monster.setTargetPlayerId(null);
            monster.setTargetMonsterId(null);
        }
        return found;
    }

    /**
     * 视野内找最近的**玩家**（仅 Evil；高度差 &lt; 140）—— 照原版，**召唤物不是索敌候选**。
     *
     * <p>
     * 原版的索敌逐字如此（`Server/OnSever.cpp:10200-10358`，`srAutoCharMain` 里）：
     * <pre>
     *   if (!lpChar->lpTargetChar) {                        // 没有"怪"目标时才索敌
     *     for (cnt = 0; cnt &lt; CONNECTMAX; cnt++) {          // ★ 遍历 rsPlayInfo[] = **玩家表**
     *       ... State != smCHAR_STATE_ENEMY / 同血盟跳过 / dwLinkObjectCode / HideMode ...
     *       dist = rX*rX + rZ*rZ + rY*rY;
     *       if (dist &lt; MinDist &amp;&amp; abs(rY) &lt; 140) { ... lprsPlayInfo = &amp;rsPlayInfo[cnt]; MinDist = dist; }
     * </pre>
     * ⇒ 取**最近的活玩家**；视野是 `smCharInfo.Sight`（玩家的蔽目/追踪技能会改它），
     * 排除同血盟与 `HideMode`。（原版还有 `dwTargetLockTime` 目标锁 —— 我们没做，见文末注。）
     *
     * <p>
     * ⚠ **召唤物要等它先动手才挨打**：怪把召唤物当目标的**唯一**入口是**反击** ——
     * `character.cpp:5998/6005` 的 `lpTargetChar-&gt;lpTargetChar = this;`（"我打了你，你现在打我"），
     * 由 {@code resolveMonsterVsMonster} 调 {@link #setTargetMonster} 落地。
     * 我**一度**把召唤物也加进了这个扫描，依据写的是 `character.cpp:5905/5969` —— 那两行其实是
     * "诅咒技能对召唤物跳过"和"Babel 回血对召唤物不适用"，**不是索敌证据**。用户 2026-09-23 实测
     * 指出"怪物刷新出来后会先找玩家的茬"，核对后确认**那就是原版行为**，遂撤回。
     *
     * <p>
     * 已知未做的差异：原版有目标锁 `dwTargetLockTime`（锁定期不重新索敌），我们每 tick 都重新校验、
     * 能自由换目标。要不要补是独立的一件事（当前没有表现出问题）。
     */
    private ResolvedTarget scanTarget(Monster monster) {
        if (monster.getNature() != 1 || monster.getViewsight() <= 0) {
            return ResolvedTarget.none();
        }
        double sight = Math.min(monster.getViewsight(), AOIManager.VIEW_RANGE);

        PlayerEntity nearestPlayer = null;
        double nearestPlayerDistSq = Double.MAX_VALUE;
        Set<PlayerEntity> nearby = aoiManager.getNearbyPlayers(monster.getX(), monster.getZ(), (float) sight);
        for (PlayerEntity entity : nearby) {
            if (entity == null || !entity.isTargetable() || entity.getMapId() != monster.getMapId()) {
                continue;
            }
            double dy = monster.getY() - entity.getY();
            if (Math.abs(dy) > AIConstants.SCAN_HEIGHT_DIFF) {
                continue;
            }
            double d = distXZ(monster, entity);
            if (d < nearestPlayerDistSq) {
                nearestPlayerDistSq = d;
                nearestPlayer = entity;
            }
        }
        return nearestPlayer != null ? ResolvedTarget.of(nearestPlayer) : ResolvedTarget.none();
    }

    /** 受击反击/仇恨指定:把目标设为指定玩家实体(供 CombatService 受击调用) */
    public void setTargetPlayer(Monster monster, PlayerEntity target, double targetX, double targetZ) {
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        if (target == null || !target.isTargetable()) {
            return;
        }
        context.setTargetPlayer(target);
        context.setTargetMonster(null);
        context.setTargetX(targetX);
        context.setTargetZ(targetZ);
        monster.setTargetPlayerId(target.getCharId());
        monster.setTargetMonsterId(null);
        log.info("[MonsterAI] {}#{} retaliate target {}", monster.getName(), monster.getId(),
            targetName(target));
    }

    /**
     * 受击反击/仇恨指定:把目标设为指定**怪物**（召唤物）。
     *
     * 调用点：怪被召唤物打中时（{@code resolveMonsterVsMonster}）—— 与玩家那条对称，
     * 都是"谁打我我打谁"。
     */
    public void setTargetMonster(Monster monster, Monster target, double targetX, double targetZ) {
        AiContext context = monsterContexts.computeIfAbsent(monster.getId(), k -> new AiContext());
        if (target == null || !target.isAlive()) {
            return;
        }
        context.setTargetMonster(target);
        context.setTargetPlayer(null);
        context.setTargetX(targetX);
        context.setTargetZ(targetZ);
        monster.setTargetMonsterId(target.getId());
        monster.setTargetPlayerId(null);
        log.info("[MonsterAI] {}#{} retaliate target {}", monster.getName(), monster.getId(),
            labelOf(target));
    }

    /** 清除怪物目标（两种目标一起清 —— 留着另一个会让"丢目标"只做了一半） */
    public void clearTarget(Monster monster) {
        AiContext context = monsterContexts.get(monster.getId());
        if (context != null) {
            context.setTargetPlayer(null);
            context.setTargetMonster(null);
        }
        monster.setTargetPlayerId(null);
        monster.setTargetMonsterId(null);
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

    private void faceTarget(Monster monster, ResolvedTarget target) {
        faceTarget(monster, target.x(), target.z());
    }

    /**
     * 站桩攻击时面朝目标（**面向算法唯一实现**；召唤物那条链也用这个）。
     *
     * 原先**只有** `MovementService.moveToward`（追击移动）会写 `monster.angle`，所以一旦进入
     * 攻击距离停下不动，朝向就停在上一次移动的方向 —— 从侧面/背面靠近或被人从背后打时，
     * 怪会**背对玩家挥击**（用户 2026-09-12 实测发现）。
     * 转向写成 monster.angle 后由 MonsterAOI.broadcastMove 下发（那里也已把朝向纳入"变化"判定）。
     */
    private void faceTarget(Monster monster, double targetX, double targetZ) {
        double dx = targetX - monster.getX();
        double dz = targetZ - monster.getZ();
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

    private boolean inAttackRange(Monster monster, ResolvedTarget target) {
        double range = monster.getAttackRange() > 0 ? monster.getAttackRange() : 2.0;
        if (distXZ(monster, target.x(), target.z()) > range) {
            return false;
        }
        double dy = monster.getY() - target.y();
        return Math.abs(dy) < AIConstants.ATTACK_HEIGHT_DIFF;
    }

    // ======== 召唤物（怪物水晶） ========

    /**
     * 召唤物"无目标"时该用哪个状态去执行。
     *
     * <p>
     * `decide()` 会给出 `RETURN`（超出 leash）或 `IDLE`；**`RETURN` 要换成 `CHASE`**，
     * 因为 `RETURN` 的位移是**写死的走路**（`MONSTER_WALK_STEP`，语义是"野怪溜达回出生点"，
     * `MonsterAOI.animOf` 也把 RETURN 映射成 0x0050 WALK），而原版跟随主人那条是
     * `if (!SetMotionFromCode(RUN)) SetMotionFromCode(WALK)` —— **能跑就跑**
     * （`character.cpp:5820-5828`）。
     *
     * <p>
     * 抽成一个可测的纯函数，是因为这条判据**写错过一次**：最初把跟随接到 `RETURN`（出生锚就是
     * 主人，位移目标正好对上），于是召唤物打怪时跑、跟人时走 —— 用户 2026-09-23 实测点破
     * "跟随只有走路，但打怪却会跑"。`AiEngineTest` 把这个映射钉住，防止有人"顺手简化"回 RETURN。
     */
    static MonsterState summonNoTargetState(MonsterState decided) {
        return decided == MonsterState.RETURN ? MonsterState.CHASE : decided;
    }

    /**
     * 召唤物的每 tick 决策。与原版 `character.cpp:5780-5830` 同一套：
     * 先把主人拴住（太远瞬移），再找怪打，最后才是归位/待机。
     *
     * <p>
     * **主人不在线时这里直接返回、不判死** —— 召唤物的收尾统一由
     * `MonsterSpawnService` 的生命周期检查处理（它对"寿命到点"和"主人没了"都调
     * `CombatService.killSummon`）。理由：致死入口只能有一个（`Monster.onDeath` 的不变式，
     * 见 `MonsterAOI.reconcile` 的自检），两条链都能改死亡状态迟早会漏写死亡负载。
     */
    private void updateSummon(Monster summon, AiContext context) {
        PlayerEntity owner = ownerOf(summon);
        if (owner == null) {
            return;
        }

        tetherToOwner(summon, context, owner);

        MonsterState prevState = summon.getState();
        Monster target = validateMonsterTarget(summon, context);
        MonsterState next = decide(
            target != null,
            target != null && inAttackRange(summon, ResolvedTarget.of(target)),
            homeDistOf(summon), leashOf(summon));

        if (target != null) {
            // 与原版一致：出了刀就站完这一刀（时长 = 攻击动画时长），否则客户端的挥击会被打断
            long lockMs = summon.getAttackIntervalMs();
            if (summon.getState() == MonsterState.ATTACK && lockMs > 0
                    && System.currentTimeMillis() - summon.getLastAttackTime() < lockMs) {
                faceTarget(summon, target.getX(), target.getZ());
                return;
            }
            if (next == MonsterState.ATTACK) {
                if (summon.getState() != MonsterState.ATTACK) {
                    logState(summon, prevState, MonsterState.ATTACK, "lock target=" + labelOf(target));
                    summon.setState(MonsterState.ATTACK);
                }
                faceTarget(summon, target.getX(), target.getZ());
                tryAttack(summon, ResolvedTarget.of(target));
            } else {
                if (summon.getState() != MonsterState.CHASE) {
                    logState(summon, prevState, MonsterState.CHASE, "chase target=" + labelOf(target));
                    summon.setState(MonsterState.CHASE);
                }
                ResolvedTarget.of(target).writeInto(context);
            }
            return;
        }

        if (next == MonsterState.RETURN) {
            // "跟上主人" —— **必须走 CHASE，不能走 RETURN**：RETURN 的位移是写死的走路，
            // 而原版跟随主人是"能跑就跑"。为什么、以及为什么不改 RETURN 本身，
            // 见 `summonNoTargetState` 的注释（这条判据错过的现场就在那里）。
            MonsterState followState = summonNoTargetState(next);
            if (summon.getState() != followState) {
                logState(summon, prevState, followState,
                    "follow owner, dist=" + (int) homeDistOf(summon));
                summon.setState(followState);
            }
            context.setTargetX(owner.getX());
            context.setTargetY(owner.getY());
            context.setTargetZ(owner.getZ());
        } else if (summon.getState() != MonsterState.IDLE) {
            logState(summon, prevState, MonsterState.IDLE, "stand");
            summon.setState(MonsterState.IDLE);
        }
    }

    /**
     * 把召唤物拴在主人身上（原版 `character.cpp:5788-5827`）。
     *
     * <p>
     * 两件事：
     * <ol>
     *   <li>**出生锚跟随主人** —— 让 `homeDistOf`/`leashOf` 这两样现成的东西自动变成
     *       "以主人为家"（用于判定"该不该跟"）。⚠ 归位**位移**并不走 RETURN：见
     *       `updateSummon` 里那段注释（RETURN 写死走路，跟人必须能跑就跑）；</li>
     *   <li>距离 ≥ {@link #SUMMON_TELEPORT_DIST} 时瞬移到主人脚下并清目标（原版就是这样，
     *       顺带解释了为什么玩家跑远了召唤物也会跟上来）。</li>
     * </ol>
     */
    private void tetherToOwner(Monster summon, AiContext context, PlayerEntity owner) {
        // 出生锚 = 主人当前位置（每 tick 更新，见上）
        summon.setSpawnX(owner.getX());
        summon.setSpawnZ(owner.getZ());

        double dx = owner.getX() - summon.getX();
        double dz = owner.getZ() - summon.getZ();
        if (dx * dx + dz * dz < SUMMON_TELEPORT_DIST * SUMMON_TELEPORT_DIST) {
            return;
        }

        // 原版：主人所在处没有地面（掉出了世界）→ 召唤物直接死，而不是跟着瞬移过去
        // （`character.cpp:5794-5796`：`y = lpStage->GetHeight(...); if (y < 0) Life[0]=0; DEAD;`）
        Double ownerGround = mapRegionService.getFloorHeightOrNull(owner.getMapId(), owner.getX(), owner.getZ());
        if (ownerGround == null) {
            log.info("[MonsterAI] {}#{} 距主人过远且主人脚下没有地面 → 按原版让召唤物死亡",
                summon.getName(), summon.getId());
            combatService.killSummon(summon, 0L);
            return;
        }

        log.info("[MonsterAI] {}#{} 距主人 {} 超出 {} → 瞬移到主人脚下",
            summon.getName(), summon.getId(), (int) Math.sqrt(dx * dx + dz * dz),
            (int) SUMMON_TELEPORT_DIST);
        // 落点用**主人自己的坐标**（含 Y），与原版逐字一致 —— 不是地面高度
        summon.setX(owner.getX());
        summon.setY(owner.getY());
        summon.setZ(owner.getZ());
        summon.setTargetMonsterId(null);
        context.setTargetMonster(null);
        summon.setLastBroadcastX(Double.NaN);   // 强制下一次广播位置（否则客户端会看到它"平滑滑过去"）
        summon.setLastBroadcastZ(Double.NaN);
    }

    /** 召唤物的主人实体；主人不在线（会话已解绑/回选角）时返回 null。 */
    PlayerEntity ownerOf(Monster summon) {
        if (!summon.isSummon()) {
            return null;
        }
        PlayerSession session = sessionManager.getSessionByCharacterId(summon.getOwnerCharId());
        if (session == null || !session.isPlaying()) {
            return null;
        }
        return session.getEntity();
    }

    /**
     * 召唤物的目标：视野内最近的**非召唤物**怪。
     *
     * <p>
     * 不打玩家的召唤物（`isSummon()` 的排除）、不打 NPC（NPC 是另一个实体类型，不在
     * `EntityRegistry` 的怪表里）。原版的过滤比这多几条（同 `ClassClan`、城堡联动、
     * `lpLinkChar` 等，见 `OnSever.cpp:10084-10130`）—— 那几条全部服务于城堡战，
     * 我们本期不做城堡水晶，故不移植；等做 GP114-116 时再照抄那段。
     */
    private Monster validateMonsterTarget(Monster summon, AiContext context) {
        Monster current = context.getTargetMonster();
        if (current != null) {
            double lose = loseRangeOf(summon);
            if (!current.isAlive()
                || current.getMapId() != summon.getMapId()
                || current.isSummon()
                || distXZ(summon, current) > lose) {
                log.info("[MonsterAI] {}#{} lost target {} (out of range/area)",
                    summon.getName(), summon.getId(), labelOf(current));
                context.setTargetMonster(null);
                summon.setTargetMonsterId(null);
                current = null;
            } else {
                ResolvedTarget.of(current).writeInto(context);
                return current;
            }
        }

        Monster found = scanMonsterTarget(summon);
        if (found != null) {
            context.setTargetMonster(found);
            ResolvedTarget.of(found).writeInto(context);
            summon.setTargetMonsterId(found.getId());
            log.info("[MonsterAI] {}#{} acquire target {} at ({},{})",
                summon.getName(), summon.getId(), labelOf(found),
                (int) found.getX(), (int) found.getZ());
        } else {
            summon.setTargetMonsterId(null);
        }
        return found;
    }

    /** 视野内最近的**非召唤物**怪（高度差同普通怪的 SCAN_HEIGHT_DIFF） */
    private Monster scanMonsterTarget(Monster summon) {
        double sight = Math.min(summon.getViewsight(), AOIManager.VIEW_RANGE);
        Monster nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Monster other : entityRegistry.monstersByMap(summon.getMapId())) {
            if (other == summon || !other.isAlive() || other.isSummon()) {
                continue;
            }
            if (Math.abs(summon.getY() - other.getY()) > AIConstants.SCAN_HEIGHT_DIFF) {
                continue;
            }
            double d = distXZ(summon, other);
            if (d > sight) {
                continue;
            }
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = other;
            }
        }
        return nearest;
    }

    /** 按攻击冷却结算一次伤害(对齐原版:站桩出刀,帧外由 tick 决定出手节奏)。**出刀节奏的唯一实现** */
    private void tryAttack(Monster monster, ResolvedTarget target) {
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

        // 结算按目标类型分派（两条链的差别只有结算方式，节奏/动画/朝向都是共用的）
        if (target.monster() != null) {
            resolveMonsterVsMonster(monster, target.monster());
            return;
        }
        resolveMonsterVsPlayer(monster, target.player(), interval);
    }

    /** 怪 → 玩家（原有实现，逐字保留；只把出刀节奏挪到了 `tryAttack`） */
    private void resolveMonsterVsPlayer(Monster monster, PlayerEntity target, long interval) {
        Player player = target.getPlayer();

        if (player == null) {
            return;
        }
        DamageResult result = damageCalculator.calculateMonsterToPlayer(monster.combatStats(), player);

        // 未命中（原版 sinGetMonsterAccuracy）：不扣血、不写战斗日志、不触发受击硬直，
        // 只广播一条 missed 让受害者头顶飘 MISS —— 低等级怪打高等级玩家常常打空，正是靠这条体现。
        if (result.isMissed()) {
            log.debug("[MonsterAI] {}#{} ATK {} -> MISS, interval={}ms",
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
            log.debug("[MonsterAI] {}#{} ATK {} -> BLOCKED, interval={}ms",
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
            // 锻造：格挡成功 → 喂盾/法球（原版 `sinCheckAgingLevel(SIN_AGING_BLOCK)`；等级差门槛由 AgeService 之外不判，
            // 这里只在"同级差"时才有意义 —— 门槛 `AGING_SUB_LEVEL=10` 见 docs/锻造与合成-源码分析.md §3.10）
            ageEffectBroadcaster.wrapUpBattleAging(player, ageService.onBlock(player));
            return;
        }

        int newHp = Math.max(0, player.getHp() - result.getFinalDamage());
        player.setHp(newHp);
        // 锻造：被击受伤 → 一次性喂五件防具（原版 `character.cpp:10876-10882` 的五个 DEFENSE_* 调用）
        ageEffectBroadcaster.wrapUpBattleAging(player, ageService.onDamaged(player));

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

    /**
     * 怪 ↔ 召唤物 的结算 —— **两个方向共用这一份**（召唤物打怪、怪打召唤物）。
     *
     * <p>
     * 不变式：**恰好一方是召唤物**。
     * <ul>
     *   <li>召唤物只把"非召唤物的怪"当目标（`scanMonsterTarget` 里排除同类）；</li>
     *   <li>普通怪的**索敌**只收玩家（`scanTarget`），它拿到"怪"目标**只有反击**这一条路
     *       （原版同：`character.cpp:5998/6005`）—— 而能打到它、从而触发反击的只有召唤物。</li>
     * </ul>
     * 两边都不是召唤物是**不该出现**的（怪打怪、且都不是召唤物）—— 那就报 error，不静默（AGENTS #12）：
     * 那种情况会被 `MonsterAOI.reconcile` 的"DEAD 却没有死亡负载"自检再抓一次，
     * 然后由 decay 路径收走，所以既不会卡住也不会被吞掉。
     *
     * <p>
     * 显示侧**客户端零改动**：用怪→玩家那条 `S2C_Damage`（`targetId` = 被打的实体 id，
     * 带权威 `currentHp`），客户端的 `applyUnitHp`/`applyMonsterHit` 本来就认怪物血量，
     * 而召唤物也是 `monsters` 表里的实体 ⇒ 打怪飘字、召唤物自己掉血都自动成立。
     */
    private void resolveMonsterVsMonster(Monster attacker, Monster defender) {
        DamageResult result = damageCalculator.calculateMonsterToMonster(
            attacker.combatStats(), defender.combatStats());

        int mapId = defender.getMapId();
        float bx = (float) defender.getX();
        float bz = (float) defender.getZ();

        if (result.isMissed()) {
            log.info("[MonsterAI] {}#{} ATK {} -> MISS, interval={}ms",
                attacker.getName(), attacker.getId(), labelOf(defender), attacker.getAttackIntervalMs());
            messageSender.broadcastToArea(mapId, bx, bz, 50,
                ServerMessage.newBuilder()
                    .setDamage(S2C_Damage.newBuilder()
                        .setTargetId(defender.getId())
                        .setDamage(0)
                        .setCurrentHp(defender.getHp())
                        .setMissed(true)
                        .build())
                    .build());
            attacker.setLastBroadcastAnim(-1);   // 下一刀仍广播攻击动作，玩家看得到挥空
            return;
        }

        int newHp = Math.max(0, defender.getHp() - result.getFinalDamage());
        defender.setHp(newHp);

        log.debug("[MonsterAI] {}#{} ATK {} dmg={} ({}->{})",
            attacker.getName(), attacker.getId(), labelOf(defender),
            result.getFinalDamage(), newHp + result.getFinalDamage(), newHp);

        messageSender.broadcastToArea(mapId, bx, bz, 50,
            ServerMessage.newBuilder()
                .setDamage(S2C_Damage.newBuilder()
                    .setTargetId(defender.getId())
                    .setDamage(result.getFinalDamage())
                    .setCurrentHp(newHp)
                    .build())
                .build());

        // 受击反击：被谁打就回头打谁（与玩家那条对称 —— Neutral 受击也反击）
        if (defender.getNature() == 0
            || (defender.getTargetPlayerId() == null && defender.getTargetMonsterId() == null)) {
            setTargetMonster(defender, attacker, attacker.getX(), attacker.getZ());
        }

        // 强制下一轮攻击广播重发（客户端每刀都能看到攻击动作）
        attacker.setLastBroadcastAnim(-1);

        if (newHp <= 0) {
            if (defender.isSummon()) {
                // 召唤物被打死：**不给任何人经验/掉落**（它只是主人的一块肉，模板本身 exp=0）
                combatService.killSummon(defender, attacker.getId());
            } else if (attacker.isSummon()) {
                // 召唤物打死了怪 → 经验/掉落记在**主人**头上（原版把主人 serial 写在 `Next_Exp` 上的语义）
                combatService.creditSummonKill(defender, attacker);
            } else {
                log.error("[MonsterAI] 怪打死了怪：{}#{} → {}#{}（不该出现 —— 目标来源只有"
                    + "「召唤物打怪」与「怪打玩家/召唤物」两条，请查是谁塞的目标）",
                    attacker.getName(), attacker.getId(), defender.getName(), defender.getId());
            }
        }
    }

    // ======== 工具 ========

    private double loseRangeOf(Monster monster) {
        double sight = monster.getViewsight();
        return Math.max(sight <= 0 ? AIConstants.MIN_LOSE_RANGE : sight, AIConstants.MIN_LOSE_RANGE);
    }

    private double distXZ(Monster monster, PlayerEntity entity) {
        return distXZ(monster, entity.getX(), entity.getZ());
    }

    /** 怪物↔怪物、怪物↔坐标 的水平距离（与玩家那条同一个算法）。 */
    private double distXZ(Monster monster, Monster other) {
        return distXZ(monster, other.getX(), other.getZ());
    }

    private double distXZ(Monster monster, double x, double z) {
        double dx = monster.getX() - x;
        double dz = monster.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String targetName(PlayerEntity entity) {
        if (entity == null) return "?";
        Player p = entity.getPlayer();
        if (p != null && p.getName() != null) return p.getName();
        PlayerSession s = entity.getSession();
        return s != null && s.getCharacterName() != null ? s.getCharacterName() : String.valueOf(entity.getCharId());
    }

    private static String labelOf(Monster monster) {
        return monster == null ? "?" : monster.getName() + "#" + monster.getId();
    }

    private void logState(Monster monster, MonsterState from, MonsterState to, String reason) {
        log.debug("[MonsterAI] {}#{} state {} -> {} ({}) at ({},{})",
            monster.getName(), monster.getId(), from, to, reason,
            (int) monster.getX(), (int) monster.getZ());
    }
}
