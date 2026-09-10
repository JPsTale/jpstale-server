package org.jpstale.server.game.item;

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
 */
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
        byUid.put(item.getId(), item);
        Map<Integer, ItemInstance> m = byLocation.computeIfAbsent(item.getLocation(), k -> new HashMap<>());
        m.put(item.getSlot(), item);
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
                if (cg != null && cg.canPlace(cg.xOf(item.getSlot()), cg.yOf(item.getSlot()), item.gridW(), item.gridH())) {
                    cg.place(cg.xOf(item.getSlot()), cg.yOf(item.getSlot()), item.gridW(), item.gridH());
                }
            }
        }
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

    /** 放入画布某格（内存+位图）。调用方需先 canPlace 校验。 */
    public void putToCanvas(int location, int slot, ItemInstance item) {
        Map<Integer, ItemInstance> m = byLocation.computeIfAbsent(location, k -> new HashMap<>());
        CanvasGrid cg = canvases.get(location);
        if (cg != null) {
            cg.place(cg.xOf(slot), cg.yOf(slot), item.gridW(), item.gridH());
        }
        m.put(slot, item);
        byUid.put(item.getId(), item);
        item.setLocation(location);
        item.setSlot(slot);
    }

    /** 非画布容器（装备/备用武器）登记一件。 */
    public void byUidPut(ItemInstance item) {
        byUid.put(item.getId(), item);
        byLocation.computeIfAbsent(item.getLocation(), k -> new HashMap<>())
                .put(item.getSlot(), item);
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
