package org.jpstale.server.game.item;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 背包
 */
@Data
public class Inventory {

    private List<ItemStack> items;
    private int capacity;

    public Inventory() {
        this.items = new ArrayList<>();
        this.capacity = 50;
    }

    public Inventory(int capacity) {
        this.items = new ArrayList<>();
        this.capacity = capacity;
    }

    /**
     * 添加物品
     */
    public boolean addItem(ItemStack item) {
        // 如果可堆叠，尝试合并
        if (item.getMaxStack() > 1) {
            for (ItemStack existing : items) {
                if (existing.getItemId() == item.getItemId() && !existing.isFull()) {
                    int canAdd = Math.min(item.getQuantity(), existing.getFreeSpace());
                    existing.setQuantity(existing.getQuantity() + canAdd);
                    item.setQuantity(item.getQuantity() - canAdd);

                    if (item.getQuantity() == 0) {
                        return true;
                    }
                }
            }
        }

        // 检查背包空间
        if (items.size() >= capacity) {
            return false;
        }

        // 添加新物品
        items.add(new ItemStack(item.getItemId(), item.getQuantity(), item.getMaxStack()));
        return true;
    }

    /**
     * 移除物品
     */
    public boolean removeItem(int itemId, int quantity) {
        int removed = 0;
        var iterator = items.iterator();

        while (iterator.hasNext() && removed < quantity) {
            ItemStack item = iterator.next();
            if (item.getItemId() == itemId) {
                int canRemove = Math.min(item.getQuantity(), quantity - removed);
                item.setQuantity(item.getQuantity() - canRemove);
                removed += canRemove;

                if (item.getQuantity() == 0) {
                    iterator.remove();
                }
            }
        }

        return removed >= quantity;
    }

    /**
     * 获取物品数量
     */
    public int getItemCount(int itemId) {
        return items.stream()
            .filter(item -> item.getItemId() == itemId)
            .mapToInt(ItemStack::getQuantity)
            .sum();
    }

    /**
     * 检查是否有足够的物品
     */
    public boolean hasItem(int itemId, int quantity) {
        return getItemCount(itemId) >= quantity;
    }

    /**
     * 获取指定物品
     */
    public Optional<ItemStack> getItem(int itemId) {
        return items.stream()
            .filter(item -> item.getItemId() == itemId)
            .findFirst();
    }

    /**
     * 检查背包是否已满
     */
    public boolean isFull() {
        return items.size() >= capacity;
    }

    /**
     * 获取已使用空间
     */
    public int getUsedSpace() {
        return items.size();
    }

    /**
     * 获取剩余空间
     */
    public int getFreeSpace() {
        return capacity - items.size();
    }
}
