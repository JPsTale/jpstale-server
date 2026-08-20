package org.jpstale.server.game.model;

/**
 * 地图硬编码数据（对齐 EU C++ InitField() 顺序，mapId 0-43）。
 * smd 为碰撞网格相对路径（相对 pt.field.smd-root）。
 * 门/出生点/中心坐标为原始 int（定点数/256 前的世界坐标）。
 */
public enum FieldMap {

    FIELD_0("forest/fore-3.smd", new int[]{-16419, -7054}, new int[][]{new int[]{-10585, -11810}, new int[]{-13659, -9753}}, new FieldMap.Gate[]{new FieldMap.Gate(1, -8508, -10576)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-16638, -6737, 267, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(24, 119112, 26028)})}),
    FIELD_1("forest/fore-2.smd", new int[]{-5427, -9915}, new int[][]{new int[]{-2272, -10314}, new int[]{-7192, -11175}}, new FieldMap.Gate[]{new FieldMap.Gate(2, -292, -9548)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-3408, -12447, 251, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_2("forest/fore-1.smd", new int[]{4184, -11016}, new int[][]{new int[]{1350, -13672}, new int[]{1761, -10815}, new int[]{4777, -10916}}, new FieldMap.Gate[]{new FieldMap.Gate(4, 4844, -6835), new FieldMap.Gate(3, 2275, -14828)}, new FieldMap.WarpGate[]{}),
    FIELD_3("ricarten/village-2.smd", new int[]{2596, -18738}, new int[][]{new int[]{2592, -18566}, new int[]{-1047, -16973}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(734, -20119, 312, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_4("ruin/ruin-4.smd", new int[]{1384, -918}, new int[][]{new int[]{2578, -5124}, new int[]{1873, 3060}}, new FieldMap.Gate[]{new FieldMap.Gate(5, 410, 4902)}, new FieldMap.WarpGate[]{}),
    FIELD_5("ruin/ruin-3.smd", new int[]{4953, 10922}, new int[][]{new int[]{875, 7910}, new int[]{1576, 14307}}, new FieldMap.Gate[]{new FieldMap.Gate(6, 3051, 16124)}, new FieldMap.WarpGate[]{}),
    FIELD_6("ruin/ruin-2.smd", new int[]{7459, 23984}, new int[][]{new int[]{3976, 19645}, new int[]{5832, 23751}, new int[]{5615, 25117}}, new FieldMap.Gate[]{new FieldMap.Gate(7, 10019, 18031), new FieldMap.Gate(17, 4470, 27774), new FieldMap.Gate(34, 12713, 23409)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(4428, 22511, 845, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_7("ruin/ruin-1.smd", new int[]{16362, 14959}, new int[][]{new int[]{12242, 16034}, new int[]{12194, 8969}}, new FieldMap.Gate[]{new FieldMap.Gate(8, 13319, 7102)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(16809, 15407, 501, 128, 32, new FieldMap.Gate[]{})}),
    FIELD_8("desert/de-1.smd", new int[]{15005, -1421}, new int[][]{new int[]{13306, 2000}, new int[]{13083, -2249}}, new FieldMap.Gate[]{new FieldMap.Gate(10, 13466, -5953), new FieldMap.Gate(9, 20041, -892)}, new FieldMap.WarpGate[]{}),
    FIELD_9("forest/village-1.smd", new int[]{22214, -1182}, new int[][]{new int[]{22214, -1182}}, new FieldMap.Gate[]{new FieldMap.Gate(11, 27110, -479), new FieldMap.Gate(30, 21840, 1062)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(21936, -1833, 855, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_10("desert/de-2.smd", new int[]{15887, -11161}, new int[][]{new int[]{11859, -11257}, new int[]{16169, -12768}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{}),
    FIELD_11("desert/de-3.smd", new int[]{34758, -1323}, new int[][]{new int[]{34758, -1323}, new int[]{29424, 322}}, new FieldMap.Gate[]{new FieldMap.Gate(12, 34372, 4277)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(36128, -2162, 804, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_12("desert/de-4.smd", new int[]{43988, 12053}, new int[][]{new int[]{33740, 6491}, new int[]{40691, 11056}}, new FieldMap.Gate[]{new FieldMap.Gate(27, 44545, 13063)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(33979, 6080, 969, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_13("dungeon/dun-1.smd", new int[]{-15384, -24310}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-15388, -24073, 100, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-15305, -28824, 1, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_14("dungeon/dun-2.smd", new int[]{-6108, -26880}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-5908, -26878, 136, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-8019, -25768, 21, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-3918, -27988, 21, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_15("dungeon/dun-3.smd", new int[]{1827, -28586}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(1810, -28924, 0, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_16("room/office.smd", new int[]{-200000, 200000}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{}),
    FIELD_17("forever-fall/forever-fall-04.smd", new int[]{-1583, 37905}, new int[][]{new int[]{-2135, 33668}, new int[]{4073, 32119}}, new FieldMap.Gate[]{new FieldMap.Gate(18, -2949, 40442)}, new FieldMap.WarpGate[]{}),
    FIELD_18("forever-fall/forever-fall-03.smd", new int[]{-5823, 43244}, new int[][]{new int[]{-1516, 45437}, new int[]{-3586, 42886}}, new FieldMap.Gate[]{new FieldMap.Gate(19, -2349, 49830)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-4730, 48107, 1173, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-6306, 43241, 779, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(25, 119025, 35680)})}),
    FIELD_19("forever-fall/forever-fall-02.smd", new int[]{1691, 52599}, new int[][]{new int[]{-1024, 45437}, new int[]{-1543, 52333}, new int[]{-1310, 54945}}, new FieldMap.Gate[]{new FieldMap.Gate(20, 667, 59371)}, new FieldMap.WarpGate[]{}),
    FIELD_20("forever-fall/forever-fall-01.smd", new int[]{2032, 71183}, new int[][]{new int[]{1875, 69871}, new int[]{2637, 60219}}, new FieldMap.Gate[]{new FieldMap.Gate(21, -8508, -10576)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(1962, 71184, 559, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_21("forever-fall/pilai.smd", new int[]{2981, 75486}, new int[][]{new int[]{2287, 74131}, new int[]{3547, 75500}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(2000, 72907, 474, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(2245, 78259, 745, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_22("dungeon/dun-4.smd", new int[]{-11108, -41681}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-10860, -41666, 398, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-12089, -40380, 95, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_23("dungeon/dun-5.smd", new int[]{-3675, -36597}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-3665, -36334, 762, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-4895, -37132, 762, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-2441, -37137, 762, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(-3207, -43830, 553, 128, 32, new FieldMap.Gate[]{new FieldMap.Gate(42, -3650, -45312), new FieldMap.Gate(42, -3668, -50022)}), new FieldMap.WarpGate(-4025, -43821, 553, 128, 32, new FieldMap.Gate[]{new FieldMap.Gate(42, -3650, -45312), new FieldMap.Gate(42, -3668, -50022)})}),
    FIELD_24("cave/tcave.smd", new int[]{120126, 26064}, new int[][]{new int[]{119319, 26034}, new int[]{125606, 24541}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(125581, 25086, 480, 80, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(118869, 26017, 538, 80, 32, new FieldMap.Gate[]{new FieldMap.Gate(0, -16490, -6930)})}),
    FIELD_25("cave/mcave.smd", new int[]{119966, 35466}, new int[][]{new int[]{119320, 35680}, new int[]{124380, 33260}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(124391, 32913, 37, 80, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(118808, 35686, 520, 80, 32, new FieldMap.Gate[]{new FieldMap.Gate(18, -6056, 43245)})}),
    FIELD_26("cave/dcave.smd", new int[]{158814, 20070}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(158042, 19525, 330, 120, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(158023, 20453, 240, 120, 32, new FieldMap.Gate[]{})}),
    FIELD_27("iron/iron-1.smd", new int[]{47617, 13626}, new int[][]{new int[]{46905, 13478}, new int[]{47357, 20845}, new int[]{46741, 21349}}, new FieldMap.Gate[]{new FieldMap.Gate(28, 45316, 21407)}, new FieldMap.WarpGate[]{}),
    FIELD_28("iron/iron-2.smd", new int[]{33938, 24229}, new int[][]{new int[]{35149, 24326}, new int[]{44893, 21380}}, new FieldMap.Gate[]{new FieldMap.Gate(29, 33618, 24011)}, new FieldMap.WarpGate[]{}),
    FIELD_29("ice/ice_ura.smd", new int[]{32621, 23865}, new int[][]{}, new FieldMap.Gate[]{new FieldMap.Gate(31, 31848, 27225)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(30407, 22232, 1349, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_30("sod/sod-1.smd", new int[]{22018, 6569}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{}),
    FIELD_31("ice/ice1.smd", new int[]{32313, 28529}, new int[][]{}, new FieldMap.Gate[]{new FieldMap.Gate(35, 33729, 38029)}, new FieldMap.WarpGate[]{}),
    FIELD_32("quest/quest_iv.smd", new int[]{22497, 9605}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{}),
    FIELD_33("castle/castle.smd", new int[]{35225, -23847}, new int[][]{new int[]{33920, -23479}, new int[]{35247, -23355}, new int[]{36164, -23446}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(32527, -30693, 774, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_34("greedy/greedy.smd", new int[]{13930, 23206}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{}),
    FIELD_35("ice/ice_2.smd", new int[]{36264, 40182}, new int[][]{}, new FieldMap.Gate[]{new FieldMap.Gate(31, 35364, 39518)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(37981, 50790, 1216, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_36("boss/boss.smd", new int[]{33000, 50036}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(33012, 49926, 1533, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_37("lost/lostisland.smd", new int[]{-12555, -1113}, new int[][]{}, new FieldMap.Gate[]{new FieldMap.Gate(38, -11586, 7704)}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-12610, -1349, 694, 64, 32, new FieldMap.Gate[]{})}),
    FIELD_38("losttemple/lost_temple.smd", new int[]{-11718, 10794}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{}),
    FIELD_39("fall_game/fall_game.smd", new int[]{5800, 36747}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(5563, 36772, 840, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(21, 2981, 75486)}), new FieldMap.WarpGate(11502, 36776, 840, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(21, 2981, 75486)})}),
    FIELD_40("endless/dun-7.smd", new int[]{14255, -39099}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(14242, -40988, 259, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(14246, -38912, 220, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(38, -12272, 11299)})}),
    FIELD_41("endless/dun-8.smd", new int[]{5255, -37897}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(5253, -37708, 69, 64, 32, new FieldMap.Gate[]{}), new FieldMap.WarpGate(5254, -41500, 139, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(43, 4896, -42220), new FieldMap.Gate(43, 6408, -42220)})}),
    FIELD_42("dungeon/dun-6a.smd", new int[]{-3660, -45200}, new int[][]{}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(-3655, -50603, 3, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(23, -2851, -43792), new FieldMap.Gate(23, -4422, -43801)}), new FieldMap.WarpGate(-3664, -44886, 145, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(23, -2851, -43792), new FieldMap.Gate(23, -4422, -43801)})}),
    FIELD_43("endless/dun-9.smd", new int[]{4896, -42330}, new int[][]{new int[]{4896, -42330}, new int[]{6418, -42330}}, new FieldMap.Gate[]{}, new FieldMap.WarpGate[]{new FieldMap.WarpGate(4896, -42090, 230, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(41, 5254, -41361)}), new FieldMap.WarpGate(6408, -42090, 230, 64, 32, new FieldMap.Gate[]{new FieldMap.Gate(41, 5254, -41361)})}),
    ;

    public final String smd;
    public final int[] center;
    public final int[][] startPoints;
    public final Gate[] gates;
    public final WarpGate[] warpGates;

    FieldMap(String smd, int[] center, int[][] startPoints, Gate[] gates, WarpGate[] warpGates) {
        this.smd = smd;
        this.center = center;
        this.startPoints = startPoints;
        this.gates = gates;
        this.warpGates = warpGates;
    }

    /** 地图门 */
    public static class Gate {
        public final int to, x, z;
        public Gate(int to, int x, int z) { this.to = to; this.x = x; this.z = z; }
    }

    /** 传送门 */
    public static class WarpGate {
        public final int x, z, y, size, height;
        public final Gate[] out;
        public WarpGate(int x, int z, int y, int size, int height, Gate[] out) {
            this.x = x; this.z = z; this.y = y; this.size = size; this.height = height; this.out = out;
        }
    }
}