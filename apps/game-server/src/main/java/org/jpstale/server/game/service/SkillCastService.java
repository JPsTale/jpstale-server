package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.DamageResult;
import org.jpstale.common.service.model.MonsterStats;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.stat.DamageCalculator;
import org.jpstale.common.service.stat.PlayerStatCalculator;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.common.enums.skill.SkillIds;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_AoeAttack;
import org.jpstale.server.proto.base.S2C_AttackResult;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 技能施法的**服务端权威编排**（设计文档 D6/D7；§9 P4，先 pikeman 三条）。
 *
 * <p><b>与原版的分工</b>：原版"选敌与段数"在客户端（Damage.cpp 的 dm_*），数值/状态在服务端
 * （Svr_Damge.cpp 的 128 case）；我们是服务端权威 ⇒ 选敌也收上来（D7），客户端只报
 * "放哪个技能、打哪只怪"。技能效果**显式登记在代码里**（AGENTS #16 / D6：不为效果建表），
 * 每条 = 逐字出处 + 数值取生成物参数表（{@link SkillDataRegistry}）。
 *
 * <p><b>本批覆盖</b>（pikeman 一转三条；其余技能仍是"当普攻结算"的旧路，
 * 逐批迁入并在册 ⑥ 段销账）：
 * <ul>
 *   <li>{@link SkillIds#PIKE_WIND Pike Wind} —— 以己为中心的圆形 AoE，**必中**，
 *       伤害 = {@code rand(Pike_Wind_Damage[p][0..1])}（**表值直掷，替换**攻击力），
 *       半径 = {@code Pike_Wind_Push_Lenght[p]}，命中者被**击退**（AttackState=1）；
 *       逐字 {@code SkillSub.cpp:105-129}（选敌）/ {@code Svr_Damge.cpp:2127-2203}（击退）。</li>
 *   <li>{@link SkillIds#CRITICAL_HIT Critical Hit} —— 单体 2 段（MotionLoop=2），
 *       每段暴击率加 {@code Critical_Hit_Critical[p]}；伤害走普攻公式（无 Power 加成）。</li>
 *   <li>{@link SkillIds#JUMPING_CRASH Jumping Crash} —— 单体 1 段，
 *       伤害 = 攻击力 × (1 + {@code Jumping_Crash_Damage[p]/100})，目标为恶魔系再 +30%
 *       （逐字 {@code Svr_Damge.cpp:2830-2836}；⚠ 恶魔 +30% 不是 skillData desc 写的 +100%）。</li>
 * </ul>
 *
 * <p><b>已知缺口（显式登记，不静默）</b>：技能 CD 未实现（公式依赖熟练度增长机制，P1 只存值），
 * 见文档 §10 未决。怪物种族数据源 = {@code monsterlist.propertymon}（用户 2026-09-24 指认），
 * 经 {@code MonsterSpawnService#broodOf} 映射到 {@link Monster.Brood}。
 */
@Slf4j
@Service
public class SkillCastService {

    /** 武器族码（idcode 高 16 位）——Jumping Crash 的可用武器门（册 ② 段：斧/枪/剑）。 */
    private static final int FAMILY_AXE = 0x0101;
    private static final int FAMILY_SPEAR = 0x0105;
    private static final int FAMILY_SWORD = 0x0107;

    @Autowired
    private SkillDataRegistry skillData;

    @Autowired
    private DamageCalculator damageCalculator;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private EntityRegistry entityRegistry;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private BattleLogService battleLogService;

    /** 死亡入口（handleMonsterDeath，同包包私有共用） */
    @Autowired
    private CombatService combatService;

    /** 受击反击（与普攻同口径）；@Lazy 防 AI 与战斗的循环依赖（CombatService 同款） */
    @Lazy
    @Autowired
    private AiEngine aiEngine;

    /** 一次施法的裁决：OK 之外的拒绝原因（调用方记日志）。 */
    public enum CastReject {
        NOT_LEARNED, NOT_ENOUGH_MP, NO_TARGET, BAD_SKILL,
    }

    /** AoE 命中者的结算明细（伤害/击退），供测试与日志。 */
    public record HitTarget(long monsterId, int damage, boolean critical, boolean missed, boolean knockedBack) {}

    /** 一次施法的完整结果（测试可见）。 */
    public record CastResult(int mpCost, List<HitTarget> hits, float aoeRadius) {}

    /**
     * 施法主入口：校验 → 扣 MP → 编排效果 → 结算并广播。
     *
     * @return 结果；null = 被拒绝（原因见日志；拒绝时**不扣 MP、不广播**）
     */
    public CastResult cast(Player player, int skillId, long targetId) {
        // —— 身份解析：skillId 必须是本职业登记过的技能（数字 id 的职业段校验，§4.6.1）——
        if ((skillId >> 16) != player.getJob()) {
            log.info("[Skill] {} 放 {} 拒绝：非本职业", player.getName(), Integer.toHexString(skillId));
            return null;
        }
        // —— 已学门（原版 sinCheckSkillUseOk 的职业组+MP；"已学"我们加：没学的技能不该有效果）——
        int point = player.getPropInt(SkillKeys.point(skillId));
        if (point < 1) {
            log.info("[Skill] {} 放 {} 拒绝：未学（point=0）", player.getName(), Integer.toHexString(skillId));
            return null;
        }
        int idx = point - 1;   // 参数表下标（0 基）；原版 [Point] 在服务端已归零，同一口径

        PlayerSession session = playerService.sessionOf(player);
        PlayerEntity self = session != null ? session.getEntity() : null;
        if (self == null) {
            return null;
        }

        if (skillId == SkillIds.PIKE_WIND.id()) {
            return castPikeWind(player, self, idx);
        }
        if (skillId == SkillIds.CRITICAL_HIT.id()) {
            return castCriticalHit(player, self, targetId, idx);
        }
        if (skillId == SkillIds.JUMPING_CRASH.id()) {
            return castJumpingCrash(player, self, targetId, idx);
        }
        return null;   // 未迁入的技能：调用方保持旧路（当普攻结算）
    }

    /* ────────────── Pike Wind：以己为中心 AoE + 必中 + 击退 ────────────── */

    private CastResult castPikeWind(Player player, PlayerEntity self, int idx) {
        // 表值直读（生成物 = sinSkill_Info.cpp:180-181 逐字）；Pike_Wind_Damage 是 **[10][2]**（min/max），
        // 必须走 table2d —— table1d 对二维表会直接抛（2026-09-24 实测踩过：死代码行导致 cast 整体失败）
        double[][] dmg2 = skillData.table2d("Pike_Wind_Damage");
        double[] radiusTable = skillData.table1d("Pike_Wind_Push_Lenght");
        if (dmg2 == null || radiusTable == null || idx >= dmg2.length || idx >= radiusTable.length) {
            log.error("[Skill] Pike Wind 参数表缺失（idx={}）", idx);
            return null;
        }
        int mpCost = mpCostOf("Pike_Wind_UseMana", idx);
        if (mpCost < 0 || player.getMp() < mpCost) {
            log.info("[Skill] {} Pike Wind 拒绝：MP 不足（需 {} 有 {}）", player.getName(), mpCost, player.getMp());
            return null;
        }

        // 半径：Push_Lenght 是**原版游戏单位**；我们实体坐标同为世界单位，直接比较（AttackRange 同口径）
        float radius = (float) radiusTable[idx];

        // 选敌：以己为中心的圆，同图、存活、非召唤物；**必中**（dm_SelectRange(…, FALSE)）
        List<Monster> targets = new ArrayList<>();
        for (Monster m : entityRegistry.allMonsters()) {
            if (!m.isAlive() || m.isSummon()) {
                continue;
            }
            double dx = m.getX() - self.getX();
            double dz = m.getZ() - self.getZ();
            if (dx * dx + dz * dz <= (double) radius * radius) {
                targets.add(m);
            }
        }

        // 广播 AoE 圈（S2C_AoeAttack 首次启用）：表现层拿圆心+半径画特效
        messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder()
                        .setAoeAttack(S2C_AoeAttack.newBuilder()
                                .setCasterId(player.getId())
                                .setSkillId(SkillIds.PIKE_WIND.id())
                                .setPosition(CommonProto.Position.newBuilder()
                                        .setX((float) self.getX()).setY((float) self.getY()).setZ((float) self.getZ()))
                                .setRange(radius))
                        .build());

        player.setMp(player.getMp() - mpCost);
        playerService.sendPlayerStatus(playerService.sessionOf(player), player);

        List<HitTarget> hits = new ArrayList<>(targets.size());
        for (Monster m : targets) {
            int power = randBetween(dmg2[idx][0], dmg2[idx][1]);
            DamageResult r = damageCalculator.calculatePlayerToMonster(player, m.combatStats(), power);
            applyDamage(player, self, m, r, radius);
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), !r.isMissed()));
        }
        log.info("[Skill] {} Pike Wind p{} 半径 {} 命中 {} 目标，MP-{}",
                player.getName(), idx + 1, radius, hits.size(), mpCost);
        return new CastResult(mpCost, hits, radius);
    }

    /* ────────────── Critical Hit：单体 2 段 + 暴击率加成 ────────────── */

    private CastResult castCriticalHit(Player player, PlayerEntity self, long targetId, int idx) {
        double[] critTable = skillData.table1d("Critical_Hit_Critical");   // sinSkill_Info.cpp:188
        if (critTable == null || idx >= critTable.length) {
            log.error("[Skill] Critical Hit 参数表缺失（idx={}）", idx);
            return null;
        }
        Monster m = requireTarget(player, self, targetId);
        if (m == null) {
            return null;
        }
        int mpCost = mpCostOf("Critical_Hit_UseMana", idx);
        if (mpCost < 0 || player.getMp() < mpCost) {
            log.info("[Skill] {} Critical Hit 拒绝：MP 不足（需 {} 有 {}）", player.getName(), mpCost, player.getMp());
            return null;
        }

        player.setMp(player.getMp() - mpCost);
        playerService.sendPlayerStatus(playerService.sessionOf(player), player);

        // 2 段（册 §2.3：MotionLoop=2 ⇒ 两次结算）；每段暴击率 += Critical_Hit_Critical[p]
        // —— DamageCalculator 的暴击率是内部掷的 ⇒ 走"暴击率加成"形参（见 castDamage 的 critBonus）
        List<HitTarget> hits = new ArrayList<>(2);
        int critBonus = (int) critTable[idx];
        for (int seg = 0; seg < 2; seg++) {
            DamageResult r = castDamage(player, m, 0, critBonus);
            applyDamage(player, self, m, r, 0);
            hits.add(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false));
        }
        log.info("[Skill] {} Critical Hit p{} 2 段（暴击+{}）打 {}#{}，MP-{}",
                player.getName(), idx + 1, critBonus, m.getName(), targetId, mpCost);
        return new CastResult(mpCost, hits, 0);
    }

    /* ────────────── Jumping Crash：单体 1 段 + Power 百分比 + 恶魔加成 ────────────── */

    private CastResult castJumpingCrash(Player player, PlayerEntity self, long targetId, int idx) {
        double[] dmgTable = skillData.table1d("Jumping_Crash_Damage");   // 册 §2.4：sinSkill_Info.cpp 逐字
        if (dmgTable == null || idx >= dmgTable.length) {
            log.error("[Skill] Jumping Crash 参数表缺失（idx={}）", idx);
            return null;
        }
        Monster m = requireTarget(player, self, targetId);
        if (m == null) {
            return null;
        }
        int mpCost = mpCostOf("Jumping_Crash_UseMana", idx);
        if (mpCost < 0 || player.getMp() < mpCost) {
            log.info("[Skill] {} Jumping Crash 拒绝：MP 不足（需 {} 有 {}）", player.getName(), mpCost, player.getMp());
            return null;
        }

        player.setMp(player.getMp() - mpCost);
        playerService.sendPlayerStatus(playerService.sessionOf(player), player);

        // 伤害 = Power + Power×Jumping_Crash_Damage[p]/100（Power += Power×表% 的加成型，不是替换）
        int power = statCalculator.baseAttack(player)[0]
                + (statCalculator.baseAttack(player)[1] - statCalculator.baseAttack(player)[0]) / 2;
        int boosted = power + power * (int) dmgTable[idx] / 100;
        // 恶魔系 +30%（Svr_Damge.cpp:2834 逐字；⚠ 30 不是 desc 写的 100）
        if (m.getBrood() == Monster.Brood.DEMON) {
            boosted += boosted * 30 / 100;
        }
        DamageResult r = damageCalculator.calculatePlayerToMonster(player, m.combatStats(), boosted);
        applyDamage(player, self, m, r, 0);
        log.info("[Skill] {} Jumping Crash p{} power={} boosted={} 打 {}#{}（brood={}），MP-{}",
                player.getName(), idx + 1, power, boosted, m.getName(), targetId, m.getBrood(), mpCost);
        return new CastResult(mpCost,
                List.of(new HitTarget(m.getId(), r.getFinalDamage(), r.isCritical(), r.isMissed(), false)), 0);
    }

    /* ────────────── 共用件 ────────────── */

    /** MP 表值（`<技能>_UseMana[point]`）；表缺失 ⇒ -1（显式失败，不静默按 0）。 */
    private int mpCostOf(String tableName, int idx) {
        double[] t = skillData.table1d(tableName);
        if (t == null || idx >= t.length) {
            log.error("[Skill] MP 表缺失：{}", tableName);
            return -1;
        }
        return (int) t[idx];
    }

    /** 单目标校验：存在/存活/非召唤物/距离（≤ 武器攻击距离；技能不改变射程，册 ② 段无射程表）。 */
    private Monster requireTarget(Player player, PlayerEntity self, long targetId) {
        Monster m = entityRegistry.findMonster(targetId);
        if (m == null || !m.isAlive()) {
            log.info("[Skill] {} 目标 {} 不存在或已死", player.getName(), targetId);
            return null;
        }
        if (m.isSummon()) {
            log.info("[Skill] {} 目标 {} 是召唤物 ⇒ 拒绝", player.getName(), targetId);
            return null;
        }
        double dx = self.getX() - m.getX();
        double dz = self.getZ() - m.getZ();
        double range = attackRangeOf(player);
        if (dx * dx + dz * dz > range * range) {
            log.info("[Skill] {} 目标 {} 超距（>{}）", player.getName(), targetId, range);
            return null;
        }
        return m;
    }

    /** 攻击距离（武器射程）：主手武器 shooting_range，无武器用近战徒手值（与 CombatService 同口径来源）。 */
    private double attackRangeOf(Player player) {
        return statCalculator.stats(player).shootingRange;
    }

    /** 技能伤害结算：skillDamage>0 时**替换**基础攻击（既有语义），critBonus 加进暴击率掷点。 */
    private DamageResult castDamage(Player player, Monster m, int skillDamage, int critBonus) {
        return damageCalculator.calculatePlayerToMonster(player, m.combatStats(), skillDamage, critBonus);
    }

    /**
     * 结算一条伤害：扣血/死亡 + S2C_AttackResult 广播 + 仇恨（与 CombatService.playerAttackMonster 同口径）。
     *
     * @param knockbackDist 击退距离（世界单位）= 原版 AttackSize（册逐字："AttackSize 装的是推离长度"，
     *                      即 Pike_Wind_Push_Lenght 全值 70..120）；0 = 不击退。
     *                      瞬移语义照原版（服务端直改怪坐标，客户端位置同步一跳）；不做穿墙检测（原版亦未做）。
     */
    private void applyDamage(Player player, PlayerEntity self, Monster m, DamageResult r, float knockbackDist) {
        S2C_AttackResult.Builder ar = S2C_AttackResult.newBuilder()
                .setAttackerId(player.getId())
                .setTargetId(m.getId())
                .setDamage(r.getFinalDamage())
                .setIsCritical(r.isCritical())
                .setHitIndex(0);
        if (r.isMissed()) {
            ar.setMissed(true);
            messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), AOIManager.VIEW_RANGE,
                    ServerMessage.newBuilder().setAttackResult(ar.build()).build());
            return;
        }
        ar.setMissed(false);
        m.setHp(m.getHp() - r.getFinalDamage());
        battleLogService.playerDealtDamage(playerService.sessionOf(player), m.getName(),
                r.getFinalDamage(), r.isCritical());

        // 受击反击（Evil 无目标时；Neutral 受击也反击）——与普攻同口径（CombatService.playerAttackMonster）
        if (m.getNature() == 0 || m.getTargetPlayerId() == null) {
            aiEngine.setTargetPlayer(m, self, self.getX(), self.getZ());
        }

        // 击退（AttackState=1），逐字 `Svr_Damge.cpp:2143-2172`（2026-09-24 实测方向反了才回头
        // 读全 2163-2172 —— 之前只读向量计算那三行就臆断了方向，教训：位移行不读不写）：
        //   ang2 = 怪→施法者 的角；ang = ang2+180°（转身背对玩家）；MoveAngle(dist)；再转回来。
        //   dist = AttackSize − 与施法者的水平距离 ⇒ **越近推得越远**；已在范围外（≤0）不推。
        //   两条门：|Δy|>100 或水平距>800 ⇒ 不推。
        // 方向 = 远离施法者（服务端权威位移，表现走怪物位置同步——主循环 broadcastMove 检出即下发）。
        if (knockbackDist > 0 && m.isAlive()) {
            double dx = self.getX() - m.getX();
            double dy = self.getY() - m.getY();
            double dz = self.getZ() - m.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dy) <= 100 && horizontal <= 800) {
                double dist = knockbackDist - horizontal;
                if (dist > 0) {
                    // -dx/-dz = 从施法者指向怪 = 远离方向
                    m.moveTo(m.getX() - dx, m.getY(), m.getZ() - dz, dist);
                }
            }
        }

        boolean died = !m.isAlive();
        if (died) {
            combatService.handleMonsterDeath(m, player);
        }
        messageSender.broadcastToArea(self.getMapId(), (float) self.getX(), (float) self.getZ(), AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder().setAttackResult(ar.build()).build());
    }

    /** [min,max] 含端点随机（原版 GetRandomPos）。 */
    private static int randBetween(double min, double max) {
        return (int) (Math.round(min) + ThreadLocalRandom.current().nextInt((int) Math.round(max - min) + 1));
    }
}
