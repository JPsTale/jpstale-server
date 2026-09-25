package org.jpstale.server.common.codec;

/**
 * 与 packets.h 中游戏逻辑相关的常量（技能、距离、等级、毒/燃烧、混配、崩溃包、地图指示等）。
 * 与 C++ #define 数值保持一致，便于协议对齐。
 */
public final class GameConstants {

    private GameConstants() {}

    // 经验倍率
    public static final double EXP_MODIFIER = 100.0;

    // ---------- 技能 ----------
    /** 技能数组最大条数 */
    public static final int MAX_SKILL_ARRAY_DATA = 150;
    /** 通用技能信息最大条数 */
    public static final int MAX_COMMON_SKILL_INFO_DATA = 25;
    /** 角色技能数量 */
    public static final int SKILL_COUNT_CHARACTER = 16;
    /** 连续技能最大数量 */
    public static final int MAX_CONTINUE_SKILL = 20;

    // ---------- 速度/帧 ----------
    /** 慢速基准值 */
    public static final int SLOW_SPEED_BASE = 256;
    /** 服务器角色帧步进 */
    public static final int SERVER_CHAR_FRAME_STEP = 320;
    /** 服务器角色跳过 */
    public static final int SERVER_CHAR_SKIP = 4;

    // ---------- 服务器 Tick Rate ----------
    /** 服务器主循环 tick 频率（次/秒） */
    public static final double TICK_RATE = 20.0;
    /** 每 tick 毫秒数 */
    public static final long TICK_MS = 50;

    // ---------- 坐标缩放 ----------
    // 世界坐标即游戏原生单位（SMD RECT / spawn point / FieldMap 同尺度）。
    // 移动步进按「米」语义表达：speed(m/s) / TICK_RATE * POSITION_SCALE。
    /** 坐标缩放因子：1 世界单位 = POSITION_SCALE 内部步进（原版 fONE=256） */
    public static final int POSITION_SCALE = 256;

    // ---------- 怪物移动速度（m/s，exm 源码实测） ----------
    /** 怪物步行速度 0.25 m/s（IQ<6 的 hobby 怪只能 walk） */
    public static final double MONSTER_WALK_SPEED = 0.25;
    /** 怪物奔跑速度 0.50 m/s（IQ≥6 且有 run 动画时） */
    public static final double MONSTER_RUN_SPEED = 0.50;

    // ---------- 怪物每 tick 步进（step = speed / TICK_RATE * POSITION_SCALE） ----------
    /** 怪物步行每 tick 步进 = 0.25/20*256 = 3.2 */
    public static final double MONSTER_WALK_STEP = MONSTER_WALK_SPEED / TICK_RATE * POSITION_SCALE;
    /** 怪物奔跑每 tick 步进 = 0.50/20*256 = 6.4 */
    public static final double MONSTER_RUN_STEP = MONSTER_RUN_SPEED / TICK_RATE * POSITION_SCALE;

    // ---------- 玩家移动速度（档位体系，对标 wartale 1~51） ----------
    /** 面板移动速度档位范围：最小值 1（wartale 1~51 > exm 1~25 > 原版 1~9） */
    public static final int MOVE_SPEED_MIN = 1;
    /** 面板移动速度档位范围：最大值 51 */
    public static final int MOVE_SPEED_MAX = 51;
    /** 档位→内部 MoveSpeed：MoveSpeed = 250 + 10×档位（原版 MoveSpeed=250+10*cnt） */
    public static final int MOVE_SPEED_BASE = 250;
    public static final int MOVE_SPEED_PER_LEVEL = 10;
    /** 每帧内部步进系数：(MoveSpeed×coeff)>>8 = 内部步进@60fps，÷256 = world */
    public static final int WALK_STEP_COEFF = 180;
    public static final int RUN_STEP_COEFF = 460;
    /** 客户端基准确率 60fps：每秒距离 = 每帧步长 × 60 */
    public static final double CLIENT_FPS = 60.0;

    /** 玩家行走每秒距离（world/s）按档位，档位 1~51（服务端限速与客户端自机步长同源） */
    public static double playerWalkSpeedWorldPerSec(int moveSpeed) {
        int step = (MOVE_SPEED_BASE + MOVE_SPEED_PER_LEVEL * moveSpeed) * WALK_STEP_COEFF >> 8;
        return step / 256.0 * CLIENT_FPS;
    }

