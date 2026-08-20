package org.jpstale.server.web.simulator;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.entity.MixList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.dao.gamedb.mapper.MixListMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 装备模拟器服务（wartale 风格查看器）。
 * <p>
 * 数据源：gamedb.itemlist（EU 风格 min/max 掷点区间）。
 * 详情展示模板原始范围；Spec/Mix/Age 下拉切换展示对应属性。
 */
@Service
public class SimulatorService {

    private final ItemListMapper itemListMapper;
    private final MixListMapper mixListMapper;

    public SimulatorService(ItemListMapper itemListMapper, MixListMapper mixListMapper) {
        this.itemListMapper = itemListMapper;
        this.mixListMapper = mixListMapper;
    }

    // ------------------------------------------------------------------
    // 分类树（wartale：Weapons / Defenses / Accessories）
    // ------------------------------------------------------------------

    /**
     * wartale 三级分类树：顶层 → 子类列表（含每类物品数）。
     */
    public Map<String, Object> categories() {
        List<ItemList> all = itemListMapper.selectList(null);
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        Map<String, List<String>> tree = ItemCategory.tree();
        tree.forEach((type, subtypes) -> {
            List<Map<String, Object>> subs = new ArrayList<>();
            for (String subtype : subtypes) {
                long count = all.stream()
                        .filter(it -> ItemCategory.of(it.getIdCode()) != null)
                        .filter(it -> subtype.equals(ItemCategory.of(it.getIdCode()).getSubtype()))
                        .count();
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", subtype);
                s.put("count", count);
                subs.add(s);
            }
            result.put(type, subs);
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tree", result);
        return r;
    }

    /**
     * 分类分页列表（按 wartale 子类过滤）。
     *
     * @param subtype 子类名（null 表示该顶层全部；需配合 type）
     * @param type    顶层名（Weapons/Defenses/Accessories）
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     */
    public Map<String, Object> list(String type, String subtype, int page, int size) {
        List<ItemList> all = itemListMapper.selectList(null);

        List<ItemSummary> filtered = all.stream()
                .filter(it -> ItemCategory.of(it.getIdCode()) != null)
                .filter(it -> type == null || type.isEmpty()
                        || type.equals(ItemCategory.of(it.getIdCode()).getType()))
                .filter(it -> subtype == null || subtype.isEmpty()
                        || subtype.equals(ItemCategory.of(it.getIdCode()).getSubtype()))
                .map(this::toSummary)
                .collect(Collectors.toList());

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<ItemSummary> pageItems = filtered.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", size <= 0 ? 0 : (total + size - 1) / size);
        result.put("items", pageItems);
        return result;
    }

    /**
     * 物品详情。
     */
    public ItemDetail detail(int id) {
        ItemList entity = itemListMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        return toDetail(entity);
    }

    /**
     * 根据 idcode 取详情。
     */
    public ItemDetail detailByCode(int idCode) {
        ItemList entity = itemListMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ItemList>()
                        .eq(ItemList::getIdCode, idCode).last("limit 1"));
        if (entity == null) {
            return null;
        }
        return toDetail(entity);
    }

    // ------------------------------------------------------------------
    // 随机骰（CreateDefItem 规则）
    // ------------------------------------------------------------------

    /**
     * 按 CreateDefItem 规则掷点生成装备实例。
     */
    public ItemInstance roll(int idCode, Long jobCodeMask) {
        ItemList def = itemListMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ItemList>()
                        .eq(ItemList::getIdCode, idCode).last("limit 1"));
        if (def == null) {
            return null;
        }
        return rollFromDef(def, jobCodeMask);
    }

    private ItemInstance rollFromDef(ItemList def, Long jobCodeMask) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        ItemInstance it = new ItemInstance();

        it.setIdCode(def.getIdCode());
        it.setName(def.getName());
        ItemCategory.Category cat = ItemCategory.of(def.getIdCode());
        if (cat != null) {
            it.setCategory(cat.getName());
            it.setGroup(cat.getGroup());
        }

        // 基础值（来自模板，非掷点）
        it.setWeight(def.getWeight());
        it.setPrice(def.getPrice());
        it.setAttackSpeed(def.getAtkSpeed());
        it.setRange(def.getRange());
        it.setCriticalHit(def.getCritical());
        it.setShootingRange(def.getRange());
        it.setPotionSpace(def.getPotionSpace());

