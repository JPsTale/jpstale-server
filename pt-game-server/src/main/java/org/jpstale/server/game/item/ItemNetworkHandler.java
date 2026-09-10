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
                .setResEarth(it.getResEarth())
                .setResWater(it.getResWater())
                .setResWind(it.getResWind())
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
                .setAgingLevel(it.getAgingNum())
                .setCritical(it.getCritical())
                .setRange(it.getAttackRange())
                .setAttackSpeed(it.attackSpeed())
                .setManaRegen((int) Math.round(it.getManaRegen() * 10))
                .setLifeRegen((int) Math.round(it.getLifeRegen() * 10))
                .setStaminaRegen((int) Math.round(it.getStaminaRegen() * 10))
                .setSpecAbsorb((int) Math.round(it.getSpecAbsorb() * 10))
                .setSpecDefence(it.getSpecDefence())
                .setSpecSpeed((int) Math.round(it.getSpecSpeed() * 10))
                .setSpecBlockRating((int) Math.round(it.getSpecBlockRating() * 10))
                .setSpecAttackSpeed(it.getSpecAttackSpeed())
                .setSpecCritical(it.getSpecCritical())
                .setSpecShootingRange(it.getSpecShootingRange())
                .setSpecMagicMastery((int) Math.round(it.getSpecMagicMastery() * 10))
                .setSpecResBionic(it.getSpecResBionic())
                .setSpecResEarth(it.getSpecResEarth())
                .setSpecResFire(it.getSpecResFire())
                .setSpecResIce(it.getSpecResIce())
                .setSpecResLighting(it.getSpecResLighting())
                .setSpecResPoison(it.getSpecResPoison())
                .setSpecResWater(it.getSpecResWater())
                .setSpecResWind(it.getSpecResWind())
                .setSpecLevMana(it.getSpecLevMana())
                .setSpecLevLife(it.getSpecLevLife())
                .setSpecLevAttackRating(it.getSpecLevAttackRating())
                .setSpecLevDamageMax(it.getSpecLevDamageMax())
                .setSpecLevResBionic(it.getSpecLevResBionic())
                .setSpecLevResEarth(it.getSpecLevResEarth())
                .setSpecLevResFire(it.getSpecLevResFire())
                .setSpecLevResIce(it.getSpecLevResIce())
                .setSpecLevResLighting(it.getSpecLevResLighting())
                .setSpecLevResPoison(it.getSpecLevResPoison())
                .setSpecLevResWater(it.getSpecLevResWater())
                .setSpecLevResWind(it.getSpecLevResWind())
                .setSpecPerManaRegen((int) Math.round(it.getSpecPerManaRegen() * 100))
                .setSpecPerLifeRegen((int) Math.round(it.getSpecPerLifeRegen() * 100))
                .setSpecPerStaminaRegen((int) Math.round(it.getSpecPerStaminaRegen() * 100));
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

    /** 背包布局上报（客户端网格权威，全量快照 + seq）：seq<=lastSeq 乱序丢弃；失败不回推旧快照 */
    @GamePacketHandler(MessageProto.ClientMessage.BAG_LAYOUT_FIELD_NUMBER)
    public void handleBagLayout(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_BagLayout req = message.getBagLayout();
        int seq = req.getSeq();
        var list = req.getEntriesList();
        if (list.isEmpty() && seq <= p.getItems().lastSeq()) {
            return; // 空且乱序：直接丢弃
        }
        java.util.List<ItemService.BagLayoutEntry> entries = new java.util.ArrayList<>(list.size());
        boolean touchesEquip = false;
        for (var e : list) {
            final long uid = e.getUid();
            final int toLocation = e.getLocation();
            final int slot = e.getSlot();
            ItemInstance src = p.getItems().byUid(uid);
            if (src != null && (src.getLocation() == ItemLocations.EQUIP
                    || src.getLocation() == ItemLocations.BACKUP_EQUIP)) {
                touchesEquip = true;
            }
            entries.add(new ItemService.BagLayoutEntry() {
                @Override public Long uid() { return uid; }
                @Override public int location() { return toLocation; }
                @Override public int slot() { return slot; }
            });
        }
        boolean ok = itemService.applyBagLayout(p, seq, entries);
        if (!ok) {
            // 乱序（seq<=lastSeq）已由 applyBagLayout 内部记录；其余失败事件记日志
            if (seq > p.getItems().lastSeq()) {
                log.warn("[BagLayout] {} seq={} 校验失败（不裁决、不改格）", session.getCharacterName(), seq);
            }
        } else if (touchesEquip) {
            refreshPlayerStats(session, p);
        }
    }

    /** 药水堆叠合并 */
    @GamePacketHandler(MessageProto.ClientMessage.STACK_MERGE_FIELD_NUMBER)
    public void handleStackMerge(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_StackMerge req = message.getStackMerge();
        ItemInstance dst = itemService.mergeStack(p, req.getSrcUid(), req.getDstUid());
        if (dst == null) {
            // 拒绝：回推双方当前权威态
            ItemInstance s = p.getItems().byUid(req.getSrcUid());
            ItemInstance d = p.getItems().byUid(req.getDstUid());
            if (s != null) {
                pushUpdate(session, s);
            }
            if (d != null) {
                pushUpdate(session, d);
            }
            return;
        }
        pushUpdate(session, dst);
        pushRemove(session, req.getSrcUid());
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
            log.info("[Pickup] {} (mapId={}) gid={} : not found/expired", session.getCharacterName(), ent.getMapId(), gid);
            return; // 已消失/过期（幂等）
        }
        double dx = gi.x - ent.getX();
        double dz = gi.z - ent.getZ();
        if (dx * dx + dz * dz > PICKUP_RANGE * PICKUP_RANGE) {
            log.info("[Pickup] {} gid={} : too far dist={} (range {})",
                session.getCharacterName(), gid, Math.sqrt(dx * dx + dz * dz), PICKUP_RANGE);
            return; // 距离裁决：太远不拾
        }
        // 高度差裁决（对齐原版 ay ≤ 64·fONE）：不能隔层(屋顶/桥上)拾取
        if (Math.abs(gi.y - ent.getY()) > PICKUP_HEIGHT_DIFF) {
            log.info("[Pickup] {} gid={} : too high diff={} (limit {})",
                session.getCharacterName(), gid, Math.abs(gi.y - ent.getY()), PICKUP_HEIGHT_DIFF);
            return;
        }
        ItemService.GrantResult result = itemService.grantInstanceToBag(p, gi.item);
        if (result.reason == ItemService.GrantReason.BAG_FULL) {
            // 背包满：物品保持原地，仅提示（对齐原版 INVENTORY_FULL 语义，不重丢）
            log.info("[Pickup] {} gid={} : bag full → 保持原地", session.getCharacterName(), gid);
            sendSystemMessageKey(session, "chat.pickup.bagFull");
            return;
        }
        if (result.reason == ItemService.GrantReason.OVER_WEIGHT) {
            // 超重：物品保持原地，仅提示（对齐原版 Weight[0]>Weight[1] 语义）
            log.info("[Pickup] {} gid={} : over weight → 保持原地", session.getCharacterName(), gid);
            sendSystemMessageKey(session, "chat.pickup.overWeight");
            return;
        }
        ItemInstance granted = result.instance;
        groundItems.remove(ent.getMapId(), gid);
        log.info("[Pickup] {} gid={} granted id={} itemListId={} name={} @bagSlot={}",
            session.getCharacterName(), gid, granted.getId(), granted.getItemListId(),
            granted.getTemplate() != null ? granted.getTemplate().getName() : "?", granted.getSlot());
        broadcastDisappear(ent.getMapId(), gi.x, gi.z, gid);
        pushUpdate(session, granted);
        refreshPlayerStats(session, p); // 负重变了，HUD/状态需要更新
    }

    /** 向地面物品所在位置周围玩家广播消失 */
    private void broadcastDisappear(int mapId, double x, double z, long gid) {
        MessageProto.ServerMessage disappear = MessageProto.ServerMessage.newBuilder()
                .setGroundItemDisappear(MessageProto.S2C_GroundItemDisappear.newBuilder().setGroundItemId(gid).build())
                .build();
        for (org.jpstale.server.game.entity.PlayerEntity pe : aoiManager.getNearbyPlayers(x, z, AOIManager.VIEW_RANGE)) {
            if (pe.getSession() != null) {
                pe.getSession().send(disappear);
            }
        }
    }

    /** 拾取判定范围（世界单位）。对照原版 C++：拾取动作在
     *  PlayAttackFromPosi(..., Dist=8000, ...) 且 GetDistanceDbl(>>8) 平方比较
     *  → 水平距离 ≤ 8000>>8 = 31.25 ≈ 32；高度差上限 64·fONE(≈64 world，贴地掉落忽略)。
     *  即原版水平拾取范围约 32 world（含客户端权威与服务器实体上报滞差余量）。 */
    private static final double PICKUP_RANGE = 32.0d;

    /** 拾取高度差上限（世界单位，原版 ay ≤ 64·fONE）：防隔层拾取（屋顶/桥上） */
    private static final double PICKUP_HEIGHT_DIFF = 64.0d;

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
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null || ent.getMapId() < 0) {
            sendError(session, "drop failed");
            return;
        }
        // 丢到地面（对齐原版 ThrowItem）：从背包/装备取出 → 玩家附近生成地面物
        ItemInstance dropped = itemService.removeToGround(p, req.getUid());
        if (dropped == null) {
            sendError(session, "drop failed");
            return;
        }
        double ang = Math.random() * Math.PI * 2;
        double dist = 0.8 + Math.random() * 1.4;
        GroundItemManager.GroundItem gi = groundItems.add(
            dropped, ent.getMapId(),
            ent.getX() + Math.cos(ang) * dist,
            ent.getY(),
            ent.getZ() + Math.sin(ang) * dist,
            session.getCharacterId(), 0);
        if (gi == null) {
            // 地图已满且无可挤兑（全 Level=1）：原版 return FALSE 亦丢弃 → 背包物品已被取出，无法原地放回，直接告知
            log.warn("[DropGround] {} uid={} 地图满({}) 掉落被丢弃", session.getCharacterName(), req.getUid(), GroundItemManager.STG_ITEM_MAX);
            pushRemove(session, req.getUid());
            refreshPlayerStats(session, p);
            sendSystemMessageKey(session, "chat.cmd.dropOverLimit");
            return;
        }
        pushRemove(session, req.getUid());
        refreshPlayerStats(session, p);
        log.info("[DropGround] {} uid={} → groundItem id={} @({},{})",
            session.getCharacterName(), req.getUid(), gi.id, (float) gi.x, (float) gi.z);
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

    private void sendSystemMessageKey(PlayerSession session, String key) {
        if (session == null) {
            return;
        }
        session.send(MessageProto.ServerMessage.newBuilder()
                .setSystemMessage(MessageProto.S2C_SystemMessage.newBuilder()
                        .setKey(key)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }
}
