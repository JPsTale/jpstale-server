package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.game.entity.GroundItem;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.proto.base.*;
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
 * （含 //party 组队邀请、/@ GM 命令、/items 调试命令）。刷物一律走 /@get。）
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
    private org.jpstale.common.service.item.ItemRollService itemRoll;


    @Autowired
    private PartyService partyService;


    @Autowired
    private org.jpstale.server.game.item.GroundItemManager groundItems;

    @Autowired
    private org.jpstale.server.game.service.AOIManager aoiManager;

    @Autowired
    private TeleportService teleportService;

    @Autowired
    private org.jpstale.common.service.item.LootService lootService;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private org.jpstale.common.service.skill.SkillMasteryService skillMasteryService;

    @Autowired
    private SkillPointService skillPointService;

    /** 转职（GM /@set_rank 走它的设值入口） */
    @Autowired
    private JobService jobService;

    /**
     * 报文入口：聊天
     */
    @org.jpstale.server.game.network.GamePacketHandler(ClientMessage.CHAT_FIELD_NUMBER)
    public void handleChat(PlayerSession session, ClientMessage message) {
        C2S_Chat chatRequest = message.getChat();

        if (session == null || !session.isPlaying()) {
            return;
        }

        String chatMessage = chatRequest.getMessage();
        if (chatMessage.isEmpty() || chatMessage.length() > MAX_CHAT_LEN) {
            return;
        }

        // "/" 开头一律作为命令由服务端权威解析，不进入频道广播
        if (chatMessage.startsWith("/")) {
            treatCommand(session, chatMessage);
            return;
        }

        CommonProto.ChatChannel channel = chatRequest.getChannel();

        // 构建聊天消息
        S2C_Chat chatResponse = S2C_Chat.newBuilder()
            .setChannel(channel)
            .setSenderId(session.getCharacterId())
            .setSenderName(session.getCharacterName() != null ? session.getCharacterName() : "")
            .setMessage(chatMessage)
            .setTimestamp(System.currentTimeMillis())
            .build();

        ServerMessage serverMessage = ServerMessage.newBuilder()
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
            if (!isGm(session)) {
                systemMessage(session, "no permission");
                return;
            }
            if (name.equals("@get")) {
                treatGet(session, parts);
                return;
            }
            if (name.equals("@set_rank")) {
                treatSetRank(session, parts);
                return;
            }
            if (name.equals("@skill_mastery")) {
                treatSkillMastery(session, parts);
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
            // /unstuck —— 脱困：零代价传送到本图最近的 StartPoint（系统菜单"脱离卡死"按钮也走这条）
            if (name.equals("unstuck") || name.equals("脱离") || name.equals("卡死")) {
                var player = playerService.getOrCreate(session);
                teleportService.respondUnstuck(session, teleportService.unstuck(player));
                return;
            }
            if (name.equals("items")) {
                var player = playerService.getOrCreate(session);
                StringBuilder sb = new StringBuilder("items=").append(player.getItems().byUidCount()).append(" [");
                for (var it : player.getItems().itemsIn(org.jpstale.common.service.item.ItemLocations.BAG)) {
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
     * /@set_rank <n> —— GM 直接设置转职阶级（0..4；任务系统上线前的转职入口，用户 2026-09-24 裁定）。
     * 副作用与真实转职一致（放开洗点守卫 + 落库 + 头模外观广播 + 面板推送），见 {@link JobService#setRank}。
     */
    private void treatSetRank(PlayerSession session, String[] parts) {
        if (parts.length < 2) {
            systemMessageKey(session, "chat.cmd.setRankUsage",
                    Map.of("max", String.valueOf(JobService.GM_MAX_RANK)));
            return;
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            systemMessageKey(session, "chat.cmd.setRankBad",
                    Map.of("arg", parts[1], "max", String.valueOf(JobService.GM_MAX_RANK)));
            return;
        }
        var player = playerService.getOrCreate(session);
        JobService.Reason r = jobService.setRank(player, rank);
        if (r != JobService.Reason.OK) {
            // BAD_RANK / NO_PLAYER：显式报错，不静默（拒绝原因也回显给 GM，便于发现手滑）
            systemMessageKey(session, "chat.cmd.setRankBad",
                    Map.of("arg", parts[1], "max", String.valueOf(JobService.GM_MAX_RANK)));
            return;
        }
        systemMessage(session, "rank=" + rank + "  (head tier=" + rank + ")");
    }

    /** GM 身份：userdb.userinfo.gamemasterlevel>0 或 gamemastertype!=0。 */
    private boolean isGm(PlayerSession session) {        if (session == null || session.getAccountId() == null) {
            return false;
        }
        UserInfo u = userInfoMapper.selectById(session.getAccountId().intValue());
        if (u == null) {
            return false;
        }
        return (u.getGameMasterLevel() != null && u.getGameMasterLevel() > 0)
                || (u.getGameMasterType() != null && u.getGameMasterType() != 0);
    }

    /** /@get 数量参数上限：与背包单堆上限一致（ItemService 合堆按 1000/堆封顶）。 */
    private static final int GET_COUNT_MAX = 1000;

    /**
     * /@get <物品码> [数量] —— GM 刷物：掷点生成物品，随机铺在玩家周围地面。
     * 数量语义（用户 2026-09-24）：可堆叠物 = **一堆一个地面物**（count=数量，拾取后自动并入
     * 药水槽/背包既有堆）；不可堆叠物（装备等）= **数量件独立掷点**的地面物，各自随机散布。
     * 投放到 GroundItemManager（可被附近玩家拾取），并向视野内玩家广播 S2C_GroundItemAppear。
     */
    private void treatGet(PlayerSession session, String[] parts) {
        if (parts.length < 2) {
            systemMessageKey(session, "chat.cmd.getUsage");
            return;
        }
        int count = 1;
        if (parts.length >= 3) {
            try {
                count = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                count = 0;
            }
            // 非法数量显式报错，不静默按 1 处理（AGENTS 零兜底）
            if (count < 1 || count > GET_COUNT_MAX) {
                systemMessageKey(session, "chat.cmd.getBadCount",
                        Map.of("arg", parts[2], "max", String.valueOf(GET_COUNT_MAX)));
                return;
            }
        }
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null || ent.getMapId() < 0) {
            return; // 尚未进场，无刷物位置
        }
        org.jpstale.common.service.item.ItemInstance first = itemRoll.rollByCode(parts[1], null);
        if (first == null || first.getTemplate() == null) {
            log.info("[GM] /@get token={} by {} : item not found", parts[1], session.getCharacterName());
            systemMessageKey(session, "chat.cmd.itemNotFound", Map.of("token", parts[1]));
            return;
        }

        // 可堆叠：数量并进同一堆（一个地面物、quantity=count）
        if (first.stackable()) {
            first.setCount(count);
            GroundItem gi = spawnGroundItem(session, ent, first);
            if (gi == null) {
                systemMessageKey(session, "chat.cmd.dropOverLimit");
                return;
            }
            systemMessage(session, "spawned ground item id=" + gi.getId() + "  name=" + first.getTemplate().getName()
                    + " code=" + first.getItemCode() + " job=" + first.getJobCodeMask() + " x" + count
                    + "  @(" + (long) gi.getX() + "," + (long) gi.getZ() + ")");
            return;
        }

        // 不可堆叠：count 件独立掷点、各自随机散布；地图满即停（余量放弃并明说）
        int spawned = 0;
        GroundItem last = null;
        for (int i = 0; i < count; i++) {
            org.jpstale.common.service.item.ItemInstance it =
                    (i == 0) ? first : itemRoll.rollByCode(parts[1], null);
            if (it == null || it.getTemplate() == null) {
                break; // 首件已验证存在，后续同码掷点不应失败
            }
            GroundItem gi = spawnGroundItem(session, ent, it);
            if (gi == null) {
                break; // 地图满
            }
            last = gi;
            spawned++;
        }
        if (spawned == 0) {
            systemMessageKey(session, "chat.cmd.dropOverLimit");
            return;
        }
        if (spawned == 1) {
            systemMessage(session, "spawned ground item id=" + last.getId() + "  name=" + first.getTemplate().getName()
                    + " code=" + first.getItemCode() + " job=" + first.getJobCodeMask()
                    + "  @(" + (long) last.getX() + "," + (long) last.getZ() + ")");
            return;
        }
        systemMessage(session, "spawned " + spawned + "/" + count
                + (spawned < count ? " (ground full)" : "")
                + "  name=" + first.getTemplate().getName() + " code=" + first.getItemCode());
    }

    /**
     * 在玩家周围随机散布投放一件地面物并向视野内广播 Appear（散布逻辑在
     * {@link org.jpstale.server.game.item.GroundItemManager#addScattered}，与玩家丢弃共用）。
     * 返回投放结果；地图满被丢弃时返回 null（挤掉 Level=0 的日志在 GroundItemManager.add 内）。
     */
    private GroundItem spawnGroundItem(PlayerSession session,
                                       org.jpstale.server.game.entity.PlayerEntity ent,
                                       org.jpstale.common.service.item.ItemInstance fresh) {
        GroundItem gi = groundItems.addScattered(fresh, ent.getMapId(), ent.getX(), ent.getY(), ent.getZ());
        if (gi == null) {
            log.info("[GM] /@get token={} : 地图满({}) 掉落被丢弃", fresh.getItemCode(), 1024);
            return null;
        }

        String itemName = fresh.getTemplate().getName();
        String dorp = fresh.getTemplate().getCodeImg1(); // 掉落模型码（dropitem/it{code}.smd）
        ServerMessage appear = ServerMessage.newBuilder()
                .setGroundItemAppear(S2C_GroundItemAppear.newBuilder()
                        .setItem(org.jpstale.server.proto.base.CommonProto.GroundItemProto.newBuilder()
                                .setGroundItemId(gi.getId())
                                .setItemId(fresh.getItemCode() == null ? 0 : fresh.getItemCode())
                                // 主键：客户端查 i18n 名用（`item.<id>.name`）
                                .setItemlistId(fresh.getItemListId())
                                .setQuantity(fresh.getCount())
                                .setPosition(org.jpstale.server.proto.base.CommonProto.Position.newBuilder()
                                        .setX((float) gi.getX()).setY((float) gi.getY()).setZ((float) gi.getZ()).build())
                                .setOwnerId(gi.ownerId)
                                .setExpireTime(gi.expireAt)
                                .setName(itemName == null ? "" : itemName)
                                .setDorpItem(dorp == null ? "" : dorp)
                                .build())
                        .build())
                .build();
        int sent = 0;
        for (org.jpstale.server.game.entity.PlayerEntity pe : aoiManager.getNearbyPlayers((float) gi.getX(), (float) gi.getZ(), AOIManager.VIEW_RANGE)) {
            if (pe.getSession() != null) {
                pe.getSession().send(appear);
                sent++;
            }
        }
        log.info("[GM] {} /@get -> groundItem id={} itemListId={} code={} name={} count={} owner={} @({},{},{}) broadcast={}",
            session.getCharacterName(), gi.getId(), fresh.getItemListId(), fresh.getItemCode(), itemName,
            fresh.getCount(), session.getCharacterId(), (float) gi.getX(), (float) gi.getY(), (float) gi.getZ(), sent);
        return gi;
    }

    /** 同地图广播（含发送者自己） */
    private void broadcastToMap(PlayerSession sender, ServerMessage message) {
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
    private void sendPrivate(PlayerSession sender, String targetName, ServerMessage message) {
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
        session.send(ServerMessage.newBuilder()
                .setSystemMessage(S2C_SystemMessage.newBuilder()
                        .setKey(key)
                        .putAllParams(params)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }

    private void systemMessageKey(PlayerSession session, String key) {
        systemMessageKey(session, key, Map.of());
    }

    /**
     * `/@skill_mastery &lt;1..100&gt;` —— GM 把**已学**技能的熟练度设为该百分比
     * （用户 2026-09-25 要求；写入的唯一实现在 `SkillMasteryService.setAllPercent`）。
     *
     * <p>两个"要不到"的情形会**如实回报**（不假装做到）：才能/装备给的下限高于目标、
     * 以及 `Element[0]` 的技能恒为 100%（这类技能的计数**不动**）。
     */
    private void treatSkillMastery(PlayerSession session, String[] parts) {
        if (parts.length < 2) {
            systemMessageKey(session, "chat.cmd.skillMasteryUsage");
            return;
        }
        int pct;
        try {
            pct = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            systemMessageKey(session, "chat.cmd.skillMasteryBad", Map.of("arg", parts[1]));
            return;
        }
        var player = playerService.getOrCreate(session);
        var r = skillMasteryService.setAllPercent(player, pct);
        if (!r.ok()) {
            systemMessageKey(session, "chat.cmd.skillMasteryBad", Map.of("arg", parts[1]));
            return;
        }
        playerService.persistStats(player);
        skillPointService.sendSkillTables(session, player);   // 面板/HUD 立刻刷新（同一批时机）
        systemMessageKey(session, "chat.cmd.skillMasteryDone", Map.of(
                "pct", String.valueOf(pct),
                "actual", String.valueOf(r.effectivePct()),
                "count", String.valueOf(r.changed()),
                "full", String.valueOf(r.elementFull())));
    }

    /** 调试输出：纯文本直发（/items 等开发者诊断，不走翻译） */
    private void systemMessage(PlayerSession session, String msg) {
        session.send(ServerMessage.newBuilder()
                .setSystemMessage(S2C_SystemMessage.newBuilder()
                        .setMessage(msg)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }
}