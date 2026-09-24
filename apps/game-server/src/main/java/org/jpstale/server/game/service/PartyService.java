package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.model.Player;
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
    /** 待应答邀请：targetId -> 记录（inviterId + 发起时刻，60s 时效） */
    private final Map<Long, PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    private final AtomicLong partyIdGenerator = new AtomicLong(1);

    private record PendingInvite(long inviterId, long at) {}

    /** 邀请有效期（毫秒）——超时后接受视为无邀请（EU 客户端弹窗 1400 帧超时同语义，我们以服务端为准） */
    private static final long INVITE_TTL_MS = 60_000;

    /** 经验/金币分享距离（世界单位）——NSPT {@code PARTY_GETTING_DIST = 18*64}；与客户端
     *  {@code WorldMap.PARTY_NEAR_DIST2=(17*64)^2} 同一单位系 */
    public static final double SHARE_DIST = 18 * 64;
    /** EU{@code unitserver.cpp:616} Normal 模式经验总量%：180 + 80×(人数-2)，2人180% → 6人500% */
    private static final int EXP_PERCENT_NORMAL_BASE = 180;
    private static final int EXP_PERCENT_NORMAL_PER = 80;
    /** EU{@code :618} Hunt 模式：80 + 20×(人数-2)，低经验换多掉落（掉落侧在 OnSetDrop，本期未挂） */
    private static final int EXP_PERCENT_HUNT_BASE = 80;
    private static final int EXP_PERCENT_HUNT_PER = 20;
    /** 邀请等级差门槛（EU {@code CPartyHandler.cpp:66-73} / NSPT 一致） */
    private static final int INVITE_LEVEL_DIFF = 10;

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
        Party inviterParty = partyOf(inviterId);
        if (inviterParty != null && inviterParty.getLeaderId() != inviterId) {
            sendError(inviterId, "chat.party.leaderOnly");
            return;
        }
        if (playerPartyMap.containsKey(targetId)) {
            sendError(inviterId, "chat.party.alreadyInParty");
            return;
        }
        if (inviterParty != null && inviterParty.isFull()) {
            sendError(inviterId, "chat.party.full");
            return;
        }
        // 等级差 ≤10（EU 取双方"各自队伍平均等级"，我们一期取"邀请者本人或其全队平均"）
        Player inviter = playerService.byId(inviterId);
        if (inviter == null) {
            return;
        }
        int inviterLevel = inviterParty != null ? avgLevel(inviterParty) : inviter.getLevel();
        if (Math.abs(inviterLevel - targetPlayer.getLevel()) > INVITE_LEVEL_DIFF) {
            sendError(inviterId, "chat.party.levelDiff");
            return;
        }
        pendingInvites.put(targetId, new PendingInvite(inviterId, System.currentTimeMillis()));
        ServerMessage msg = ServerMessage.newBuilder()
            .setPartyInvite(S2C_PartyInvite.newBuilder()
                .setPartyId(inviterParty != null ? inviterParty.getId() : 0)
                .setInviterId(inviterId)
                .setInviterName(inviter.getName())
                .build())
            .build();
        messageSender.sendToPlayer(targetId, msg);
        log.info("[Party] invite: {} -> {}", inviter.getName(), targetPlayer.getName());
    }

    /** 接受邀请（C2S_PartyAccept{inviter_id}）。创建/加入的归属判定照 EU PacketJoinParty。 */
    public void accept(long playerId, long inviterId) {
        if (playerPartyMap.containsKey(playerId)) {
            sendError(playerId, "chat.party.alreadyInParty");
            return;
        }
        PendingInvite invite = pendingInvites.remove(playerId);
        if (invite == null || invite.inviterId() != inviterId
            || System.currentTimeMillis() - invite.at() > INVITE_TTL_MS) {
            sendError(playerId, "chat.party.noInvite");
            return;
        }
        Player inviter = playerService.byId(inviterId);
        if (inviter == null || !isOnline(inviterId)) {
            sendError(playerId, "chat.party.inviteExpired");
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
                sendError(playerId, "chat.party.full");
                return;
            }
            playerPartyMap.put(playerId, inviterParty.getId());
            inviterParty.getMemberIds().add(playerId);
            broadcastUpdate(inviterParty);
            log.info("[Party] {} joined {}", playerName(playerId), inviterParty.getId());
        }
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
            ? EXP_PERCENT_HUNT_BASE + EXP_PERCENT_HUNT_PER * (members.size() - 2)
            : EXP_PERCENT_NORMAL_BASE + EXP_PERCENT_NORMAL_PER * (members.size() - 2);
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
        double dist2 = SHARE_DIST * SHARE_DIST;
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
                if (dx * dx + dz * dz <= SHARE_DIST * SHARE_DIST) {
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
        ServerMessage msg = ServerMessage.newBuilder()
            .setError(S2C_Error.newBuilder()
                .setErrorCode(CommonProto.ErrorCode.PARTY_ERROR)
                .setKey(key)
                .build())
            .build();
        messageSender.sendToPlayer(playerId, msg);
    }
}
