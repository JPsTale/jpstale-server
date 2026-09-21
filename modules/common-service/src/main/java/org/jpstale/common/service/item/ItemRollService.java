package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 物品掷点生成服务（**权威逻辑，全仓唯一实现**）。
 * <p>
 * 溯源：由 pt-web-server 模拟器当年那份 `SimulatorService.rollFromDef` 派生，派生时补齐了它缺的东西
 * （REQ_MOD 需求调整、一组 spec 字段、0.1 的小数步长）。
 * ⚠ 那份 web 侧副本**已随 {@code POST /api/simulator/roll} 接口一并删除**（接口待重新设计）：
 * 它后来与这边分叉，症状是"模拟器显示的值与游戏内不一致"。模拟器要重做掷点展示时**直接调本类**，
 * 不要再实现一遍。
 * <p>
 * 掷点产物为一个新的 {@link ItemInstance}，属性 = 模板 [min,max] 区间一次抽样定格；
 * 之后属性不再变化，只由耐久/锻造等状态字段演进。
 */
@Service
public class ItemRollService {

    private final ItemListMapper itemListMapper;
    /** itemlist.id → ItemList（全字段，掷点需要 min/max + addSpec*；启动惰性填充） */
    private final Map<Integer, ItemList> defCache = new ConcurrentHashMap<>();

    public ItemRollService(ItemListMapper itemListMapper) {
        this.itemListMapper = itemListMapper;
    }

    private ItemList defById(int itemListId) {
        return defCache.computeIfAbsent(itemListId, itemListMapper::selectById);
    }

    /** 按 itemlist.id 取模板（含缓存），供装载补 template 用。 */
    public ItemList itemListById(int itemListId) {
        return defById(itemListId);
    }

