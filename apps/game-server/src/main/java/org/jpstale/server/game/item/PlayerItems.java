package org.jpstale.server.game.item;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家物品持有状态（服务端权威）。
 * <p>
 * 持有：背包大画布(12×12) + 仓库画布(9×9) + 装备栏(槽1~13) + 备用武器(槽1/2)。
 * 内存中同时维护：每容器的占用位图 + 全部 item 实例（按 uid 索引）。
 * 变更经 {@link #markDirty(int, int, long)} 记录，由持久化层 flush。
 * <p>
 * ⚠ 不变式：同一 (角色, location, slot) 只允许一件物品（数据库 `uq_item_active_slot` 是同一条）。
 * 入槽一律经 {@link #occupy}，槽位被别的 uid 占着时**拒绝 + 报错**，不静默覆盖。
 */
@Slf4j
public class PlayerItems {

    /** location → 该容器全部实例（含被引用的画布格）。 */
    private final Map<Integer, Map<Integer, ItemInstance>> byLocation = new HashMap<>();
    /** 全部实例 uid → 实例 */
    private final Map<Long, ItemInstance> byUid = new HashMap<>();
    /** location → 画布位图（仅画布容器有） */
    private final Map<Integer, CanvasGrid> canvases = new HashMap<>();

    /** dirty 记录：location → slot → 变更标记（供持久化 diff） */
    private final Map<Integer, Map<Integer, Long>> dirty = new HashMap<>();

    /** 布局上报序号（C2S_BagLayout.seq）：记录最新已接受序号，丢弃 seq<=lastSeq 的乱序/重放包 */
    private volatile int lastSeq = 0;

    public int lastSeq() {
        return lastSeq;
    }

    public void setLastSeq(int seq) {
        this.lastSeq = seq;
    }

    public PlayerItems() {
        canvases.put(ItemLocations.BAG, new CanvasGrid(ItemLocations.BAG_W, ItemLocations.BAG_H));
        canvases.put(ItemLocations.WAREHOUSE, new CanvasGrid(ItemLocations.WH_W, ItemLocations.WH_H));
        for (int loc : new int[]{ItemLocations.BAG, ItemLocations.WAREHOUSE,
                ItemLocations.EQUIP, ItemLocations.BACKUP_WEAPON}) {
            byLocation.put(loc, new HashMap<>());
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public ItemInstance byUid(long uid) {
        return byUid.get(uid);
    }

    /** 当前持有物品总数（活物，不含软删）。 */
    public int byUidCount() {
        return byUid.size();
    }

    /** 某容器的全部实例（背包/仓库=画布占用物；装备=槽）。 */
    public List<ItemInstance> itemsIn(int location) {
        Map<Integer, ItemInstance> m = byLocation.get(location);
        return m == null ? Collections.emptyList() : new ArrayList<>(m.values());
    }

    public CanvasGrid canvas(int location) {
        return canvases.get(location);
    }

    /** 某容器是否为空。 */
    public boolean isEmpty(int location) {
        Map<Integer, ItemInstance> m = byLocation.get(location);
        return m == null || m.isEmpty();
    }

    /** 画布某格当前物品（无则 null）。 */
    public ItemInstance at(int location, int slot) {
        Map<Integer, ItemInstance> m = byLocation.get(location);
        return m == null ? null : m.get(slot);
    }

    // ------------------------------------------------------------------
    // 装载（上线从 DB 读入，重建位图）
    // ------------------------------------------------------------------

    /** 加入一件已落库/新实例（内部登记）；不负责写库。 */
    public void index(ItemInstance item) {
        if (!occupy(item, "index(装载)")) {
            return;   // 同槽冲突：拒绝登记（已 error 日志），不让它变成幽灵
        }
        if (ItemLocations.isCanvas(item.getLocation())) {
            CanvasGrid cg = canvases.get(item.getLocation());
            if (cg != null) {
                cg.place(cg.xOf(item.getSlot()), cg.yOf(item.getSlot()), item.gridW(), item.gridH());
            }
        }
    }

    /** 全量装载完成后重建位图（防 dirty 顺序影响）。 */
    public void rebuildBitmaps() {
        for (CanvasGrid cg : canvases.values()) {
            cg.clear();
        }
        for (ItemInstance item : byUid.values()) {
            if (!item.isDeleted() && ItemLocations.isCanvas(item.getLocation())) {
                CanvasGrid cg = canvases.get(item.getLocation());
                if (cg == null) {
                    continue;
                }
                int x = cg.xOf(item.getSlot());
                int y = cg.yOf(item.getSlot());
                if (cg.canPlace(x, y, item.gridW(), item.gridH())) {
                    cg.place(x, y, item.gridW(), item.gridH());
                } else {
                    // ⚠ 别静默跳过：格子已被别人占着却还要放 —— 这就是"同槽两件"在内存里的样子
                    // （位图只认先来的，后来那件连画布都进不去 → 界面上凭空少一件）。
                    log.error("[SlotConflict] 重建位图时格子已被占用：loc={} slot={} 物品={} 未进位图"
                            + "（数据库里可能仍有同槽多行，需人工清理）",
                        item.getLocation(), item.getSlot(),
                        item.getTemplate() != null ? item.getTemplate().getName() : item.getId());
                }
            }
        }
    }

    /**
     * 登记一件物品到 (location, slot) —— **所有入槽路径的唯一入口**（`index` / `putToCanvas` / `byUidPut`）。
     *
     * `byLocation` 的键就是 (location, slot)，所以"同一个槽位放第二件"在这个结构里表现为
     * **静默覆盖**：旧的那件从槽位索引里消失、只剩在 `byUid` 里 → 一件谁也看不见的幽灵。
     * 数据库那一层已经用唯一键挡住了这种行，这里补的是**内存侧的同一条不变式**：
     * 发现"该槽已被**别的 uid** 占用"就**拒绝覆盖 + 大声报错**（AGENTS #12：降级必须可见，
     * 静默兜底会把"数据有问题"伪装成"正常运行"）。
     *
     * @return true=已登记；false=槽位被别的物品占着，**本次未登记**（保持原占位者）
     */
    private boolean occupy(ItemInstance item, String from) {
        Map<Integer, ItemInstance> m = byLocation.computeIfAbsent(item.getLocation(), k -> new HashMap<>());
        ItemInstance occupant = m.get(item.getSlot());
        if (occupant != null && occupant.getId() != null && item.getId() != null
                && !occupant.getId().equals(item.getId())) {
            log.error("[SlotConflict] {} 入槽被拒：loc={} slot={} 已被 uid={}（{}）占用，"
                    + "本次 uid={}（{}）不登记 —— 同一 (角色, location, slot) 不允许两件物品",
                from, item.getLocation(), item.getSlot(),
                occupant.getId(), occupant.getTemplate() != null ? occupant.getTemplate().getName() : "?",
                item.getId(), item.getTemplate() != null ? item.getTemplate().getName() : "?");
            return false;
        }
        m.put(item.getSlot(), item);
        byUid.put(item.getId(), item);
        return true;
    }

    // ------------------------------------------------------------------
    // 变更（内存操作 + dirty 标记）
    // ------------------------------------------------------------------

    public void markDirty(int location, int slot, long itemUid) {
        dirty.computeIfAbsent(location, k -> new HashMap<>()).put(slot, itemUid);
    }

    public void markClean() {
        dirty.clear();
    }

    public Map<Integer, Map<Integer, Long>> takeDirty() {
        Map<Integer, Map<Integer, Long>> d = dirty;
        return d;
    }

    /**
     * 从画布某格移除一件（内存+位图），返回它。调用方负责 dirty 已记录。
     */
    public ItemInstance takeFromCanvas(int location, int slot) {
        Map<Integer, ItemInstance> m = byLocation.get(location);
        if (m == null) {
            return null;
        }
        ItemInstance item = m.remove(slot);
        if (item == null) {
            return null;
        }
        CanvasGrid cg = canvases.get(location);
        if (cg != null) {
            cg.remove(cg.xOf(slot), cg.yOf(slot), item.gridW(), item.gridH());
        }
        byUid.remove(item.getId());
        return item;
    }

    /** 放入画布某格（内存+位图）。调用方需先 canPlace 校验；同槽冲突会被 `occupy` 拒掉并报错。 */
    public boolean putToCanvas(int location, int slot, ItemInstance item) {
        item.setLocation(location);
        item.setSlot(slot);
        if (!occupy(item, "putToCanvas")) {
            return false;
        }
        CanvasGrid cg = canvases.get(location);
        if (cg != null) {
            cg.place(cg.xOf(slot), cg.yOf(slot), item.gridW(), item.gridH());
        }
        return true;
    }

    /** 非画布容器（装备/备用武器）登记一件。同槽冲突 → 拒绝登记并报错。 */
    public boolean byUidPut(ItemInstance item) {
        return occupy(item, "byUidPut");
    }

    /**
     * 装备栏里**真正装备着**的那些件 —— **排除鼠标位**（`slot = HELD_SLOT(-1)`）。
     *
     * 所有"按装备算属性/外观/抗性/推送"的遍历都必须走这里：拿在手上的那件还没装备，
     * 它进了 `itemsIn(EQUIP)` 会让玩家"手里拿着武器却还挂着武器属性"（而且**静默**、不报错）。
     * 需要**包含**手持位的只有一处：负重求和（原版 `CheckWeight` 把 `InvenItem` 与鼠标缓冲一起算）。
     */
    public List<ItemInstance> equippedItems() {
        Map<Integer, ItemInstance> m = byLocation.get(ItemLocations.EQUIP);
        if (m == null || m.isEmpty()) {
            return Collections.emptyList();
        }
        List<ItemInstance> out = new ArrayList<>(m.size());
        for (ItemInstance it : m.values()) {
            if (!ItemLocations.isHeld(it)) {
                out.add(it);
            }
        }
        return out;
    }

    /** 按 uid 移除（同时从所在容器摘除）；调用方负责脏标记。 */
    public void byUidRemove(long uid) {
        ItemInstance it = byUid.remove(uid);
        if (it != null) {
            Map<Integer, ItemInstance> m = byLocation.get(it.getLocation());
            if (m != null) {
                m.remove(it.getSlot());
            }
            if (ItemLocations.isCanvas(it.getLocation())) {
                CanvasGrid cg = canvases.get(it.getLocation());
                if (cg != null) {
                    cg.remove(cg.xOf(it.getSlot()), cg.yOf(it.getSlot()), it.gridW(), it.gridH());
                }
            }
        }
    }
}
