package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 传送目的地表：**物品（或物品族）→ 去哪张图 + 怎么选落点**。
 *
 * 为什么要这张表：游戏里传送来源很多（以太核心/传送卷轴/传送门/婚戒/GM/脱困），
 * 它们的差别只有"落点怎么来"和"代价与限流"；搬运机制在 `TeleportService`，
 * 而"哪个物品去哪"属于**游戏数据**——所以放 DB（与 maplist/itemlist 同一套口径）。
 *
 * 依据（原版）：客户端 `character.cpp` 的 `switch (UseEtherCoreCode)` 把
 * `sinEC1|sin01/02/04` 分别映射到 `START_FIELD_NUM(3)/NEBISCO(9)/MORYON(21)`；
 * idcode 家族口径 = `CODE & 0xFFFF0000`（原版 `sinITEM_MASK2`）。
 * `note` 列逐行写明是硬证还是推断（见 postgres-init 的 seed）。
 *
 * @since 2026-09-13
 */
@Data
@TableName(schema = "gamedb", value = "teleportdestination")
public class TeleportDestination {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** idcode 高 16 位（原版 sinITEM_MASK2 口径），例：EC 族 = 0x0601 */
    @TableField("itemfamily")
    private Integer itemFamily;

    /** idcode 低 16 位；NULL = 该族通用（所有低 16 位都命中这条） */
    @TableField("itemcode")
    private Integer itemCode;

    /** 便于人读的码名（EC101 / Union Core…） */
    @TableField("itemname")
    private String itemName;

    /** 目标图 id（= gamedb.maplist.id） */
    @TableField("destmap")
    private Integer destMap;

    /** 落点策略：startpoint-random（原版回城道具）/ startpoint-nearest / center / fixed */
    @TableField("landing")
    private String landing;

    @TableField("fixedx")
    private Integer fixedX;

    @TableField("fixedz")
    private Integer fixedZ;

    /** 依据说明（硬证/推断/我们自己的决定）——**不许写空**，出处即结论的一部分 */
    @TableField("note")
    private String note;
}
