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
import java.util.concurrent.ThreadLocalRandom;

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

    @Autowired
    private org.jpstale.server.game.item.ItemRollService itemRoll;

    @Autowired
    private org.jpstale.server.game.item.GroundItemManager groundItems;

    @Autowired
    private org.jpstale.server.game.service.AOIManager aoiManager;

    @Autowired
    private org.jpstale.server.game.service.MapRegionService mapRegionService;

    @Autowired
    private org.jpstale.server.game.item.LootService lootService;

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

        // /@xxx —— GM 命令（全局重新设计）
        if (cmd.startsWith("/@")) {
            if (name.equals("@get")) {
                treatGet(session, parts);
                return;
            }
            if (name.equals("@reloadloot")) {
                lootService.reload();
                systemMessage(session, "loot table reloaded");
                return;
            }
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

    /**
     * /@get <名字|idCode|itemlistId> —— GM 刷物：掷点生成一件装备，随机铺在玩家周围地面。
     * 投放到 GroundItemManager（可被附近玩家拾取），并向视野内玩家广播 S2C_GroundItemAppear。
     */
    private void treatGet(PlayerSession session, String[] parts) {
        if (parts.length < 2) {
            systemMessageKey(session, "chat.cmd.getUsage");
            return;
        }
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null || ent.getMapId() < 0) {
            return; // 尚未进场，无刷物位置
        }
        org.jpstale.server.game.item.ItemInstance fresh = itemRoll.rollByIdOrCodeOrName(parts[1], null);
        if (fresh == null || fresh.getTemplate() == null) {
            log.info("[GM] /@get token={} by {} : item not found", parts[1], session.getCharacterName());
            systemMessageKey(session, "chat.cmd.itemNotFound", Map.of("token", parts[1]));
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble() * Math.PI * 2;
        double dist = 0.5 + rnd.nextDouble() * 29.5; // 世界单位，散布 0.5~30
        double nx = ent.getX() + Math.cos(ang) * dist;
        double nz = ent.getZ() + Math.sin(ang) * dist;
        double ny = mapRegionService.getHeight(ent.getMapId(), nx, nz); // 落点地形高度，避免沉入地下

        org.jpstale.server.game.item.GroundItemManager.GroundItem gi =
                groundItems.add(fresh, ent.getMapId(), nx, ny, nz, 0, 0);
        if (gi == null) {
            log.info("[GM] /@get token={} : 地图满({}) 掉落被丢弃", parts[1], 1024);
            systemMessageKey(session, "chat.cmd.dropOverLimit");
            return;
        }

        String itemName = fresh.getTemplate().getName();
        String dorp = fresh.getTemplate().getCodeImg1(); // 掉落模型码（dropitem/it{code}.smd）
        MessageProto.ServerMessage appear = MessageProto.ServerMessage.newBuilder()
                .setGroundItemAppear(MessageProto.S2C_GroundItemAppear.newBuilder()
                        .setItem(org.jpstale.server.proto.base.CommonProto.GroundItemProto.newBuilder()
                                .setGroundItemId(gi.id)
                                .setItemId(fresh.getItemCode() == null ? 0 : fresh.getItemCode())
                                .setQuantity(fresh.getCount())
                                .setPosition(org.jpstale.server.proto.base.CommonProto.Position.newBuilder()
                                        .setX((float) nx).setY((float) ny).setZ((float) nz).build())
                                .setOwnerId(gi.ownerId)
                                .setExpireTime(gi.expireAt)
                                .setName(itemName == null ? "" : itemName)
                                .setDorpItem(dorp == null ? "" : dorp)
                                .build())
                        .build())
                .build();
        int sent = 0;
        for (org.jpstale.server.game.entity.PlayerEntity pe : aoiManager.getNearbyPlayers((float) nx, (float) nz, AOIManager.VIEW_RANGE)) {
            if (pe.getSession() != null) {
                pe.getSession().send(appear);
                sent++;
            }
        }
        log.info("[GM] {} /@get -> groundItem id={} itemListId={} code={} name={} owner={} @({},{},{}) broadcast={}",
            session.getCharacterName(), gi.id, fresh.getItemListId(), fresh.getItemCode(), itemName,
            session.getCharacterId(), (float) nx, (float) ny, (float) nz, sent);
        systemMessage(session, "spawned ground item id=" + gi.id + "  name=" + itemName
                + " code=" + fresh.getItemCode() + " job=" + fresh.getJobCodeMask()
                + "  @(" + (long) nx + "," + (long) nz + ")");
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