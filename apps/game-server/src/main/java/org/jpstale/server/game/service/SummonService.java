package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.CrystalService;
import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.MonsterList;
import org.jpstale.server.game.entity.EntityRegistry;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.model.Monster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 怪物水晶的**召唤**：落点挑选 + 建体 + 归属 + 寿命。
 *
 * <p>
 * 分工（一件一岗，别在这里塞进第二份实现）：
 * <ul>
 *   <li>{@link CrystalService} —— 「哪颗水晶召哪只怪」（代码里的分派表）；</li>
 *   <li>{@code MonsterSpawnService.applyTemplate/createSummon} —— 「按模板造一只怪」；</li>
 *   <li>本类 —— 落点、主人的归属字段、寿命；</li>
 *   <li>{@code AiEngine.updateSummon} / {@code MonsterSpawnService.summonShouldDie}
 *       —— 出生之后的牵引、战斗与收场。</li>
 * </ul>
 *
 * <p>
 * 数值全部来自原版 `OnSever.cpp:3726-3796`（落点、寿命、按主人等级加血），
 * 见 {@code docs/召唤物系统-源码分析.md} §3.4/§3.5。
 * 单位换算：原版用 8.8 定点（`FLOATNS = 8`，1 格 = 256 world），我们的坐标已经是"格"
 * （协议注释"world 坐标（服务端已 /256）"、`AIConstants.ATTACK_HEIGHT_DIFF = 64`
 * 对应原版 `64 * fONE`）⇒ 原版的格值拿来直接用。
 */
@Slf4j
@Component
public class SummonService {

    /** 落点散布半径 = 原版 `ITEM_SET_DIST(24) << (FLOATNS+1)` = 12288 world ÷ 256 = **48 格**。 */
    static final double LANDING_RADIUS = 48.0;

    /** 落点高度容差 = 原版 `32 * fONE` ÷ 256 = **32 格**（落差超过它就换一个方向）。 */
    static final double LANDING_HEIGHT_TOLERANCE = 32.0;

