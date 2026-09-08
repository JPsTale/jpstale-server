package org.jpstale.server.game.item;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 物品掷点生成服务（权威逻辑）。
 * <p>
 * 基准：pt-web-server simulator {@code SimulatorService.rollFromDef}（已与前端 JS 双份核对，
 * 掷点仅在 Java 端；REQ_MOD 需求调整前端独有、此处补入；锻造/合成本轮不实现操作逻辑）。
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

    public ItemInstance roll(ItemList def, Integer jobCodeMask) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
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
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextInt(10) > 3) {
            return; // 70% 未命中
        }
        int chosen;
        if (jobCodeMask != null && jobCodeMask != 0) {
            chosen = jobCodeMask;
        } else if (randomJobs.size() == 1) {
            chosen = randomJobs.get(0);
        } else {
            chosen = randomJobs.get(rnd.nextInt(randomJobs.size()));
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
        put(1, new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));
        put(2, new ReqMod(new int[]{15,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));
        put(3, new ReqMod(new int[]{-25,-15}, new int[]{-20,-10}, new int[]{0,0}, new int[]{20,30}, new int[]{0,0}));
        put(4, new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));
        put(5, new ReqMod(new int[]{-20,-15}, new int[]{-20,-10}, new int[]{0,0}, new int[]{20,30}, new int[]{0,0}));
        put(6, new ReqMod(new int[]{15,25}, new int[]{-15,-10}, new int[]{5,10}, new int[]{-20,-15}, new int[]{0,0}));
        put(7, new ReqMod(new int[]{-30,-25}, new int[]{25,30}, new int[]{-15,-10}, new int[]{-20,-15}, new int[]{0,0}));
        put(8, new ReqMod(new int[]{-30,-25}, new int[]{25,30}, new int[]{-15,-10}, new int[]{-20,-15}, new int[]{0,0}));
        put(9, new ReqMod(new int[]{20,25}, new int[]{-20,-10}, new int[]{0,0}, new int[]{-20,-15}, new int[]{0,0}));
        put(10, new ReqMod(new int[]{-30,-25}, new int[]{25,30}, new int[]{-15,-10}, new int[]{-20,-15}, new int[]{0,0}));
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
        return min + ThreadLocalRandom.current().nextInt(sb);
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
        int sb = (int) ((max - min) * 100.0);
        if (sb <= 0) {
            return max;
        }
        int rnd = ThreadLocalRandom.current().nextInt(sb + 1);
        return Math.round((min + rnd / 100.0) * 100.0) / 100.0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 职业掩码 → 职业名（含二转），供日志/显示。 */
    public static List<String> jobNamesOf(long mask) {
        Map<Long, String> jobs = new LinkedHashMap<>();
        jobs.put(0x00000001L, "Mechanician");
        jobs.put(0x00000002L, "Fighter");
        jobs.put(0x00000004L, "Pikeman");
        jobs.put(0x00000008L, "Archer");
        jobs.put(0x00000010L, "Mechanic Master");
        jobs.put(0x00000020L, "Warrior");
        jobs.put(0x00000040L, "Combatant");
        jobs.put(0x00000080L, "Hunter Master");
        jobs.put(0x00000100L, "Metal Leader");
        jobs.put(0x00000200L, "Champion");
        jobs.put(0x00000400L, "Lancer");
        jobs.put(0x00000800L, "Dion's Disciple");
        jobs.put(0x00001000L, "Metallion");
        jobs.put(0x00002000L, "Immortal Warrior");
        jobs.put(0x00004000L, "Lancelot");
        jobs.put(0x00008000L, "Sagittarion");
        jobs.put(0x00010000L, "Knight");
        jobs.put(0x00020000L, "Atalanta");
        jobs.put(0x00040000L, "Priest");
        jobs.put(0x00080000L, "Magician");
        jobs.put(0x00100000L, "Paladin");
        jobs.put(0x00200000L, "Valkyrie");
        jobs.put(0x00400000L, "Saintess");
        jobs.put(0x00800000L, "Wizard");
        jobs.put(0x01000000L, "Holy Knight");
        jobs.put(0x02000000L, "Brunhild");
        jobs.put(0x04000000L, "Bishop");
        jobs.put(0x08000000L, "Royal Wizard");
        jobs.put(0x10000000L, "Saint Knight");
        jobs.put(0x20000000L, "Valhalla");
        jobs.put(0x40000000L, "Celestial");
        jobs.put(0x80000000L, "Arch Mage");
        List<String> names = new ArrayList<>();
        for (Map.Entry<Long, String> e : jobs.entrySet()) {
            if ((mask & e.getKey()) != 0) {
                names.add(e.getValue());
            }
        }
        return names;
    }
}
