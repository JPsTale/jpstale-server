package org.jpstale.server.web.simulator;

import lombok.Data;

/**
 * 装备模拟器：物品列表条目。
 */
@Data
public class ItemSummary {

    private Integer id;
    private Integer idCode;
    private String name;
    private String category;
    private String group;
    private Integer width;
    private Integer height;
    private Integer weaponClass;
    private Integer classItem;
    private Integer reqLevel;
    private Integer price;
}
