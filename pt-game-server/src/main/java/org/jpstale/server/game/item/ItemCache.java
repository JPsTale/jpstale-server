package org.jpstale.server.game.item;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品缓存
 * 启动时加载物品模板
 */
@Slf4j
@Component
public class ItemCache {

    private final Map<Integer, ItemTemplate> templates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadDefaultItems();
        log.info("ItemCache initialized with {} items", templates.size());
    }

    /**
     * 加载默认物品
     */
    private void loadDefaultItems() {
        // 武器
        addItemTemplate(createItem(1, "Sword", ItemType.WEAPON, 0, 1, 10, 0, 0, 0, 100, 20, false, 1));
        addItemTemplate(createItem(2, "Axe", ItemType.WEAPON, 0, 1, 15, 0, 0, 0, 150, 30, false, 1));
        addItemTemplate(createItem(3, "Staff", ItemType.WEAPON, 1, 1, 5, 0, 10, 10, 120, 25, false, 1));

        // 防具
        addItemTemplate(createItem(10, "Leather Armor", ItemType.ARMOR, 0, 1, 0, 5, 0, 0, 80, 15, false, 1));
        addItemTemplate(createItem(11, "Iron Armor", ItemType.ARMOR, 0, 3, 0, 15, 0, 0, 200, 40, false, 1));

        // 饰品
        addItemTemplate(createItem(20, "Ring of Strength", ItemType.ACCESSORY, 0, 1, 3, 0, 0, 0, 50, 10, false, 1));

        // 消耗品
        addItemTemplate(createItem(100, "Health Potion", ItemType.CONSUMABLE, 0, 1, 0, 0, 50, 0, 20, 5, true, 99));
        addItemTemplate(createItem(101, "Mana Potion", ItemType.CONSUMABLE, 1, 1, 0, 0, 0, 50, 20, 5, true, 99));

        // 材料
        addItemTemplate(createItem(200, "Iron Ore", ItemType.MATERIAL, 0, 1, 0, 0, 0, 0, 10, 2, true, 99));
        addItemTemplate(createItem(201, "Leather", ItemType.MATERIAL, 0, 1, 0, 0, 0, 0, 8, 1, true, 99));
    }

    private ItemTemplate createItem(int itemId, String name, ItemType type, int subType, int level,
                                   int attack, int defense, int hp, int mp,
                                   int price, int sellPrice, boolean stackable, int maxStack) {
        ItemTemplate template = new ItemTemplate();
        template.setItemId(itemId);
        template.setName(name);
        template.setType(type);
        template.setSubType(subType);
        template.setLevel(level);
        template.setAttack(attack);
        template.setDefense(defense);
        template.setHp(hp);
        template.setMp(mp);
        template.setPrice(price);
        template.setSellPrice(sellPrice);
        template.setStackable(stackable);
        template.setMaxStack(maxStack);
        return template;
    }

    private void addItemTemplate(ItemTemplate template) {
        templates.put(template.getItemId(), template);
    }

    /**
     * 获取物品模板
     */
    public ItemTemplate getTemplate(int itemId) {
        return templates.get(itemId);
    }

    /**
     * 获取所有模板
     */
    public List<ItemTemplate> getAllTemplates() {
        return new ArrayList<>(templates.values());
    }

    /**
     * 按类型获取模板
     */
    public List<ItemTemplate> getTemplatesByType(ItemType type) {
        return templates.values().stream()
            .filter(t -> t.getType() == type)
            .toList();
    }
}
