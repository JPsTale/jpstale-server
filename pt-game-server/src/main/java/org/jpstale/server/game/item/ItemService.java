package org.jpstale.server.game.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Player;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 物品操作服务（服务端权威）：发放/画布移动/穿脱/丢弃/堆叠。
 * <p>
 * 所有操作：(1) 内存改 PlayerItems（位图+引用）→ (2) DB 写该行。
 * DB 是最终一致源；崩溃丢尾部可接受（同原版 dat 语义）。属性重算/网络推送由调用方触发。
 */
@Slf4j
@Service
public class ItemService {

    private final ItemRollService roll;
    private final ItemStorageService storage;
    private final org.jpstale.server.game.service.PlayerStatCalculator statCalculator;

    public ItemService(ItemRollService roll, ItemStorageService storage,
                       org.jpstale.server.game.service.PlayerStatCalculator statCalculator) {
        this.roll = roll;
        this.storage = storage;
        this.statCalculator = statCalculator;
    }

    /**
     * 掷点并发放一件到玩家背包（GM/拾取/奖励入口）。
     * <p>
     * 可堆叠物品：优先并入已有同种堆叠（count 相加），无同种或无法并入才开新格。
     *
     * @return 发放的实例（新开格）或并入的已有实例；背包满/模板缺失返回 null
     */
    @Transactional
    public ItemInstance grantToBag(Player player, int itemListId, Integer jobCodeMask) {
        ItemInstance fresh = roll.rollById(itemListId, jobCodeMask);
        if (fresh == null) {
            return null;
        }
        GrantResult r = grantInstanceToBag(player, fresh);
        return r.instance; // GM/奖励路径：失败返回 null 即可（不给理由）
    }

    /**
     * 把一件已掷点物品放入背包（保留其随机属性；丢弃/拾取/掉落通用）。
     * 堆叠物优先并入已有堆；无空位返回原因（背包满/超重）。
     */
    @Transactional
    public GrantResult grantInstanceToBag(Player player, ItemInstance fresh) {
        PlayerItems items = player.getItems();
        fresh.setCharacterId(Math.toIntExact(player.getId()));
        fresh.setLocation(ItemLocations.BAG_PAGE);
        fresh.setSlot(0);
        // 负重预检（对齐原版 CheckWeight → Weight[0] > Weight[1] 语义）
        if (statCalculator.isOverWeight(player, fresh)) {
            return GrantResult.OVER_WEIGHT;
        }
        // 堆叠物先尝试并入已有
        if (fresh.stackable()) {
            for (ItemInstance existing : items.itemsIn(ItemLocations.BAG_PAGE)) {
                if (existing.getItemListId().equals(fresh.getItemListId())
                        && !existing.isDeleted()
                        && existing.getCount() > 0) {
                    int add = Math.min(fresh.getCount(), 1000 - existing.getCount());
                    if (add > 0) {
                        existing.setCount(existing.getCount() + add);
                        storage.update(existing);
                        return GrantResult.ok(existing);
                    }
                }
            }
        }
        int slot = items.canvas(ItemLocations.BAG_PAGE).findFreeSlot(fresh.gridW(), fresh.gridH());
        if (slot < 0) {
            return GrantResult.BAG_FULL; // 背包满
        }
        fresh.setSlot(slot);
        if (fresh.getId() != null) {
            // 已有 DB 行（丢地软删后拾回）：恢复原行，避免重复
            storage.restore(fresh);
        } else {
            storage.insert(fresh);
        }
        items.index(fresh);
        return GrantResult.ok(fresh);
    }

    /** grantInstanceToBag 结果（成功实例 or 失败原因） */
    public enum GrantReason { OK, BAG_FULL, OVER_WEIGHT }

    public static final class GrantResult {
        public final GrantReason reason;
        public final ItemInstance instance;
        private GrantResult(GrantReason reason, ItemInstance instance) {
            this.reason = reason;
            this.instance = instance;
        }
        public static GrantResult ok(ItemInstance it) { return new GrantResult(GrantReason.OK, it); }
        public static final GrantResult BAG_FULL = new GrantResult(GrantReason.BAG_FULL, null);
        public static final GrantResult OVER_WEIGHT = new GrantResult(GrantReason.OVER_WEIGHT, null);
    }

