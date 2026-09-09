package org.jpstale.server.game.item;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.stereotype.Component;

/**
 * 物品网络入口：进图下发背包快照 + C2S 背包/装备/丢弃操作。
 * <p>
 * 所有操作走 {@link ItemService}（服务端权威：位图校验 + DB 事务），
 * 成功后向客户端推送增量（S2C_ItemUpdate / ItemRemove）+ 属性重算推送。
 */
@Slf4j
@Component
public class ItemNetworkHandler {

    private final ItemService itemService;
    private final PlayerService playerService;
    private final org.jpstale.server.game.service.AppearanceService appearanceService;
    private final org.jpstale.server.game.service.AOIManager aoiManager;
    private final GroundItemManager groundItems;

    public ItemNetworkHandler(ItemService itemService, PlayerService playerService,
                              org.jpstale.server.game.service.AppearanceService appearanceService,
                              org.jpstale.server.game.service.AOIManager aoiManager,
                              GroundItemManager groundItems) {
        this.itemService = itemService;
        this.playerService = playerService;
        this.appearanceService = appearanceService;
        this.aoiManager = aoiManager;
        this.groundItems = groundItems;
    }

    // ------------------------------------------------------------------
    // 进图快照（selectCharacter 完成后由 PlayerService/AccountService 调用）
    // ------------------------------------------------------------------

    public void sendInventorySnapshot(PlayerSession session, Player player) {
        MessageProto.S2C_InventorySnapshot.Builder snap = MessageProto.S2C_InventorySnapshot.newBuilder()
                .setGold(player.getGold());
        PlayerItems items = player.getItems();
        if (items != null) {
            for (int loc : new int[]{ItemLocations.BAG, ItemLocations.WAREHOUSE,
                    ItemLocations.EQUIP, ItemLocations.BACKUP_WEAPON}) {
                for (ItemInstance it : items.itemsIn(loc)) {
                    snap.addItems(toProto(it));
                }
            }
        }
        session.send(MessageProto.ServerMessage.newBuilder()
                .setInventorySnapshot(snap)
                .build());
    }

    public MessageProto.S2C_ItemUpdate toItemUpdate(ItemInstance it) {
        return MessageProto.S2C_ItemUpdate.newBuilder()
                .setItem(toProto(it))
                .build();
    }

