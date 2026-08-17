package org.jpstale.server.game.item;

import lombok.Data;

import java.util.EnumMap;
import java.util.Map;

/**
 * 装备栏
 */
@Data
public class Equipment {

    private final Map<EquipmentSlotType, ItemStack> slots;

    public Equipment() {
        this.slots = new EnumMap<>(EquipmentSlotType.class);
    }

    /**
     * 装备物品
     */
    public ItemStack equip(ItemStack item, ItemCache itemCache) {
        ItemTemplate template = itemCache.getTemplate(item.getItemId());
        if (template == null) {
            return null;
        }

        EquipmentSlotType slotType = getSlotType(template.getType());
        if (slotType == null) {
            return null;
        }

        // 卸下旧装备
        ItemStack oldItem = slots.put(slotType, item);
        return oldItem;
    }

    /**
     * 卸下装备
     */
    public ItemStack unequip(EquipmentSlotType slotType) {
        return slots.remove(slotType);
    }

    /**
     * 获取指定槽位的装备
     */
    public ItemStack getEquipped(EquipmentSlotType slotType) {
        return slots.get(slotType);
    }

    /**
     * 计算总攻击力
     */
    public int calculateAttack(ItemCache itemCache) {
        int totalAttack = 0;
        for (ItemStack item : slots.values()) {
            ItemTemplate template = itemCache.getTemplate(item.getItemId());
            if (template != null) {
                totalAttack += template.getAttack();
            }
        }
        return totalAttack;
    }

    /**
     * 计算总防御力
     */
    public int calculateDefense(ItemCache itemCache) {
        int totalDefense = 0;
        for (ItemStack item : slots.values()) {
            ItemTemplate template = itemCache.getTemplate(item.getItemId());
            if (template != null) {
                totalDefense += template.getDefense();
            }
        }
        return totalDefense;
    }

    private EquipmentSlotType getSlotType(ItemType itemType) {
        return switch (itemType) {
            case WEAPON -> EquipmentSlotType.WEAPON;
            case ARMOR -> EquipmentSlotType.BODY;
            case ACCESSORY -> EquipmentSlotType.ACCESSORY;
            default -> null;
        };
    }
}
