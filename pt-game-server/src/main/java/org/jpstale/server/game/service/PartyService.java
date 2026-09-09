package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Party;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.CommonProto;
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
            sendError(inviterId, "chat.party.targetOffline", Map.of("name", targetName));
            return;
        }

        if (inviterId == target.getCharacterId()) {
            sendError(inviterId, "chat.party.cannotInviteSelf");
            return;
        }

        // 检查邀请者是否已在队伍中
        Long inviterPartyId = playerPartyMap.get(inviterId);
        if (inviterPartyId != null) {
            Party party = parties.get(inviterPartyId);
            if (party != null && party.isFull()) {
                sendError(inviterId, "chat.party.full");
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
            sendError(playerId, "chat.party.alreadyInParty");
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
                sendError(playerId, "chat.party.notFound");
                return;
            }
            if (party.isFull()) {
                sendError(playerId, "chat.party.full");
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

    /**
     * 组队聊天：广播给本队所有成员（含发送者自己）。
     * 未组队时给发送者回系统提示。
     */
    public void broadcastChat(long playerId, MessageProto.ServerMessage message) {
        Long partyId = playerPartyMap.get(playerId);
        if (partyId == null) {
            MessageProto.ServerMessage err = MessageProto.ServerMessage.newBuilder()
                .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                    .setKey("chat.party.noParty")
                    .setTimestamp(System.currentTimeMillis())
                    .build())
                .build();
            messageSender.sendToPlayer(playerId, err);
            return;
        }
        Party party = parties.get(partyId);
        if (party == null) {
            playerPartyMap.remove(playerId);
            return;
        }
        for (Long memberId : party.getMemberIds()) {
            messageSender.sendToPlayer(memberId, message);
        }
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

    private void sendError(long playerId, String key, Map<String, String> params) {
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setError(MessageProto.S2C_Error.newBuilder()
                .setErrorCode(CommonProto.ErrorCode.PARTY_ERROR)
                .setKey(key)
                .putAllParams(params)
                .build())
            .build();
        messageSender.sendToPlayer(playerId, msg);
    }

    private void sendError(long playerId, String key) {
        sendError(playerId, key, Map.of());
    }
}