        // 需求
        it.setLevel(def.getReqLevel());
        it.setStrength(def.getReqStrengh());
        it.setSpirit(def.getReqSpirit());
        it.setTalent(def.getReqTalent());
        it.setAgility(def.getReqAgility());
        it.setHealth(def.getReqHealth());

        // 耐久：max 掷点于 [min,max]，current 掷点于 [max/2, max]
        Integer dMin = def.getIntegrityMin();
        Integer dMax = def.getIntegrityMax();
        if (dMin != null && dMin != 0) {
            int max = (dMax != null && dMax != 0) ? rndInt(dMin, dMax) : dMin;
            int min = rndInt(max / 2, max);
            it.setDurabilityCurrent(min);
            it.setDurabilityMax(max);
        }

        // 抗性（5 种）
        it.setOrganicResistance(resistRoll(def.getOrganicMin(), def.getOrganicMax()));
        it.setFireResistance(resistRoll(def.getFireMin(), def.getFireMax()));
        it.setFrostResistance(resistRoll(def.getFrostMin(), def.getFrostMax()));
        it.setLightningResistance(resistRoll(def.getLightningMin(), def.getLightningMax()));
        it.setPoisonResistance(resistRoll(def.getPoisonMin(), def.getPoisonMax()));

        // 攻击（EU DB 列语义，用户确认）：
        // 小攻击区间 = [ATKPow1Min, ATKPow2Min]，大攻击区间 = [ATKPow1Max, ATKPow2Max]
        if (def.getAtkPow1Max() != null && def.getAtkPow1Max() != 0) {
            int p1min = def.getAtkPow1Min() == null ? 0 : def.getAtkPow1Min();
            int p1max = def.getAtkPow1Max() == null ? 0 : def.getAtkPow1Max();
            int p2min = def.getAtkPow2Min() == null ? 0 : def.getAtkPow2Min();
            int p2max = def.getAtkPow2Max() == null ? 0 : def.getAtkPow2Max();
            int dmgMin = rndInt(p1min, p2min);
            int dmgMax = rndInt(p1max, p2max);
            it.setDamageMin(dmgMin);
            it.setDamageMax(dmgMax);
        }
        it.setAttackRating(rndIntOr(def.getAtkRatingMin(), def.getAtkRatingMax()));
        it.setAbsorb(rndFloat(def.getAbsorbMin(), def.getAbsorbMax()));
        it.setDefence(rndIntOr(def.getDefenseMin(), def.getDefenseMax()));
        it.setBlockRating(rndFloat(def.getBlockMin(), def.getBlockMax()));
        it.setSpeed(rndFloat2(def.getRunSpeedMin(), def.getRunSpeedMax()));

        // 回复
        it.setManaRegen(rndFloat2(def.getRegenerationMpMin(), def.getRegenerationMpMax()));
        it.setLifeRegen(rndFloat2(def.getRegenerationHpMin(), def.getRegenerationHpMax()));
        it.setStaminaRegen(rndFloat2(def.getRegenerationStmMin(), def.getRegenerationStmMax()));

        // 增加上限
        it.setIncreaseLife(floatOfIntRoll(def.getAddHpMin(), def.getAddHpMax()));
        it.setIncreaseMana(floatOfIntRoll(def.getAddMpMin(), def.getAddMpMax()));
        it.setIncreaseStamina(floatOfIntRoll(def.getAddStmMin(), def.getAddStmMax()));

        // 职业特效：30% 概率
        applyJobEffects(def, it, jobCodeMask);

        // 锻造初始状态
        it.setAgingLevel(0);
        it.setAgingExp(0);
        it.setAgingExpMax(0);

