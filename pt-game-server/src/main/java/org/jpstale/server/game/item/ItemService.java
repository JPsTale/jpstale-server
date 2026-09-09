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

    public ItemService(ItemRollService roll, ItemStorageService storage) {
        this.roll = roll;
        this.storage = storage;
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
        return fresh == null ? null : grantInstanceToBag(player, fresh);
    }

    /**
     * 把一件已掷点物品放入背包（保留其随机属性；丢弃/拾取/掉落通用）。
     * 堆叠物优先并入已有堆；无空位返回 null（背包满，不落库）。
     */
    @Transactional
    public ItemInstance grantInstanceToBag(Player player, ItemInstance fresh) {
        PlayerItems items = player.getItems();
        fresh.setCharacterId(Math.toIntExact(player.getId()));
        fresh.setLocation(ItemLocations.BAG);
        fresh.setSlot(0);
        // 堆叠物先尝试并入已有
        if (fresh.stackable()) {
            for (ItemInstance existing : items.itemsIn(ItemLocations.BAG)) {
                if (existing.getItemListId().equals(fresh.getItemListId())
                        && !existing.isDeleted()
                        && existing.getCount() > 0) {
                    int add = Math.min(fresh.getCount(), 1000 - existing.getCount());
                    if (add > 0) {
                        existing.setCount(existing.getCount() + add);
                        storage.update(existing);
                        return existing;
                    }
                }
            }
        }
        int slot = items.canvas(ItemLocations.BAG).findFreeSlot(fresh.gridW(), fresh.gridH());
        if (slot < 0) {
            return null; // 背包满
        }
        fresh.setSlot(slot);
        storage.insert(fresh);
        items.index(fresh);
        return fresh;
    }

    /**
     * 画布内移动/换格（含跨画布 背包↔仓库）。目标格空才可放（位图校验）。
     *
     * @return true=成功
     */
    @Transactional
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