    /**
     * 画布内移动/换格（含跨画布 背包↔仓库）。目标格空才可放（位图校验）。
     *
     * @return true=成功
     */
    @Transactional
    /**
     * 背包画布落子（对齐原版拖放语义）：
     *  - 空位：直接放置；
     *  - 命中 1 件且同为可堆叠物品：合并数量（目标保留，源软删）；
     *  - 命中恰 1 件（异种/不可叠）：换手 —— 源放落到目标格，被撞件移到背包空位
     *    （客户端将其表现为"拿起被撞件"继续拖放，ChangeInvenItem 语义）；
     *  - 命中 ≥2 件/无空位：拒绝。
     */
    public BagMoveResult moveToBagCanvas(Player player, long uid, int toSlot) {
        BagMoveResult r = new BagMoveResult();
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.isDeleted() || it.getLocation() != ItemLocations.BAG) {
            return r;
        }
        if (it.getSlot() == toSlot) {
            r.ok = true;
            r.placed = it;
            return r;
        }
        CanvasGrid cg = items.canvas(ItemLocations.BAG);
        if (cg == null) {
            return r;
        }
        int x = cg.xOf(toSlot);
        int y = cg.yOf(toSlot);
        int gw = it.gridW();
        int gh = it.gridH();
        if (x + gw > cg.width() || y + gh > cg.height()) {
            log.info("[BagMove] {} uid={} → slot{} : 越界", player.getName(), uid, toSlot);
            return r;
        }
        // 与目标占格交叠的其它背包物品（先于 canPlace：占用即换手/合并，非直接失败）
        java.util.List<ItemInstance> occ = new java.util.ArrayList<>();
        for (ItemInstance o : items.itemsIn(ItemLocations.BAG)) {
            if (o.getId().equals(uid) || o.isDeleted()) {
                continue;
            }
            int ox = cg.xOf(o.getSlot());
            int oy = cg.yOf(o.getSlot());
            if (rectHit(ox, oy, o.gridW(), o.gridH(), x, y, gw, gh)) {
                occ.add(o);
            }
        }
        if (occ.isEmpty()) {
            // 空位（拿起语义：自身当前足迹视为空，允许上移/下移时部分重叠自己）
            int sx = cg.xOf(it.getSlot());
            int sy = cg.yOf(it.getSlot());
            if (!cg.canPlaceExcept(x, y, gw, gh, sx, sy, gw, gh)) {
                log.info("[BagMove] {} uid={} → slot{} : 目标仍被占(非自身)", player.getName(), uid, toSlot);
                return r;
            }
            items.takeFromCanvas(ItemLocations.BAG, it.getSlot());
            it.setLocation(ItemLocations.BAG);
            it.setSlot(toSlot);
            items.putToCanvas(ItemLocations.BAG, toSlot, it);
            items.markDirty(ItemLocations.BAG, toSlot, it.getId());
            storage.update(it);
            r.ok = true;
            r.placed = it;
            log.info("[BagMove] {} uid={} → slot{} : 放置", player.getName(), uid, toSlot);
            return r;
        }
        // 合并（命中 1 件、同定义、可堆叠、总量不超）
        if (occ.size() == 1 && occ.get(0).stackable() && it.stackable()
                && java.util.Objects.equals(occ.get(0).getItemListId(), it.getItemListId())) {
            ItemInstance target = occ.get(0);
            int cap = 1000;
            if (target.getCount() + it.getCount() <= cap) {
                target.setCount(target.getCount() + it.getCount());
                items.takeFromCanvas(ItemLocations.BAG, it.getSlot());
                items.byUidRemove(it.getId());
                items.markDirty(ItemLocations.BAG, target.getSlot(), target.getId());
                storage.update(target);
                storage.softDelete(it.getId());
                r.ok = true;
                r.merged = target;
                r.removedUid = it.getId();
                log.info("[BagMove] {} uid={} → slot{} : 合并到{} 总数{}", player.getName(), uid, toSlot, target.getId(), target.getCount());
                return r;
            }
            log.info("[BagMove] {} uid={} → slot{} : 合并超上限", player.getName(), uid, toSlot);
        }
        // 换手：命中恰 1 件 → 源放落目标，被撞件拿起（客户端呈现）；腾位保证不重叠：
        // 1) 先清出双方（原版拿起语义：源与目标此刻都视为空）
        if (occ.size() == 1) {
            ItemInstance displaced = occ.get(0);
            int oldItSlot = it.getSlot();
            int oldDisSlot = displaced.getSlot();
            items.takeFromCanvas(ItemLocations.BAG, oldDisSlot);
            items.takeFromCanvas(ItemLocations.BAG, oldItSlot);
            boolean sameShape = displaced.gridW() == gw && displaced.gridH() == gh;
            if (sameShape) {
                // 同尺寸：直接互换锚点（不占额外空位，天然无重叠）
                it.setLocation(ItemLocations.BAG);
                it.setSlot(oldDisSlot);
                displaced.setLocation(ItemLocations.BAG);
                displaced.setSlot(oldItSlot);
                items.putToCanvas(ItemLocations.BAG, it.getSlot(), it);
                items.putToCanvas(ItemLocations.BAG, displaced.getSlot(), displaced);
                items.markDirty(ItemLocations.BAG, it.getSlot(), it.getId());
                items.markDirty(ItemLocations.BAG, displaced.getSlot(), displaced.getId());
                storage.update(it);
                storage.update(displaced);
                r.ok = true;
                r.placed = it;
                r.displaced = displaced;
                log.info("[BagMove] {} uid={} → slot{} : 换手(同尺寸互换, displaced uid={}→slot{})",
                    player.getName(), uid, toSlot, displaced.getId(), displaced.getSlot());
                return r;
            }
            // 异尺寸：先把源放入目标格，再为被撞件找空位（位图已含源，绝不与源重叠）
            it.setLocation(ItemLocations.BAG);
            it.setSlot(toSlot);
            items.putToCanvas(ItemLocations.BAG, toSlot, it);
            int freeSlot = cg.findFreeSlot(displaced.gridW(), displaced.gridH());
            if (freeSlot < 0) {
                // 无空位 → 回滚：源放回原格、被撞件放回原格
                items.takeFromCanvas(ItemLocations.BAG, toSlot);
                items.putToCanvas(ItemLocations.BAG, oldDisSlot, displaced);
                items.putToCanvas(ItemLocations.BAG, oldItSlot, it);
                storage.update(it);
                storage.update(displaced);
                log.info("[BagMove] {} uid={} → slot{} : 异尺寸换手无空位 → 回滚", player.getName(), uid, toSlot);
                return r;
            }
            displaced.setLocation(ItemLocations.BAG);
            displaced.setSlot(freeSlot);
            items.putToCanvas(ItemLocations.BAG, freeSlot, displaced);
            items.markDirty(ItemLocations.BAG, toSlot, it.getId());
            items.markDirty(ItemLocations.BAG, freeSlot, displaced.getId());
            storage.update(it);
            storage.update(displaced);
            r.ok = true;
            r.placed = it;
            r.displaced = displaced;
            log.info("[BagMove] {} uid={} → slot{} : 换手(异尺寸, displaced uid={}→slot{})",
                player.getName(), uid, toSlot, displaced.getId(), freeSlot);
            return r;
        }
        log.info("[BagMove] {} uid={} → slot{} : ≥2冲突({})", player.getName(), uid, toSlot, occ.size());
        return r; // ≥2 件冲突：拒绝
    }

    private boolean rectHit(int ox, int oy, int ow, int oh, int x, int y, int w, int h) {
        return ox < x + w && ox + ow > x && oy < y + h && oy + oh > y;
    }

    /**
     * 客户端布局上报（客户端网格权威，全量快照 + 单调递增 seq）：
     * <p>
     * - seq：客户端单调递增序号；服务端记录 lastSeq，丢弃 seq<=lastSeq 的乱序/重放包。
     * - 校验每件：属于本角色 / location 合法且 slot 界内且 footprint 不越界 / 无重复目标格 /
     *   装备↔背包 特殊规则（装备落背包目标格必须为空，不做换位）。
     * - 全部合法才执行：先腾出涉及物品 → 逐件落到目标格（跨容器）→ 写库。
     * - 不做重叠裁决、不回推旧快照（作弊按 §7 软删 + RemovedUids）。
     *
     * @return 成功 true；乱序（seq<=lastSeq）也返回 false（该包整体丢弃，不改格子）
     */
    @Transactional
    public boolean applyBagLayout(Player player, int seq,
                                  java.util.List<? extends BagLayoutEntry> entries) {
        PlayerItems items = player.getItems();
        if (seq <= items.lastSeq()) {
            log.warn("[BagLayout] {} 丢弃乱序/重放 seq={} (lastSeq={})",
                player.getName(), seq, items.lastSeq());
            return false;
        }
        if (entries == null || entries.isEmpty()) {
            items.setLastSeq(seq); // 空快照仍推进 seq（客户端清空动作）
            return true;
        }
        java.util.List<ItemInstance> involved = new java.util.ArrayList<>(entries.size());
        java.util.Set<String> targetKeys = new java.util.HashSet<>();
        java.util.Map<Long, Integer> uidToSlot = new java.util.HashMap<>();
        java.util.Map<Long, Integer> uidToLocation = new java.util.HashMap<>();
        java.util.List<ItemInstance> fromEquip = new java.util.ArrayList<>();
        for (BagLayoutEntry e : entries) {
            if (e == null || e.uid() == null) {
                return false;
            }
            ItemInstance it = items.byUid(e.uid());
            if (it == null || it.isDeleted()) {
                log.warn("[BagLayout] {} 拒绝: uid={} 不存在或已软删",
                    player.getName(), e.uid());
                return false;
            }
            int toLocation = e.location();
            int slot = e.slot();
            boolean srcBag = ItemLocations.isBagPage(it.getLocation());
            boolean srcWarehouse = ItemLocations.isWarehousePage(it.getLocation());
            boolean srcEquip = it.getLocation() == ItemLocations.EQUIP
                || it.getLocation() == ItemLocations.BACKUP_EQUIP;
            if (!srcBag && !srcWarehouse && !srcEquip) {
                log.warn("[BagLayout] {} 拒绝: uid={} 源位置 location={} 非法",
                    player.getName(), e.uid(), it.getLocation());
                return false; // 任务栏/商店等未启用容器
            }
            // 目标容器校验：BagLayout 只接受画布落子（背包页/仓库页）。
            // 装备/副装备槽走 EquipItem/UnequipItem/SwitchWeapon（带 slotAllows/属性重算），
            // 不在布局上报路径内（防任意装备槽后门）。
            boolean dstBag = ItemLocations.isBagPage(toLocation);
            boolean dstWarehouse = ItemLocations.isWarehousePage(toLocation);
            if (!dstBag && !dstWarehouse) {
                log.warn("[BagLayout] {} 拒绝: uid={} 目标 location={} 非画布（装备槽不经布局上报）",
                    player.getName(), e.uid(), toLocation);
                return false;
            }
            // 装备只能落非背包格（防写坏）；背包/仓库互不越界
            CanvasGrid cg = items.canvas(toLocation);
            if (cg != null) {
                int x = cg.xOf(slot);
                int y = cg.yOf(slot);
                if (x + it.gridW() > cg.width() || y + it.gridH() > cg.height()) {
                    log.warn("[BagLayout] {} 拒绝: uid={} slot={} 越界 loc={} w={} h={}",
                        player.getName(), e.uid(), slot, toLocation, it.gridW(), it.gridH());
                    return false;
                }
            } else {
                // 非画布（装备栏/副装备栏）：槽号压 1~13 段
                if (slot < 1 || slot > 13) {
                    log.warn("[BagLayout] {} 拒绝: uid={} 装备槽 slot={} 越界",
                        player.getName(), e.uid(), slot);
                    return false;
                }
            }
            String key = toLocation + ":" + slot;
            if (!targetKeys.add(key)) {
                log.warn("[BagLayout] {} 拒绝: uid={} 重复目标格 ({})",
                    player.getName(), e.uid(), key);
                return false; // 重复目标格
            }
            // 从装备套/备用套 → 背包 目标格必须为空（不做换位/合并）。
            // 严格原版：装备只能卸到背包，不能直入仓库（需先卸包再转）。
            if ((srcEquip) && dstBag) {
                if (cg == null || !cg.canPlace(cg.xOf(slot), cg.yOf(slot), it.gridW(), it.gridH())) {
                    log.warn("[BagLayout] {} 拒绝: uid={} 装备→画布 target slot={}(loc={}) 被占/越界 w={} h={}",
                        player.getName(), e.uid(), slot, toLocation, it.gridW(), it.gridH());
                    return false;
                }
            }
            if (srcEquip && dstWarehouse) {
                log.warn("[BagLayout] {} 拒绝: uid={} 装备/副装备不能直入仓库",
                    player.getName(), e.uid());
                return false;
            }
            involved.add(it);
            if (srcEquip) {
                fromEquip.add(it);
            }
            uidToSlot.put(it.getId(), slot);
            uidToLocation.put(it.getId(), toLocation);
        }
        // 执行：先全部腾出（含旧容器格），再按目标落子（顺序安全，不产生临时重叠）
        for (ItemInstance it : involved) {
            items.byUidRemove(it.getId());
        }
        for (ItemInstance it : involved) {
            int slot = uidToSlot.get(it.getId());
            int toLocation = uidToLocation.get(it.getId());
            it.setLocation(toLocation);
            it.setSlot(slot);
            if (ItemLocations.isCanvas(toLocation)) {
                items.putToCanvas(toLocation, slot, it);
            } else {
                items.byUidPut(it);
            }
            storage.update(it);
        }
        items.rebuildBitmaps();
        for (ItemInstance it : involved) {
            log.info("[BagLayout] {} 落子: uid={} name={} → location={} slot={} (w={},h={})",
                player.getName(), it.getId(),
                it.getTemplate() != null ? it.getTemplate().getName() : "?",
                it.getLocation(), it.getSlot(), it.gridW(), it.gridH());
        }
        items.setLastSeq(seq);
        log.info("[BagLayout] {} 提交 {} 件 seq={}", player.getName(), involved.size(), seq);
        return true;
    }

    /** 布局上报条目适配（C2S_BagLayout 的 SnapshotEntry） */
    public interface BagLayoutEntry {
        Long uid();
        int location();
        int slot();
    }

    /**
     * 把物品从玩家身上取出用于"丢到地面"：从所在容器移除并软删 DB 行；
     * 返回脱离的实例（调用方负责在玩家附近生成地面物并广播）。
     */
    public ItemInstance removeToGround(Player player, long uid) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.isDeleted()) {
            return null;
        }
        items.byUidRemove(uid);
        storage.softDelete(uid);
        // 保留 id：拾回时按"恢复软删行"处理（有 id 恢复原行、无 id 才 INSERT）
        it.setDeleted(false);
        log.info("[DropGround] {} 取出 uid={} name={} 用于丢地", player.getName(), uid,
            it.getTemplate() != null ? it.getTemplate().getName() : "?");
        return it;
    }

    /**
     * 药水堆叠合并：src 并入 dst（同 itemlist、均可堆叠、容量允许）。
     */
    public ItemInstance mergeStack(Player player, long srcUid, long dstUid) {
        PlayerItems items = player.getItems();
        ItemInstance src = items.byUid(srcUid);
        ItemInstance dst = items.byUid(dstUid);
        if (src == null || dst == null || src.getId().equals(dst.getId())
                || src.getLocation() != ItemLocations.BAG || dst.getLocation() != ItemLocations.BAG
                || !src.stackable() || !dst.stackable()
                || !java.util.Objects.equals(src.getItemListId(), dst.getItemListId())) {
            return null;
        }
        int cap = 1000;
        if (dst.getCount() + src.getCount() > cap) {
            return null;
        }
        dst.setCount(dst.getCount() + src.getCount());
        items.takeFromCanvas(ItemLocations.BAG, src.getSlot());
        items.byUidRemove(src.getId());
        items.markDirty(ItemLocations.BAG, dst.getSlot(), dst.getId());
        storage.update(dst);
        storage.softDelete(src.getId());
        log.info("[StackMerge] {} srcUid={} → dstUid={} total={}", player.getName(), srcUid, dstUid, dst.getCount());
        return dst;
    }

    /** 背包画布落子结果（供 handler 推送 itemUpdate/ItemRemove） */
    public static class BagMoveResult {
        public boolean ok;
        /** 落下的物品（移动到目标格） */
        public ItemInstance placed;
        /** 合并后保留的堆叠（药水合并）；removedUid 为被并入的源 */
        public ItemInstance merged;
        public Long removedUid;
        /** 换手时被撞件的新位置（客户端将其当作"拿起"） */
        public ItemInstance displaced;
    }

    public boolean moveOnCanvas(Player player, long uid, int toLocation, int toSlot) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.isDeleted()) {
            return false;
        }
        if (it.getLocation() == toLocation && it.getSlot() == toSlot) {
            return true;
        }
        if (!ItemLocations.isCanvas(toLocation)) {
            return false;
        }
        CanvasGrid cg = items.canvas(toLocation);
        if (cg == null) {
            return false;
        }
        int x = cg.xOf(toSlot);
        int y = cg.yOf(toSlot);
        if (!cg.canPlace(x, y, it.gridW(), it.gridH())) {
            return false; // 目标被占/越界
        }
        // 从源移除
        if (ItemLocations.isCanvas(it.getLocation())) {
            items.takeFromCanvas(it.getLocation(), it.getSlot());
        } else {
            items.itemsIn(it.getLocation()).removeIf(i -> i.getId().equals(uid));
            byUidRemove(items, uid);
        }
        it.setLocation(toLocation);
        it.setSlot(toSlot);
        items.putToCanvas(toLocation, toSlot, it);
        items.markDirty(toLocation, toSlot, uid);
        storage.update(it);
        return true;
    }

    /**
     * 穿装备：背包格物品 → 装备槽。校验：槽位合法 + 需求(等级/5属性)满足。
     * 同槽旧件自动回背包；双手武器主手占用时槽2一并处理。
     *
     * @return 穿上的实例；校验失败/槽位非法返回 null
     */
    @Transactional
    public ItemInstance equipFromBag(Player player, long uid, int equipSlot) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.getLocation() != ItemLocations.BAG) {
            return null;
        }
        // 槽位类型校验
        if (!EquipSlots.slotAllows(it.getTemplate(), equipSlot)) {
            return null;
        }
        // 需求校验（等级 + 5 属性，req 已含职业修正）
        if (!meetsRequirements(player, it)) {
            return null;
        }
        // 双手武器只能进主手(1)
        boolean twoHand = EquipSlots.isTwoHand(it.getTemplate());
        if (twoHand && equipSlot != ItemLocations.SLOT_MAIN_HAND) {
            return null;
        }
        // 取同槽旧件（若占用）先放回背包；双手武器还清副手槽
        ItemInstance replaced = takeEquipSlot(items, equipSlot);
        if (twoHand && equipSlot == ItemLocations.SLOT_MAIN_HAND) {
            ItemInstance offReplaced = takeEquipSlot(items, ItemLocations.SLOT_OFF_HAND);
            if (offReplaced != null) {
                returnToBag(items, offReplaced);
            }
        }
        // 从背包格移除
        items.takeFromCanvas(ItemLocations.BAG, it.getSlot());
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(equipSlot);
        items.byUidPut(it);
        items.markDirty(ItemLocations.EQUIP, equipSlot, it.getId());
        storage.update(it);
        if (replaced != null) {
            returnToBag(items, replaced);
        }
        return it;
    }

    /** 需求校验：角色等级与 5 属性 ≥ 物品 req_*。 */
    public boolean meetsRequirements(Player player, ItemInstance it) {
        if (player.getLevel() < it.getReqLevel()) {
            return false;
        }
        if (player.getStrength() < it.getReqStrength()) {
            return false;
        }
        if (player.getSpirit() < it.getReqSpirit()) {
            return false;
        }
        if (player.getTalent() < it.getReqTalent()) {
            return false;
        }
        if (player.getAgility() < it.getReqAgility()) {
            return false;
        }
        return player.getHealth() >= it.getReqHealth();
    }

    private boolean returnToBag(PlayerItems items, ItemInstance it) {
        int free = items.canvas(ItemLocations.BAG).findFreeSlot(it.gridW(), it.gridH());
        if (free < 0) {
            return false;
        }
        it.setLocation(ItemLocations.BAG);
        it.setSlot(free);
        items.putToCanvas(ItemLocations.BAG, free, it);
        items.markDirty(ItemLocations.BAG, free, it.getId());
        return true;
    }

    private ItemInstance takeEquipSlot(PlayerItems items, int equipSlot) {
        ItemInstance cur = null;
        for (ItemInstance i : items.itemsIn(ItemLocations.EQUIP)) {
            if (i.getSlot() == equipSlot) {
                cur = i;
                break;
            }
        }
        if (cur != null) {
            items.byUidRemove(cur.getId());
            items.markDirty(ItemLocations.EQUIP, equipSlot, 0L);
        }
        return cur;
    }

    /**
     * 脱装备：装备槽 → 背包空格。背包满则失败。
     */
    @Transactional
    public boolean unequipToBag(Player player, int equipSlot) {
        PlayerItems items = player.getItems();
        ItemInstance it = null;
        for (ItemInstance i : items.itemsIn(ItemLocations.EQUIP)) {
            if (i.getSlot() == equipSlot) {
                it = i;
                break;
            }
        }
        if (it == null) {
            return false;
        }
        int free = items.canvas(ItemLocations.BAG).findFreeSlot(it.gridW(), it.gridH());
        if (free < 0) {
            return false;
        }
        items.byUidRemove(it.getId());
        it.setLocation(ItemLocations.BAG);
        it.setSlot(free);
        items.putToCanvas(ItemLocations.BAG, free, it);
        items.markDirty(ItemLocations.BAG, free, it.getId());
        storage.update(it);
        return true;
    }

    /**
     * 丢弃：物品 → 软删（DB delete_time）。地面实体由上层/扫地管理。
     */
    @Transactional
    public boolean discard(Player player, long uid) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null) {
            return false;
        }
        int loc = it.getLocation();
        if (ItemLocations.isCanvas(loc)) {
            items.takeFromCanvas(loc, it.getSlot());
        } else {
            items.itemsIn(loc).removeIf(i -> i.getId().equals(uid));
            items.byUidRemove(uid);
        }
        it.setDeleted(true);
        storage.softDelete(uid);
        return true;
    }

    private void byUidRemove(PlayerItems items, long uid) {
        items.byUidRemove(uid);
    }

    /**
     * W 键武器切换：主装备栏 slot1(主手)+slot2(副手) 与 备用武器槽(location 6) 同槽互换。
     * <p>
     * 对应原版 ChangeABItem case 2（InvenItemTemp[80..81] ↔ 穿着中 1/2），UI 不可见、常驻第二套武器。
     */
    @Transactional
    public boolean switchWeaponSet(Player player) {
        PlayerItems items = player.getItems();
        for (int slot : new int[]{ItemLocations.SLOT_MAIN_HAND, ItemLocations.SLOT_OFF_HAND}) {
            ItemInstance main = null;
            for (ItemInstance i : items.itemsIn(ItemLocations.EQUIP)) {
                if (i.getSlot() == slot) {
                    main = i;
                    break;
                }
            }
            ItemInstance backup = null;
            for (ItemInstance i : items.itemsIn(ItemLocations.BACKUP_WEAPON)) {
                if (i.getSlot() == slot) {
                    backup = i;
                    break;
                }
            }
            // 互换 location+slot（不走画布，直接改引用）
            if (main != null) {
                items.byUidRemove(main.getId());
            }
            if (backup != null) {
                items.byUidRemove(backup.getId());
            }
            if (main != null) {
                main.setLocation(ItemLocations.BACKUP_WEAPON);
                main.setSlot(slot);
                items.byUidPut(main);
                items.markDirty(ItemLocations.BACKUP_WEAPON, slot, main.getId());
                storage.update(main);
            }
            if (backup != null) {
                backup.setLocation(ItemLocations.EQUIP);
                backup.setSlot(slot);
                items.byUidPut(backup);
                items.markDirty(ItemLocations.EQUIP, slot, backup.getId());
                storage.update(backup);
            }
        }
        return true;
    }

    /**
     * 装备到备用武器槽（供 GM/后续逻辑直接放第二套武器）。
     */
    @Transactional
    public ItemInstance equipToBackup(Player player, long uid, int slot) {
        if (slot != ItemLocations.SLOT_MAIN_HAND && slot != ItemLocations.SLOT_OFF_HAND) {
            return null;
        }
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.getLocation() != ItemLocations.BAG) {
            return null;
        }
        if (!EquipSlots.slotAllows(it.getTemplate(), slot)) {
            return null;
        }
        items.takeFromCanvas(ItemLocations.BAG, it.getSlot());
        // 清同槽旧备用
        ItemInstance old = null;
        for (ItemInstance i : items.itemsIn(ItemLocations.BACKUP_WEAPON)) {
            if (i.getSlot() == slot) {
                old = i;
                break;
            }
        }
        if (old != null) {
            items.byUidRemove(old.getId());
            returnToBag(items, old);
        }
        it.setLocation(ItemLocations.BACKUP_WEAPON);
        it.setSlot(slot);
        items.byUidPut(it);
        items.markDirty(ItemLocations.BACKUP_WEAPON, slot, it.getId());
        storage.update(it);
        return it;
    }
}
