package org.jpstale.common.service.item;

import org.jpstale.common.service.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 力量石（Force Orb）：**吃 buff**（走 USE 消息）+ **力量大师转化**（材料石 → 力量石）。
 *
 * <p>
 * 依据 EU：buff = 一段**限时攻击力加成**（`itemserver.cpp:8868-8869` 把
 * `ForceDamageTable[档]` 与 `ForceDamagePercentTable[档]` 写到玩家身上，时长 `ForceDurationTable[档]`），
 * 伤害应用在 `HNSSkill.cpp:1775-1784` —— **百分比以基础攻击力为基数、flat 最后加**（见 {@link ForceOrb}）。
 *
 * <p>
 * **我们定的两处**（方案 §4 记过）：
 * <ul>
 *   <li><b>14 档全开</b>（EU 只启用部分档位）；</li>
 *   <li><b>转化的产出规则</b>：EU 源码里只读到"按石种累加价格"（`CheckForceOrbPrice`）与
 *       "服务端回 `Count` 件结果"（`sinRecvForceOrb`），**没有**读到"价格→产出档位"的换算。
 *       我们取最直白的读法：**每颗材料石 → 一颗同档力量石**（N 颗进、N 颗出，档位一一对应）。
 *       ⚠ 若日后要做"多颗低档换一颗高档"，那是**新规则**，得另行决定并记进文档 —— 别在代码里偷偷加。</li>
 * </ul>
 */
@Service
public class ForceOrbService {

    private static final Logger log = LoggerFactory.getLogger(ForceOrbService.class);

    /** 材料石家族（OS 族，14 档）。 */
    private static final int STONE_FAMILY = 0x02350000;

    private final ItemStorageService storage;
    private final ItemRollService roll;

    public ForceOrbService(ItemStorageService storage, ItemRollService roll) {
        this.storage = storage;
        this.roll = roll;
    }

    /** 失败/拒绝原因（协议按 key 走）。 */
    public enum Reason {
        OK, ORB_NOT_FOUND, ORB_NOT_ALLOWED, STONE_NOT_FOUND, STONE_NOT_ALLOWED, NO_STONES;

        public String key() {
            return "item.op.force." + name().toLowerCase().replace('-', '-');
        }
    }

    /** 结果。 */
    public static final class Result {
        public final Reason reason;
        public final int tier;
        public final int flat;
        public final int percent;
        public final int durationSec;

        private Result(Reason reason, int tier, int flat, int percent, int durationSec) {
            this.reason = reason;
            this.tier = tier;
            this.flat = flat;
            this.percent = percent;
            this.durationSec = durationSec;
        }

        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private static Result fail(Reason r) {
        return new Result(r, -1, 0, 0, 0);
    }

    /** **吃**一颗力量石（走 `C2S_UseItem`）：设定 buff 的截止时间（同档重复使用 = 覆盖刷新，照 EU）。 */
    public Result activate(Player player, long orbUid) {
        PlayerItems items = player.getItems();
        if (items == null) {
            return fail(Reason.ORB_NOT_FOUND);
        }
        ItemInstance orb = items.byUid(orbUid);
        if (orb == null || orb.isDeleted() || !isUsableLocation(orb)) {
            return fail(Reason.ORB_NOT_FOUND);
        }
        int index = ForceOrb.tierIndexOf(orb.getItemCode());
        if (index < 0) {
            return fail(Reason.ORB_NOT_ALLOWED);
        }
        // 消耗力量石
        items.byUidRemove(orb.getId());
        orb.setDeleted(true);
        storage.softDelete(orb.getId());
        // 设定 buff（EU：`dwForceOrbCode` + `dwForceOrbTime = dwPlayTime + ForceOrbUseTime[档]*1000`）
        int flat = ForceOrb.flatDamage(index);
        int percent = ForceOrb.percentDamage(index);
        long until = System.currentTimeMillis() + ForceOrb.durationMs(index);
        player.setForceOrbCode(orb.getItemCode() == null ? 0 : orb.getItemCode());
        player.setForceOrbUntil(until);
        player.setForceOrbFlat(flat);
        player.setForceOrbPercent(percent);
        log.info("[ForceOrb] {} 吃下 {}（档 {}）→ 攻击力 +{} 与 +{}%（{} 秒）", player.getName(),
                orb.name(), index + 1, flat, percent, ForceOrb.durationSec(index));
        return new Result(Reason.OK, index + 1, flat, percent, ForceOrb.durationSec(index));
    }

