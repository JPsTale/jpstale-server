package org.jpstale.server.game.item;

import lombok.Data;

/**
 * 物品模板
 */
@Data
public class ItemTemplate {

    private int itemId;
    private String name;
    private ItemType type;
    private int subType;
    private int level;
    private int attack;
    private int defense;
    private int hp;
    private int mp;
    private int price;      // 购买价格
    private int sellPrice;  // 出售价格
    private String description;
    private boolean stackable;
    private int maxStack;

    public ItemTemplate() {
        this.stackable = true;
        this.maxStack = 99;
    }
}
