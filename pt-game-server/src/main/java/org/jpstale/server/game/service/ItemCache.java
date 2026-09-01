package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.ItemTemplate;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品缓存
 * 启动时从 gamedb.item_list 加载所有物品模板
 */
@Slf4j
@Component
public class ItemCache {

    @Autowired
    private ItemListMapper itemListMapper;

    private final Map<Integer, ItemTemplate> templates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDatabase();
        log.info("ItemCache initialized with {} items", templates.size());
    }

    /**
     * 从数据库加载物品
     */
    private void loadFromDatabase() {
        // 对齐原版 CreateItemMemoryTable：仅 QuestID=0 进活动表，ORDER BY ID ASC 顺序匹配取最小 ID
        List<ItemList> items = itemListMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ItemList>()
                .eq(ItemList::getQuestId, 0)
                .orderByAsc(ItemList::getId));
        for (ItemList row : items) {
            ItemTemplate template = convert(row);
            templates.putIfAbsent(template.getId(), template);
        }
        log.info("Loaded {} items from gamedb.item_list", templates.size());
    }

    /**
     * 将数据库行转换为 ItemTemplate
     */
    private ItemTemplate convert(ItemList row) {
        ItemTemplate t = new ItemTemplate();
        t.setId(row.getId());
        t.setIdCode(row.getIdCode() != null ? row.getIdCode() : 0);
        t.setName(row.getName());
        t.setNullcode(row.getNullcode());
        t.setCodeImg1(row.getCodeImg1());
        t.setCodeImg2(row.getCodeImg2());
        t.setClassItem(row.getClassItem() != null ? row.getClassItem() : 0);
        t.setWeaponClass(row.getWeaponClass() != null ? row.getWeaponClass() : 0);
        t.setReqLevel(row.getReqLevel() != null ? row.getReqLevel() : 0);
        t.setReqStrength(row.getReqStrengh() != null ? row.getReqStrengh() : 0);
        t.setReqSpirit(row.getReqSpirit() != null ? row.getReqSpirit() : 0);
        t.setReqTalent(row.getReqTalent() != null ? row.getReqTalent() : 0);
        t.setReqAgility(row.getReqAgility() != null ? row.getReqAgility() : 0);
        t.setReqHealth(row.getReqHealth() != null ? row.getReqHealth() : 0);
        t.setWeight(row.getWeight() != null ? row.getWeight() : 0);
        t.setPrice(row.getPrice() != null ? row.getPrice() : 0);
        t.setAtkPow1Min(row.getAtkPow1Min() != null ? row.getAtkPow1Min() : 0);
        t.setAtkPow1Max(row.getAtkPow1Max() != null ? row.getAtkPow1Max() : 0);
        t.setAtkPow2Min(row.getAtkPow2Min() != null ? row.getAtkPow2Min() : 0);
        t.setAtkPow2Max(row.getAtkPow2Max() != null ? row.getAtkPow2Max() : 0);
        t.setRange(row.getRange() != null ? row.getRange() : 0);
        t.setAtkSpeed(row.getAtkSpeed() != null ? row.getAtkSpeed() : 0);
        t.setAtkRatingMin(row.getAtkRatingMin() != null ? row.getAtkRatingMin() : 0);
        t.setAtkRatingMax(row.getAtkRatingMax() != null ? row.getAtkRatingMax() : 0);
        t.setCritical(row.getCritical() != null ? row.getCritical() : 0);
        t.setBlockMin(row.getBlockMin() != null ? row.getBlockMin() : 0);
        t.setBlockMax(row.getBlockMax() != null ? row.getBlockMax() : 0);
        t.setAbsorbMin(row.getAbsorbMin() != null ? row.getAbsorbMin() : 0);
        t.setAbsorbMax(row.getAbsorbMax() != null ? row.getAbsorbMax() : 0);
        t.setDefenseMin(row.getDefenseMin() != null ? row.getDefenseMin() : 0);
        t.setDefenseMax(row.getDefenseMax() != null ? row.getDefenseMax() : 0);
        t.setPotionSpace(row.getPotionSpace() != null ? row.getPotionSpace() : 0);
        t.setPotionCount(row.getPotionCount() != null ? row.getPotionCount() : 0);
        t.setRegenerationHpMin(row.getRegenerationHpMin() != null ? row.getRegenerationHpMin() : 0);
        t.setRegenerationHpMax(row.getRegenerationHpMax() != null ? row.getRegenerationHpMax() : 0);
        t.setRegenerationMpMin(row.getRegenerationMpMin() != null ? row.getRegenerationMpMin() : 0);
        t.setRegenerationMpMax(row.getRegenerationMpMax() != null ? row.getRegenerationMpMax() : 0);
        t.setRegenerationStmMin(row.getRegenerationStmMin() != null ? row.getRegenerationStmMin() : 0);
        t.setRegenerationStmMax(row.getRegenerationStmMax() != null ? row.getRegenerationStmMax() : 0);
        t.setAddHpMin(row.getAddHpMin() != null ? row.getAddHpMin() : 0);
        t.setAddHpMax(row.getAddHpMax() != null ? row.getAddHpMax() : 0);
        t.setAddMpMin(row.getAddMpMin() != null ? row.getAddMpMin() : 0);
        t.setAddMpMax(row.getAddMpMax() != null ? row.getAddMpMax() : 0);
        t.setAddStmMin(row.getAddStmMin() != null ? row.getAddStmMin() : 0);
        t.setAddStmMax(row.getAddStmMax() != null ? row.getAddStmMax() : 0);
        t.setRunSpeedMin(row.getRunSpeedMin() != null ? row.getRunSpeedMin() : 0);
        t.setRunSpeedMax(row.getRunSpeedMax() != null ? row.getRunSpeedMax() : 0);
        t.setCannotDrop(row.getCannotDrop() != null ? row.getCannotDrop() : 0);
        return t;
    }

    /**
     * 获取物品模板
     */
    public ItemTemplate getTemplate(int itemId) {
        return templates.get(itemId);
    }

    /**
     * 获取所有模板
     */
    public List<ItemTemplate> getAllTemplates() {
        return new ArrayList<>(templates.values());
    }
}