    /** 玩家奔跑每秒距离（world/s）按档位，档位 1~51 */
    public static double playerRunSpeedWorldPerSec(int moveSpeed) {
        int step = (MOVE_SPEED_BASE + MOVE_SPEED_PER_LEVEL * moveSpeed) * RUN_STEP_COEFF >> 8;
        return step / 256.0 * CLIENT_FPS;
    }

    // ---------- 档位 → 动画速率表（初始化时算一次，之后只查表） ----------
    /**
     * 走/跑**动画速率** = 该档速度 ÷ 1档速度 —— 用来把走/跑动画的播放速度按**实际移速**缩放
     * （动画是按 1 档基准做的；加速后位移变快、步频得跟着变，否则看起来在滑行）。
     *
     * **为什么是表**（用户 2026-09-16 提）：速率**只由档位决定** —— `速度 = f(档位)` 是纯函数、
     * 1 档速度是常量 ⇒ `rate(档位)` 本身就是**档位的纯函数**，而档位只有
     * `MOVE_SPEED_MIN..MOVE_SPEED_MAX` 这些取值。所以启动时算一次、使用时一次数组索引，
     * 不必在每个下发路径上重做除法。
     *
     * 附带性质（可断言，见自检）：`WALK_ANIM_RATE[MOVE_SPEED_MIN] == 1.0`（1 档即基准）。
     */
    /**
     * 动画速率基准：**原版 `FrameStep = 80 * MoveSpeed / 300`**（exm `character.cpp:4017`）
     * ⇒ 播放速率 = `MoveSpeed / 300`，基准落在 **MoveSpeed 300 = 档位 5**（**不是 1 档**）。
     * 换算：档位 1 → 0.867（略慢于标准）、档位 5 → 1.0、档位 51 → 2.533。
     *
     * ⚠ 这条推翻了早先"以 1 档为基准"的实现——那不是原版：51 档时我们算 2.92 而原版 2.53，
     * 快 15%，表现为"腿跑出残影"（用户实测）。原版帧步**只跟 `MoveSpeed` 有关、走/跑不分** ⇒
     * 两张表的值相同（proto 仍保留两个字段，留给将来可能的差异）。
     */
    public static final int ANIM_RATE_BASE_MOVE_SPEED = 300;

    public static final double[] WALK_ANIM_RATE = buildAnimRateTable();
    public static final double[] RUN_ANIM_RATE = buildAnimRateTable();

    /** 建表：速率 = `MoveSpeed / 300`（原版公式）。索引越界按 clamp 取值，不抛。 */
    private static double[] buildAnimRateTable() {
        final double[] table = new double[MOVE_SPEED_MAX + 1];
        for (int i = 0; i < table.length; i++) {
            final int tier = Math.clamp(i, MOVE_SPEED_MIN, MOVE_SPEED_MAX);
            table[i] = (MOVE_SPEED_BASE + MOVE_SPEED_PER_LEVEL * tier) / (double) ANIM_RATE_BASE_MOVE_SPEED;
        }
        return table;
    }

    // ---------- 燃烧/毒 ----------
    /** 燃烧 tick 间隔（毫秒） */
    public static final int BURNING_TICKRATE_MS = 500;
    /** 毒最大叠层 */
    public static final int POISON_MAXSTACK = 5;
    /** 毒持续时间（秒） */
    public static final int POISON_DURATION_SEC = 30;
    /** 毒 tick 间隔（毫秒） */
    public static final int POISON_TICKRATE_MS = 1000;

