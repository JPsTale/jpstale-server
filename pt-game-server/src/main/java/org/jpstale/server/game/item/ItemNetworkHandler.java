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
    private final org.jpstale.server.game.network.MessageSender messageSender;


    private final ItemService itemService;
    private final PlayerService playerService;
    private final org.jpstale.server.game.service.AppearanceService appearanceService;
    private final org.jpstale.server.game.service.AOIManager aoiManager;
    private final GroundItemManager groundItems;
    private final org.jpstale.server.game.service.TeleportService teleportService;
    private final org.jpstale.server.game.service.MapManager mapManager;

    public ItemNetworkHandler(ItemService itemService, PlayerService playerService,
                              org.jpstale.server.game.service.AppearanceService appearanceService,
                              org.jpstale.server.game.service.AOIManager aoiManager,
                              GroundItemManager groundItems,
                              org.jpstale.server.game.service.TeleportService teleportService,
                              org.jpstale.server.game.service.MapManager mapManager,
                              org.jpstale.server.game.network.MessageSender messageSender) {
        this.itemService = itemService;
        this.playerService = playerService;
        this.appearanceService = appearanceService;
        this.aoiManager = aoiManager;
        this.groundItems = groundItems;
        this.teleportService = teleportService;
        this.mapManager = mapManager;
        this.messageSender = messageSender;
    }

    // ------------------------------------------------------------------
    // 消耗品使用（背包右键 / 药水槽快捷键，同一入口）
    // ------------------------------------------------------------------

    /** idcode 家族（原版 `sinITEM_MASK2` 口径）：高 16 位 */
    private static int familyOf(int idCode) {
        return (idCode >>> 16) & 0xFFFF;
    }

    /** 药水族（`sinPL1 = 0x0401`，PL101/PL102… 生命/魔法/体力药水） */
    private static final int FAMILY_POTION = 0x0401;

    /**
     * `C2S_UseItem`：使用背包里的一个消耗品。客户端只报 **uid（+数量）**，其余全服务端判。
     *
     * 流程：查物品 → **按 idcode 家族分发**（对齐原版 `cINVENTORY::RButtonDown` 的
     * `CODE & sinITEM_MASK2` 分派）→ 能落地效果**才**扣道具 → 推送背包变化。
     *
     * 落地判据（按家族）：
     *   · 传送类（目的地表里有这个 idcode）→ `TeleportService`：先 `canTeleportTo`（含等级门槛），
     *     再解析落点，都通过才扣；任何一步不过 → **可见提示**且不扣道具
     *   · 药水族 → 回复 HP/MP/SP（数值待补：`items-11job.json` 目前没保留 `*생명력상승` 等列）
     *   · 其它家族 → 明确回"暂未实现"，**不静默、不错扣**
     */
    /** 客户端 STATE 枚举里的 EAT（`anim-state-machine.ts` 的 `EAT = 0x0140`） */
    private static final int ANIM_STATE_EAT = 0x0140;

    /** 药水 → 向 AOI 广播一次 EAT 动作（位置/朝向取当前实体，只作动作载体） */
    private void broadcastEatIfPotion(PlayerSession session, Player p, long uid) {
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null) {
            return;
        }
        ItemInstance it = p.getItems().byUid(uid);
        if (it == null || it.getTemplate() == null || !ItemClass.isPotion(it.getTemplate().getClassItem())) {
            return;
        }
        MessageProto.S2C_PlayerMove move = MessageProto.S2C_PlayerMove.newBuilder()
                .setPlayerId(p.getId())
                .setPosition(org.jpstale.server.proto.base.CommonProto.Position.newBuilder()
                        .setX((float) ent.getX()).setY((float) ent.getY()).setZ((float) ent.getZ()).build())
                .setAngle((float) ent.getAngle())
                .setAnimState(ANIM_STATE_EAT)
                .setTimestamp(System.currentTimeMillis())
                .build();
        messageSender.broadcastToArea(ent.getMapId(), (float) ent.getX(), (float) ent.getZ(), 50,
                MessageProto.ServerMessage.newBuilder().setPlayerMove(move).build());
    }

    @GamePacketHandler(MessageProto.ClientMessage.USE_ITEM_FIELD_NUMBER)
    public void handleUseItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_UseItem req = message.getUseItem();
        // 使用**药水**时把 EAT 动作广播给 AOI（原版 sinActionPotion → CHRMOTION_STATE_EAT）。
        // 收到请求即发：原版是点击瞬间本地切动作，服务端不等效果结算；
        // 旁观者按 anim_state 本地匹配自己那套 EAT 条目（服务端不解释动画数据，只透传状态）。
        broadcastEatIfPotion(session, p, req.getUid());
        ItemInstance it = p.getItems().byUid(req.getUid());
        // 可使用的位置：**背包**，以及**药水快捷槽**（ITEMSLOT 11/12/13）——
        // 后者就是"按数字键 1/2/3 吃药"的链路（docs/pt-core-gameplay.md 19 节）。
        if (it == null || it.isDeleted() || !isUsableLocation(it)) {
            sendErrorKey(session, "chat.cmd.useItemNotInBag");
            return;
        }
        int qty = Math.max(1, req.getQuantity());
        int idCode = it.getItemCode() != null ? it.getItemCode() : 0;
        int family = familyOf(idCode);

        // ---- ① 传送类：固定目的地写在 TeleportService 的代码表里（照原版 switch；不依赖 DB）----
        org.jpstale.server.game.service.TeleportService.ItemDestination dest = teleportService.destinationOf(idCode);
        if (dest != null) {
            org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
            if (ent == null) {
                return;
            }
            if (!teleportService.canTeleportTo(p, dest.destMap(), org.jpstale.server.game.service.TeleportService.Reason.ITEM)) {
                return;   // 门槛/目标非法：canTeleportTo 已给可见提示，且**没扣道具**
            }
            double[] pos = teleportService.resolveLanding(dest.destMap(), dest.landing(),
                    null, null, ent.getX(), ent.getZ());
            if (pos == null) {
                log.warn("[UseItem] {} idCode={} 目标图 {} 无可用落点（landing={}）→ 拒绝且不扣道具",
                        p.getName(), idCode, dest.destMap(), dest.landing());
                sendErrorKey(session, "chat.cmd.teleportNoLanding");
                return;
            }
            ItemInstance used = itemService.consumeAt(p, req.getUid(), qty);
            if (used == null) {
                sendErrorKey(session, "chat.cmd.useItemFailed");
                return;
            }
            pushAfterUse(session, used);
            boolean ok = teleportService.teleport(p, dest.destMap(), pos[0], pos[1],
                    org.jpstale.server.game.service.TeleportService.Reason.ITEM);
            log.info("[UseItem] {} idCode=0x{} → 传送 map {} ({},{}) ok={} 依据: {}",
                    p.getName(), Integer.toHexString(idCode), dest.destMap(),
                    (int) pos[0], (int) pos[1], ok, dest.note());
            return;
        }

        // ---- ② 回复类（药水）：数值就是 gamedb.itemlist 的 recovery* 三列（ItemList 已映射）----
        // 判据用**数据**（recovery 非零）而不是 idcode 族：`items-11job.json` 里 Life/Mana 的
        // idcode 家族与 DB 相反（见 docs/传送系统.md §6），按数据判就不会被那处冲突传染。
        int[] rec = rollRecovery(it.getTemplate());
        if (rec != null) {
            ItemInstance used = itemService.consumeAt(p, req.getUid(), qty);
            if (used == null) {
                sendErrorKey(session, "chat.cmd.useItemFailed");
                return;
            }
            pushAfterUse(session, used);
            applyRecovery(session, p, rec, it);
            return;
        }

        // ---- ②b 占药水槽但没有任何回复数值：说明是别的使用类（增益/力量石…）→ 可见拒绝 ----
        if (family == FAMILY_POTION || ItemClass.isPotion(it.getTemplate() != null ? it.getTemplate().getClassItem() : 0)) {
            sendErrorKey(session, "chat.cmd.useItemPotionPending");
            log.info("[UseItem] {} 药水槽物品 idCode={} 无 recovery 数值 → 视作未实现的增益类，未消耗",
                    p.getName(), idCode);
            return;
        }

        // ---- ③ 其它：明确回"暂未实现"，把 idcode 打进日志便于逐个补 ----
        sendErrorKey(session, "chat.cmd.useItemUnsupported");
        log.info("[UseItem] {} 未支持的消耗品 family=0x{} idCode={} name={}（未消耗）",
                p.getName(), Integer.toHexString(family), idCode,
                it.getTemplate() != null ? it.getTemplate().getName() : "?");
    }

    // ---------------- 回复类（药水）效果 ----------------

    /** 掷一次回复量 [hp, mp, stm]；三者全 0 → null（不是回复类，别乱扣） */
    private static int[] rollRecovery(org.jpstale.dao.gamedb.entity.ItemList t) {
        if (t == null) {
            return null;
        }
        int hp = roll(t.getRecoveryHpMin(), t.getRecoveryHpMax());
        int mp = roll(t.getRecoveryMpMin(), t.getRecoveryMpMax());
        int stm = roll(t.getRecoveryStmMin(), t.getRecoveryStmMax());
        return (hp == 0 && mp == 0 && stm == 0) ? null : new int[]{hp, mp, stm};
    }

    /** [min,max] 闭区间掷点（原版药水是区间随机；min==max 取该值；max<=0 → 0） */
    private static int roll(Integer min, Integer max) {
        int a = min == null ? 0 : min;
        int b = max == null ? 0 : max;
        if (b <= 0) return 0;
        if (a > b) a = b;
        if (a == b) return b;
        return a + java.util.concurrent.ThreadLocalRandom.current().nextInt(b - a + 1);
    }

    /** 应用到角色（clamp 到上限）→ 推权威状态刷 HUD。满值时不特殊处理（原版也照喝照扣） */
    private void applyRecovery(PlayerSession session, Player p, int[] rec, ItemInstance src) {
        if (rec[0] > 0) p.setHp(Math.min(p.getMaxHp(), p.getHp() + rec[0]));
        if (rec[1] > 0) p.setMp(Math.min(p.getMaxMp(), p.getMp() + rec[1]));
        if (rec[2] > 0) p.setSp(Math.min(p.getMaxSp(), p.getSp() + rec[2]));
        playerService.sendPlayerStatus(session, p);
        log.info("[UseItem] {} 使用 {} → HP+{} MP+{} STM+{}（现 {}/{} {} {}）",
                p.getName(),
                src.getTemplate() != null ? src.getTemplate().getName() : "?",
                rec[0], rec[1], rec[2],
                p.getHp(), p.getMaxHp(), p.getMp(), p.getSp());
    }

    /** 用掉之后把背包变化推给客户端：堆叠没耗尽推 update、耗尽推 remove */
    private void pushAfterUse(PlayerSession session, ItemInstance used) {
        if (used.getCount() > 0) {
            pushUpdate(session, used);
        } else {
            pushRemove(session, used.getId());
        }
    }

    // ------------------------------------------------------------------
    // 进图快照（selectCharacter 完成后由 PlayerService/AccountService 调用）
    // ------------------------------------------------------------------

    public void sendInventorySnapshot(PlayerSession session, Player player) {
        MessageProto.S2C_InventorySnapshot.Builder snap = MessageProto.S2C_InventorySnapshot.newBuilder()
                .setGold(player.getGold());
        PlayerItems items = player.getItems();
        if (items != null) {
            // EQUIP 段含**鼠标位**（slot=-1）→ 断线重连/重登时把"手上还拿着的那件"一并下发，
            // 客户端据此原样恢复手持（用户 2026-09-14 定：重登必须还原）。
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
                .setRange(it.getShootingRange())
                .setAttackSpeed(it.getAttackSpeed())
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
    /** 该物品当前位置是否允许"使用"：背包，或药水快捷槽。
     *  （无注解 —— 只被 handleUseItem 调用；注解必须紧贴 public 入口方法，
     *   否则注册器 getMethods() 扫不到，消息会静默变成 "No handler registered"。） */
    private static boolean isUsableLocation(ItemInstance it) {
        if (it.getLocation() == ItemLocations.BAG) {
            return true;
        }
        return it.getLocation() == ItemLocations.EQUIP
                && org.jpstale.server.game.item.EquipSlots.isPotionSlot(it.getSlot());
    }

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
        int lastSeqBefore = p.getItems().lastSeq();
        boolean ok = itemService.applyBagLayout(p, seq, entries);
        if (!ok) {
            // ⚠ 非乱序的失败必须**回错**：过去这里什么都不发 → 客户端乐观改动永不回滚，
            // 屏幕上留下"两件叠在同一格"（用户 2026-09-14 实测）。乱序/重放是正常丢弃，不该报错。
            if (seq > lastSeqBefore) {
                sendErrorKey(session, "item.op.failed");
            }
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

    /**
     * **换手**：手上那件 ↔ 背包/仓库里某件，原子互换（原版 `ChangeInvenItem` 的换手）。
     * 客户端在"拿着 A 点已占格上的 B"时发这条；服务端用两行互换的写库原语落地。
     */
    @GamePacketHandler(MessageProto.ClientMessage.BAG_SWAP_FIELD_NUMBER)
    public void handleBagSwap(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_BagSwap req = message.getBagSwap();
        ItemService.OpResult r = itemService.swapWithHand(p, req.getHandUid(), req.getTargetUid());
        if (r.reason != ItemService.OpReason.OK) {
            sendErrorKey(session, "item.op." + opKeySuffix(r.reason));   // 客户端据前缀回滚两件
            return;
        }
        // 换到手上那件（目标）+ 落到目标格那件（原手上）都要推
        ItemInstance nowHeld = r.instance;
        pushUpdate(session, nowHeld);
        ItemInstance moved = p.getItems().byUid(req.getHandUid());
        if (moved != null && !moved.isDeleted()) {
            pushUpdate(session, moved);
        }
        refreshPlayerStats(session, p);   // 换下来的若原本在装备位？不会（目标必须在画布上）→ 但负重/外观保持一致
        log.info("[Swap] {} hand={} target={} 完成", session.getCharacterName(), req.getHandUid(), req.getTargetUid());
    }

    /**
     * **拿起**：任意容器 → 鼠标位（装备栏 `slot = -1`）。见 `ItemLocations.HELD_SLOT`。    /**
     * **拿起**：任意容器 → 鼠标位（装备栏 `slot = -1`）。见 `ItemLocations.HELD_SLOT`。
     * 放下不需要配套消息：放到装备槽/药水槽走 `EquipItem`、放到背包/仓库格走 `BagLayout`、丢地上走 `DropItem`，
     * 这些路径现在都接受"来源 = 鼠标位"。
     */
    @GamePacketHandler(MessageProto.ClientMessage.TAKE_TO_HAND_FIELD_NUMBER)
    public void handleTakeToHand(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        long uid = message.getTakeToHand().getUid();
        ItemService.OpResult r = itemService.takeToHand(p, uid);
        if (r.reason != ItemService.OpReason.OK) {
            sendErrorKey(session, "item.op." + opKeySuffix(r.reason));
            return;
        }
        pushUpdate(session, r.instance);
        // 拿起装备 → 立刻掉属性/换外观（原版拿起即 sinSetCharItem(FALSE) + CheckWeight）
        refreshPlayerStats(session, p);
    }

    /** 穿装备：背包 → 装备槽 */
    @GamePacketHandler(MessageProto.ClientMessage.EQUIP_ITEM_FIELD_NUMBER)
    public void handleEquipItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_EquipItem req = message.getEquipItem();
        long srcUid = req.getUid();
        ItemService.OpResult r = itemService.equipFromBag(p, srcUid, req.getEquipSlot());
        if (r.reason != ItemService.OpReason.OK) {
            // 按**原因**回 key：原版 CheckSetOk 也是按原因分别弹 MESSAGE_OVER_WEIGHT / MESSAGE_NO_USE_ITEM。
            // key 前缀 `item.op.` 同时是客户端的"回滚乐观更新"信号（不再靠字符串匹配）。
            sendErrorKey(session, "item.op." + opKeySuffix(r.reason));
            return;
        }
        ItemInstance equipped = r.instance;
        pushUpdate(session, equipped);
        // **拆堆搬入**（药水槽：超出容量的部分留在背包，`putPotionToSlot`）走的是"新建槽内记录 +
        // 扣减源堆"，所以**源堆不是** r.instance → 必须单独推一次，否则客户端背包里那堆
        // 数量停在旧值（服务端只推了新记录）。整堆搬入时 r.instance 就是源记录，跳过。
        if (equipped.getId() == null || equipped.getId() != srcUid) {
            ItemInstance src = p.getItems().byUid(srcUid);
            if (src != null && !src.isDeleted() && src.getCount() > 0) {
                pushUpdate(session, src);
            }
        }
        refreshPlayerStats(session, p);
        // 被换下的件（同槽旧件 / 双手武器另一只手那件）已进背包 → 推它的新位置。
        // 这一步不能省：客户端只知道"新件进槽了"，被换下那件就成了界面上的幽灵 ——
        // 既不在原来的槽（被占了）也不在背包（没收到通知），用户看到的是"直接消失了"。
        for (ItemInstance d : r.displaced) {
            pushUpdate(session, d);
        }
    }

    /** OpReason → i18n key 后缀（客户端 `locales/*.json` 的 `item.op.*`） */
    private static String opKeySuffix(ItemService.OpReason r) {
        switch (r) {
            case NOT_IN_BAG: return "notInBag";
            case JOB_NOT_ALLOWED: return "jobNotAllowed";
            case SLOT_MISMATCH: return "slotMismatch";
            case REQ_NOT_MET: return "reqNotMet";
            case OVER_WEIGHT: return "overWeight";
            case TWO_HAND_SLOT: return "twoHandSlot";
            case NOT_POTION: return "notPotion";
            case SLOT_FULL: return "slotFull";
            case DIFFERENT_POTION: return "differentPotion";
            case BAG_FULL: return "bagFull";
            case HAND_BUSY: return "handBusy";
            case NOT_HOLDABLE: return "notHoldable";
            default: return "failed";
        }
    }

    /** 脱装备：装备槽 → 背包 */
    @GamePacketHandler(MessageProto.ClientMessage.UNEQUIP_ITEM_FIELD_NUMBER)
    public void handleUnequipItem(PlayerSession session, MessageProto.ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_UnequipItem req = message.getUnequipItem();
        ItemService.OpReason reason = itemService.unequipToBag(p, req.getEquipSlot());
        if (reason != ItemService.OpReason.OK) {
            sendErrorKey(session, "item.op." + opKeySuffix(reason));
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
        Long cid = session.getCharacterId();
        if (gi.ownerId != 0 && (cid == null || gi.ownerId != cid)) {
            log.info("[Pickup] {} gid={} : not owner", session.getCharacterName(), gid);
            return;
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
        // ① 负重：原版在拾取入口就查，超重则整次拾取拒绝（物品留在地上）
        if (itemService.pickupOverWeight(p, gi.item)) {
            log.info("[Pickup] {} gid={} : over weight → 保持原地", session.getCharacterName(), gid);
            sendSystemMessageKey(session, "chat.pickup.overWeight");
            return;
        }
        // ② 药水优先填药水槽（原版 AutoSetPotion）：灌不完的余数才继续走"上手 / 进背包"
        java.util.List<ItemInstance> potionSlots = itemService.pourIntoPotionSlots(p, gi.item);
        for (ItemInstance t : potionSlots) {
            pushUpdate(session, t);
        }
        ItemInstance granted = null;
        if (gi.item.getCount() > 0) {
            // ③ 余数：拾取上手（原版背包窗口开着时 `memcpy(&MouseItem, ...)`，不走背包也不需要空格）
            if (message.getPickupItem().getToHand()) {
                granted = itemService.grantToHand(p, gi.item);
            }
            // ④ 还落不下就进背包（手位被占 / 未要求上手）；背包满则保持原地
            if (granted == null) {
                ItemService.GrantResult result = itemService.grantInstanceToBag(p, gi.item);
                if (result.reason == ItemService.GrantReason.BAG_FULL) {
                    log.info("[Pickup] {} gid={} : bag full（药水槽已收 {}）→ 余数留在地上",
                            session.getCharacterName(), gid, potionSlots.size());
                    sendSystemMessageKey(session, "chat.pickup.bagFull");
                    return;
                }
                granted = result.instance;
            }
        }
        // 整堆都被药水槽吃掉 → 地上那件已耗尽；否则移除地面物并广播消失
        if (gi.item.getCount() > 0) {
            groundItems.remove(ent.getMapId(), gid);
            broadcastDisappear(ent.getMapId(), gi.x, gi.z, gid);
        }
        if (granted != null) {
            log.info("[Pickup] {} gid={} granted id={} itemListId={} name={} @loc={}/slot={}",
                session.getCharacterName(), gid, granted.getId(), granted.getItemListId(),
                granted.getTemplate() != null ? granted.getTemplate().getName() : "?",
                granted.getLocation(), granted.getSlot());
            pushUpdate(session, granted);
        }
        refreshPlayerStats(session, p); // 负重/属性（拿起装备会撤效果）需要更新
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
            sendErrorKey(session, "item.op.failed");
            return;
        }
        // 禁丢清单（原版 NotDrow_Item_*，见 ItemRules）：任务物品等不许丢到地面。
        // 权威判定在这里；客户端也有一份同样的预校验（免得本地先移除、服务端却拒绝）。
        ItemInstance toDrop = p.getItems().byUid(req.getUid());
        if (toDrop == null) {
            sendErrorKey(session, "item.op.failed");
            return;
        }
        int dropCode = toDrop.getItemCode() != null ? toDrop.getItemCode() : 0;
        if (!ItemRules.isDroppable(dropCode)) {
            log.info("[DropItem] {} 拒绝丢弃 uid={} idCode=0x{}（禁丢清单：任务物品）",
                    p.getName(), req.getUid(), Integer.toHexString(dropCode));
            sendErrorKey(session, "item.op.notDroppable");
            return;
        }
        // 丢到地面（对齐原版 ThrowItem）：从背包/装备取出 → 玩家附近生成地面物
        ItemInstance dropped = itemService.removeToGround(p, req.getUid());
        if (dropped == null) {
            sendErrorKey(session, "item.op.failed");
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
        for (ItemInstance it : p.getItems().equippedItems()) {   // 排除鼠标位（推送装备槽变化，不该推手上那件）
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
        for (ItemInstance it : p.getItems().equippedItems()) {   // 排除鼠标位（推送装备槽变化，不该推手上那件）
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
