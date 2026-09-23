package org.jpstale.server.game.skill;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.common.service.props.SkillKeys;
import org.jpstale.common.service.skill.SkillBindRules;
import org.jpstale.common.service.skill.SkillDataRegistry;
import org.jpstale.common.service.skill.SkillRules;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.service.GoldService;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.game.service.SkillPointService;
import org.jpstale.server.proto.base.C2S_SetSkillBinding;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_Error;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 技能点：学/升级技能 · 洗点 · 改技能绑定（拳位 / F1~F8）。
 *
 * <p>判定全在 {@link SkillRules} / {@link SkillBindRules}（唯一判定处），求值与写入全在
 * {@link SkillPointService}；这里只做编排 —— 扣钱走唯一金库通道（它顺带整行落库 + 推状态，
 * 于是 props 里刚写的技能等级一起落库），然后推技能表。失败一律回 `skill.op.*` / `skill.bind.*`
 * 原因码（客户端按前缀决定是否回滚乐观 UI）。
 */
@Slf4j
@Component
public class SkillNetworkHandler {

    private final SkillPointService skillPoints;
    private final GoldService goldService;
    private final PlayerService playerService;
    private final SkillDataRegistry skillData;

    @Autowired
    public SkillNetworkHandler(SkillPointService skillPoints, GoldService goldService,
                               PlayerService playerService, SkillDataRegistry skillData) {
        this.skillPoints = skillPoints;
        this.goldService = goldService;
        this.playerService = playerService;
        this.skillData = skillData;
    }

    /** 学/升级**一级**：判定 → 扣钱 → 写技能等级（在 learn 里写的）→ 回推技能表。 */
    @GamePacketHandler(ClientMessage.LEARN_SKILL_FIELD_NUMBER)
    public void handleLearnSkill(PlayerSession session, ClientMessage message) {
        Player p = playerService.requirePlayer(session);
        if (p == null) {
            return;
        }
        int skillId = message.getLearnSkill().getSkillId();
        SkillPointService.LearnResult r = skillPoints.learn(p, skillId);
        if (!r.ok()) {
            sendErrorKey(session, "skill.op." + r.reason().key());
            return;
        }
        if (goldService.add(session, p, -r.cost(), "skill_learn") != GoldService.Result.OK) {
            // 钱门在判定里已经过了，走到这里只可能是"这一瞬间钱被别人花掉" —— 仍必须把刚写的
            // 那一级退回去，否则就是白得一级（回滚是显式的，不是静默忽略）
            skillPoints.undoLearn(p, skillId);
            log.error("[Skill] {} 学 {} 扣钱被拒，已退回刚学的那一级", p.getName(), SkillKeys.describe(skillId));
            sendErrorKey(session, "skill.op." + SkillRules.Reason.NO_GOLD.key());
            return;
        }
        // 状态推送（含两个技能点字段）已由金库通道发过，这里只补技能表 + 绑定表（同一批时机）
        skillPoints.sendSkillTables(session, p);
        log.info("[Skill] {} 学/升 {} 花 {} 钱", p.getName(), SkillKeys.describe(skillId), r.cost());
    }

    /** 洗点：清等级/熟练度（守卫 = 会话内存标记）→ 落库 + 回推状态与技能表。 */
    @GamePacketHandler(ClientMessage.RESET_SKILL_POINTS_FIELD_NUMBER)
    public void handleResetSkillPoints(PlayerSession session, ClientMessage message) {
        Player p = playerService.requirePlayer(session);
        if (p == null) {
            return;
        }
        SkillRules.Reason reason = skillPoints.reset(p);
        if (reason != SkillRules.Reason.OK) {
            sendErrorKey(session, "skill.op." + reason.key());
            return;
        }
        playerService.persistStats(p);                // 整行 UPDATE 是 props 唯一的落库路径
        playerService.sendPlayerStatus(session, p);   // 点数回到上限 + 面板刷新
        skillPoints.sendSkillTables(session, p);
    }

    /**
     * 改一个技能绑定（拳位 / F1~F8）。
     *
     * <p>判定全在 {@link SkillBindRules#judge}（唯一判定处：kind/index 越界、非本职业 id、
     * `useCode` 不允许该位置一律拒）；通过后写 props（内存）→ **`persistStats` 落库**（props 唯一的
     * 落库路径，AGENTS #25：`markDirty` 在本项目没有兑现方）→ 回推绑定表（客户端不做乐观更新）。
     *
     * <p>被拒一律回 `skill.bind.*` 原因码，**绝不静默忽略、也绝不写成 0**
     * —— 静默写 0 会把"客户端发了垃圾"演成"玩家自己解绑了"（AGENTS #12）。
     */
    @GamePacketHandler(ClientMessage.SET_SKILL_BINDING_FIELD_NUMBER)
    public void handleSetSkillBinding(PlayerSession session, ClientMessage message) {
        Player p = playerService.requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_SetSkillBinding req = message.getSetSkillBinding();
        SkillBindRules.Judged j = SkillBindRules.judge(skillData, p, req.getKind(), req.getIndex(),
                req.getSkillId());
        if (!j.ok()) {
            log.info("[SkillBind] {} 改绑定被拒：kind={} index={} skill={} 原因={}",
                    p.getName(), req.getKind(), req.getIndex(), SkillKeys.describe(req.getSkillId()),
                    j.reason());
            sendErrorKey(session, "skill.bind." + j.reason().key());
            return;
        }
        SkillBindRules.apply(p, j.kind(), j.index(), j.skillId());
        playerService.persistStats(p);
        skillPoints.sendSkillBindings(session, p);
        log.info("[SkillBind] {} 改绑定 {}（useCode={}）", p.getName(),
                SkillBindRules.describe(j.kind(), j.index(), j.skillId()), j.useCode());
    }

    /** 原因码（客户端 `locales` 的 `skill.op.*` / `skill.bind.*`）。 */
    private void sendErrorKey(PlayerSession session, String key) {
        session.send(ServerMessage.newBuilder()
            .setError(S2C_Error.newBuilder()
                .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                .setKey(key)
                .build())
            .build());
    }
}