        return it;
    }

    private void applyJobEffects(ItemList def, ItemInstance it, Long jobCodeMask) {
        List<Long> randomJobs = new ArrayList<>();
        Integer[] specClasses = {
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
        for (int i = 0; i < specClasses.length; i++) {
            if (specClasses[i] != null && specClasses[i] != 0) {
                randomJobs.add(jobBits[i]);
            }
        }
        if (randomJobs.isEmpty()) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int chance = rnd.nextInt(10);
        if (chance > 3) {
            return;
        }

        Long chosen = null;
        if (jobCodeMask != null && jobCodeMask != 0) {
            chosen = jobCodeMask;
        } else if (randomJobs.size() == 1) {
            chosen = randomJobs.get(0);
        } else {
            chosen = randomJobs.get(rnd.nextInt(randomJobs.size()));
        }

        it.setJobCodeMask(chosen);
        it.setJobNames(jobNamesOf(chosen));

        it.setSpecAbsorb(rndFloat(def.getAddSpecAbsorbMin(), def.getAddSpecAbsorbMax()));
        it.setSpecDefence(rndIntOr(def.getAddSpecDefenseMin(), def.getAddSpecDefenseMax()));
        it.setSpecSpeed(rndFloat(def.getAddSpecRunSpeedMin(), def.getAddSpecRunSpeedMax()));
        it.setSpecMagicMastery(null);
        it.setSpecManaRegen(rndFloat2(def.getAddSpecMpRegenMin(), def.getAddSpecMpRegenMax()));
        it.setSpecLevAttackRating(rndIntOr(def.getAddSpecAtkRatingMin(), def.getAddSpecAtkRatingMax()));

        // 职业加成：价格 +20%
        it.setPrice((int) (it.getPrice() + (it.getPrice() * 2L) / 10L));
    }

    /**
     * 职业位掩码 → 职业名列表。
     */
    public static List<String> jobNamesOf(Long mask) {
        List<String> names = new ArrayList<>();
        if (mask == null || mask == 0) {
            return names;
        }
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
        for (Map.Entry<Long, String> e : jobs.entrySet()) {
            if ((mask & e.getKey()) != 0) {
                names.add(e.getValue());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Mix 配方
    // ------------------------------------------------------------------

    /**
     * 物品的 Mix 配方类型名（mixlist.typemixname）。
     * wartale 分类 → mixlist typemixname 映射。
     */
    public String mixTypeNameOf(String type, String subtype) {
        if (subtype == null) {
            return null;
        }
        switch (subtype) {
            case "Axes":
            case "Bows":
            case "Claws":
            case "Daggers":
            case "Hammers":
            case "Javelins":
            case "Phantoms":
            case "Scythes":
            case "Swords":
            case "Wands & Staffs":
                return "Weapons";
            case "Armors":
            case "Robes":
                return "ArmourRobe";
            case "Shields":
                return "Sheilds";
            case "Orbs":
                return "Orbs";
            case "Bracelets":
                return "Bracelets";
            case "Gauntlets":
                return "Gauntlets";
            case "Boots":
                return "Boots";
            default:
                return null;
        }
    }

    /**
     * 某物品的可用 Mix 配方列表（含效果字段）。
     */
    public List<Map<String, Object>> mixes(String type, String subtype) {
        String mixTypeName = mixTypeNameOf(type, subtype);
        if (mixTypeName == null) {
            return new ArrayList<>();
        }
        return mixListMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MixList>()
                                .eq(MixList::getTypeMixName, mixTypeName)
                                .orderByAsc(MixList::getMixUniqueId))
                .stream()
                .map(this::toMixRecipe)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toMixRecipe(MixList m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getMixUniqueId());
        r.put("description", m.getDescription());
        // 效果：typeAtributte 为 MIXATTRTYPE 码（shared/item.h EMixAttributeType）
        List<Map<String, Object>> effects = new ArrayList<>();
        addMixEffect(effects, m.getTypeAtributte(), m.getAtributte(), m.getPerAtributte());
        addMixEffect(effects, m.getTypeAtributte2(), m.getAtributte2(), m.getPerAtributte2());
        addMixEffect(effects, m.getTypeAtributte3(), m.getAtributte3(), m.getPerAtributte3());
        addMixEffect(effects, m.getTypeAtributte4(), m.getAtributte4(), m.getPerAtributte4());
        addMixEffect(effects, m.getTypeAtributte5(), m.getAtributte5(), m.getPerAtributte5());
        addMixEffect(effects, m.getTypeAtributte6(), m.getAtributte6(), m.getPerAtributte6());
        addMixEffect(effects, m.getTypeAtributte7(), m.getAtributte7(), m.getPerAtributte7());
        addMixEffect(effects, m.getTypeAtributte8(), m.getAtributte8(), m.getPerAtributte8());
        r.put("effects", effects);
        return r;
    }

    private void addMixEffect(List<Map<String, Object>> effects, Integer code, Double value, Integer percent) {
        if (code == null || code == 0 || value == null || value == 0) {
            return;
        }
        String name = mixAttrName(code);
        if (name == null) {
            return;
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("attr", name);
        e.put("value", value);
        e.put("percent", percent != null && percent == 0); // perAtributte=0 表示百分比
        effects.add(e);
    }

    /**
     * MIXATTRTYPE 码 → 属性名（shared/item.h EMixAttributeType）。
     */
    private static String mixAttrName(int code) {
        switch (code) {
            case 1: return "Fire Res";
            case 2: return "Ice Res";
            case 4: return "Lightning Res";
            case 8: return "Poison Res";
            case 16: return "Organic Res";
            case 32: return "Critical";
            case 64: return "Attack Rating";
            case 128: return "Min DMG";
            case 256: return "Max DMG";
            case 512: return "Attack Speed";
            case 1024: return "Absorb";
            case 2048: return "Defense";
            case 4096: return "Block";
            case 8192: return "Speed";
            case 16384: return "+HP";
            case 32768: return "+MP";
            case 65536: return "+SP";
            case 131072: return "HP Regen";
            case 262144: return "MP Regen";
            case 524288: return "SP Regen";
            case 1048576: return "Potion Storage";
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private ItemSummary toSummary(ItemList e) {
        ItemSummary s = new ItemSummary();
        s.setId(e.getId());
        s.setIdCode(e.getIdCode());
        s.setName(e.getName());
        ItemCategory.Category cat = ItemCategory.of(e.getIdCode());
        if (cat != null) {
            s.setCategory(cat.getName());
            s.setGroup(cat.getGroup());
        }
        s.setWidth(e.getWidth());
        s.setHeight(e.getHeight());
        s.setWeaponClass(e.getWeaponClass());
        s.setClassItem(e.getClassItem());
        s.setReqLevel(e.getReqLevel());
        s.setPrice(e.getPrice());
        return s;
    }

    private ItemDetail toDetail(ItemList e) {
        ItemDetail d = new ItemDetail();
        d.setId(e.getId());
        d.setIdCode(e.getIdCode());
        d.setName(e.getName());
        ItemCategory.Category cat = ItemCategory.of(e.getIdCode());
        if (cat != null) {
            d.setCategory(cat.getName());
            d.setGroup(cat.getGroup());
        }
        d.setWidth(e.getWidth());
        d.setHeight(e.getHeight());
        d.setWeight(e.getWeight());
        d.setPrice(e.getPrice());
        d.setWeaponClass(e.getWeaponClass());
        d.setClassItem(e.getClassItem());
        d.setReqLevel(e.getReqLevel());
        d.setReqStrength(e.getReqStrengh());
        d.setReqSpirit(e.getReqSpirit());
        d.setReqTalent(e.getReqTalent());
        d.setReqAgility(e.getReqAgility());
        d.setReqHealth(e.getReqHealth());
        d.setAtkSpeed(e.getAtkSpeed());
        d.setRange(e.getRange());
        d.setCritical(e.getCritical());
        d.setPotionSpace(e.getPotionSpace());
        d.setPotionCount(e.getPotionCount());
        d.setPrimarySpec(e.getPrimarySpec());
        d.setCannotDrop(e.getCannotDrop());

        d.setIntegrityMin(e.getIntegrityMin());
        d.setIntegrityMax(e.getIntegrityMax());
        d.setOrganicMin(e.getOrganicMin());
        d.setOrganicMax(e.getOrganicMax());
        d.setFireMin(e.getFireMin());
        d.setFireMax(e.getFireMax());
        d.setFrostMin(e.getFrostMin());
        d.setFrostMax(e.getFrostMax());
        d.setLightningMin(e.getLightningMin());
        d.setLightningMax(e.getLightningMax());
        d.setPoisonMin(e.getPoisonMin());
        d.setPoisonMax(e.getPoisonMax());
        d.setAtkPow1Min(e.getAtkPow1Min());
        d.setAtkPow1Max(e.getAtkPow1Max());
        d.setAtkPow2Min(e.getAtkPow2Min());
        d.setAtkPow2Max(e.getAtkPow2Max());
        d.setAtkRatingMin(e.getAtkRatingMin());
        d.setAtkRatingMax(e.getAtkRatingMax());
        d.setBlockMin(e.getBlockMin());
        d.setBlockMax(e.getBlockMax());
        d.setAbsorbMin(e.getAbsorbMin());
        d.setAbsorbMax(e.getAbsorbMax());
        d.setDefenseMin(e.getDefenseMin());
        d.setDefenseMax(e.getDefenseMax());
        d.setRunSpeedMin(e.getRunSpeedMin());
        d.setRunSpeedMax(e.getRunSpeedMax());
        d.setAddHpMin(e.getAddHpMin());
        d.setAddHpMax(e.getAddHpMax());
        d.setAddMpMin(e.getAddMpMin());
        d.setAddMpMax(e.getAddMpMax());
        d.setAddStmMin(e.getAddStmMin());
        d.setAddStmMax(e.getAddStmMax());
        d.setRegenerationHpMin(e.getRegenerationHpMin());
        d.setRegenerationHpMax(e.getRegenerationHpMax());
        d.setRegenerationMpMin(e.getRegenerationMpMin());
        d.setRegenerationMpMax(e.getRegenerationMpMax());
        d.setRegenerationStmMin(e.getRegenerationStmMin());
        d.setRegenerationStmMax(e.getRegenerationStmMax());

        d.setAddSpecClass1(e.getAddSpecClass1());
        d.setAddSpecClass2(e.getAddSpecClass2());
        d.setAddSpecClass3(e.getAddSpecClass3());
        d.setAddSpecClass4(e.getAddSpecClass4());
        d.setAddSpecClass5(e.getAddSpecClass5());
        d.setAddSpecClass6(e.getAddSpecClass6());
        d.setAddSpecClass7(e.getAddSpecClass7());
        d.setAddSpecClass8(e.getAddSpecClass8());
        d.setAddSpecClass9(e.getAddSpecClass9());
        d.setAddSpecClass10(e.getAddSpecClass10());
        d.setAddSpecClass11(e.getAddSpecClass11());
        d.setAddSpecClass12(e.getAddSpecClass12());
        d.setAddSpecRunSpeedMin(e.getAddSpecRunSpeedMin());
        d.setAddSpecRunSpeedMax(e.getAddSpecRunSpeedMax());
        d.setAddSpecAbsorbMin(e.getAddSpecAbsorbMin());
        d.setAddSpecAbsorbMax(e.getAddSpecAbsorbMax());
        d.setAddSpecDefenseMin(e.getAddSpecDefenseMin());
        d.setAddSpecDefenseMax(e.getAddSpecDefenseMax());
        d.setAddSpecAtkSpeed(e.getAddSpecAtkSpeed());
        d.setAddSpecCritical(e.getAddSpecCritical());
        d.setAddSpecAtkPowerMin(e.getAddSpecAtkPowerMin());
        d.setAddSpecAtkPowerMax(e.getAddSpecAtkPowerMax());
        d.setAddSpecAtkRatingMin(e.getAddSpecAtkRatingMin());
        d.setAddSpecAtkRatingMax(e.getAddSpecAtkRatingMax());
        d.setAddSpecHpRegen(e.getAddSpecHpRegen());
        d.setAddSpecMpRegenMin(e.getAddSpecMpRegenMin());
        d.setAddSpecMpRegenMax(e.getAddSpecMpRegenMax());
        d.setAddSpecStmRegen(e.getAddSpecStmRegen());
        d.setAddSpecBlock(e.getAddSpecBlock());
        d.setAddSpecRange(e.getAddSpecRange());
        return d;
    }

    // ------------------------------------------------------------------
    // 掷点工具
    // ------------------------------------------------------------------

    private static int rndInt(int min, int max) {
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

    private static int rndIntOr(Integer min, Integer max) {
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

    private static int resistRoll(Integer min, Integer max) {
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

    private static Double rndFloat(Double min, Double max) {
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

    private static Double rndFloat2(Double min, Double max) {
        if (min == null && max == null) {
            return 0.0;
        }
        if (min == null) {
            min = 0.0;
        }
        if (max == null || max == 0.0) {
            return min;
        }
        int sb = (int) ((max - min) * 10.0);
        if (sb <= 0) {
            return max;
        }
        int rnd = ThreadLocalRandom.current().nextInt(sb + 1);
        return Math.round((min + rnd / 10.0) * 100.0) / 100.0;
    }

    private static Double floatOfIntRoll(Integer min, Integer max) {
        return (double) rndIntOr(min, max);
    }

    private static Integer add(Integer v, int delta) {
        return (v == null ? 0 : v) + delta;
    }

    private static Double add(Double v, double delta) {
        return (v == null ? 0.0 : v) + delta;
    }
}