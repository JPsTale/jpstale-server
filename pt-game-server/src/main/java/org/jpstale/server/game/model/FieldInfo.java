package org.jpstale.server.game.model;

import lombok.Data;

import java.util.List;

/**
 * 单张地图（field）的静态数据。
 * <p>
 * 来源 fields/fields.json（scripts/gen-map-catalog.py 从 EU MapGame.cpp 生成，纯数组）。
 * 坐标域 z = -(EU MapGame.cpp rawZ)，正北为正（与旧 FieldMap 数值域一致）。
 * <p>
 * name/levelReq/pvp/stageFile/刷怪点 的权威仍在 DB gamedb.Map_List；本类只承载静态字段数据。
 */
@Data
public class FieldInfo {

    /** == gamedb.Map_List.ID / EU EMapID */
    private int id;
    /** 仅可读标识（DB Short_Name 冗余）；DB 才是权威 */
    private String shortname;
    /** 碰撞网格 smd，相对 /res/field/（磁盘实际小写路径） */
    private String model;
    /** field/map/{minimap}.tga 基名（小写无扩展名）；无小地图为 null */
    private String minimap;
    private int[] center;
    private List<int[]> startPoints;
    private List<FieldGate> fieldGates;
    private List<WarpGate> warpGates;
    private List<StageObject> stageObjects;

    /** 出生兜底点：优先第一个出生点，否则中心 */
    public int[] fallbackStart() {
        if (startPoints != null && !startPoints.isEmpty()) {
            return startPoints.get(0);
        }
        return center;
    }

    /** 相邻地图边界门（AddBorder），玩家走图互通 */
    @Data
    public static class FieldGate {
        private int to;
        private int x;
        private int z;
    }

    /** 传送门（AddTeleport1/2） */
    @Data
    public static class WarpGate {
        private int x;
        private int z;
        private int y;
        private int size;
        private int height;
        /** 0=Direct 1=Warp 2=WarpGate（EU ETeleportType） */
        private int mode;
        private List<WarpDestination> destinations;
    }

    /** 传送落点 */
    @Data
    public static class WarpDestination {
        private int map;
        private int x;
        private int z;
        private int y;
        private int level;
    }

    /** 地图内静态物件（AddStaticModel） */
    @Data
    public static class StageObject {
        private String model;
        /** 有动画（EU AddStaticModel 第二参 TRUE） */
        private boolean bip;
    }
}