    // ---------- 距离（XY 平面，与 C++ 注释米数一致） ----------
    /** 152 米 */
    public static final int DISTANCE_XY_152_METERS = 16_777_216;
    /** 107 米 */
    public static final int DISTANCE_XY_107_METERS = 8_388_608;
    /** 76 米 */
    public static final int DISTANCE_XY_76_METERS = 4_194_304;
    /** 60 米 */
    public static final int DISTANCE_XY_60_METERS = 2_621_440;
    /** 54 米 */
    public static final int DISTANCE_XY_54_METERS = 2_097_152;
    /** 47 米（如 iLureDistance） */
    public static final int DISTANCE_XY_47_METERS = 1_638_400;
    /** 41 米 */
    public static final int DISTANCE_XY_41_METERS = 1_228_800;
    /** 38 米 */
    public static final int DISTANCE_XY_38_METERS = 1_048_576;
    /** 33 米 */
    public static final int DISTANCE_XY_33_METERS = 819_200;
    /** 30 米 */
    public static final int DISTANCE_XY_30_METERS = 640_000;
    /** 27 米 */
    public static final int DISTANCE_XY_27_METERS = 524_288;
    /** 24 米 */
    public static final int DISTANCE_XY_24_METERS = 409_600;
    /** 20 米 */
    public static final int DISTANCE_XY_20_METERS = 291_600;
    /** 19 米 */
    public static final int DISTANCE_XY_19_METERS = 262_144;
    /** 15 米 */
    public static final int DISTANCE_XY_15_METERS = 164_025;
    /** 13 米 */
    public static final int DISTANCE_XY_13_METERS = 131_072;
    /** 10 米 */
    public static final int DISTANCE_XY_10_METERS = 65_536;
    /** 7 米 */
    public static final int DISTANCE_XY_7_METERS = 32_768;
    /** 5 米 */
    public static final int DISTANCE_XY_5_METERS = 16_384;
    /** 3 米 */
    public static final int DISTANCE_XY_3_METERS = 8_192;
    /** 约 1.5 米 */
    public static final int DISTANCE_XY_05 = 4_096;
    /** 约 0.7 米 */
    public static final int DISTANCE_XY_025 = 2_048;

    /** 约 11 米 (300 单位) */
    public static final int DISTANCE_300 = 90_000;

    /** 单位视野上限（107 米） */
    public static final int DISTANCE_MAX_UNIT_VIEWLIMIT = DISTANCE_XY_107_METERS;
    /** 单位超出范围（60 米） */
    public static final int DISTANCE_MAX_UNIT_OUTOFRANGE = DISTANCE_XY_60_METERS;
    /** 玩家基础视野（54 米） */
    public static final int DISTANCE_MAX_PLAYER_BASIC_VIEW = DISTANCE_XY_54_METERS;
    /** 单位基础视野（54 米） */
    public static final int DISTANCE_MAX_UNIT_BASIC_VIEW = DISTANCE_XY_54_METERS;
    /** 队伍最大距离（41 米） */
    public static final int DISTANCE_MAX_PARTY = DISTANCE_XY_41_METERS;
    /** 聊天最大距离（41 米） */
    public static final int DISTANCE_MAX_CHATRANGE = DISTANCE_XY_41_METERS;
    /** 玩家详细视野（33 米） */
    public static final int DISTANCE_MAX_PLAYER_DETAILED_VIEW = DISTANCE_XY_33_METERS;
    /** 单位详细视野（33 米） */
    public static final int DISTANCE_MAX_UNIT_DETAILED_VIEW = DISTANCE_XY_33_METERS;
    /** 玩家基础视野 RICT（33 米） */
    public static final int DISTANCE_MAX_PLAYER_BASIC_VIEW_RICT = DISTANCE_XY_33_METERS;
    /** 技能可见距离（24 米） */
    public static final int DISTANCE_MAX_SKILL_VISUAL = DISTANCE_XY_24_METERS;
    /** 宠物范围（24 米） */
    public static final int DISTANCE_MAX_PET_RANGE = DISTANCE_XY_24_METERS;
    /** 玩家详细视野 RICT（24 米） */
    public static final int DISTANCE_MAX_PLAYER_DETAILED_VIEW_RICT = DISTANCE_XY_24_METERS;
    /** BOSS 视野（24 米） */
    public static final int DISTANCE_MAX_BOSS = DISTANCE_XY_24_METERS;
    /** 技能施放距离（24 米） */
    public static final int DISTANCE_MAX_SKILL_RANGE = DISTANCE_XY_24_METERS;
    /** 冰封圣所树（19 米） */
    public static final int DISTANCE_MAX_FROZENSANCTUARY_TREE = DISTANCE_XY_19_METERS;

    /** 杂项距离 */
    public static final int DISTANCE_MISC = 0x1000;
    /** 杂项 Y 方向距离 */
    public static final int DISTANCE_MISC_Y = 300;
    /** 杂项 Y 扩展 */
    public static final int DISTANCE_MISC_Y_EX = 1000;

