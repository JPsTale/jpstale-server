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
import java.util.stream.Collectors;

/**
 * 装备模拟器服务（wartale 风格查看器）。
 * <p>
 * 数据源：gamedb.itemlist（EU 风格 min/max 掷点区间）。
 * 详情展示模板原始范围；Spec/Mix/Age 下拉切换展示对应属性。
 *
 * ⚠ **本类不再自己掷点**：原先这里有一份与游戏内并行的掷点实现（`rollFromDef`），
 * 它已经与游戏侧**分叉**（小数步长 0.01 vs 0.1、缺一组 spec 字段、同 idCode 取行的规则不同）。
 * 掷点的**唯一实现**是 common-service 的
 * {@link org.jpstale.common.service.item.ItemRollService}（掷完得到
 * {@link org.jpstale.common.service.item.ItemInstance}，自带模板引用）。
 * 那个 {"@code POST /api/simulator/roll"} 接口已随旧实现一起删除，**重做接口时请直接调它，不要再实现一遍** ——
 * "模拟器显示的值与游戏内不一致"是当初最难查的一类问题。
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
        s.setDorpItem(e.getCodeImg1());
        s.setDropFolder(e.getDropFolder());
        s.setModelPosition(e.getModelPosition());
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
        d.setDorpItem(e.getCodeImg1());
        d.setDropFolder(e.getDropFolder());
        d.setModelPosition(e.getModelPosition());

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

}