    /**
     * **力量大师转化**：把投入的材料石换成同档力量石（见类注释的"我们定的"第二条）。
     * 消耗材料石、按档位生成对应的 `sinFO1|tier` 力量石（用 `ItemRollService` 掷点保证实例合法）。
     *
     * @return 生成的力量石实例（已入库/入包，调用方负责推送）
     */
    public List<ItemInstance> convert(Player player, List<Long> stoneUids) {
        PlayerItems items = player.getItems();
        if (items == null || stoneUids == null || stoneUids.isEmpty()) {
            return List.of();
        }
        List<ItemInstance> out = new ArrayList<>(stoneUids.size());
        for (Long uid : stoneUids) {
            ItemInstance stone = uid == null ? null : items.byUid(uid);
            if (stone == null || stone.isDeleted() || stone.getLocation() != ItemLocations.BAG_PAGE
                    || !isMaterialStone(stone.getItemCode())) {
                log.warn("[ForceOrb] 转化：uid={} 不是背包里的材料石，整批放弃（不静默跳过）", uid);
                return List.of();
            }
            out.add(stone);
        }
        List<ItemInstance> created = new ArrayList<>(out.size());
        for (ItemInstance stone : out) {
            int tier = (stone.getItemCode() & 0xFFFF) >>> 8;                 // 1..14
            int orbCode = ForceOrb.FAMILY | (tier << 8);
            ItemInstance orb = roll.rollByIdCode(orbCode, 0);
            if (orb == null) {
                log.error("[ForceOrb] 转化：力量石码 0x{} 在物品表里不存在（档 {}）—— 该批已消耗的石头请人工补",
                        Integer.toHexString(orbCode), tier);
                continue;
            }
            items.byUidRemove(stone.getId());
            stone.setDeleted(true);
            storage.softDelete(stone.getId());
            ItemInstance placed = placeInBag(player, orb);
            created.add(placed);
            log.info("[ForceOrb] {} 转化 1×{} → {}（码 0x{}）", player.getName(), stone.name(),
                    placed.name(), Integer.toHexString(orbCode));
        }
        return created;
    }

    /** 放进背包（放不下就报错并留在手上——这里简化为报错 + 不入包，避免物品凭空消失）。 */
    private ItemInstance placeInBag(Player player, ItemInstance orb) {
        PlayerItems items = player.getItems();
        int slot = items.canvas(ItemLocations.BAG_PAGE).findFreeSlot(orb.gridW(), orb.gridH());
        if (slot < 0) {
            log.error("[ForceOrb] 背包放不下转化产物 {}（uid 未落库）—— 请手动补偿", orb.name());
            return orb;
        }
        orb.setLocation(ItemLocations.BAG_PAGE);
        orb.setSlot(slot);
        storage.insert(orb);
        items.byUidPut(orb);
        return orb;
    }

    // ------------------------------------------------------------------ 查询（伤害侧用）

    /** buff 是否还在（`forceOrbUntil` 是**绝对**毫秒时间戳）。 */
    public static boolean active(Player player) {
        return player != null && player.getForceOrbUntil() > System.currentTimeMillis();
    }

    /** 当前的固定加成（未激活 = 0）。 */
    public static int flatBonus(Player player) {
        return active(player) ? player.getForceOrbFlat() : 0;
    }

    /** 当前的百分比加成（未激活 = 0）。 */
    public static int percentBonus(Player player) {
        return active(player) ? player.getForceOrbPercent() : 0;
    }

    private static boolean isUsableLocation(ItemInstance it) {
        int loc = it.getLocation();
        return loc == ItemLocations.BAG_PAGE
                || loc == ItemLocations.EQUIP && it.getSlot() >= ItemLocations.SLOT_POTION_1
                && it.getSlot() <= ItemLocations.SLOT_POTION_3;
    }

    private static boolean isMaterialStone(Integer idCode) {
        if (idCode == null || (idCode & 0xFFFF0000) != STONE_FAMILY) {
            return false;
        }
        int tier = (idCode & 0xFFFF) >>> 8;
        return tier >= 1 && tier <= 14;
    }
}
