package org.jpstale.server.game.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Player;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
     * 装备 / 药水槽操作的结果（成功给实例，失败给**原因**）。
     * <p>
     * 原因存在的意义是"让客户端能给出对的那句提示"：原版 `CheckSetOk` 会按原因分别弹
     * `MESSAGE_OVER_WEIGHT` / `MESSAGE_NO_USE_ITEM`，而我们过去一律回 null → 客户端只能显示
     * 一句硬编码的 "equip failed"。原因同时用来告诉客户端"要不要回滚乐观更新"。
     */
    public enum OpReason {
        OK,
        /** 物品不存在 / 不在背包（不在可装备的位置） */
        NOT_IN_BAG,
        /** 职业门拒绝（原版 `NotUseFlag`，见 ItemRules.canUse） */
        JOB_NOT_ALLOWED,
        /** 该件放不进这个槽（位值不匹配） */
        SLOT_MISMATCH,
        /** 等级 / 属性需求不满足 */
        REQ_NOT_MET,
        /** 超重（原版 `CheckSetOk` 负重分支） */
        OVER_WEIGHT,
        /** 双手武器只能进主手或副手 */
        TWO_HAND_SLOT,
        /** 不是药水 */
        NOT_POTION,
        /** 药水槽已满 */
        SLOT_FULL,
        /** 药水槽里是另一种药水（原版"同槽同种"） */
        DIFFERENT_POTION,
        /** 背包满 */
        BAG_FULL,
        /** 鼠标位已占用（手上已经有东西了） */
        HAND_BUSY,
        /** 这件物品不在可拿起的容器里（任务栏/商店等未启用容器） */
        NOT_HOLDABLE
    }

    public static final class OpResult {
        public final OpReason reason;
        public final ItemInstance instance;
        /**
         * 被这次操作**挪走的其它件**（换装时被换下的装备、双手武器清掉的另一只手那件）。
         * 调用方**必须**把它们推给客户端 —— 否则客户端只知道"新件进槽了"，
         * 那件被换下的就成了界面上的幽灵：既不在原来的槽（槽被占了）也不在背包（没收到通知）。
         */
        public final List<ItemInstance> displaced;

        private OpResult(OpReason reason, ItemInstance instance, List<ItemInstance> displaced) {
            this.reason = reason;
            this.instance = instance;
            this.displaced = displaced;
        }

        public static OpResult ok(ItemInstance it) {
            return new OpResult(OpReason.OK, it, List.of());
        }

        public static OpResult ok(ItemInstance it, List<ItemInstance> displaced) {
            return new OpResult(OpReason.OK, it, displaced == null ? List.of() : displaced);
        }

        public static OpResult fail(OpReason r) {
            return new OpResult(r, null, List.of());
        }
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
                // 互换位置 → 走"先停车再落地"（唯一键下两行不能同时抢一个槽），见 writeMoved
                storage.writeMoved(java.util.List.of(it, displaced));
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
            storage.writeMoved(java.util.List.of(it, displaced));   // 两行同时换位 → 见 writeMoved
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
            // 鼠标位（装备栏 slot=-1）也是合法来源：拿起的那件可以直接落进背包格/仓库格。
            boolean srcHeld = ItemLocations.isHeld(it);
            boolean srcEquip = !srcHeld && (it.getLocation() == ItemLocations.EQUIP
                || it.getLocation() == ItemLocations.BACKUP_EQUIP);
            if (!srcBag && !srcWarehouse && !srcEquip && !srcHeld) {
                log.warn("[BagLayout] {} 拒绝: uid={} 源位置 location={} slot={} 非法",
                    player.getName(), e.uid(), it.getLocation(), it.getSlot());
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
            if ((srcEquip || srcHeld) && dstBag) {
                // 鼠标位那件不在任何画布上，所以目标格必须真的是空的
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
            // **只把"位置真的变了"的件收进 involved**（用户 2026-09-14 问："什么时候会有任意批量？"）：
            // 客户端 `reportLayout()` 上报的是**整包快照**（背包+仓库全部），不是"动过的那些"。
            // 以前把快照里每一件都摘索引、重落位、逐行写库 —— 拖一次格子重写整个背包（144+81 行）。
            // 跳过没动的之后，一次上报的写库量 = **真正移动的件数**（客户端手势每次只 1 件），
            // 于是 `writeMoved` 的单件/两件档覆盖了客户端这条路径。
            if (it.getLocation() == toLocation && it.getSlot() == slot) {
                continue;
            }
            involved.add(it);
            if (srcEquip) {
                fromEquip.add(it);
            }
            uidToSlot.put(it.getId(), slot);
            uidToLocation.put(it.getId(), toLocation);
        }
        if (involved.isEmpty()) {
            // 整包快照但一件都没动（最常见：拿起/放下的中间态上报）→ 不碰内存、不写库
            items.setLastSeq(seq);
            log.debug("[BagLayout] {} 快照 {} 条，无位置变化 seq={}", player.getName(), entries.size(), seq);
            return true;
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
        }
        // 内存已经"先全部腾出、再按目标落子"了，写库也必须同样顺序：
        // 这一批里可能互为对方的目标格（A→B 的格、B→A 的格），逐行 update 会撞唯一键。
        storage.writeMoved(involved);
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
     * 从背包**扣掉**一个消耗品（使用道具的原语）。
     *
     * 只做"能不能扣 + 扣"，**不管效果**（效果分发在调用方，见 ItemNetworkHandler.handleUseItem）。
     * 调用顺序约定：调用方**先**确认"效果能落地"（例如目标图等级够、有可用落点）**再**扣，
     * 这样不需要"扣了再退"的回滚路径。
     *
     * @return 扣成功返回该实例（堆叠未耗尽时 count 已减、耗尽时已从背包移除并软删）；
     *         null = 不可用（不在背包 / 已删 / 数量不足）
     */
    public ItemInstance consumeFromBag(Player player, long uid, int qty) {
        ItemInstance it = player.getItems().byUid(uid);
        if (it == null || it.isDeleted() || it.getLocation() != ItemLocations.BAG) {
            return null;
        }
        return consumeAt(player, uid, qty);
    }

    /**
     * 药水堆叠合并：src 并入 dst（同 itemlist、均可堆叠、容量允许）。
     */
    public ItemInstance mergeStack(Player player, long srcUid, long dstUid) {
        PlayerItems items = player.getItems();
        ItemInstance src = items.byUid(srcUid);
        ItemInstance dst = items.byUid(dstUid);
        if (src == null || dst == null || src.getId().equals(dst.getId())
                || (src.getLocation() != ItemLocations.BAG && !ItemLocations.isHeld(src))
                || dst.getLocation() != ItemLocations.BAG
                || !src.stackable() || !dst.stackable()
                || !java.util.Objects.equals(src.getItemListId(), dst.getItemListId())) {
            return null;
        }
        int cap = 1000;
        if (dst.getCount() + src.getCount() > cap) {
            return null;
        }
        dst.setCount(dst.getCount() + src.getCount());
        if (src.getLocation() == ItemLocations.BAG) {
            items.takeFromCanvas(ItemLocations.BAG, src.getSlot());
        }
        items.byUidRemove(src.getId());   // 鼠标位来源也要从容器索引摘掉（byUidRemove 两种都处理）
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
     * 药水快捷槽的**每槽容量上限**。
     *
     * 依据（用户亲授 docs/pt-core-gameplay.md 19 节「药水快捷槽（与护腕/臂环联动）」）：
     * - **不装臂环也能用**，此时每槽基础容量 = 药水自身的 potioncount（实测 15 瓶药水全是 2）；
     * - **装备臂环**后按臂环的 potionspace 扩容（实测 OA206 Elven Armlets = 34，全表臂环 26 件、范围 20~132）。
     *
     * 护腕 = 臂环 = Bracelet（ItemClass.ARMLET，槽位 8），同一件物品。
     */
    public int potionSlotCapacity(Player player, ItemList potionDef) {
        ItemInstance armlet = player.getItems().at(ItemLocations.EQUIP, ItemLocations.SLOT_ARMLET);
        if (armlet != null && armlet.getTemplate() != null && armlet.getTemplate().getPotionSpace() != null) {
            int ps = armlet.getTemplate().getPotionSpace();
            if (ps > 0) {
                return ps;
            }
        }
        int base = potionDef != null && potionDef.getPotionCount() != null ? potionDef.getPotionCount() : 0;
        return base > 0 ? base : 2;   // 数据缺失时的原版基础容量
    }

    /**
     * 药水放入快捷槽（ITEMSLOT 11/12/13）。**堆叠语义**，不是装备语义：
     * - **同槽同种**：槽里已有药水时，必须与它同一种（同 itemlist）；
     * - **容量上限** = potionSlotCapacity；超出容量的部分**留在背包**（拆堆），不整堆拒绝；
     * - 槽满 / 异种 / 非药水 / 非背包来源 → 失败并给**原因**（调用方按原因给可见提示）。
     *
     * @return 成功给槽内那条实例；失败给原因
     */
    @Transactional
    public OpResult putPotionToSlot(Player player, long uid, int slot) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.isDeleted()
                || (it.getLocation() != ItemLocations.BAG && !ItemLocations.isHeld(it))) {
            return OpResult.fail(OpReason.NOT_IN_BAG);   // 源只能是背包格或鼠标位
        }
        ItemList def = it.getTemplate();
        if (!EquipSlots.isPotion(def) || !EquipSlots.isPotionSlot(slot)) {
            log.info("[Potion] 拒绝 uid={} slot={}：isPotion={} classItem={} isPotionSlot={}",
                    uid, slot, EquipSlots.isPotion(def),
                    def == null ? "模板为空" : def.getClassItem(), EquipSlots.isPotionSlot(slot));
            return OpResult.fail(OpReason.NOT_POTION);
        }
        // 超重（原版 CheckSetOk 的负重分支）：搬运不改变总重 → 等价于"当前已超重就拒绝"
        if (overWeightBlocks(player, it)) {
            return OpResult.fail(OpReason.OVER_WEIGHT);
        }
        int cap = potionSlotCapacity(player, def);
        ItemInstance inSlot = items.at(ItemLocations.EQUIP, slot);
        if (inSlot != null && (inSlot.getTemplate() == null
                || !java.util.Objects.equals(inSlot.getTemplate().getId(), def.getId()))) {
            log.info("[Potion] 拒绝 uid={} slot={}：同槽同种（槽内 uid={} 是别的药水）",
                    uid, slot, inSlot.getId());
            return OpResult.fail(OpReason.DIFFERENT_POTION);
        }
        int used = inSlot != null ? Math.max(0, inSlot.getCount()) : 0;
        int space = cap - used;
        if (space <= 0) {
            log.info("[Potion] 拒绝 uid={} slot={}：槽已满（{}/{}，臂环={}）", uid, slot, used, cap,
                    player.getItems().at(ItemLocations.EQUIP, ItemLocations.SLOT_ARMLET) != null);
            return OpResult.fail(OpReason.SLOT_FULL);
        }
        int n = Math.min(Math.max(1, it.getCount()), space);

        ItemInstance target;
        if (inSlot != null) {
            inSlot.setCount(used + n);
            items.markDirty(ItemLocations.EQUIP, slot, inSlot.getId());
            storage.update(inSlot);
            target = inSlot;
        } else if (n >= it.getCount()) {
            // 整堆搬入：同一条记录换位置（与 equipFromBag 同一做法）。
            // 摘件必须按**来源自己的 location** 摘：源既可以是背包格，也可以是**鼠标位**（装备栏 slot=-1）。
            // 曾写死 `takeFromCanvas(BAG, ...)`：从药水槽拿起再放回（源=鼠标位）时旧索引从未释放
            // ⇒ 同一件同时挂在 (EQUIP,-1) 与药水槽上 ⇒ 之后每次"拿起"都被拒"鼠标位已被占用"
            //（用户 2026-09-14 实测；服务端日志 `[Potion] 放入药水槽2 ... x1 -> 槽内 1/40` 之后全被拒）。
            items.byUidRemove(it.getId());
            it.setLocation(ItemLocations.EQUIP);
            it.setSlot(slot);
            items.byUidPut(it);
            items.markDirty(ItemLocations.EQUIP, slot, it.getId());
            storage.update(it);
            target = it;
        } else {
            // 拆堆：背包那堆留一部分，槽里新建一条。用工厂生成（字段与掉落/奖励同源）；
            // 药水不参与战斗数值计算，掷点差异无影响。
            ItemInstance fresh = roll.roll(def, it.getJobCodeMask());
            fresh.setCount(n);
            fresh.setLocation(ItemLocations.EQUIP);
            fresh.setSlot(slot);
            storage.update(fresh);          // id 为空 -> 内部转 insert 并回填 id
            items.byUidPut(fresh);
            items.markDirty(ItemLocations.EQUIP, slot, fresh.getId());
            target = fresh;
        }
        // 源堆扣减（整堆搬入时 target == it，已在上面处理）
        if (target != it) {
            if (n >= it.getCount()) {
                items.byUidRemove(it.getId());   // 按来源自己的 location 摘（背包格 / 鼠标位都认）
                storage.softDelete(it.getId());
            } else {
                it.setCount(it.getCount() - n);
                items.markDirty(it.getLocation(), it.getSlot(), it.getId());
                storage.update(it);
            }
        }
        log.info("[Potion] {} 放入药水槽{}: {} x{} -> 槽内 {}/{}", player.getName(), slot - 10,
                def.getName(), n, used + n, cap);
        return OpResult.ok(target);
    }

    /**
     * 消耗任意位置的堆叠物（背包或药水快捷槽）。
     * consumeFromBag 委托到这里，保证"扣减/删除/软删"只有一份实现。
     *
     * @return 被消耗的实例（数量已扣好；整堆用完时 count=0 且已软删）；失败 null
     */
    @Transactional
    public ItemInstance consumeAt(Player player, long uid, int qty) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.isDeleted()) {
            return null;
        }
        boolean fromBag = it.getLocation() == ItemLocations.BAG;
        boolean fromPotionSlot = it.getLocation() == ItemLocations.EQUIP && EquipSlots.isPotionSlot(it.getSlot());
        if (!fromBag && !fromPotionSlot) {
            return null;
        }
        int n = Math.max(1, qty);
        if (it.getCount() < n) {
            return null;
        }
        if (it.getCount() > n) {
            it.setCount(it.getCount() - n);
            items.markDirty(it.getLocation(), it.getSlot(), it.getId());
            storage.update(it);
        } else {
            if (fromBag) {
                items.takeFromCanvas(ItemLocations.BAG, it.getSlot());
            } else {
                items.byUidRemove(it.getId());   // 药水槽：从容器索引摘除（无画布位图）
            }
            storage.softDelete(it.getId());
            it.setCount(0);
        }
        return it;
    }

    /**
     * 穿装备：**背包格或鼠标位** → 装备槽（含药水快捷槽）。校验：槽位合法 + 需求(等级/5属性)满足
     * + 职业门 + 未超重。同槽旧件进鼠标位（原版语义：换下来的那件在手上），双手武器另一只手那件回背包。
     *
     * @return 成功给穿上的实例；失败给原因（供客户端提示与回滚判断）
     */
    @Transactional
    public OpResult equipFromBag(Player player, long uid, int equipSlot) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || (it.getLocation() != ItemLocations.BAG && !ItemLocations.isHeld(it))) {
            log.info("[Equip] 拒绝 uid={} slot={}：不在背包也不在鼠标位 (loc={}, slot={})", uid, equipSlot,
                    it == null ? "该 uid 不存在" : it.getLocation(),
                    it == null ? "-" : it.getSlot());
            return OpResult.fail(OpReason.NOT_IN_BAG);
        }
        // 职业门（原版 `NotUseFlag` 语义，见 ItemRules.canUse）：该职业用不了这件装备 → 拒绝。
        int useCode = it.getItemCode() != null ? it.getItemCode() : 0;
        if (!ItemRules.canUse(player.getJob(), useCode)) {
            log.info("[Equip] 拒绝 {} uid={} idCode=0x{}（职业门：job={}）",
                    player.getName(), uid, Integer.toHexString(useCode), player.getJob());
            return OpResult.fail(OpReason.JOB_NOT_ALLOWED);
        }
        // 超重（原版 CheckSetOk 的负重分支，见 overWeightBlocks）：搬运不改变总重
        if (overWeightBlocks(player, it)) {
            log.info("[Equip] 拒绝 {} uid={}：当前已超重 {}>{}", player.getName(), uid,
                    statCalculator.currentWeight(player), statCalculator.maxWeight(player));
            return OpResult.fail(OpReason.OVER_WEIGHT);
        }
        // 药水快捷槽（ITEMSLOT 11/12/13）走**堆叠**语义，不是"一格一件"的装备语义：
        // 同槽同种 + 容量上限 + 超出部分留在背包（拆堆）。见 putPotionToSlot。
        if (EquipSlots.isPotionSlot(equipSlot)) {
            return putPotionToSlot(player, uid, equipSlot);
        }
        // 槽位类型校验
        if (!EquipSlots.slotAllows(it.getTemplate(), equipSlot)) {
            return OpResult.fail(OpReason.SLOT_MISMATCH);
        }
        // 需求校验（等级 + 5 属性，req 已含职业修正）
        if (!meetsRequirements(player, it)) {
            return OpResult.fail(OpReason.REQ_NOT_MET);
        }
        // 双手武器占**两只手**（原版 `OverlapTwoHandItem`，sinInvenTory1.cpp:5241）：
        // 放槽1就清槽2、放槽2就清槽1，被清掉的那件自动回背包。
        // 原先我们只允许它进主手(1)，与"进哪个槽就清另一个槽"的原版行为不一致。
        boolean twoHand = EquipSlots.isTwoHand(it.getTemplate());
        if (twoHand && equipSlot != ItemLocations.SLOT_MAIN_HAND
                && equipSlot != ItemLocations.SLOT_OFF_HAND) {
            return OpResult.fail(OpReason.TWO_HAND_SLOT);   // 双手武器只能进主手或副手
        }
        // 被换下的件：同槽旧件 +「另一只手」那件。
        // 「另一只手」要腾空有**两种**情形（都是原版 `sInven[]` 的**占位语义**）：
        //   ① **新件是双手武器** → 它要占两只手，另一只手必须空出来；
        //   ② **另一只手拿着双手武器** → 它本来就占着两只手（原版 `sInven[1].ItemIndex` 也指向它
        //      那一件 —— 即副手格"被占位"，见 `OverlapTwoHandItem`/`OverlapTwoHandSwitch`，两者都只在
        //      "放进去的这件是双手武器"时动作，规则是**涌现**的：往任一手放东西都与它撞件 → 走换手）。
        // ② 正是我们漏掉的那半：实测"装了双手匕首还能再装副手盾"（用户 2026-09-14）。
        final int srcSlot = it.getSlot();
        ItemInstance replaced = takeEquipSlot(items, equipSlot);
        ItemInstance otherItem = null;
        final int otherSlot = equipSlot == ItemLocations.SLOT_MAIN_HAND
                ? ItemLocations.SLOT_OFF_HAND : ItemLocations.SLOT_MAIN_HAND;
        if (twoHand) {
            otherItem = takeEquipSlot(items, otherSlot);       // ① 新件占两只手 → 另一只手腾空
        } else if (equipSlot == ItemLocations.SLOT_MAIN_HAND
                || equipSlot == ItemLocations.SLOT_OFF_HAND) {
            ItemInstance inOther = items.at(ItemLocations.EQUIP, otherSlot);
            if (inOther != null && !inOther.isDeleted()
                    && EquipSlots.isTwoHand(inOther.getTemplate())) {
                otherItem = takeEquipSlot(items, otherSlot);   // ② 另一只手是双手武器 → 它让位
                log.info("[Equip] {} 另一只手是双手武器(uid={}) → 让位", player.getName(), otherItem.getId());
            }
        }
        // **先把所有涉及的行从容器索引里摘出来，再逐个落位**（与 `applyBagLayout` 同一套顺序）。
        // 源可能是背包格（画布上）或**鼠标位**（装备栏 slot=-1）：从鼠标位换装时，被换下的那件要进鼠标位
        // —— 那就是**一次互换**，两条 UPDATE 会撞唯一键，所以这里统一收集、最后用 `writeMoved` 一次写库
        // （它内部先"停车"再落地，任意调换都安全）。
        items.takeFromCanvas(ItemLocations.BAG, srcSlot);
        items.byUidRemove(it.getId());
        List<ItemInstance> moved = new ArrayList<>();
        boolean okReplaced = replaced == null || returnToHandOrBag(items, replaced, moved);
        // **两件被换下的件按同一优先级依次"优先进鼠标位、手忙则回背包"** ——
        // 对应原版 `sinInvenTory.cpp:6545` 那段：谁进鼠标位取决于**谁是"撞件"**：
        //   · 目标槽被占 → 撞件 = 目标槽那件 → 它进鼠标位（`memcpy(pItem, &TempItem)`，pItem 就是鼠标位）；
        //     另一只手那件走 `AutoSetItemIndex` → **背包**（`InvenEmptyAearCheck`，没空格才丢地）。
        //   · 目标槽**空**且新件是双手 → `OverlapTwoHandSwitch` 把**另一只手那件**标成撞件 → **它进鼠标位**
        //     （用户 2026-09-14 实测："副手盾 + 主手空 + 装双手武器 → 盾应交换到鼠标位"）。
        // 所以顺序 = replaced 先占手位，otherItem 再看手位是否还空着 —— 一条规则覆盖全部三种组合。
        boolean okOther = okReplaced && (otherItem == null || returnToHandOrBag(items, otherItem, moved));
        if (!okReplaced || !okOther) {
            // **全撤**：被换下的件都放回原槽 —— 要么"换上"，要么"原样"，
            // 不留"旧件掉进背包、新件还没穿上"的半成品。此时**还没有任何写库**，所以只需复原内存。
            if (replaced != null) {
                restoreEquipSlot(items, replaced, equipSlot);
            }
            if (otherItem != null) {
                restoreEquipSlot(items, otherItem, otherSlot);
            }
            // 新件放回原格（原样：背包格 或 鼠标位）
            if (it.getSlot() == ItemLocations.HELD_SLOT && srcSlot == ItemLocations.HELD_SLOT) {
                it.setLocation(ItemLocations.EQUIP);
                it.setSlot(ItemLocations.HELD_SLOT);
                items.byUidPut(it);
            } else {
                it.setLocation(ItemLocations.BAG);
                it.setSlot(srcSlot);
                items.putToCanvas(ItemLocations.BAG, srcSlot, it);
                items.markDirty(ItemLocations.BAG, srcSlot, it.getId());
            }
            log.info("[Equip] {} uid={} → 槽{} 放弃：被换下的件放不进背包（背包满）",
                    player.getName(), uid, equipSlot);
            return OpResult.fail(OpReason.BAG_FULL);
        }
        // 新件进目标槽（内存），与上面几件一起写库
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(equipSlot);
        items.byUidPut(it);
        items.markDirty(ItemLocations.EQUIP, equipSlot, it.getId());
        moved.add(it);
        storage.writeMoved(moved);   // 一次写库：内部先停车再落地 → 互换位置也安全
        List<ItemInstance> displaced = new ArrayList<>();
        if (replaced != null) {
            displaced.add(replaced);
        }
        if (otherItem != null) {
            displaced.add(otherItem);
        }
        return OpResult.ok(it, displaced);
    }

    /** 撤销 `takeEquipSlot`：把被换下的件放回它原来的装备槽（已进过背包的要先撤出来）。 */
    private void restoreEquipSlot(PlayerItems items, ItemInstance it, int slot) {
        if (it.getLocation() == ItemLocations.BAG) {
            items.takeFromCanvas(ItemLocations.BAG, it.getSlot());
        }
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(slot);
        items.byUidPut(it);
        items.markDirty(ItemLocations.EQUIP, slot, it.getId());
        storage.update(it);
    }

    /**
     * 原版 `CheckSetOk` 的**负重分支**（`sinInvenTory.cpp:6021`）：`Weight[0] + 该件重量 > Weight[1]` 即拒。
     * <p>
     * 由于 `Weight[0]` 是"背包 + 装备"的合计、**不含鼠标上那件**，加上该件恰好就是搬运后的总重，
     * 而背包↔装备槽 / 背包↔药水槽之间搬运**不改变总重** ⇒ 该判定等价于**"当前已超重就拒绝搬运"**，
     * 不需要逐件重量（客户端因此也不必持有任何重量表）。
     * <p>
     * 例外：原版对 `ITEM_KIND_QUEST_WEAPON` 豁免（超重也放行）。我们**没有** `ItemKindCode` 这一列，
     * 用**任务家族**近似（与禁丢清单同一份近似，见 `ItemRules`）——宁可放行任务武器，也不卡任务。
     */
    private boolean overWeightBlocks(Player player, ItemInstance it) {
        if (!statCalculator.isOverloaded(player)) {
            return false;
        }
        int code = it.getItemCode() != null ? it.getItemCode() : 0;
        return !ItemRules.isQuestFamily(code);
    }

    /** 需求校验：角色等级与 5 属性 ≥ 物品 req_*。 */
    public boolean meetsRequirements(Player player, ItemInstance it) {
        return ItemRules.meetsRequirements(player, it);   // 唯一实现在 ItemRules（属性门只此一份）
    }

    /**
     * 被换下的那件去哪：**优先放到鼠标位**（装备栏 slot=-1）—— 原版换装时换下来的那件就在手上
     * （用户 2026-09-14 报："装备栏的武器没有交换到手上，看上去直接消失了"）。
     * 手上已经有东西（同一批里双手武器会换下两件）时才回背包。
     */
    private boolean returnToHandOrBag(PlayerItems items, ItemInstance it, List<ItemInstance> moved) {
        if (items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT) == null) {
            placeToHandMem(items, it);
            moved.add(it);
            log.info("[Equip] 被换下的件 {} 进鼠标位（装备栏 slot={}）", it.getId(), ItemLocations.HELD_SLOT);
            return true;
        }
        return returnToBag(items, it, moved);
    }

    /** 落到鼠标位（**只改内存**；由调用方统一写库）。 */
    private void placeToHandMem(PlayerItems items, ItemInstance it) {
        items.byUidRemove(it.getId());          // 可能还挂在旧容器/画布上，先摘干净
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(ItemLocations.HELD_SLOT);
        items.byUidPut(it);
    }

    /**
     * **把拾取到的药水优先灌进药水槽** —— 原版 `cINVENTORY::AutoSetPotion`（由 `AutoSetInvenItem`
     * 在"自动入包"时调用；窗口开着的那条路是玩家自己放，不走这里）。
     *
     * 规则（逐槽 11→12→13）：**同种且未满 → 补满**；**空槽 → 放 min(剩余, 容量)**；异种/已满 → 下一格。
     * 灌不完的余数留在 `fresh.getCount()` 上，由调用方走"上手 / 进背包"。
     *
     * ⚠ 与原版的**有意差异**：原版只用**第一个能用的槽**，该槽灌满后余数直接进背包；
     * 我们改成**把所有能用（含同种未满）的槽都灌满**再交给背包 —— 依据是用户 2026-09-14 的期望
     * （"如果药水槽有空位，或者同类型药水在药水槽没达到数量上限，应该优先填充药水槽，
     * 多余的部分才是进入背包或鼠标位"）。
     *
     * @return 被改动的**槽内堆**（调用方需要推送给客户端）；非药水/已空 → 空列表
     */
    @Transactional
    public List<ItemInstance> pourIntoPotionSlots(Player player, ItemInstance fresh) {
        List<ItemInstance> touched = new ArrayList<>();
        PlayerItems items = player.getItems();
        if (fresh == null || fresh.getCount() <= 0 || !EquipSlots.isPotion(fresh.getTemplate())) {
            return touched;
        }
        int cap = potionSlotCapacity(player, fresh.getTemplate());
        if (cap <= 0) {
            return touched;
        }
        for (int slot : new int[]{ItemLocations.SLOT_POTION_1, ItemLocations.SLOT_POTION_2,
                ItemLocations.SLOT_POTION_3}) {
            if (fresh.getCount() <= 0) {
                break;
            }
            ItemInstance inSlot = items.at(ItemLocations.EQUIP, slot);
            if (inSlot == null) {
                int n = Math.min(fresh.getCount(), cap);
                // ⚠ **一律拆堆**（哪怕整堆都装得下）：本函数的契约是"**源只会被扣减，不会被搬走**"，
                // 调用方靠 `fresh.getCount() > 0` 判断"还有余数要走背包/手上"。
                // 早先对"整堆装得下"走的是"同一条记录换位置"（与 putPotionToSlot 一致），
                // 结果源实例的瓶数原样留着 → 调用方把**已经在槽里的那堆**又当成余数发一次（重复给）。
                // 字段与掉落/奖励同源；药水不参与战斗数值计算，掷点差异无影响。
                ItemInstance part = roll.roll(fresh.getTemplate(), fresh.getJobCodeMask());
                part.setCharacterId(Math.toIntExact(player.getId()));
                part.setCount(n);
                part.setLocation(ItemLocations.EQUIP);
                part.setSlot(slot);
                storage.update(part);      // id 为空 → 内部转 insert 并回填
                items.byUidPut(part);
                touched.add(part);
                fresh.setCount(fresh.getCount() - n);
            } else if (inSlot.getTemplate() != null && fresh.getTemplate() != null
                    && java.util.Objects.equals(inSlot.getTemplate().getId(), fresh.getTemplate().getId())
                    && inSlot.getCount() < cap) {
                int n = Math.min(fresh.getCount(), cap - inSlot.getCount());
                inSlot.setCount(inSlot.getCount() + n);
                items.markDirty(ItemLocations.EQUIP, slot, inSlot.getId());
                storage.update(inSlot);
                touched.add(inSlot);
                fresh.setCount(fresh.getCount() - n);
            }
            // 异种 / 已满 → 看下一格
        }
        if (!touched.isEmpty()) {
            log.info("[Pickup] {} 药水优先进槽：{} 个槽被填充，剩 {} 瓶走背包/手上",
                    player.getName(), touched.size(), Math.max(0, fresh.getCount()));
        }
        return touched;
    }

    /**
     * **换手**：手上那件 ↔ 背包/仓库里的某件，**原子互换**（对应原版 `ChangeInvenItem` 的"换手"）。
     *
     * 为什么必须原子：鼠标位只有一个。A 还在手上时 `takeToHand(B)` 必然 `handBusy`；
     * 而"先把 A 放下"又要求 B 让开它那一格 —— 拆成两步在任何顺序下都会撞死。
     *
     * 落地用 `writeMoved`（两行互换：A → B 的原格、B → 鼠标位），它内部先停车再落地，
     * 唯一键下任意调换都安全（见 `ItemStorageService.writeMoved`）。
     *
     * @return 成功给**换到手上**的那件（B）；失败给原因
     */
    @Transactional
    public OpResult swapWithHand(Player player, long handUid, long targetUid, int toLocation, int toSlot) {
        PlayerItems items = player.getItems();
        ItemInstance hand = items.byUid(handUid);
        ItemInstance target = items.byUid(targetUid);
        // 手上那件必须是**当前鼠标位那件**（按位置核对，不信客户端报的 uid）
        if (hand == null || hand.isDeleted() || !ItemLocations.isHeld(hand)) {
            log.info("[Swap] 拒绝 {} handUid={}：不在鼠标位", player.getName(), handUid);
            return OpResult.fail(OpReason.NOT_IN_BAG);
        }
        if (target == null || target.isDeleted() || !ItemLocations.isCanvas(target.getLocation())) {
            log.info("[Swap] 拒绝 {} targetUid={}：不在画布上（loc={}）", player.getName(), targetUid,
                    target == null ? "-" : target.getLocation());
            return OpResult.fail(OpReason.NOT_IN_BAG);
        }
        if (ItemLocations.isHeld(target)) {
            return OpResult.fail(OpReason.NOT_IN_BAG);
        }
        // 手上那件落到**客户端算出的落点**（`toLocation/toSlot`），不是"被撞件自己的锚格" ——
        // 2×4 与 2×3 只是**部分重叠**时两者不同（实测 2026-09-14：按被撞件锚格校验会误判越界）。
        CanvasGrid cg = items.canvas(toLocation);
        if (cg == null) {
            return OpResult.fail(OpReason.SLOT_MISMATCH);
        }
        int tx = cg.xOf(toSlot);
        int ty = cg.yOf(toSlot);
        if (cg.xOf(toSlot) < 0 || cg.yOf(toSlot) < 0
                || tx + hand.gridW() > cg.width() || ty + hand.gridH() > cg.height()) {
            log.info("[Swap] 拒绝 {}：落点 loc={} slot={} 越界（手上 {}x{}）",
                    player.getName(), toLocation, toSlot, hand.gridW(), hand.gridH());
            return OpResult.fail(OpReason.SLOT_MISMATCH);
        }
        // 同画布时，被撞件那一块视为已腾空（它要去手上）；异画布（背包↔仓库）则要求落点本来就空
        boolean sameCanvas = target.getLocation() == toLocation;
        boolean free = sameCanvas
                ? cg.canPlaceExcept(tx, ty, hand.gridW(), hand.gridH(),
                        cg.xOf(target.getSlot()), cg.yOf(target.getSlot()), target.gridW(), target.gridH())
                : cg.canPlace(tx, ty, hand.gridW(), hand.gridH());
        if (!free) {
            log.info("[Swap] 拒绝 {}：手上那件 {}x{} 放不进落点 loc={} slot={}",
                    player.getName(), hand.gridW(), hand.gridH(), toLocation, toSlot);
            return OpResult.fail(OpReason.SLOT_MISMATCH);   // 落点容不下（原版也是拒绝）
        }
        // 与"搬运"同一套门：超重则拒绝（原版 CheckSetOk 的负重分支）
        if (overWeightBlocks(player, hand)) {
            return OpResult.fail(OpReason.OVER_WEIGHT);
        }
        // 内存：先都摘出索引（清掉各自在画布上的足迹），再各落各家（与 applyBagLayout 同一顺序）
        items.byUidRemove(hand.getId());
        items.byUidRemove(target.getId());
        hand.setLocation(toLocation);
        hand.setSlot(toSlot);
        if (ItemLocations.isCanvas(toLocation)) {
            items.putToCanvas(toLocation, toSlot, hand);   // 落点画布：连同位图一起登记
        } else {
            items.byUidPut(hand);
        }
        items.markDirty(toLocation, toSlot, hand.getId());
        target.setLocation(ItemLocations.EQUIP);
        target.setSlot(ItemLocations.HELD_SLOT);
        items.byUidPut(target);
        items.markDirty(ItemLocations.EQUIP, ItemLocations.HELD_SLOT, target.getId());
        storage.writeMoved(java.util.List.of(hand, target));   // 两行互换 → 停车再落地
        log.info("[Swap] {} 换手：{} → loc={}/slot={}，{} → 鼠标位",
                player.getName(), hand.getId(), toLocation, toSlot, target.getId());
        return OpResult.ok(target);
    }

    /** 拾取前的负重检查（原版在拾取入口就查，超重则**整次拾取拒绝**、物品留在地上）。 */
    public boolean pickupOverWeight(Player player, ItemInstance fresh) {
        return statCalculator.isOverWeight(player, fresh);
    }

    /**
     * **拾取/奖励：优先直接放到手上**（鼠标位）—— 对应原版"背包窗口开着时拾取物 `memcpy` 进 `MouseItem`"，
     * 那条路径**不需要背包空格**（原版关着窗口走 `AutoSetInvenItem`，背包满还会把物品丢回地上）。
     *
     * @return 上手成功给该实例；手上已有东西 / 超重 → null（调用方回退到 {@link #grantInstanceToBag}）
     */
    @Transactional
    public ItemInstance grantToHand(Player player, ItemInstance fresh) {
        PlayerItems items = player.getItems();
        if (items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT) != null) {
            return null;   // 手位被占：**不覆盖**玩家手上那件（原版会直接 memcpy 覆盖，我们不这么做）
        }
        if (statCalculator.isOverWeight(player, fresh)) {
            return null;   // 超重：交给调用方给出"负重超限"提示（原版此处直接丢回地面）
        }
        fresh.setCharacterId(Math.toIntExact(player.getId()));
        fresh.setLocation(ItemLocations.EQUIP);
        fresh.setSlot(ItemLocations.HELD_SLOT);
        if (fresh.getId() != null) {
            storage.restore(fresh);      // 丢地软删后拾回：恢复原行
        } else {
            storage.insert(fresh);
        }
        items.byUidPut(fresh);
        log.info("[TakeToHand] {} 拾取上手 uid={} {}", player.getName(), fresh.getId(),
                fresh.getTemplate() != null ? fresh.getTemplate().getName() : "?");
        return fresh;
    }

    /**
     * **拿起**：把一件物品移到鼠标位（装备栏 `slot = -1`），原容器腾空。
     *
     * 拿起要成为一次真实的服务端位置变更，理由见 {@link ItemLocations#HELD_SLOT}：
     * 属性/外观/负重都是服务端算的，"拿在手上"必须立刻反映（原版拿起装备即 `sinSetCharItem(..., FALSE)`）；
     * 而且断线重连后这一行还在，客户端据此**原样恢复"手上拿着它"**（用户 2026-09-14 定）。
     *
     * @return 成功给拿起的那件；失败给原因（鼠标位已占 / 不在可拿的容器 / uid 不存在）
     */
    @Transactional
    public OpResult takeToHand(Player player, long uid) {
        PlayerItems items = player.getItems();
        ItemInstance it = items.byUid(uid);
        if (it == null || it.isDeleted()) {
            return OpResult.fail(OpReason.NOT_IN_BAG);
        }
        if (ItemLocations.isHeld(it)) {
            return OpResult.ok(it);   // 幂等：已经拿在手上了
        }
        if (items.at(ItemLocations.EQUIP, ItemLocations.HELD_SLOT) != null) {
            log.info("[TakeToHand] {} uid={} 拒绝：鼠标位已被占用", player.getName(), uid);
            return OpResult.fail(OpReason.HAND_BUSY);
        }
        // 可拿起的来源：背包页 / 仓库页 / 装备栏 / 副装备栏 / 药水槽（都在这几段里）。
        // 任务栏、商店等未启用容器不接受拿起。
        int loc = it.getLocation();
        boolean holdable = ItemLocations.isBagPage(loc) || ItemLocations.isWarehousePage(loc)
                || loc == ItemLocations.EQUIP || loc == ItemLocations.BACKUP_EQUIP;
        if (!holdable) {
            log.info("[TakeToHand] {} uid={} 拒绝：源容器 location={} 不可拿起", player.getName(), uid, loc);
            return OpResult.fail(OpReason.NOT_HOLDABLE);
        }
        // 从原容器摘除（画布/位图/索引一起），再落到鼠标位
        final int srcSlot = it.getSlot();   // ⚠ 必须在改 slot 之前取：否则日志里永远打 -1（曾经如此，害得排查时看不出源头）
        if (ItemLocations.isCanvas(loc)) {
            items.takeFromCanvas(loc, srcSlot);
        }
        items.byUidRemove(it.getId());
        it.setLocation(ItemLocations.EQUIP);
        it.setSlot(ItemLocations.HELD_SLOT);
        items.byUidPut(it);
        storage.update(it);
        log.info("[TakeToHand] {} 拿起 uid={} {} ← location={} slot={}",
                player.getName(), uid,
                it.getTemplate() != null ? it.getTemplate().getName() : "?",
                loc, srcSlot);
        return OpResult.ok(it);
    }

    /**
     * 把一件装备/物品放回背包空位。
     * <p>
     * ⚠ **必须落库**（`storage.update`）：本项目的 `items.markDirty(...)` 目前**没有兑现方**
     * （`takeDirty()` 全仓无调用者），持久化只认显式的 `storage.update`。
     * 曾漏掉这一句 → 被换下的装备在内存里进了背包、DB 行却仍写着原装备槽，
     * 重登后与新装备挤在同一个槽位（用户 2026-09-14 实测）。
     */
    private boolean returnToBag(PlayerItems items, ItemInstance it, List<ItemInstance> moved) {
        if (!placeToBagMem(items, it)) {
            return false;
        }
        moved.add(it);
        return true;
    }

    /** 落到背包空位（**只改内存**，不写库）。返回 false = 没有能放下的空格。 */
    private boolean placeToBagMem(PlayerItems items, ItemInstance it) {
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
     * 脱装备：装备槽 → 背包空格。背包满 / 该槽为空则失败。
     *
     * @return 成功 OK；失败原因（BAG_FULL / NOT_IN_BAG）
     */
    @Transactional
    public OpReason unequipToBag(Player player, int equipSlot) {
        PlayerItems items = player.getItems();
        ItemInstance it = null;
        for (ItemInstance i : items.itemsIn(ItemLocations.EQUIP)) {
            if (i.getSlot() == equipSlot) {
                it = i;
                break;
            }
        }
        if (it == null) {
            return OpReason.NOT_IN_BAG;
        }
        int free = items.canvas(ItemLocations.BAG).findFreeSlot(it.gridW(), it.gridH());
        if (free < 0) {
            return OpReason.BAG_FULL;
        }
        items.byUidRemove(it.getId());
        it.setLocation(ItemLocations.BAG);
        it.setSlot(free);
        items.putToCanvas(ItemLocations.BAG, free, it);
        items.markDirty(ItemLocations.BAG, free, it.getId());
        storage.update(it);
        return OpReason.OK;
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
            java.util.List<ItemInstance> swapped = new java.util.ArrayList<>(2);
            if (main != null) {
                main.setLocation(ItemLocations.BACKUP_WEAPON);
                main.setSlot(slot);
                items.byUidPut(main);
                items.markDirty(ItemLocations.BACKUP_WEAPON, slot, main.getId());
                swapped.add(main);
            }
            if (backup != null) {
                backup.setLocation(ItemLocations.EQUIP);
                backup.setSlot(slot);
                items.byUidPut(backup);
                items.markDirty(ItemLocations.EQUIP, slot, backup.getId());
                swapped.add(backup);
            }
            // 两行是**互换**位置（主套 ↔ 备用套）→ 唯一键下必须"先停车再落地"
            storage.writeMoved(swapped);
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
            if (placeToBagMem(items, old)) {
                storage.update(old);
            }
        }
        it.setLocation(ItemLocations.BACKUP_WEAPON);
        it.setSlot(slot);
        items.byUidPut(it);
        items.markDirty(ItemLocations.BACKUP_WEAPON, slot, it.getId());
        storage.update(it);
        return it;
    }
}