    // ---------- 组队（docs/组队系统-源码分析.md；数值对齐 EU CPartyHandler / NSPT） ----------
    /** 队伍人数上限（EU MAX_PARTY_MEMBERS / NSPT PARTY_PLAYER_MAX，三源一致） */
    public static final int PARTY_MAX_MEMBERS = 6;
    /** 邀请等级差门槛——i18n 以 {max} 参数下发，文案不写死。
     *  ⚠ EU/NSPT 原值为 10；工作区实测已调到 40（迁移时原样保留，若要回原值改这里） */
    public static final int PARTY_INVITE_LEVEL_DIFF = 40;
    /** 经验/金币分享距离（**我方世界单位**）——NSPT PARTY_GETTING_DIST = 18*64（原版坐标 ÷fONE 后的网格单位，
     *  与我方世界单位同尺度）。⚠ GameConstants 里另有 EU 的 DISTANCE_MAX_PARTY（41 米）是原版 raw
     *  平方坐标系（DISTANCE_XY_* 族），两套单位不同，勿混用 */
    public static final double PARTY_SHARE_DIST = 18 * 64;
    /** EU unitserver.cpp:616 Normal 模式经验总量%：180 + 80×(人数-2)，2 人 180% → 6 人 500%，再 ÷人数 */
    public static final int PARTY_EXP_PERCENT_NORMAL_BASE = 180;
    public static final int PARTY_EXP_PERCENT_NORMAL_PER = 80;
    /** EU unitserver.cpp:618 Hunt 模式：80 + 20×(人数-2)；Hunt 的"每杀 +1 掉落"在掉落侧，尚未挂 */
    public static final int PARTY_EXP_PERCENT_HUNT_BASE = 80;
    public static final int PARTY_EXP_PERCENT_HUNT_PER = 20;
    /** 邀请有效期（毫秒）——EU 客户端弹窗 1400 帧超时同语义，我们以服务端为准 */
    public static final long PARTY_INVITE_TTL_MS = 60_000;

    // ---------- 物品/背包（与 C++ #define 一致） ----------
    /** 对应 C++ #define INVENTORYSERVER_MAX 100 */
    public static final int INVENTORYSERVER_MAX = 100;
    /** 对应 C++ #define MAX_ITEMSINITEMBOX 100 */
    public static final int MAX_ITEMSINITEMBOX = 100;

    // ---------- 等级与数据量 ----------
    /** 服务器最大等级 */
    public static final int SERVER_LEVEL_MAX = 120;
    /** 单位 PlayData 最大数量 */
    public static final int MAX_UNIT_PLAYDATA = 75;
    /** 任务包数据最大条数 */
    public static final int MAX_QUESTPACKETDATA = 15;

    // ---------- 混配/崩溃/地图/伤害统计等 ----------
    /** 混配功能总数 */
    public static final int MIXLIST_FUNCTION_TOTAL = 70;
    /** 崩溃数据块大小 */
    public static final int CRASHDATA_SIZE = 0x1F00;
    /** 地图指示器最大数量 */
    public static final int MAX_MAP_INDICATORS = 30;
    /** 复活节道具最大数量 */
    public static final int EASTER_ITEMS_MAX = 3;
    /** 拼图道具最大数量 */
    public static final int PUZZLE_ITEMS_MAX = 3;
    /** 伤害调试容器长度 */
    public static final int DAMAGEDEBUGCONTAINER_LENGTH = 0x1FF6;
    /** 愤怒竞技场 X 最小值 */
    public static final int XMIN_FURYARENA = -1_050_946;
    /** 愤怒竞技场 X 最大值 */
    public static final int XMAX_FURYARENA = -806_751;
    /** 愤怒竞技场 Z 最小值 */
    public static final int ZMIN_FURYARENA = -11_170_306;
    /** 愤怒竞技场 Z 最大值 */
    public static final int ZMAX_FURYARENA = -10_928_372;
    /** 校验和函数总数 */
    public static final int CHECKSUM_FUNCTION_TOTAL = 400;
    /** 作弊窗口列表总数 */
    public static final int WINDOW_CHEATLIST_TOTAL = 50;
    /** 祝福城堡 Top 伤害数据条数 */
    public static final int MAX_TOP_DAMAGEDATA = 10;
    /** 伤害数据最大条数 */
    public static final int MAX_DAMAGEDATA = 100;
    /** 祝福城堡 clan 皇冠数量 */
    public static final int MAX_BLESSCASTLE_CLANCROWN = 3;
}
