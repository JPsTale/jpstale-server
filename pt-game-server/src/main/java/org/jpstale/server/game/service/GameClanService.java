package org.jpstale.server.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.redis.ClanMessageData;
import org.jpstale.server.common.redis.ClanMessageTypes;
import org.jpstale.server.common.redis.CommonMsg;
import org.jpstale.server.common.redis.RedisMsgProducer;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GameClanService {

    private final RedisMsgProducer producer;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public GameClanService(RedisMsgProducer producer) {
        this.producer = producer;
    }

    public boolean createClan(String userId, String charName, String clanName, Integer charType, Integer level) {
        try {
            ClanMessageData data = ClanMessageData.create(clanName, userId, charName, charType, level);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_CREATE, OBJECT_MAPPER.writeValueAsString(data)));
            log.info("Clan create message sent: clan={}, char={}", clanName, charName);
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan create message", e);
            return false;
        }
    }

    public boolean dissolveClan(String userId, String charName, String clanName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_DISSOLVE, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan dissolve message", e);
            return false;
        }
    }

    public boolean inviteMember(String userId, String charName, String clanName,
                                 String targetName, String targetUserId, Integer targetType, Integer targetLevel) {
        try {
            ClanMessageData data = ClanMessageData.create(clanName, userId, charName, null, null);
            data.setTargetName(targetName);
            data.setTargetUserId(targetUserId);
            data.setTargetType(targetType);
            data.setTargetLevel(targetLevel);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_INVITE, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan invite message", e);
            return false;
        }
    }

    public boolean kickMember(String userId, String charName, String clanName, String targetName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            data.setTargetName(targetName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_KICK, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan kick message", e);
            return false;
        }
    }

    public boolean leaveClan(String userId, String charName, String clanName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_LEAVE, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan leave message", e);
            return false;
        }
    }

    public boolean transferLeader(String userId, String charName, String clanName, String targetName) {
        try {
            ClanMessageData data = ClanMessageData.simple(clanName, userId, charName);
            data.setTargetName(targetName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_TRANSFER_LEADER, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send clan transfer message", e);
            return false;
        }
    }

    public boolean setSubLeader(String charName) {
        try {
            ClanMessageData data = new ClanMessageData();
            data.setCharName(charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_SET_SUB_LEADER, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send set sub-leader message", e);
            return false;
        }
    }

    public boolean releaseSubLeader(String charName) {
        try {
            ClanMessageData data = new ClanMessageData();
            data.setCharName(charName);
            producer.sendMessage(new CommonMsg(ClanMessageTypes.CLAN_RELEASE_SUB_LEADER, OBJECT_MAPPER.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.error("Failed to send release sub-leader message", e);
            return false;
        }
    }
}