    /** 原版 `ptItemSettingPosi[8]`（`OnSever.cpp:789-798`）的八个方向，归一化成单位向量。 */
    private static final int[][] DIRECTIONS = {
        {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
    };

    /** 原版寿命：4 分钟 + 主人每级 2 秒（`OnSever.cpp:3785/3791`）。 */
    static final long SUMMON_BASE_LIFE_MS = 4 * 60 * 1000L;

    static final long SUMMON_LIFE_PER_OWNER_LEVEL_MS = 2000L;

    /** 原版：召唤物的**最大血** += 主人等级 × 3，并以满血出场（`OnSever.cpp:3792-3795`）。 */
    static final int SUMMON_HP_PER_OWNER_LEVEL = 3;

    /** 原版对等级加成的门槛：`Level > 0 && Level < 100`。 */
    static final int SUMMON_LEVEL_BONUS_MAX_LEVEL = 100;

    /** 召唤体模板名的后缀 —— 显示名要把去掉（`Hopy_Crystal` → `Hopy`）。 */
    private static final String TEMPLATE_SUFFIX = "_Crystal";

    @Autowired
    private MonsterSpawnService monsterSpawnService;

    @Autowired
    private MapRegionService mapRegionService;

    @Autowired
    private EntityRegistry entityRegistry;

    /** 「该处的地面高度」，**没有地面返回 null**（原版 `GetFloorHeight == CLIP_OUT`）。 */
    @FunctionalInterface
    public interface HeightLookup {
        Double heightAt(double x, double z);
    }

    /**
     * 启动自检：分派表里每一个召唤体 id 都必须能在 `monsterlist` 里找到。
     *
     * <p>
     * **为什么必须有**：原版是靠怪物**名字**字符串回填模板指针的，名字对不上就
     * `return FALSE` → 水晶被静默消耗、什么也不出来（源码分析 §9 的 D5/D6）。我们改成按
     * `monsterid` 绑定，但这个"数据缺了"的失败模式还在 —— 只是从"每次使用静默失败"
     * 变成"启动时喊一声"。**不静默**是刻意的（AGENTS #12）。
     */
    @PostConstruct
    public void validateCrystalTable() {
        int ok = 0;
        for (int idCode : CrystalService.supportedCodes()) {
            CrystalService.CrystalDef def = CrystalService.defOf(idCode);
            for (CrystalService.Roll roll : def.pool()) {
                MonsterList template = monsterSpawnService.findTemplateByMonsterId(roll.monsterId());
                if (template == null) {
                    log.error("[Summon] 水晶 0x{} 的召唤体 monsterid={} 在 monsterlist 里不存在"
                            + " → 这颗水晶用了也不会出东西（依据：{}）",
                        Integer.toHexString(idCode), roll.monsterId(), def.note());
                } else {
                    ok++;
                }
            }
        }
        log.info("[Summon] 水晶分派表自检：{} 项召唤体全部可解析（{} 颗水晶）",
            ok, CrystalService.supportedCodes().size());
    }

    /**
     * 这次召唤会出哪只怪 —— 给调用方在**扣物品之前**确认用。
     *
     * <p>
     * 模板缺失时返回 {@code null} 并打 error（含缺的是哪个 `monsterid`、以及这条映射的依据），
     * 由调用方回一条可见错误且**不扣物品**。原版在这里是 `lpCharInfo == NULL → return FALSE`，
     * 水晶照扣、什么也不出来（源码分析 §9 的 D5/D6）—— 我们不复刻那个静默。
     *
     * @param roll0to99 掷点（神秘水晶按权重池抽；单怪水晶的池里只有一项，掷点无关）
     */
    public MonsterList resolveSummon(CrystalService.CrystalDef def, int roll0to99) {
        int monsterId = CrystalService.rollSummon(def, roll0to99);
        if (monsterId <= 0) {
            log.error("[Summon] 水晶 0x{} 的权重池掷不出任何召唤体（roll={}）→ 拒绝，不消耗物品。依据：{}",
                Integer.toHexString(def.crystalIdCode()), roll0to99, def.note());
            return null;
        }
        MonsterList template = monsterSpawnService.findTemplateByMonsterId(monsterId);
        if (template == null) {
            log.error("[Summon] 水晶 0x{} 掷出 monsterid={}（roll={}），但 monsterlist 里没有这一行"
                    + " → 拒绝，不消耗物品。依据：{}",
                Integer.toHexString(def.crystalIdCode()), monsterId, roll0to99, def.note());
        }
        return template;
    }

    /**
     * 召唤一只，并把它登记进实体表。返回 {@code null} = 模板查不到（调用方**不要**扣物品，
     * 并回一条可见的错误）。
     *
     * @param crystalIdCode 只用于日志（"谁用哪颗水晶召了什么"是排查的第一手信息）
     */
    public Monster spawn(int crystalIdCode, MonsterList template, PlayerEntity owner, Player ownerPlayer) {
        if (template == null) {
            log.error("[Summon] 水晶 0x{} 没有可用的召唤体模板 → 不召唤（调用方不应扣物品）",
                Integer.toHexString(crystalIdCode));
            return null;
        }
        int mapId = owner.getMapId();
        int[] dirOrder = new int[DIRECTIONS.length];
        for (int i = 0; i < dirOrder.length; i++) {
            dirOrder[i] = ThreadLocalRandom.current().nextInt(DIRECTIONS.length);
        }
        double[] landing = pickLanding(owner.getX(), owner.getY(), owner.getZ(), dirOrder,
            (x, z) -> mapRegionService.getFloorHeightOrNull(mapId, x, z));

        Monster summon = monsterSpawnService.createSummon(template, mapId, landing[0], landing[1]);

        // —— 归属（原版的三件套 + 我们显式化的字段，见 Monster 的注释）——
        summon.setOwnerCharId(owner.getCharId());
        summon.setOwnerEntityId(owner.getId());
        summon.setOwnerName(displayNameOf(owner, ownerPlayer));

        // 显示名：模板名去掉 `_Crystal` 后缀。实测 12 只召唤体的模板名去掉后缀后**恰好等于**
        // 基础怪的名字（Hopy_Crystal→Hopy、Head Cutter_Crystal→Head Cutter …），与"原版显示
        // 那只怪自己的名字"一致（见源码分析 §4.1 的对照表）。
        summon.setName(displayNameOfTemplate(template.getName()));

        // —— 寿命与按主人等级加血（原版 `OnSever.cpp:3785-3795`）——
        int level = ownerPlayer != null ? ownerPlayer.getLevel() : 0;
        long lifeMs = lifeMsOf(level);
        summon.setSummonLifeTotalMs(lifeMs);                                  // 总量：客户端画倒计时条要按比例
        summon.setSummonExpireMs(System.currentTimeMillis() + lifeMs);
        if (level > 0 && level < SUMMON_LEVEL_BONUS_MAX_LEVEL) {
            summon.setMaxHp(summon.getMaxHp() + level * SUMMON_HP_PER_OWNER_LEVEL);
            summon.setHp(summon.getMaxHp());
        }

        entityRegistry.register(summon);
        // 不用手工发 Appear：`MonsterAOI.syncSessions` 下一次（≤50ms）就会按距离把它推给观察者 ——
        // 与刷怪、NPC 完全相同的一条链（`MonsterSpawnService` 也只 register，不自己发）。
        log.info("[Summon] {}#{}（{}，模板 {}#{}, 血 {}）由 {} 用 0x{} 召出于图 {} ({},{})，"
                + "寿命 {}ms，距主人 {} 格",
            summon.getName(), summon.getId(), summon.getModelFile(),
            template.getMonsterId(), template.getName(), summon.getMaxHp(),
            summon.getOwnerName(), Integer.toHexString(crystalIdCode), mapId,
            (int) summon.getX(), (int) summon.getZ(), lifeMsOf(level),
            (int) Math.hypot(summon.getX() - owner.getX(), summon.getZ() - owner.getZ()));
        return summon;
    }

    /** 寿命 = 4 分钟 + 等级 × 2 秒（原版 `dwUpdateCharInfoTime` 的算法）。 */
    static long lifeMsOf(int ownerLevel) {
        return SUMMON_BASE_LIFE_MS + Math.max(0, ownerLevel) * SUMMON_LIFE_PER_OWNER_LEVEL_MS;
    }

    /** 模板名 → 显示名（去掉 `_Crystal` 后缀；没有后缀就原样）。 */
    static String displayNameOfTemplate(String templateName) {
        if (templateName == null) {
            return "";
        }
        String s = templateName.trim();
        return s.endsWith(TEMPLATE_SUFFIX)
            ? s.substring(0, s.length() - TEMPLATE_SUFFIX.length())
            : s;
    }

    private static String displayNameOf(PlayerEntity owner, Player ownerPlayer) {
        String name = ownerPlayer != null ? ownerPlayer.getName() : null;
        return name != null ? name : owner.getName();
    }

    /**
     * 落点挑选 —— 原版 `OnSever.cpp:3726-3741` 的**纯函数**形式（表驱动单测的断言面）。
     *
     * <p>
     * 语义：在给定方向序列上逐个试，取**第一个**"脚下有地面、且与玩家脚下落差 &lt; 32 格"的点；
     * 一个都不成立就用玩家自己的坐标（原版 `if (cnt < 8) {...}`，否则保持原值）。
     *
     * @param dirOrder 方向序列（原版每次 `rand() % 8`，调用方给 8 个随机方向；单测传固定序列）
     * @return {@code {x, z}} —— **Y 不在这里给**：怪物落地的 Y 由
     *         {@code MonsterSpawnService.createSummon} 用 `getHeight` 统一取，
     *         避免"两处都能决定怪的 Y"
     */
    static double[] pickLanding(double x, double y, double z, int[] dirOrder, HeightLookup heightAt) {
        for (int raw : dirOrder) {
            int d = raw & 7;
            double dx = x + DIRECTIONS[d][0] * LANDING_RADIUS;
            double dz = z + DIRECTIONS[d][1] * LANDING_RADIUS;
            Double h = heightAt.heightAt(dx, dz);
            if (h == null) {
                continue;   // 原版 `CLIP_OUT`
            }
            if (Math.abs(h - y) < LANDING_HEIGHT_TOLERANCE) {
                return new double[]{dx, dz};
            }
        }
        return new double[]{x, z};   // 8 次都不行 → 玩家脚下（原版同）
    }
}
