package org.jpstale.server.game.model;

import lombok.Data;

/**
 * 物品堆叠
 */
@Data
public class ItemStack {

    private int itemId;
    private int quantity;
    private int maxStack;

    public ItemStack() {
        this.maxStack = 99;
    }

    public ItemStack(int itemId, int quantity) {
        this.itemId = itemId;
        this.quantity = quantity;
        this.maxStack = 99;
    }

    public ItemStack(int itemId, int quantity, int maxStack) {
        this.itemId = itemId;
        this.quantity = quantity;
        this.maxStack = maxStack;
    }

    public boolean isFull() {
        return quantity >= maxStack;
    }

    public int getFreeSpace() {
        return maxStack - quantity;
    }
}
