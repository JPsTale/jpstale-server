package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.common.codec.GameConstants;
import org.jpstale.server.common.enums.party.PartyAction;
import org.jpstale.server.common.enums.party.PartyMode;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.jpstale.server.game.model.Party;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组队服务（2026-09-24 按 docs/组队系统-源码分析.md §8 实现；用户裁定 D1-D7）。
 *
 * <p>形态对齐 EU {@code CPartyHandler}（两级消息：成员变动发全量名单 + 500ms 周期发动态数据），
 * 但**不做 Raid**（D3）。生命周期规则照 EU：邀请等级差 ≤10、断线即退队、队长离开自动顺延、
 * 剩 ≤2 人解散。经验分配照 EU {@code OnSendExp}：同图 + 分享距离内即有份（无伤害贡献要求）、
 * Normal/Hunt 双模式总量%、按与"加权平均队等级"的差折减。
 *
 * <p>与原版的两处有意偏差（都是收窄，不是兜底）：
 * ① EU 允许普通成员发起邀请并转发队长批准，一期我们**只允许队长（或未组队者）邀请**，
 *    成员邀请回 {@code chat.party.leaderOnly}；
 * ② 500ms 增量包发给**全部**其他成员（EU 只发给跨图/超距者以省带宽）——我们消息小、
 *    客户端队伍 HUD 无论可见与否都要 HP/buff，全发省掉"本地可见性判定"这层耦合。
 */
@Slf4j
@Service
public class PartyService {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private BuffStateService buffStateService;

    private final Map<Long, Party> parties = new ConcurrentHashMap<>();
    private final Map<Long, Long> playerPartyMap = new ConcurrentHashMap<>(); // playerId -> partyId
    /**
     * 待应答邀请/申请：**接收弹窗者 id** -> 记录（发起者 id + 方向 + 60s 时效）。
     *   · {@code joinRequest=false}（场景 1/5）：对方邀请**我**入队 → 我接受则入 inviter 的队；
     *   · {@code joinRequest=true}（场景 3/4）：**对方申请加入我的队** → 我（队长/队员）同意后，
     *     发起者入**我的**队（队员身份时还要经队长终审，见 accept）。
     */
    private final Map<Long, PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    /** 待队长批复：leaderId -> 记录（队员/目标/场景 + 60s 时效）——两层确认的第一层 */
    private final Map<Long, PendingRecommend> pendingRecommends = new ConcurrentHashMap<>();
    private final AtomicLong partyIdGenerator = new AtomicLong(1);

    private record PendingInvite(long inviterId, boolean joinRequest, long at) {}

    /** 队员推荐（场景2）/ 队员代申请人转呈（场景4）；{@code joinRequest=true} 表示申请人已确认要进队 */
    private record PendingRecommend(long memberId, long targetId, boolean joinRequest, long at) {}

    /** S2C_PartyRecommendAsk.stage：0 = 场景2（队员荐散人，批准后仍需目标确认） */
    private static final int RECOMMEND_STAGE_MEMBER_ONLY = 0;
    /** S2C_PartyRecommendAsk.stage：1 = 场景4（散人申请、队员已同意，批准即入队） */
    private static final int RECOMMEND_STAGE_REQUESTER_CONFIRMED = 1;

    // ==================== C2S 入口（PacketRouter 自动注册） ====================

    @GamePacketHandler(ClientMessage.PARTY_INVITE_FIELD_NUMBER)
    public void handlePartyInvite(PlayerSession session, ClientMessage message) {
        Long cid = session.getCharacterId();
        if (cid == null) {
            return;
        }
        invite(cid, message.getPartyInvite().getTargetId());
    }

    @GamePacketHandler(ClientMessage.PARTY_ACCEPT_FIELD_NUMBER)
    public void handlePartyAccept(PlayerSession session, ClientMessage message) {
        Long cid = session.getCharacterId();
        if (cid == null) {
            return;
        }
        accept(cid, message.getPartyAccept().getInviterId());
    }

    @GamePacketHandler(ClientMessage.PARTY_LEAVE_FIELD_NUMBER)
    public void handlePartyLeave(PlayerSession session, ClientMessage message) {
        Long cid = session.getCharacterId();
        if (cid == null) {
            return;
        }
        leave(cid);
    }

