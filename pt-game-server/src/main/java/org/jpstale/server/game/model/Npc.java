package org.jpstale.server.game.model;

import lombok.Getter;
import lombok.Setter;

/**
 * NPC 实体（静态站桩）。
 *
 * 由 gamedb.mapnpc（分布）+ gamedb.npclist（定义）加载而来；纯展示，无移动/AI/交互。
 * 坐标域与服务端实体一致（raw 整数）：x=x, y=y, z=-z；angle 弧度（0~4095 → 0~2π）。
 */
@Getter
@Setter
public class Npc {

    private int npcId;       // npclist.id
    private String nameKey;  // 本地化 slug（npclist.name）
    private String modelFile; // 规范化模型路径（char/npc/xxx/xxx.inx）
    private double x, y, z;  // world
    private double angle;    // 弧度
    private int mapId;       // maplist.id
}
