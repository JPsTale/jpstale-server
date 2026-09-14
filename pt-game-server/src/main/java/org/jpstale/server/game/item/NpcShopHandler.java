package org.jpstale.server.game.item;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.game.model.Npc;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.service.GoldService;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.game.service.NpcShopService;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * NPC 商店：打开 / 买入 / 卖出。
 *
 * **所有校验都在服务端**（用户 2026-09-14 要求："买卖道具时，应该检查玩家和 npc 之间的距离、
 * npc 的存在与否，防止外挂远程打开 npc 面板，除非操作者是 GM"）：
 * <ol>
 *   <li>NPC 定义存在且**是商家**（`npclist` 三个商店列任一非空）；</li>
 *   <li>该 NPC 在玩家**当前地图**上有实例（`mapnpc` 生成出来的），且取**最近**的那个；</li>
 *   <li>距离 ≤ {@link #NPC_INTERACT_RANGE}（我们的反外挂界；原版这条校验在客户端，服务端没有）；</li>
 *   <li>`mapnpc.onlygm != 0` 的 NPC **只有 GM 能交互**（原版 `unitinfo.cpp:1731` + `unitserver.cpp:339-345`
 *       的 `bGMOnly`：NPC 照常可见，非 GM 点击时被拒并提示 "> Only for Admins!"）；</li>
 *   <li>买入的商品必须真的在**该 NPC 的清单**里（防伪造 itemlist_id）；</li>
 *   <li>钱与背包都够（买入）、物品真的在**自己背包**里且不可卖清单未命中（卖出）。</li>
 * </ol>
 * GM 例外只影响第 3、4 条（距离与 onlygm），其余校验对 GM 一样生效。
 *
 * 卖出价与"先判上限再交货/收钱"的顺序照原版：`sinShop.cpp:1668-1752 SellItemToShop`
 * 先做金币上限检查，通过后才 `SellItemToServer`。
 */
@Slf4j
@Component
public class NpcShopHandler {

    /**
     * NPC 交互距离上限（世界单位）。原版这条校验在**客户端**（点谁是谁），服务端没有；
     * 我们加它是为了防"远程开面板"，所以取一个明显够用又不过分的值（玩家与 NPC 正常面对面）。
     */
    private static final double NPC_INTERACT_RANGE = 96.0d;

    /** 一次买入/卖出的数量上限（原版 EU 侧拒绝 `iCount > 10000`；我们收紧到 1000，与背包堆叠上限一致）。 */
    private static final int MAX_TRADE_COUNT = 1000;

    private final NpcShopService shopService;
    private final ItemService itemService;
    private final ItemRollService itemRollService;
    private final ItemStorageService storage;
    private final GoldService goldService;
    private final UserInfoMapper userInfoMapper;
    private final PlayerService playerService;

    @Autowired
    public NpcShopHandler(NpcShopService shopService, ItemService itemService,
                          ItemRollService itemRollService, ItemStorageService storage,
                          GoldService goldService, UserInfoMapper userInfoMapper,
                          PlayerService playerService) {
        this.shopService = shopService;
        this.itemService = itemService;
        this.itemRollService = itemRollService;
        this.storage = storage;
        this.goldService = goldService;
        this.userInfoMapper = userInfoMapper;
        this.playerService = playerService;
    }

    // ------------------------------------------------------------------
    // ① 点击 NPC → 打开商店
    // ------------------------------------------------------------------

    @GamePacketHandler(MessageProto.ClientMessage.NPC_INTERACT_FIELD_NUMBER)
    public void handleNpcInteract(PlayerSession session, MessageProto.ClientMessage message) {
        long npcId = message.getNpcInteract().getNpcId();
        Npc npc = resolveInteractableNpc(session, npcId, "shop.outOfRange");
        if (npc == null) {
            return;
        }
        List<NpcShopService.Offer> offers = shopService.offers(npcId);
        if (offers.isEmpty()) {
            // 走到这里说明 npclist 说它是商家但清单解析不出（resolveCode 已 log.error 报过具体码）
            log.error("[Shop] npc={} 是商家但商品清单为空（数据问题）", npcId);
            sendErrorKey(session, "shop.noItems");
            return;
        }
        MessageProto.S2C_ShopOpen.Builder open = MessageProto.S2C_ShopOpen.newBuilder().setNpcId(npcId);
        for (NpcShopService.Offer o : offers) {
            open.addItems(MessageProto.ShopItemProto.newBuilder()
                    .setItemlistId(o.itemlistId())
                    .setCode(o.code() == null ? "" : o.code())
                    .setName(o.name() == null ? "" : o.name())
                    .setPrice(o.price())
                    .setKind(o.kind()));
        }
        session.send(MessageProto.ServerMessage.newBuilder().setShopOpen(open).build());
        log.info("[Shop] {} 打开 npc={}（{} 件商品）", session.getCharacterName(), npcId, offers.size());
    }

    // ------------------------------------------------------------------
    // ② 买入
    // ------------------------------------------------------------------

    @GamePacketHandler(MessageProto.ClientMessage.SHOP_BUY_FIELD_NUMBER)
    public void handleShopBuy(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        long npcId = message.getShopBuy().getNpcId();
        int itemlistId = message.getShopBuy().getItemlistId();
        int count = message.getShopBuy().getCount();
        if (resolveInteractableNpc(session, npcId, "shop.outOfRange") == null) {
            return;
        }
        // 商品必须是**该 NPC 清单里的**（防伪造 itemlist_id 买别家的东西）
        NpcShopService.Offer offer = shopService.offers(npcId).stream()
                .filter(o -> o.itemlistId() == itemlistId).findFirst().orElse(null);
        if (offer == null) {
            log.warn("[Shop] {} 买入被拒：npc={} 清单里没有 itemlistId={}（可疑）",
                    session.getCharacterName(), npcId, itemlistId);
            sendErrorKey(session, "shop.notSold");
            return;
        }
        ItemInstance fresh = itemRollService.rollById(itemlistId, null);
        if (fresh == null) {
            log.error("[Shop] 买入失败：itemlistId={} 掷不出实例（数据缺失）", itemlistId);
            sendErrorKey(session, "shop.failed");
            return;
        }
        // 数量：只有可堆叠物才谈得上多件（原版：药水才有数量框，其余一次一件）
        int n = fresh.stackable() ? Math.max(1, Math.min(count, MAX_TRADE_COUNT)) : 1;
        if (count > MAX_TRADE_COUNT) {
            log.warn("[Shop] {} 买入数量 {} 超过上限 {}（截到上限，可疑）",
                    session.getCharacterName(), count, MAX_TRADE_COUNT);
        }
        long total = offer.price() * (long) n;
        if (total > 0 && p.getGold() < total) {
            sendErrorKey(session, "shop.notEnoughMoney");
            return;
        }
        // 顺序：**先验钱 → 再发货 → 最后扣钱**。发货失败（背包满）时一行都没动、钱也没扣。
        fresh.setCount(n);
        ItemService.GrantResult grant = itemService.grantInstanceToBag(p, fresh);
        if (grant.reason != ItemService.GrantReason.OK) {
            log.info("[Shop] {} 买入被拒：npc={} itemlistId={} x{} → {}",
                    session.getCharacterName(), npcId, itemlistId, n, grant.reason);
            sendErrorKey(session, grant.reason == ItemService.GrantReason.BAG_FULL
                    ? "item.op.bagFull" : "item.op.overWeight");
            return;
        }
        if (total > 0) {
            GoldService.Result r = goldService.add(session, p, -total, "shop_buy");
            if (r != GoldService.Result.OK) {
                // 预检已通过，正常到不了这里；真到了就回滚已发的货，并把异常喊出来
                log.error("[Shop] {} 扣款失败（{}）但货已发出：uid={} x{} → 回滚发货",
                        session.getCharacterName(), r, grant.instance.getId(), n);
                p.getItems().byUidRemove(grant.instance.getId());
                storage.softDelete(grant.instance.getId());
                sendErrorKey(session, "shop.failed");
                return;
            }
        }
        log.info("[Shop] {} 买入 npc={} itemlistId={} code={} x{} 花费 {}",
                session.getCharacterName(), npcId, itemlistId, offer.code(), n, total);
    }

    // ------------------------------------------------------------------
    // ③ 卖出
    // ------------------------------------------------------------------

    @GamePacketHandler(MessageProto.ClientMessage.SHOP_SELL_FIELD_NUMBER)
    public void handleShopSell(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        long npcId = message.getShopSell().getNpcId();
        long uid = message.getShopSell().getUid();
        int count = message.getShopSell().getCount();
        if (resolveInteractableNpc(session, npcId, "shop.outOfRange") == null) {
            return;
        }
        // 物品必须真的在**自己背包**里（不信客户端报的位置/价格）
        ItemInstance it = p.getItems().byUid(uid);
        if (it == null || it.isDeleted() || it.getLocation() != ItemLocations.BAG_PAGE) {
            log.info("[Shop] {} 卖出被拒：uid={} 不在背包（loc={}）",
                    session.getCharacterName(), uid, it == null ? "无此件" : it.getLocation());
            sendErrorKey(session, "item.op.notInBag");
            return;
        }
        int code = it.getItemCode() == null ? 0 : it.getItemCode();
        if (!ItemRules.isSellable(code)) {
            // 原版 NotSell_Item_*（任务物品等）
            log.info("[Shop] {} 卖出被拒：uid={} code=0x{} 在不可卖清单里",
                    session.getCharacterName(), uid, Integer.toHexString(code));
            sendErrorKey(session, "shop.cannotSell");
            return;
        }
        int n = Math.max(1, Math.min(count, it.getCount()));
        long price = NpcShopService.sellPrice(
                it.getTemplate() == null || it.getTemplate().getPrice() == null ? 0 : it.getTemplate().getPrice(),
                it.getDurability(), it.getDurabilityMax());
        long total = price * n;
        // 顺序照原版：**先判金币上限**（超限则本次卖出整笔不做，物品不动），再交货收钱
        GoldService.Result r = goldService.add(session, p, total, "shop_sell");
        if (r != GoldService.Result.OK) {
            log.info("[Shop] {} 卖出被拒：{}（{} x{} = {}）",
                    session.getCharacterName(), r, n, price, total);
            sendErrorKey(session, r == GoldService.Result.OVER_LIMIT
                    ? "item.pickup.overMoney" : "shop.failed");
            return;
        }
        if (n >= it.getCount()) {
            p.getItems().byUidRemove(it.getId());
            storage.softDelete(it.getId());
        } else {
            it.setCount(it.getCount() - n);
            storage.update(it);
        }
        pushUpdate(session, it, n >= it.getCount() ? 0 : it.getCount());
        log.info("[Shop] {} 卖出 npc={} uid={} code=0x{} x{} 得到 {}",
                session.getCharacterName(), npcId, uid, Integer.toHexString(code), n, total);
    }

    // ------------------------------------------------------------------
    // 校验与工具
    // ------------------------------------------------------------------

    /**
     * 取"当前可交互的那个 NPC 实例"：定义是商家 + 本图有实例 + 距离够 + onlygm 门。
     * 任一不过 → 回明确的错误 key 并返回 null（调用方直接 return）。
     */
    private Npc resolveInteractableNpc(PlayerSession session, long npcId, String outOfRangeKey) {
        PlayerEntity ent = session.getEntity();
        if (ent == null || ent.getMapId() < 0) {
            return null;
        }
        if (!shopService.isMerchant(npcId)) {
            log.info("[Shop] {} 交互被拒：npc={} 不是商家（或不存在）",
                    session.getCharacterName(), npcId);
            sendErrorKey(session, "shop.notMerchant");
            return null;
        }
        Npc npc = shopService.nearestInstance(ent.getMapId(), npcId, ent.getX(), ent.getZ());
        if (npc == null) {
            // 该 NPC 不在玩家这张图上（或已随昼夜消失）——外挂最爱撞的就是这条
            log.warn("[Shop] {} 交互被拒：npc={} 在 mapId={} 上没有实例（可疑）",
                    session.getCharacterName(), npcId, ent.getMapId());
            sendErrorKey(session, "shop.notHere");
            return null;
        }
        boolean gm = isGm(session);
        if (!gm) {
            if (npc.isGmOnly()) {
                // 原版：非 GM 点 onlygm 的 NPC → 提示 "> Only for Admins!"（NPC 本身可见）
                log.info("[Shop] {} 交互被拒：npc={} 是 GM 专用", session.getCharacterName(), npcId);
                sendErrorKey(session, "npc.gmOnly");
                return null;
            }
            double dx = npc.getX() - ent.getX();
            double dz = npc.getZ() - ent.getZ();
            if (dx * dx + dz * dz > NPC_INTERACT_RANGE * NPC_INTERACT_RANGE) {
                log.warn("[Shop] {} 交互被拒：npc={} 距离 {} 超过 {}（可疑）",
                        session.getCharacterName(), npcId, Math.sqrt(dx * dx + dz * dz), NPC_INTERACT_RANGE);
                sendErrorKey(session, outOfRangeKey);
                return null;
            }
        }
        return npc;
    }

    /** GM 判定：`UserInfo.gamemastertype != 0 && gamemasterlevel > 0`（EU 用 `GameMasterType/Level`）。 */
    private boolean isGm(PlayerSession session) {
        Long accountId = session.getAccountId();
        if (accountId == null) {
            return false;
        }
        UserInfo u = userInfoMapper.selectById(accountId);
        return u != null && u.getGameMasterType() != null && u.getGameMasterType() != 0
                && u.getGameMasterLevel() != null && u.getGameMasterLevel() > 0;
    }

    private Player requirePlayer(PlayerSession session) {
        Player p = playerService.requirePlayer(session);   // 与其它 handler 共用同一份判据
        if (p == null) {
            log.warn("[Shop] 会话不在局内/拿不到 Player，忽略请求");
        }
        return p;
    }

    private void pushUpdate(PlayerSession session, ItemInstance it, int countForPush) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setItemUpdate(MessageProto.S2C_ItemUpdate.newBuilder()
                        .setItem(ItemNetworkHandler.toProto(it))
                        .build())
                .build());
    }

    private void sendErrorKey(PlayerSession session, String key) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setError(MessageProto.S2C_Error.newBuilder()
                        .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                        .setKey(key)
                        .build())
                .build());
    }
}