    @GamePacketHandler(ClientMessage.PARTY_ACTION_FIELD_NUMBER)
    public void handlePartyAction(PlayerSession session, ClientMessage message) {
        Long cid = session.getCharacterId();
        if (cid == null) {
            return;
        }
        C2S_PartyAction a = message.getPartyAction();
        action(cid, a.getAction(), a.getTargetId());
    }

    /**
     * 聊天命令 {@code //party <名字>} 的入口（原版 OnSever.cpp RecvCommand //party 同路；
     * 用户裁定**保留**这条原版手感入口，与目标窗"组队"按钮的专用包 C2S_PartyInvite 并存，
     * 走同一条校验链——只是这里按名在会话表中找目标）。
     */
    public void inviteByName(long inviterId, String targetName) {
        PlayerSession target = sessionManager.getSessionByCharacterName(targetName);
        if (target == null || target.getCharacterId() == null) {
            sendError(inviterId, "chat.party.targetOffline");
            return;
        }
        invite(inviterId, target.getCharacterId());
    }

    /** 邀请（C2S_PartyInvite{target_id}，D1：专门的包；与 //party 命令并存，同一条校验链） */
    public void invite(long inviterId, long targetId) {
        if (inviterId == targetId) {
            sendError(inviterId, "chat.party.cannotInviteSelf");
            return;
        }
        PlayerSession target = sessionManager.getSessionByCharacterId(targetId);
        Player targetPlayer = target != null && target.isPlaying() ? playerService.byId(targetId) : null;
        if (targetPlayer == null) {
            sendError(inviterId, "chat.party.targetOffline");
            return;
        }
        Player inviter = playerService.byId(inviterId);
        if (inviter == null) {
            return;
        }
        Party inviterParty = partyOf(inviterId);
        Party targetParty = partyOf(targetId);
        boolean inviterIsLeader = inviterParty != null && inviterParty.getLeaderId() == inviterId;
        boolean targetIsLeader = targetParty != null && targetParty.getLeaderId() == targetId;

        // 双方都有队：无任何弹窗（EU：AlreadyParty / 同队提示；我们不产生新流程）
        if (inviterParty != null && targetParty != null) {
            sendError(inviterId, inviterParty.getId() == targetParty.getId()
                ? "chat.party.sameParty" : "chat.party.alreadyInParty");
            return;
        }
        // 等级差（EU 取双方"各自队伍平均等级"）——发起时就拦，免得批到一半失败
        int inviterLevel = inviterParty != null ? avgLevel(inviterParty) : inviter.getLevel();
        int targetLevel = targetParty != null ? avgLevel(targetParty) : targetPlayer.getLevel();
        if (Math.abs(inviterLevel - targetLevel) > GameConstants.PARTY_INVITE_LEVEL_DIFF) {
            sendError(inviterId, "chat.party.levelDiff", Map.of("max", String.valueOf(GameConstants.PARTY_INVITE_LEVEL_DIFF)));
            return;
        }

        // ===== 场景 1：我是队长 → 对方散人 ⇒ 标准邀请（对方确认即入队）=====
        if (inviterIsLeader) {
            if (inviterParty.isFull()) {
                sendError(inviterId, "chat.party.full", Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
                return;
            }
            sendStandardInvite(targetId, inviterId, inviterParty.getId());
            log.info("[Party] invite: leader {} -> {}", inviter.getName(), targetPlayer.getName());
            return;
        }

        // ===== 场景 2：我是队员 → 对方散人 ⇒ 两层：队长批准 → 转为队长名义的邀请 =====
        if (inviterParty != null) {
            if (inviterParty.isFull()) {
                sendError(inviterId, "chat.party.full", Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
                return;
            }
            long leaderId = inviterParty.getLeaderId();
            if (!isOnline(leaderId)) {
                sendError(inviterId, "chat.party.leaderOffline");
                return;
            }
            pendingRecommends.put(leaderId,
                new PendingRecommend(inviterId, targetId, false, System.currentTimeMillis()));
            messageSender.sendToPlayer(leaderId, ServerMessage.newBuilder()
                .setPartyRecommendAsk(S2C_PartyRecommendAsk.newBuilder()
                    .setMemberId(inviterId)
                    .setMemberName(inviter.getName())
                    .setTargetId(targetId)
                    .setTargetName(targetPlayer.getName())
                    .setStage(RECOMMEND_STAGE_MEMBER_ONLY)
                    .build())
                .build());
            sendSystem(inviterId, "chat.party.recommendSent",
                Map.of("leader", playerName(leaderId), "target", targetPlayer.getName()));
            log.info("[Party] recommend(场景2): member {} -> leader {} for target {}",
                inviter.getName(), playerName(leaderId), targetPlayer.getName());
            return;
        }

        // ===== 场景 3/4：我是散人 → 对方在队 ⇒ 入队申请（方向=joinRequest）=====
        // 场景 3：对方是队长 → 队长同意即入队（申请人自己发起的，不需要再确认自己）；
        // 场景 4：对方是队员 → 队员先同意，再转队长终审（EU 语义：队员不能擅自放人进队）。
        if (targetParty != null) {
            if (targetParty.isFull()) {
                sendError(inviterId, "chat.party.full", Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
                return;
            }
            pendingInvites.put(targetId,
                new PendingInvite(inviterId, true, System.currentTimeMillis()));
            messageSender.sendToPlayer(targetId, ServerMessage.newBuilder()
                .setPartyInvite(S2C_PartyInvite.newBuilder()
                    .setPartyId(targetParty.getId())
                    .setInviterId(inviterId)
                    .setInviterName(inviter.getName())
                    .setDirection(1)   // 1 = 申请加入你的队
                    .build())
                .build());
            log.info("[Party] join-request(场景{}): {} -> {}",
                targetIsLeader ? "3" : "4", inviter.getName(), targetPlayer.getName());
            return;
        }

        // ===== 场景 5：双方都无队 ⇒ 直接邀请（对方接受则发起者当队长）=====
        sendStandardInvite(targetId, inviterId, 0L);
        log.info("[Party] invite: {} -> {} (both solo)", inviter.getName(), targetPlayer.getName());
    }

    /**
     * 接受/同意（C2S_PartyAccept{inviter_id}）—— 按 pending 的**方向**分派（用户 2026-09-25 矩阵）：
     *   · joinRequest=false（场景 1/5）：我接受对方的邀请 ⇒ 我入**对方**的队（EU PacketJoinParty 同构）；
     *   · joinRequest=true（场景 3/4）：我同意对方的入队申请 ⇒ 对方入**我的**队；
     *     我是队员时（场景 4）不直接放人，转队长终审（pendingRecommends + S2C_PartyRecommendAsk）。
     */
    public void accept(long playerId, long inviterId) {
        if (playerPartyMap.containsKey(playerId) && pendingInvites.get(playerId) == null) {
            sendError(playerId, "chat.party.alreadyInParty");
            return;
        }
        PendingInvite invite = pendingInvites.remove(playerId);
        if (invite == null || invite.inviterId() != inviterId
            || System.currentTimeMillis() - invite.at() > GameConstants.PARTY_INVITE_TTL_MS) {
            sendError(playerId, "chat.party.noInvite");
            return;
        }
        Player inviter = playerService.byId(inviterId);
        if (inviter == null || !isOnline(inviterId)) {
            sendError(playerId, "chat.party.inviteExpired");
            return;
        }
        // ===== 入队申请（场景 3/4）：申请人入我的队 =====
        if (invite.joinRequest()) {
            Party myParty = partyOf(playerId);
            if (myParty == null) {
                sendError(playerId, "chat.party.noParty");
                return;
            }
            if (playerPartyMap.containsKey(inviterId)) {
                sendError(playerId, "chat.party.alreadyInParty");
                return;
            }
            if (myParty.isFull()) {
                sendError(playerId, "chat.party.full", Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
                return;
            }
            if (myParty.getLeaderId() != playerId) {
                // 场景 4：我是队员 ⇒ 转队长终审（两层模型的第二跳）
                long leaderId = myParty.getLeaderId();
                if (!isOnline(leaderId)) {
                    sendError(playerId, "chat.party.leaderOffline");
                    return;
                }
                pendingRecommends.put(leaderId,
                    new PendingRecommend(playerId, inviterId, true, System.currentTimeMillis()));
                messageSender.sendToPlayer(leaderId, ServerMessage.newBuilder()
                    .setPartyRecommendAsk(S2C_PartyRecommendAsk.newBuilder()
                        .setMemberId(playerId)
                        .setMemberName(playerName(playerId))
                        .setTargetId(inviterId)
                        .setTargetName(inviter.getName())
                        .setStage(RECOMMEND_STAGE_REQUESTER_CONFIRMED)
                        .build())
                    .build());
                sendSystem(inviterId, "chat.party.joinPending", Map.of("name", playerName(leaderId)));
                log.info("[Party] join-request(场景4): member {} agreed, awaiting leader {}",
                    playerName(playerId), playerName(leaderId));
                return;
            }
            // 场景 3：我是队长 ⇒ 同意即入队（申请人自己发起的申请，不需要再确认自己）
            joinExisting(myParty, inviterId);
            log.info("[Party] join-request(场景3) accepted: {} joined {}", inviter.getName(), myParty.getId());
            return;
        }
        Party inviterParty = partyOf(inviterId);
        if (inviterParty == null) {
            // 双方都无队：邀请发起者当队长（EU DoParty：pcLeader = 发请求的对方）
            Party party = new Party(partyIdGenerator.getAndIncrement(), inviterId);
            parties.put(party.getId(), party);
            playerPartyMap.put(inviterId, party.getId());
            playerPartyMap.put(playerId, party.getId());
            party.getMemberIds().add(playerId);
            broadcastUpdate(party);
            log.info("[Party] created {} by {} + {}", party.getId(), inviter.getName(),
                playerService.byId(playerId) != null ? playerService.byId(playerId).getName() : playerId);
        } else {
            if (inviterParty.isFull()) {
                sendError(playerId, "chat.party.full", Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
                return;
            }
            playerPartyMap.put(playerId, inviterParty.getId());
            inviterParty.getMemberIds().add(playerId);
            broadcastUpdate(inviterParty);
            log.info("[Party] {} joined {}", playerName(playerId), inviterParty.getId());
        }
    }

    /**
     * 队长批复（{@code C2S_PartyRecommendAnswer}）——两层确认的第一层收口：
     *   · stage=MEMBER_ONLY（场景2：队员荐散人）⇒ 批准后**转为队长名义的标准邀请**，目标还要确认；
     *   · stage=REQUESTER_CONFIRMED（场景4：散人申请、队员已同意）⇒ 批准即 **joinExisting**（申请人已确认）。
     * 拒绝一律只通知发起者（原版无通知；两层模型下这是他唯一的反馈）。
     */
    public void recommendAnswer(long leaderId, long memberId, long targetId, boolean accept) {
        PendingRecommend rec = pendingRecommends.remove(leaderId);
        if (rec == null || rec.memberId() != memberId || rec.targetId() != targetId
            || System.currentTimeMillis() - rec.at() > GameConstants.PARTY_INVITE_TTL_MS) {
            sendError(leaderId, "chat.party.noInvite");
            return;
        }
        Party party = partyOf(leaderId);
        if (party == null || party.getLeaderId() != leaderId) {
            sendError(leaderId, "chat.party.noParty");
            return;
        }
        if (!party.isMember(memberId)) {
            sendError(leaderId, "chat.party.notMember");
            return;
        }
        if (!accept) {
            Player target = playerService.byId(targetId);
            sendSystem(memberId, "chat.party.recommendRejected",
                Map.of("target", target != null ? target.getName() : String.valueOf(targetId)));
            log.info("[Party] recommend rejected by leader {}: member {} target {}", leaderId, memberId, targetId);
            return;
        }
        if (party.isFull()) {
            sendError(leaderId, "chat.party.full", Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
            return;
        }
        if (!isOnline(targetId) || playerPartyMap.containsKey(targetId)) {
            sendError(leaderId, "chat.party.targetOffline");
            return;
        }
        if (rec.joinRequest()) {
            // 场景 4 收口：申请人已确认要进队 ⇒ 批准即入队
            joinExisting(party, targetId);
            log.info("[Party] recommend approved(场景4): leader {} admitted {}", leaderId, targetId);
        } else {
            // 场景 2 收口：转为**队长名义**的标准邀请，等目标确认
            sendStandardInvite(targetId, leaderId, party.getId());
            log.info("[Party] recommend approved(场景2): leader {} invited target {} (recommended by {})",
                leaderId, targetId, memberId);
        }
    }

    @GamePacketHandler(ClientMessage.PARTY_RECOMMEND_ANSWER_FIELD_NUMBER)
    public void handlePartyRecommendAnswer(PlayerSession session, ClientMessage message) {
        Long cid = session.getCharacterId();
        if (cid == null) {
            return;
        }
        C2S_PartyRecommendAnswer a = message.getPartyRecommendAnswer();
        recommendAnswer(cid, a.getMemberId(), a.getTargetId(), a.getAccept());
    }

    /** 向目标发标准邀请（direction=0：邀请你入队）；partyId=0 表示"双方都无队，接受则发起者当队长" */
    private void sendStandardInvite(long targetId, long inviterId, long partyId) {
        pendingInvites.put(targetId, new PendingInvite(inviterId, false, System.currentTimeMillis()));
        messageSender.sendToPlayer(targetId, ServerMessage.newBuilder()
            .setPartyInvite(S2C_PartyInvite.newBuilder()
                .setPartyId(partyId)
                .setInviterId(inviterId)
                .setInviterName(playerName(inviterId))
                .setDirection(0)
                .build())
            .build());
    }

    /** 把 playerId 加入既有队伍（复判满员/未入队），并广播名单——场景 3/4 与 accept 的落地点 */
    private void joinExisting(Party party, long playerId) {
        if (party.isFull() || playerPartyMap.containsKey(playerId)) {
            sendError(party.getLeaderId(), "chat.party.full",
                Map.of("max", String.valueOf(GameConstants.PARTY_MAX_MEMBERS)));
            return;
        }
        playerPartyMap.put(playerId, party.getId());
        party.getMemberIds().add(playerId);
        broadcastUpdate(party);
        log.info("[Party] {} joined {}", playerName(playerId), party.getId());
    }

    /** 无参/带参系统提示（成功类回执，如"已向队长转达"） */
    private void sendSystem(long playerId, String key, Map<String, String> params) {
        messageSender.sendToPlayer(playerId, ServerMessage.newBuilder()
            .setSystemMessage(S2C_SystemMessage.newBuilder()
                .setKey(key)
                .putAllParams(params)
                .setTimestamp(System.currentTimeMillis())
                .build())
            .build());
    }

    /** 队伍动作（C2S_PartyAction）：Kick/Delegate/Disband 限队长（服务端校验，不信任客户端）。 */
    public void action(long playerId, int actionValue, long targetId) {
        PartyAction action = PartyAction.fromValue(actionValue);
        Party party = partyOf(playerId);
        if (action == PartyAction.LEAVE) {
            leave(playerId);
            return;
        }
        if (party == null) {
            sendError(playerId, "chat.party.noParty");
            return;
        }
        boolean leader = party.getLeaderId() == playerId;
        switch (action) {
            case KICK -> {
                if (!leader) {
                    sendError(playerId, "chat.party.leaderOnly");
                    return;
                }
                if (!party.isMember(targetId)) {
                    sendError(playerId, "chat.party.notMember");
                    return;
                }
                removeMember(party, targetId);
                log.info("[Party] {} kicked from {}", playerName(targetId), party.getId());
            }
            case DELEGATE -> {
                if (!leader) {
                    sendError(playerId, "chat.party.leaderOnly");
                    return;
                }
                if (!party.isMember(targetId)) {
                    sendError(playerId, "chat.party.notMember");
                    return;
                }
                party.setLeaderId(targetId);
                party.getMemberIds().remove(Long.valueOf(targetId));
                party.getMemberIds().add(0, targetId); // 队长恒首位
                broadcastUpdate(party);
                log.info("[Party] leader delegated to {} in {}", playerName(targetId), party.getId());
            }
            case DISBAND_PARTY -> {
                if (!leader) {
                    sendError(playerId, "chat.party.leaderOnly");
                    return;
                }
                disband(party);
                log.info("[Party] {} disbanded by leader", party.getId());
            }
            case CHANGE_MODE -> {
                if (!leader) {
                    sendError(playerId, "chat.party.leaderOnly");
                    return;
                }
                party.setMode(party.getMode() == PartyMode.NORMAL ? PartyMode.HUNT : PartyMode.NORMAL);
                broadcastUpdate(party);
                log.info("[Party] {} mode -> {}", party.getId(), party.getMode());
            }
            default -> {
                // NONE / 未知值：显式忽略（未知动作不执行任何事）
            }
        }
    }

    /** 离开/掉线退队（EU LeaveParty：队长走则自动顺延；剩 ≤2 人整队解散）。 */
    public void leave(long playerId) {
        Party party = partyOf(playerId);
        if (party == null) {
            return;
        }
        removeMember(party, playerId);
        log.info("[Party] {} left {}", playerName(playerId), party.getId());
    }

    // ==================== 战斗结算接入 ====================

    /**
     * 击杀经验的全队分摊（EU {@code unitserver.cpp:580-740} OnSendExp）。
     *
     * @return {@code null} = 击杀者未组队（走单人路径）；否则返回"应得经验成员 → 份额"表
     *         （**含击杀者**，按各自等级折减后可能为 0 份额——0 份额者不进表，即不涨经验）
     */
    public Map<Long, Long> distributeExp(Player killer, Monster monster, long baseExp) {
        Long partyId = playerPartyMap.get(killer.getId());
        if (partyId == null) {
            return null;
        }
        Party party = parties.get(partyId);
        if (party == null) {
            playerPartyMap.remove(killer.getId());
            return null;
        }
        int n = party.getMemberIds().size();
        if (n < 2) {
            return null;
        }
        List<Player> members = new ArrayList<>();
        for (Long id : party.getMemberIds()) {
            Player p = playerService.byId(id);
            if (p != null) {
                members.add(p);
            } else {
                log.warn("[Party] member {} of party {} 不在内存缓存（可能刚掉线）→ 本轮跳过", id, party.getId());
            }
        }
        if (members.size() < 2) {
            return null;
        }
        int percent = party.getMode() == PartyMode.HUNT
            ? GameConstants.PARTY_EXP_PERCENT_HUNT_BASE + GameConstants.PARTY_EXP_PERCENT_HUNT_PER * (members.size() - 2)
            : GameConstants.PARTY_EXP_PERCENT_NORMAL_BASE + GameConstants.PARTY_EXP_PERCENT_NORMAL_PER * (members.size() - 2);
        long perMember = baseExp * percent / 100 / members.size();
        // 加权平均队等级（EU GetWeighedAveragePartyLevel：Σ(lv_i / Σlv × lv_i)，高等级权重更大）
        double weighted = 0;
        long lvSum = 0;
        for (Player p : members) {
            lvSum += p.getLevel();
        }
        for (Player p : members) {
            weighted += ((double) p.getLevel() / lvSum) * p.getLevel();
        }
        int avg = (int) Math.round(weighted);
        double dist2 = GameConstants.PARTY_SHARE_DIST * GameConstants.PARTY_SHARE_DIST;
        Map<Long, Long> shares = new LinkedHashMap<>();
        for (Player p : members) {
            // 参战判定 = 同图 + 距击杀点 SHARE_DIST 内（EU :650 同图 + DISTANCE_MAX_PARTY；无伤害贡献要求）
            PlayerEntity e = playerService.entityOf(p);
            if (e == null || e.getMapId() != monster.getMapId()) {
                continue;
            }
            double dx = e.getX() - monster.getX();
            double dz = e.getZ() - monster.getZ();
            if (dx * dx + dz * dz > dist2) {
                continue;
            }
            // 个人折减：与加权平均队等级的差（EU GetExpLevelDiferenceVsPlayer :178-192）
            int diff = Math.abs(avg - p.getLevel());
            double factor = diff < 15 ? 1.0 : diff < 20 ? 0.75 : diff < 25 ? 0.5 : 0.25;
            long share = Math.round(perMember * factor);
            if (share > 0) {
                shares.put(p.getId(), share);
            }
        }
        return shares;
    }

    /**
     * 金币拾取分摊（D5：EU 无阈值一律均分，{@code itemserver.cpp:6661-6748}）。
     *
     * @return 拾取者组队时 = 距拾取者 SHARE_DIST 内的同队成员 → 各自份额（**余数归拾取者**，
     *         EU GetPartyMoney 同构）；未组队/无人分摊 = 仅拾取者拿全额。调用方逐份入账。
     */
    public Map<Long, Long> splitGold(PlayerEntity picker, long amount) {
        Long partyId = playerPartyMap.get(picker.getCharId());
        if (partyId == null) {
            return Map.of(picker.getCharId(), amount);
        }
        Party party = parties.get(partyId);
        if (party == null || party.getMemberIds().size() < 2) {
            return Map.of(picker.getCharId(), amount);
        }
        List<Long> inRange = new ArrayList<>();
        for (Long id : party.getMemberIds()) {
            if (id == picker.getCharId()) {
                inRange.add(id);
                continue;
            }
            PlayerEntity e = entityOf(id);
            if (e != null && e.getMapId() == picker.getMapId()) {
                double dx = e.getX() - picker.getX();
                double dz = e.getZ() - picker.getZ();
                if (dx * dx + dz * dz <= GameConstants.PARTY_SHARE_DIST * GameConstants.PARTY_SHARE_DIST) {
                    inRange.add(id);
                }
            }
        }
        if (inRange.size() < 2) {
            return Map.of(picker.getCharId(), amount);
        }
        int n = inRange.size();
        long per = amount / n;
        long remainder = amount % n; // 余数给拾取者（EU PartyMoney[0] = per + money % count）
        Map<Long, Long> shares = new LinkedHashMap<>();
        for (Long id : inRange) {
            shares.put(id, id == picker.getCharId() ? per + remainder : per);
        }
        return shares;
    }

    // ==================== 周期广播 ====================

    /** 500ms 动态数据广播（EU UpdatePartyData 的 500ms 节奏）。发给每个在线成员**除自己外**的全队数据。 */
    @Scheduled(fixedRate = 500)
    public void broadcastPlayUpdate() {
        // 退队自检：会话已消失 = 该角色已离开世界（断线/登出/回选角）→ 按 EU"断线即退队"处理。
        // 放在这里而不是 channelInactive，是因为离开世界的入口有 4 处，而会话消失是它们共同的后置事实。
        for (Party party : parties.values()) {
            List<Long> gone = null;
            for (Long id : party.getMemberIds()) {
                if (!isOnline(id)) {
                    if (gone == null) {
                        gone = new ArrayList<>();
                    }
                    gone.add(id);
                }
            }
            if (gone != null) {
                for (Long id : gone) {
                    log.info("[Party] member {} offline → auto leave party {}", id, party.getId());
                    removeMember(party, id);
                }
            }
        }
        for (Party party : parties.values()) {
            if (party.getMemberIds().size() < 2) {
                continue;
            }
            for (Long receiverId : party.getMemberIds()) {
                PlayerSession receiver = sessionManager.getSessionByCharacterId(receiverId);
                if (receiver == null || !receiver.isPlaying()) {
                    continue;
                }
                S2C_PartyPlayUpdate.Builder update = S2C_PartyPlayUpdate.newBuilder();
                for (Long id : party.getMemberIds()) {
                    if (id.equals(receiverId)) {
                        continue; // 自己的 HP/坐标本地权威，不回灌
                    }
                    Player p = playerService.byId(id);
                    PlayerEntity e = p != null ? playerService.entityOf(p) : null;
                    if (p == null || e == null) {
                        continue; // 刚掉线/未进场：本轮缺席，名单包（成员变动）才是权威
                    }
                    PartyMemberPlay.Builder m = PartyMemberPlay.newBuilder()
                        .setId(id)
                        .setLevel(p.getLevel())
                        .setHp(p.getHp())
                        .setMaxHp(p.getMaxHp())
                        .setMp(p.getMp())
                        .setMaxMp(p.getMaxMp())
                        .setMapId(e.getMapId())
                        .setX(e.getX())
                        .setZ(e.getZ());
                    m.addAllBuffs(buffStateService.build(p).getBuffsList());
                    update.addMembers(m);
                }
                messageSender.sendToPlayer(receiverId, ServerMessage.newBuilder()
                    .setPartyPlayUpdate(update.build())
                    .build());
            }
        }
    }

    /**
     * 组队聊天：广播给本队所有成员（含发送者自己）。
     * 未组队时给发送者回系统提示。
     */
    public void broadcastChat(long playerId, ServerMessage message) {
        Party party = partyOf(playerId);
        if (party == null) {
            ServerMessage err = ServerMessage.newBuilder()
                .setSystemMessage(S2C_SystemMessage.newBuilder()
                    .setKey("chat.party.noParty")
                    .setTimestamp(System.currentTimeMillis())
                    .build())
                .build();
            messageSender.sendToPlayer(playerId, err);
            return;
        }
        for (Long memberId : party.getMemberIds()) {
            messageSender.sendToPlayer(memberId, message);
        }
    }

    // ==================== 内部 ====================

    /** 广播全量名单（成员变动时）；空队时发送方收到空 members = 清窗信号。 */
    private void broadcastUpdate(Party party) {
        S2C_PartyUpdate.Builder update = S2C_PartyUpdate.newBuilder()
            .setPartyId(party.getId())
            .setMode(party.getMode().getValue());
        for (Long id : party.getMemberIds()) {
            Player p = playerService.byId(id);
            if (p == null) {
                log.warn("[Party] roster 成员 {} 无运行时数据（可能刚掉线）→ 本包缺席", id);
                continue;
            }
            update.addMembers(PartyMemberInfo.newBuilder()
                .setId(id)
                .setName(p.getName())
                .setClassId(p.getJob())
                .setLeader(id == party.getLeaderId()));
        }
        ServerMessage msg = ServerMessage.newBuilder().setPartyUpdate(update.build()).build();
        for (Long id : party.getMemberIds()) {
            messageSender.sendToPlayer(id, msg);
        }
    }

    /** 移除成员：被移除者收空名单清窗；剩 ≤2 人解散；队长走了自动顺延首位。 */
    private void removeMember(Party party, long playerId) {
        party.getMemberIds().remove(Long.valueOf(playerId));
        playerPartyMap.remove(playerId);
        boolean wasLeader = party.getLeaderId() == playerId;
        if (party.getMemberIds().size() <= 1) {
            disband(party);
            return;
        }
        if (wasLeader) {
            party.setLeaderId(party.getMemberIds().get(0)); // EU LeavePartyMaster：顺延成员[0]
        }
        // 被移除者收空名单（EU 给退出者发空 PacketUpdateParty，客户端据此清窗）
        messageSender.sendToPlayer(playerId, ServerMessage.newBuilder()
            .setPartyUpdate(S2C_PartyUpdate.newBuilder().build())
            .build());
        broadcastUpdate(party);
    }

    private void disband(Party party) {
        for (Long id : party.getMemberIds()) {
            playerPartyMap.remove(id);
            messageSender.sendToPlayer(id, ServerMessage.newBuilder()
                .setPartyUpdate(S2C_PartyUpdate.newBuilder().build())
                .build());
        }
        parties.remove(party.getId());
    }

    private Party partyOf(long playerId) {
        Long partyId = playerPartyMap.get(playerId);
        return partyId != null ? parties.get(partyId) : null;
    }

    private PlayerEntity entityOf(long playerId) {
        Player p = playerService.byId(playerId);
        return p != null ? playerService.entityOf(p) : null;
    }

    private boolean isOnline(long playerId) {
        PlayerSession s = sessionManager.getSessionByCharacterId(playerId);
        return s != null && s.isPlaying();
    }

    private String playerName(long playerId) {
        Player p = playerService.byId(playerId);
        return p != null ? p.getName() : String.valueOf(playerId);
    }

    private int avgLevel(Party party) {
        int sum = 0;
        int cnt = 0;
        for (Long id : party.getMemberIds()) {
            Player p = playerService.byId(id);
            if (p != null) {
                sum += p.getLevel();
                cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private void sendError(long playerId, String key) {
        sendError(playerId, key, Map.of());
    }

    /** 带命名参数的错误（如 levelDiff 的 {max}）——阈值是服务端配置，文案不得写死数值 */
    private void sendError(long playerId, String key, Map<String, String> params) {
        ServerMessage msg = ServerMessage.newBuilder()
            .setError(S2C_Error.newBuilder()
                .setErrorCode(CommonProto.ErrorCode.PARTY_ERROR)
                .setKey(key)
                .putAllParams(params)
                .build())
            .build();
        messageSender.sendToPlayer(playerId, msg);
    }
}
