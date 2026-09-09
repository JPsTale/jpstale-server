package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 聊天服务
 * 报文入口 + 业务方法（方案1：handler 并入 service）
 *
 * 频道语义（对齐 docs/pt-core-gameplay.md §16）：
 * - CHAT_MAP   普通聊天，本图广播
 * - CHAT_TRADE 交易频道，全服广播（原版 "/TRADE> 消息"）
 * - CHAT_PARTY 组队聊天，广播给队伍成员（原版 "@消息"）
 * - CHAT_PRIVATE 私聊（原版 "/名字: 消息" 或 "/名字; 消息"）
 *
 * 客户端只把固定前缀（/:、/;、/TRADE>、@）识别为专属 channel，
 * 其余 "/" 开头的文本原样上送，由本服务 treatCommand 权威解析
 * （含 //party 组队邀请、/@ GM 命令、/giveitem /items 调试命令）。
 */
@Slf4j
@Component
public class ChatService {

    /** 防御性单条消息长度上限（原版客户端 78、服务端截断 70） */
    private static final int MAX_CHAT_LEN = 200;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private GameMessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private org.jpstale.server.game.item.ItemService itemService;

    @Autowired
    private org.jpstale.server.game.item.ItemNetworkHandler itemNetwork;

    @Autowired
    private PartyService partyService;

    /**
     * 报文入口：聊天
     */
    @org.jpstale.server.game.network.GamePacketHandler(MessageProto.ClientMessage.CHAT_FIELD_NUMBER)
    public void handleChat(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_Chat chatRequest = message.getChat();

        if (session == null || !session.isPlaying()) {
            return;
        }

        String chatMessage = chatRequest.getMessage();
        if (chatMessage == null || chatMessage.isEmpty() || chatMessage.length() > MAX_CHAT_LEN) {
            return;
        }

        // "/" 开头一律作为命令由服务端权威解析，不进入频道广播
        if (chatMessage.startsWith("/")) {
            treatCommand(session, chatMessage);
            return;
        }

        CommonProto.ChatChannel channel = chatRequest.getChannel();

        // 构建聊天消息
        MessageProto.S2C_Chat chatResponse = MessageProto.S2C_Chat.newBuilder()
            .setChannel(channel)
            .setSenderId(session.getCharacterId())
            .setSenderName(session.getCharacterName() != null ? session.getCharacterName() : "")
            .setMessage(chatMessage)
            .setTimestamp(System.currentTimeMillis())
            .build();

        MessageProto.ServerMessage serverMessage = MessageProto.ServerMessage.newBuilder()
            .setChat(chatResponse)
            .build();

        switch (channel) {
            case CHAT_MAP:
                broadcastToMap(session, serverMessage);
                break;
            case CHAT_TRADE:
            case CHAT_WORLD:
                messageSender.broadcastToAll(serverMessage);
                break;
            case CHAT_PARTY:
                partyService.broadcastChat(session.getCharacterId(), serverMessage);
                break;
            case CHAT_GUILD:
                // 公会系统未开放
                systemMessageKey(session, "chat.guildNotOpen");
                break;
            case CHAT_PRIVATE:
                // 客户端已把 "/名字:" 识别为 PRIVATE + target_name
                sendPrivate(session, chatRequest.getTargetName(), serverMessage);
                break;
            default:
                log.warn("Unsupported chat channel: {}", channel);
                break;
        }
    }

    /**
     * 命令解析（服务端权威）。
     * 原版 RecvCommand 语义："/" 前缀文本在此分支，不广播。
     */
    private void treatCommand(PlayerSession session, String cmd) {
        String[] parts = cmd.trim().substring(1).split("\\s+");
        String name = parts[0].toLowerCase();

        // //party 玩家名 —— 组队邀请（对齐原版 OnSever.cpp RecvCommand //party）
        if (cmd.startsWith("//")) {
            String target = cmd.trim().substring(2).trim();
            if (target.isEmpty()) {
                systemMessageKey(session, "chat.cmd.partyUsage");
                return;
            }
            // 原版 //party 取第一个词为目标
            String targetName = target.split("\\s+")[0];
            partyService.invite(session.getCharacterId(), targetName);
            return;
        }

        // /@xxx —— GM 命令（全局重新设计，GM 系统开发中）
        if (cmd.startsWith("/@")) {
            systemMessageKey(session, "chat.cmd.unknownGm", Map.of("name", name));
            return;
        }

        try {
            if (name.equals("giveitem") && parts.length >= 2) {
                int itemListId = Integer.parseInt(parts[1]);
                var player = playerService.getOrCreate(session);
                var granted = itemService.grantToBag(player, itemListId, null);
                String msg = granted != null
                        ? "granted itemlist#" + itemListId + " uid=" + granted.getId()
                        : "grant failed: template missing or bag full (itemlist#" + itemListId + ")";
                systemMessage(session, msg);
                if (granted != null) {
                    itemNetwork.pushUpdate(session, granted);
                }
                return;
            }
            if (name.equals("items")) {
                var player = playerService.getOrCreate(session);
                StringBuilder sb = new StringBuilder("items=").append(player.getItems().byUidCount()).append(" [");
                for (var it : player.getItems().itemsIn(org.jpstale.server.game.item.ItemLocations.BAG)) {
                    sb.append("#").append(it.getItemListId())
                      .append("@").append(it.getSlot())
                      .append(" x").append(it.getCount()).append("; ");
                }
                sb.append("]");
                systemMessage(session, sb.toString());
                return;
            }
            systemMessageKey(session, "chat.cmd.unknown", Map.of("name", name));
        } catch (NumberFormatException e) {
            systemMessageKey(session, "chat.cmd.invalidNumber", Map.of("cmd", cmd));
        }
    }

    /** 同地图广播（含发送者自己） */
    private void broadcastToMap(PlayerSession sender, MessageProto.ServerMessage message) {
        int mapId = sender.getEntity() != null ? sender.getEntity().getMapId() : -1;
        for (PlayerSession session : sessionManager.getAllSessions()) {
            if (session.isPlaying()
                    && session.getEntity() != null
                    && session.getEntity().getMapId() == mapId) {
                session.send(message);
            }
        }
    }

    /** 私聊：发给目标 + 发送者（回显 "To> 名字"），对齐原版 WhisperMode 流程 */
    private void sendPrivate(PlayerSession sender, String targetName, MessageProto.ServerMessage message) {
        if (targetName == null || targetName.isEmpty()) {
            return;
        }

        PlayerSession targetSession = sessionManager.getSessionByCharacterName(targetName);
        if (targetSession == null) {
            systemMessageKey(sender, "chat.private.offline", Map.of("name", targetName));
            return;
        }

        targetSession.send(message);
        sender.send(message);
    }

    /** 正式提示：minecraft 式翻译 key + 命名参数（客户端按 locale 渲染） */
    private void systemMessageKey(PlayerSession session, String key, Map<String, String> params) {
        if (session == null) return;
        session.send(MessageProto.ServerMessage.newBuilder()
                .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                        .setKey(key)
                        .putAllParams(params)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }

    private void systemMessageKey(PlayerSession session, String key) {
        systemMessageKey(session, key, Map.of());
    }

    /** 调试输出：纯文本直发（giveitem/items 等开发者诊断，不走翻译） */
    private void systemMessage(PlayerSession session, String msg) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                        .setMessage(msg)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }
}