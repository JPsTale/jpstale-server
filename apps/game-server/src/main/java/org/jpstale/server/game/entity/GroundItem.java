package org.jpstale.server.game.entity;

import org.jpstale.common.service.item.ItemInstance;

/**
 * 地面物品（继承 BaseEntity，与其他实体同级）。
 *
 * 字段不可变（构造后不再修改）：地面物是内存对象、不进 DB，生命周期为
 * 投放 → 过期/被拾取/被挤掉。坐标由构造时确定，不移动。
 */
public class GroundItem extends BaseEntity {

    public final ItemInstance item;
    public final long ownerId;
    /**
     * 私有窗口截止时刻（仅"怪物掉落的私有战利品"有意义）：窗口内只有归属者看得见/捡得走。
     * 依据：原版 `dwCreateTime += 5000`（ex-machina / NewSourcePT `OnSever.cpp`、EU
     * `unitserver.cpp:1072 //for other players`）。**玩家主动丢弃不走这条路**（无 +5000）⇒ ownerId=0。
     */
    public final long privateUntil;
    public final long expireAt;
    /** 挤压级：1=不可覆盖（材料/装备），0=可被新掉落覆盖（金币/药水）。对齐原版 StgItems[].Level */
    public final int level;
    /**
     * **金币金额**（仅金币掉落物 > 0）：拾取时入账用（原版 `sITEMINFO.Money`）。
     */
    public final int money;

    public GroundItem(long id, ItemInstance item, int mapId, double x, double y, double z,
                      long ownerId, long privateUntil, long expireAt, int level, int money) {
        super(id);
        this.item = item;
        this.setMapId(mapId);
        this.x = x;
        this.y = y;
        this.z = z;
        this.ownerId = ownerId;
        this.privateUntil = privateUntil;
        this.expireAt = expireAt;
        this.level = level;
        this.money = money;
    }

    /**
     * 现在是否仍处于私有窗口（只有归属者能看见/捡）。
     * `ownerId == 0`（公共掉落）恒 false。
     */
    public boolean isPrivateAt(long now) {
        return ownerId != 0 && now < privateUntil;
    }

    public boolean isExpired(long now) {
        return expireAt <= now;
    }

    /** 显示名（模板不存在返回原始 itemCode 字符串） */
    public String displayName() {
        if (item.getTemplate() != null && item.getTemplate().getName() != null && !item.getTemplate().getName().isEmpty()) {
            return item.getTemplate().getName();
        }
        return "item#" + item.getItemCode();
    }
}