    public void pushUpdate(PlayerSession session, ItemInstance it) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setItemUpdate(toItemUpdate(it))
                .build());
    }

    public void pushRemove(PlayerSession session, long uid) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setItemRemove(MessageProto.S2C_ItemRemove.newBuilder().setUid(uid).build())
                .build());
    }

    // ------------------------------------------------------------------
    // 转换 ItemInstance → ItemProto
    // ------------------------------------------------------------------

    public static CommonProto.ItemProto toProto(ItemInstance it) {
        CommonProto.ItemProto.Builder b = CommonProto.ItemProto.newBuilder()
                .setUid(it.getId() == null ? 0 : it.getId())
                .setItemlistId(it.getItemListId() == null ? 0 : it.getItemListId())
                .setItemCode(it.getItemCode() == null ? 0 : it.getItemCode())
                .setLocation(it.getLocation())
                .setSlot(it.getSlot())
                .setCount(it.getCount())
                .setDurability(it.getDurability())
                .setDurabilityMax(it.getDurabilityMax())
                .setDamageMin(it.getDamageMin())
                .setDamageMax(it.getDamageMax())
                .setAttackRating(it.getAttackRating())
                .setDefence(it.getDefence())
                .setBlockRating((int) Math.round(it.getBlockRating() * 10))
                .setAbsorb((int) Math.round(it.getAbsorb() * 10))
                .setSpeed((int) Math.round(it.getSpeed() * 10))
                .setResBionic(it.getResBionic())
                .setResFire(it.getResFire())
                .setResIce(it.getResIce())
                .setResLighting(it.getResLighting())
                .setResPoison(it.getResPoison())
                .setIncreaseLife((int) Math.round(it.getIncreaseLife()))
                .setIncreaseMana((int) Math.round(it.getIncreaseMana()))
                .setIncreaseStamina((int) Math.round(it.getIncreaseStamina()))
                .setReqLevel(it.getReqLevel())
                .setReqStrength(it.getReqStrength())
                .setReqSpirit(it.getReqSpirit())
                .setReqTalent(it.getReqTalent())
                .setReqAgility(it.getReqAgility())
                .setReqHealth(it.getReqHealth())
                .setPrice(it.getPrice())
                .setJobCodeMask(it.getJobCodeMask())
                .setAgingLevel(it.getAgingNum());
        return b.build();
    }

    // ------------------------------------------------------------------
    // C2S Handlers
    // ------------------------------------------------------------------

    private Player requirePlayer(PlayerSession session) {
        if (session == null || !session.isPlaying()) {
            return null;
        }
        Player p = playerService.getPlayer(session);
        if (p == null) {
            p = playerService.getOrCreate(session);
        }
        return p;
    }

    /** 背包内移动/换格（含背包↔仓库） */
    @GamePacketHandler(MessageProto.ClientMessage.INVENTORY_MOVE_FIELD_NUMBER)
    public void handleInventoryMove(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_InventoryMove req = message.getInventoryMove();
        boolean ok = itemService.moveOnCanvas(p, req.getUid(), req.getToLocation(), req.getToSlot());
        if (!ok) {
            sendError(session, "move failed");
            return;
        }
        ItemInstance it = p.getItems().byUid(req.getUid());
        if (it != null) {
            pushUpdate(session, it);
        }
    }

    /** 穿装备：背包 → 装备槽 */
    @GamePacketHandler(MessageProto.ClientMessage.EQUIP_ITEM_FIELD_NUMBER)
    public void handleEquipItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_EquipItem req = message.getEquipItem();
        ItemInstance equipped = itemService.equipFromBag(p, req.getUid(), req.getEquipSlot());
        if (equipped == null) {
            sendError(session, "equip failed (slot/requirement)");
            return;
        }
        pushUpdate(session, equipped);
        refreshPlayerStats(session, p);
        // 同槽旧件已回背包（equipFromBag 内部），此处推送被换下的旧件
    }

    /** 脱装备：装备槽 → 背包 */
    @GamePacketHandler(MessageProto.ClientMessage.UNEQUIP_ITEM_FIELD_NUMBER)
    public void handleUnequipItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_UnequipItem req = message.getUnequipItem();
        boolean ok = itemService.unequipToBag(p, req.getEquipSlot());
        if (!ok) {
            sendError(session, "unequip failed (bag full?)");
            return;
        }
        // 推送装备槽清空 + 背包新位置
        for (ItemInstance it : p.getItems().itemsIn(ItemLocations.BAG)) {
            if (it.getSlot() >= 0) {
                pushUpdate(session, it);
            }
        }
        refreshPlayerStats(session, p);
    }

    /** 拾取地面物品（C2S_PickupItem）：服务端距离裁决 + 入背包 + 同图消失广播。 */
    @GamePacketHandler(MessageProto.ClientMessage.PICKUP_ITEM_FIELD_NUMBER)
    public void handlePickupItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (p == null || ent == null || ent.getMapId() < 0) {
            return;
        }
        long gid = message.getPickupItem().getGroundItemId();
        GroundItemManager.GroundItem gi = groundItems.byId(ent.getMapId(), gid);
        if (gi == null) {
            return; // 已消失/过期（幂等）
        }
        double dx = gi.x - ent.getX();
        double dz = gi.z - ent.getZ();
        if (dx * dx + dz * dz > PICKUP_RANGE * PICKUP_RANGE) {
            return; // 距离裁决：太远不拾
        }
        ItemInstance granted = itemService.grantInstanceToBag(p, gi.item);
        if (granted == null) {
            sendErrorKey(session, "chat.pickup.bagFull");
            return;
        }
        groundItems.remove(ent.getMapId(), gid);
        MessageProto.ServerMessage disappear = MessageProto.ServerMessage.newBuilder()
                .setGroundItemDisappear(MessageProto.S2C_GroundItemDisappear.newBuilder().setGroundItemId(gid).build())
                .build();
        for (org.jpstale.server.game.entity.PlayerEntity pe : aoiManager.getNearbyPlayers(gi.x, gi.z, AOIManager.VIEW_RANGE)) {
            if (pe.getSession() != null) {
                pe.getSession().send(disappear);
            }
        }
        pushUpdate(session, granted);
    }

    /** 拾取判定范围（世界单位 ≈1.1 米） */
    private static final double PICKUP_RANGE = 1.1d;

    private void sendErrorKey(PlayerSession session, String key) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setError(MessageProto.S2C_Error.newBuilder()
                        .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                        .setKey(key)
                        .build())
                .build());
    }

    /** 丢弃（软删） */
    @GamePacketHandler(MessageProto.ClientMessage.DROP_ITEM_FIELD_NUMBER)
    public void handleDropItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_DropItem req = message.getDropItem();
        boolean ok = itemService.discard(p, req.getUid());
        if (!ok) {
            sendError(session, "drop failed");
            return;
        }
        pushRemove(session, req.getUid());
    }

    /** W 武器切换 */
    @GamePacketHandler(MessageProto.ClientMessage.SWITCH_WEAPON_FIELD_NUMBER)
    public void handleSwitchWeapon(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        itemService.switchWeaponSet(p);
        // 推送主/备武器槽变化
        for (ItemInstance it : p.getItems().itemsIn(ItemLocations.EQUIP)) {
            pushUpdate(session, it);
        }
        for (ItemInstance it : p.getItems().itemsIn(ItemLocations.BACKUP_WEAPON)) {
            pushUpdate(session, it);
        }
        refreshPlayerStats(session, p);
    }

    private void refreshPlayerStats(PlayerSession session, Player p) {
        playerService.recalcPanel(p);
        playerService.sendPlayerStatus(session, p);
        // 抗性重算：遍历当前装备
        int[] res = new int[8];
        for (ItemInstance it : p.getItems().itemsIn(ItemLocations.EQUIP)) {
            res[0] += it.getResBionic();
            res[1] += it.getResEarth();
            res[2] += it.getResFire();
            res[3] += it.getResIce();
            res[4] += it.getResLighting();
            res[5] += it.getResPoison();
            res[6] += it.getResWater();
            res[7] += it.getResWind();
        }
        p.setResistances(res);
        // 外观重算 + 广播（自机 + 视野玩家），驱动 3D 换装
        var app = appearanceService.recalc(p);
        org.jpstale.server.game.entity.PlayerEntity entity = session != null ? session.getEntity() : null;
        if (entity != null) {
            aoiManager.broadcastAppearance(entity, app);
        }
    }

    private void sendError(PlayerSession session, String msg) {
        session.send(MessageProto.ServerMessage.newBuilder()
                .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                        .setMessage(msg)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }
}
