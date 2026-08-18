package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Party;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组队服务
 */
@Slf4j
@Service
public class PartyService {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private GameMessageSender messageSender;

    private final Map<Long, Party> parties = new ConcurrentHashMap<>();
    private final Map<Long, Long> playerPartyMap = new ConcurrentHashMap<>(); // playerId -> partyId
    private final AtomicLong partyIdGenerator = new AtomicLong(1);

    /**
     * 邀请组队
     */
    public void invite(long inviterId, String targetName) {
        PlayerSession inviter = sessionManager.getSessionByCharacterId(inviterId);
        if (inviter == null) return;

        PlayerSession target = sessionManager.getSessionByCharacterName(targetName);
        if (target == null) {
            sendError(inviterId, "目标玩家不在线");
            return;
        }

        if (inviterId == target.getCharacterId()) {
            sendError(inviterId, "不能邀请自己");
            return;
        }

        // 检查邀请者是否已在队伍中
        Long inviterPartyId = playerPartyMap.get(inviterId);
        if (inviterPartyId != null) {
            Party party = parties.get(inviterPartyId);
            if (party != null && party.isFull()) {
                sendError(inviterId, "队伍已满");
                return;
            }
        }

        // 发送邀请
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setPartyInvite(MessageProto.S2C_PartyInvite.newBuilder()
                .setPartyId(inviterPartyId != null ? inviterPartyId : 0)
                .setInviterId(inviterId)
                .setInviterName(inviter.getCharacterName())
                .build())
            .build();
        messageSender.sendToPlayer(target.getCharacterId(), msg);

        log.info("Party invite: {} -> {}", inviter.getCharacterName(), targetName);
    }

    /**
     * 接受组队
     */
    public void accept(long playerId, long partyId) {
        PlayerSession player = sessionManager.getSessionByCharacterId(playerId);
        if (player == null) return;

        // 检查是否已在队伍中
        if (playerPartyMap.containsKey(playerId)) {
            sendError(playerId, "你已在队伍中");
            return;
        }

        Party party;
        if (partyId == 0) {
            // 创建新队伍
            long newPartyId = partyIdGenerator.getAndIncrement();
            party = new Party(playerId);
            parties.put(newPartyId, party);
            playerPartyMap.put(playerId, newPartyId);
        } else {
            // 加入现有队伍
            party = parties.get(partyId);
            if (party == null) {
                sendError(playerId, "队伍不存在");
                return;
            }
            if (party.isFull()) {
                sendError(playerId, "队伍已满");
                return;
            }
            party.addMember(playerId);
            playerPartyMap.put(playerId, partyId);
        }

        // 通知所有队员
        broadcastPartyUpdate(party);

        log.info("Party joined: player {} joined party {}", player.getCharacterName(), partyId);
    }

    /**
     * 离开队伍
     */
    public void leave(long playerId) {
        Long partyId = playerPartyMap.remove(playerId);
        if (partyId == null) return;

        Party party = parties.get(partyId);
        if (party == null) return;

        party.removeMember(playerId);

        if (party.isEmpty()) {
            parties.remove(partyId);
        } else {
            broadcastPartyUpdate(party);
        }

        log.info("Party left: player {} left party {}", playerId, partyId);
    }

    private void broadcastPartyUpdate(Party party) {
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setPartyUpdate(MessageProto.S2C_PartyUpdate.newBuilder()
                .setPartyId(0) // TODO: 获取 partyId
                .setLeaderId(party.getLeaderId())
                .addAllMemberIds(party.getMemberIds())
                .build())
            .build();

        for (Long memberId : party.getMemberIds()) {
            messageSender.sendToPlayer(memberId, msg);
        }
    }

    private void sendError(long playerId, String error) {
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setError(MessageProto.S2C_Error.newBuilder()
                .setErrorMessage(error)
                .build())
            .build();
        messageSender.sendToPlayer(playerId, msg);
    }
}