    private ItemList defByFirstIdCode(int idCode) {
        for (ItemList v : defCache.values()) {
            if (v.getIdCode() != null && v.getIdCode() == idCode && (v.getQuestId() == null || v.getQuestId() == 0)) {
                return v;
            }
        }
        ItemList found = itemListMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ItemList>()
                        .eq(ItemList::getIdCode, idCode)
                        .eq(ItemList::getQuestId, 0)
                        .orderByAsc(ItemList::getId)
                        .last("limit 1"));
        if (found != null) {
            defCache.put(Math.toIntExact(found.getId()), found);
        }
        return found;
    }

    /**
     * 按 itemlist.id（DB 主键）掷点生成一件物品实例。
     *
     * @param itemListId gamedb.itemlist.id
     * @param jobCodeMask 期望职业特效掩码；null=随机（30% 概率）或无职业限定
     * @return 掷点结果；模板不存在返回 null
     */
    public ItemInstance rollById(int itemListId, Integer jobCodeMask) {
        ItemList def = defById(itemListId);
        if (def == null) {
            return null;
        }
        return roll(def, jobCodeMask);
    }

    /**
     * 按 idcode（原版物品码）掷点；取最小 id（对齐 CreateItemMemoryTable 规则）。
     */
    public ItemInstance rollByIdCode(int idCode, Integer jobCodeMask) {
        ItemList def = defByFirstIdCode(idCode);
        if (def == null) {
            return null;
        }
        return roll(def, jobCodeMask);
    }

    /**
     * GM 刷物入口（`/@get`）：按**物品码**取模板并掷点。
     */
    public ItemInstance rollByCode(String itemCode, Integer jobCodeMask) {
        ItemList def = resolveByCode(itemCode);
        return def != null ? roll(def, jobCodeMask) : null;
    }

    /**
     * 物品码 → 模板。**参数只有一种意思：物品码**（`gamedb.itemlist.codeimg1`，忽略大小写，
     * 例 `ec101` / `EC101` / `bi108`）。不做任何兜底、不看参数形态：
     * 数字**不**当主键、也不当 idCode；名称**不**匹配（连 LIKE 都没有）。
     *
     * 为什么（用户 2026-09-13 明确要求）：一个参数"按形态决定含义"就等于模糊 ——
     * `435` 到底是主键还是 idCode？`Ether Core` 会命中哪一行？调用者无法从命令本身断言结果。
     * 宁可这里解析不出来、回一句"找不到物品: <token>"，也不在下面给出一个猜测的物品。
     */
    public ItemList resolveByCode(String itemCode) {
        if (itemCode == null || itemCode.isBlank()) {
            return null;
        }
        return itemListMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ItemList>()
                        .apply("lower(codeimg1) = {0}", itemCode.trim().toLowerCase())
                        .orderByAsc(ItemList::getId)
                        .last("limit 1"));
    }

    public ItemInstance roll(ItemList def, Integer jobCodeMask) {
        ItemInstance it = new ItemInstance();
        it.setItemListId(Math.toIntExact(def.getId()));
        it.setItemCode(def.getIdCode() == null ? 0 : def.getIdCode());
        it.setTemplate(def);

        // 需求基础（Req 之后可能被职业特效百分比修正）
        it.setReqLevel(nz(def.getReqLevel()));
        it.setReqStrength(nz(def.getReqStrengh()));
        it.setReqSpirit(nz(def.getReqSpirit()));
        it.setReqTalent(nz(def.getReqTalent()));
        it.setReqAgility(nz(def.getReqAgility()));
        it.setReqHealth(nz(def.getReqHealth()));

        // 耐久：max 掷点于 [min,max]，current 掷点于 [max/2, max]（CreateDefItem 语义）
        Integer dMin = def.getIntegrityMin();
        Integer dMax = def.getIntegrityMax();
        if (dMin != null && dMin != 0) {
            int max = (dMax != null && dMax != 0) ? rndInt(dMin, dMax) : dMin;
            int cur = rndInt(max / 2, max);
            it.setDurabilityMax((short) max);
            it.setDurability((short) cur);
        }

        // 8 元素抗性（DB 无 water/wind 掷点区间 → 恒 0）
        it.setResBionic((short) resistRoll(def.getOrganicMin(), def.getOrganicMax()));
        it.setResFire((short) resistRoll(def.getFireMin(), def.getFireMax()));
        it.setResIce((short) resistRoll(def.getFrostMin(), def.getFrostMax()));
        it.setResLighting((short) resistRoll(def.getLightningMin(), def.getLightningMax()));
        it.setResPoison((short) resistRoll(def.getPoisonMin(), def.getPoisonMax()));

        // 攻击：小攻击区间=[AtkPow1Min,AtkPow2Min]，大攻击区间=[AtkPow1Max,AtkPow2Max]
        if (nz(def.getAtkPow1Max()) != 0) {
            it.setDamageMin((short) rndInt(nz(def.getAtkPow1Min()), nz(def.getAtkPow2Min())));
            it.setDamageMax((short) rndInt(nz(def.getAtkPow1Max()), nz(def.getAtkPow2Max())));
        }
        it.setAttackRating(rndIntOr(def.getAtkRatingMin(), def.getAtkRatingMax()));
        it.setAbsorb(rndFloat(def.getAbsorbMin(), def.getAbsorbMax()));
        it.setDefence(rndIntOr(def.getDefenseMin(), def.getDefenseMax()));
        it.setBlockRating(rndFloat(def.getBlockMin(), def.getBlockMax()));
        it.setSpeed(rndFloat(def.getRunSpeedMin(), def.getRunSpeedMax()));
        // 必杀 / 射程 / 攻速（武器信息框展示；DB 单值列，随实例持久化）
        if (def.getCritical() != null) {
            it.setCritical(def.getCritical());
        }
        if (def.getRange() != null) {
            it.setShootingRange(def.getRange());
        }
        it.setAttackSpeed(nz(def.getAtkSpeed()));

        // 回复
        it.setManaRegen(rndFloat(def.getRegenerationMpMin(), def.getRegenerationMpMax()));
        it.setLifeRegen(rndFloat(def.getRegenerationHpMin(), def.getRegenerationHpMax()));
        it.setStaminaRegen(rndFloat(def.getRegenerationStmMin(), def.getRegenerationStmMax()));

        // 增加上限
        it.setIncreaseLife((double) rndIntOr(def.getAddHpMin(), def.getAddHpMax()));
        it.setIncreaseMana((double) rndIntOr(def.getAddMpMin(), def.getAddMpMax()));
        it.setIncreaseStamina((double) rndIntOr(def.getAddStmMin(), def.getAddStmMax()));

        // 价格（含职业特效命中 +20%，见下）
        int basePrice = nz(def.getPrice());
        it.setPrice(basePrice);

        // 职业特效：物品无可选职业 → 无
        List<Integer> jobBits = specJobBits(def);
        if (!jobBits.isEmpty()) {
            applyJobEffects(def, it, jobCodeMask, jobBits, basePrice);
        }

        return it;
    }

    /**
     * 职业特效：30% 概率命中；命中则 price +20%，并对属性需求做职业百分比修正
     * （REQ_MOD，引用 EU saItemRequeriments / CheckAndAdjustItemRequirements）。
     */
    private void applyJobEffects(ItemList def, ItemInstance it, Integer jobCodeMask,
                                 List<Integer> randomJobs, int basePrice) {
        if (nextInt(10) > 3) {
            return; // 70% 未命中
        }
        int chosen;
        if (jobCodeMask != null && jobCodeMask != 0) {
            chosen = jobCodeMask;
        } else if (randomJobs.size() == 1) {
            chosen = randomJobs.get(0);
        } else {
            chosen = nextInt(randomJobs.size());
        }
        it.setJobCodeMask(chosen);

        // 价格 +20%（原版 ItemCreateLog：命中职业特效价 +20%）
        it.setPrice(basePrice + (basePrice * 2) / 10);

        // 需求百分比修正（REQ_MOD → 掷一个最终具体值）
        ReqMod mod = REQ_MOD[chosen];
        if (mod != null) {
            it.setReqStrength(reqAdjust(it.getReqStrength(), mod.str));
            it.setReqSpirit(reqAdjust(it.getReqSpirit(), mod.spr));
            it.setReqTalent(reqAdjust(it.getReqTalent(), mod.tal));
            it.setReqAgility(reqAdjust(it.getReqAgility(), mod.agi));
            it.setReqHealth(reqAdjust(it.getReqHealth(), mod.hp));
        }

        // 职业特效增益掷点（spec_*，区间在模板 add_spec_*）
        it.setSpecAbsorb(rndFloat(def.getAddSpecAbsorbMin(), def.getAddSpecAbsorbMax()));
        it.setSpecDefence(rndIntOr(def.getAddSpecDefenseMin(), def.getAddSpecDefenseMax()));
        it.setSpecSpeed(rndFloat(def.getAddSpecRunSpeedMin(), def.getAddSpecRunSpeedMax()));
        it.setSpecPerManaRegen(rndFloat(def.getAddSpecMpRegenMin(), def.getAddSpecMpRegenMax()));
        it.setSpecLevAttackRating(rndIntOr(def.getAddSpecAtkRatingMin(), def.getAddSpecAtkRatingMax()));
        it.setSpecLevDamageMax(rndIntOr(def.getAddSpecAtkPowerMin(), def.getAddSpecAtkPowerMax()));
        // 模板单值特效（非随机）
        it.setSpecAttackSpeed(nz(def.getAddSpecAtkSpeed()));
        it.setSpecCritical(nz(def.getAddSpecCritical()));
        it.setSpecShootingRange(nz(def.getAddSpecRange()));
        it.setSpecBlockRating(def.getAddSpecBlock() == null ? 0 : def.getAddSpecBlock());
        it.setSpecPerLifeRegen(def.getAddSpecHpRegen() == null ? 0 : def.getAddSpecHpRegen());
        it.setSpecPerStaminaRegen(def.getAddSpecStmRegen() == null ? 0 : def.getAddSpecStmRegen());
    }

    /** 该模板声明了哪些可选职业（add_spec_class1..12 非 0 → 对应位）。 */
    private List<Integer> specJobBits(ItemList def) {
        List<Integer> bits = new ArrayList<>();
        Integer[] classes = {
                def.getAddSpecClass1(), def.getAddSpecClass2(), def.getAddSpecClass3(),
                def.getAddSpecClass4(), def.getAddSpecClass5(), def.getAddSpecClass6(),
                def.getAddSpecClass7(), def.getAddSpecClass8(), def.getAddSpecClass9(),
                def.getAddSpecClass10(), def.getAddSpecClass11(), def.getAddSpecClass12()
        };
        long[] jobBits = {
                0x00000001L, 0x00000002L, 0x00000004L, 0x00000008L,
                0x00000010L, 0x00000020L, 0x00000040L, 0x00000080L,
                0x00000100L, 0x00000200L, 0x00000400L, 0x00000800L
        };
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] != null && classes[i] != 0) {
                bits.add((int) jobBits[i]);
            }
        }
        return bits;
    }

    // ------------------------------------------------------------------
    // REQ_MOD：职业特效命中时的属性需求百分比修正（来自前端 JS，EU saItemRequeriments）
    // 索引：职业位掩码（1..10 对应前 10 个职业 bit），0=不适用
    // ------------------------------------------------------------------
    private static final class ReqMod {
        final int[] str, spr, tal, agi, hp;
        ReqMod(int[] str, int[] spr, int[] tal, int[] agi, int[] hp) {
            this.str = str; this.spr = spr; this.tal = tal; this.agi = agi; this.hp = hp;
        }
    }

    private static final ReqMod[] REQ_MOD = new ReqMod[2048];

    static {
        // EPT GetItemRequirementAdjustmentForClass 顺序：
        // Fighter,Mechanician,Archer,Pikeman,Atalanta,Knight,Magician,Priestess,Assassin,Shaman
        // 索引 = 基础职业位 bit = 1<<(ECharacterClass-1)
        put(1,    new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));   // 1 Fighter 武士
        put(2,    new ReqMod(new int[]{15,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));   // 2 Mechanician 机械兵
        put(4,    new ReqMod(new int[]{-25,-15}, new int[]{-20,-10}, new int[]{0,0}, new int[]{20,30}, new int[]{0,0}));   // 3 Archer 弓箭手
        put(8,    new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));   // 4 Pikeman 枪兵
        put(16,   new ReqMod(new int[]{-20,-15}, new int[]{-20,-10}, new int[]{0,0}, new int[]{20,30}, new int[]{0,0}));   // 5 Atalanta 魔枪兵
        put(32,   new ReqMod(new int[]{15,25}, new int[]{-15,-10}, new int[]{5,10}, new int[]{-20,-15}, new int[]{0,0}));  // 6 Knight 游侠
        put(64,   new ReqMod(new int[]{-30,-25}, new int[]{25,30}, new int[]{-15,-10}, new int[]{-20,-15}, new int[]{0,0}));// 7 Magician 魔法师
        put(128,  new ReqMod(new int[]{-30,-25}, new int[]{25,30}, new int[]{-15,-10}, new int[]{-20,-15}, new int[]{0,0}));// 8 Priestess 祭司
        put(256,  new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));   // 9 Assassin 刺客
        put(512,  new ReqMod(new int[]{-30,-25}, new int[]{25,30}, new int[]{-15,-10}, new int[]{-20,-15}, new int[]{0,0}));// 10 Shaman 萨满
        put(1024, new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));   // 11 MartialArtist 格斗家
    }

    private static void put(int bit, ReqMod m) {
        REQ_MOD[bit] = m;
    }

    private static int reqAdjust(int base, int[] pct) {
        if (base == 0) {
            return 0;
        }
        int lo = base + (int) Math.floor(base * pct[0] / 100.0);
        int hi = base + (int) Math.floor(base * pct[1] / 100.0);
        return lo == hi ? lo : rndInt(lo, hi);
    }

    // ------------------------------------------------------------------
    // 随机工具（与 SimulatorService 一致：min..max 闭区间）
    // ------------------------------------------------------------------

    /**
     * 随机源覆盖，**仅供测试注入固定种子**；null = 正常行为（{@link ThreadLocalRandom}）。
     *
     * 必须是静态字段：从 {@link #applyJobEffects} 到 {@link #reqAdjust}、{@link #rndInt} 的
     * 调用链全是 static，没有实例可挂。静态覆盖是**全 JVM** 的，测试用完必须在 `@AfterEach`
     * 复位为 null，否则会污染同一 JVM 里的其它用例。
     */
    private static volatile Random rngOverride;

    static void setRngOverride(Random rng) {
        rngOverride = rng;
    }

    /** 掷点随机数唯一入口：有覆盖走覆盖，否则走 {@link ThreadLocalRandom}。 */
    private static int nextInt(int bound) {
        Random rng = rngOverride;
        return rng == null ? ThreadLocalRandom.current().nextInt(bound) : rng.nextInt(bound);
    }

    static int rndInt(int min, int max) {
        if (max < min) {
            int t = min;
            min = max;
            max = t;
        }
        int sb = (max + 1) - min;
        if (sb <= 0) {
            return max;
        }
        return min + nextInt(sb);
    }

    static int rndIntOr(Integer min, Integer max) {
        if (min == null && max == null) {
            return 0;
        }
        if (min == null) {
            min = 0;
        }
        if (max == null || max == 0) {
            return min;
        }
        return rndInt(min, max);
    }

    static int resistRoll(Integer min, Integer max) {
        return rndIntOr(min, max);
    }

    /**
     * 掷点浮点属性。装备属性精度为 0.1（客户端显示 /10 一位小数），
     * 故按 0.1 步进掷点，而非 0.01（曾产生 1.17 这类两位小数，超出装备精度）。
     */
    static double rndFloat(Double min, Double max) {
        if (min == null && max == null) {
            return 0.0;
        }
        if (min == null) {
            min = 0.0;
        }
        if (max == null || max == 0.0) {
            return min;
        }
        int sb = (int) Math.round((max - min) * 10.0);
        if (sb <= 0) {
            return Math.round(max * 10.0) / 10.0;
        }
        int rnd = nextInt(sb + 1);
        return Math.round((min + rnd / 10.0) * 10.0) / 10.0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 职业掩码 → 基础职业名（bit = 1<<(ECharacterClass-1)），供日志/显示。 */
    public static List<String> jobNamesOf(long mask) {
        Map<Long, String> jobs = new LinkedHashMap<>();
        jobs.put(0x00000001L, "Fighter");       // 1
        jobs.put(0x00000002L, "Mechanician");   // 2
        jobs.put(0x00000004L, "Archer");        // 3
        jobs.put(0x00000008L, "Pikeman");       // 4
        jobs.put(0x00000010L, "Atalanta");      // 5
        jobs.put(0x00000020L, "Knight");        // 6
        jobs.put(0x00000040L, "Magician");      // 7
        jobs.put(0x00000080L, "Priestess");     // 8
        jobs.put(0x00000100L, "Assassin");      // 9
        jobs.put(0x00000200L, "Shaman");        // 10
        jobs.put(0x00000400L, "MartialArtist"); // 11
        List<String> names = new ArrayList<>();
        for (Map.Entry<Long, String> e : jobs.entrySet()) {
            if ((mask & e.getKey()) != 0) {
                names.add(e.getValue());
            }
        }
        return names;
    }
}
