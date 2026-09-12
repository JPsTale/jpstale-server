package org.jpstale.server.game.item;

import org.jpstale.dao.userdb.entity.Item;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物品持久化：userdb.item 行 ↔ 内存 {@link ItemInstance} 转换 + 读写。
 * <p>
 * location/slot 语义见 {@link ItemLocations}（画布版：0背包 1仓库 2装备 6备用武器）。
 */
@Slf4j
@Service
public class ItemStorageService {

    private final ItemMapper itemMapper;

    public ItemStorageService(ItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    public Item toRow(ItemInstance it) {
        Item r = new Item();
        r.setId(it.getId());
        r.setCharacterId(it.getCharacterId());
        r.setLocation((short) it.getLocation());
        r.setSlot((short) it.getSlot());
        r.setItemCode(it.getItemCode());
        r.setItemListId(it.getItemListId());
        r.setCount(it.getCount());

        r.setDurability((short) it.getDurability());
        r.setDurabilityMax((short) it.getDurabilityMax());
        r.setResBionic((short) it.getResBionic());
        r.setResEarth((short) it.getResEarth());
        r.setResFire((short) it.getResFire());
        r.setResIce((short) it.getResIce());
        r.setResLighting((short) it.getResLighting());
        r.setResPoison((short) it.getResPoison());
        r.setResWater((short) it.getResWater());
        r.setResWind((short) it.getResWind());
        r.setDamageMin((short) it.getDamageMin());
        r.setDamageMax((short) it.getDamageMax());
        r.setAttackRating(it.getAttackRating());
        r.setCritical(it.getCritical());
        r.setShootingRange(it.getShootingRange());
        r.setAttackSpeed(it.getAttackSpeed());
        r.setAbsorb(it.getAbsorb());
        r.setDefence(it.getDefence());
        r.setBlockRating(it.getBlockRating());
        r.setSpeed(it.getSpeed());
        r.setManaRegen(it.getManaRegen());
        r.setLifeRegen(it.getLifeRegen());
        r.setStaminaRegen(it.getStaminaRegen());
        r.setIncreaseLife(it.getIncreaseLife());
        r.setIncreaseMana(it.getIncreaseMana());
        r.setIncreaseStamina(it.getIncreaseStamina());

        r.setReqLevel(it.getReqLevel());
        r.setReqStrength(it.getReqStrength());
        r.setReqSpirit(it.getReqSpirit());
        r.setReqTalent(it.getReqTalent());
        r.setReqAgility(it.getReqAgility());
        r.setReqHealth(it.getReqHealth());
        r.setPrice(it.getPrice());
        r.setJobCodeMask(it.getJobCodeMask());

        r.setSpecAbsorb(it.getSpecAbsorb());
        r.setSpecDefence(it.getSpecDefence());
        r.setSpecSpeed(it.getSpecSpeed());
        r.setSpecBlockRating(it.getSpecBlockRating());
        r.setSpecAttackSpeed(it.getSpecAttackSpeed());
        r.setSpecCritical(it.getSpecCritical());
        r.setSpecShootingRange(it.getSpecShootingRange());
        r.setSpecMagicMastery(it.getSpecMagicMastery());
        r.setSpecResBionic((short) it.getSpecResBionic());
        r.setSpecResEarth((short) it.getSpecResEarth());
        r.setSpecResFire((short) it.getSpecResFire());
        r.setSpecResIce((short) it.getSpecResIce());
        r.setSpecResLighting((short) it.getSpecResLighting());
        r.setSpecResPoison((short) it.getSpecResPoison());
        r.setSpecResWater((short) it.getSpecResWater());
        r.setSpecResWind((short) it.getSpecResWind());
        r.setSpecLevMana(it.getSpecLevMana());
        r.setSpecLevLife(it.getSpecLevLife());
        r.setSpecLevAttackRating(it.getSpecLevAttackRating());
        r.setSpecLevDamageMax(it.getSpecLevDamageMax());
        r.setSpecLevResBionic((short) it.getSpecLevResBionic());
        r.setSpecLevResEarth((short) it.getSpecLevResEarth());
        r.setSpecLevResFire((short) it.getSpecLevResFire());
        r.setSpecLevResIce((short) it.getSpecLevResIce());
        r.setSpecLevResLighting((short) it.getSpecLevResLighting());
        r.setSpecLevResPoison((short) it.getSpecLevResPoison());
        r.setSpecLevResWater((short) it.getSpecLevResWater());
        r.setSpecLevResWind((short) it.getSpecLevResWind());
        r.setSpecPerManaRegen(it.getSpecPerManaRegen());
        r.setSpecPerLifeRegen(it.getSpecPerLifeRegen());
        r.setSpecPerStaminaRegen(it.getSpecPerStaminaRegen());

        r.setAgingNum((short) it.getAgingNum());
        r.setAgingNum2((short) it.getAgingNum2());
        r.setAgingExp(it.getAgingExp());
        r.setAgingExpMax(it.getAgingExpMax());
        return r;
    }

    public ItemInstance fromRow(Item r) {
        ItemInstance it = new ItemInstance();
        it.setId(r.getId());
        it.setCharacterId(r.getCharacterId() == null ? 0 : r.getCharacterId());
        it.setLocation(r.getLocation() == null ? 0 : r.getLocation());
        it.setSlot(r.getSlot() == null ? 0 : r.getSlot());
        it.setItemCode(r.getItemCode());
        it.setItemListId(r.getItemListId() != null ? r.getItemListId() : r.getItemCode());
        it.setCount(r.getCount() == null ? 1 : r.getCount());

        it.setDurability(r.getDurability() == null ? 0 : r.getDurability());
        it.setDurabilityMax(r.getDurabilityMax() == null ? 0 : r.getDurabilityMax());
        it.setResBionic(nzS(r.getResBionic()));
        it.setResEarth(nzS(r.getResEarth()));
        it.setResFire(nzS(r.getResFire()));
        it.setResIce(nzS(r.getResIce()));
        it.setResLighting(nzS(r.getResLighting()));
        it.setResPoison(nzS(r.getResPoison()));
        it.setResWater(nzS(r.getResWater()));
        it.setResWind(nzS(r.getResWind()));
        it.setDamageMin(r.getDamageMin() == null ? 0 : r.getDamageMin());
        it.setDamageMax(r.getDamageMax() == null ? 0 : r.getDamageMax());
        it.setAttackRating(nz(r.getAttackRating()));
        it.setCritical(nz(r.getCritical()));
        it.setShootingRange(nz(r.getShootingRange()));
        it.setAttackSpeed(nz(r.getAttackSpeed()));
        it.setAbsorb(r.getAbsorb() == null ? 0 : r.getAbsorb());
        it.setDefence(nz(r.getDefence()));
        it.setBlockRating(r.getBlockRating() == null ? 0 : r.getBlockRating());
        it.setSpeed(r.getSpeed() == null ? 0 : r.getSpeed());
        it.setManaRegen(r.getManaRegen() == null ? 0 : r.getManaRegen());
        it.setLifeRegen(r.getLifeRegen() == null ? 0 : r.getLifeRegen());
        it.setStaminaRegen(r.getStaminaRegen() == null ? 0 : r.getStaminaRegen());
        it.setIncreaseLife(r.getIncreaseLife() == null ? 0 : r.getIncreaseLife());
        it.setIncreaseMana(r.getIncreaseMana() == null ? 0 : r.getIncreaseMana());
        it.setIncreaseStamina(r.getIncreaseStamina() == null ? 0 : r.getIncreaseStamina());

        it.setReqLevel(nz(r.getReqLevel()));
        it.setReqStrength(nz(r.getReqStrength()));
        it.setReqSpirit(nz(r.getReqSpirit()));
        it.setReqTalent(nz(r.getReqTalent()));
        it.setReqAgility(nz(r.getReqAgility()));
        it.setReqHealth(nz(r.getReqHealth()));
        it.setPrice(nz(r.getPrice()));
        it.setJobCodeMask(nz(r.getJobCodeMask()));

        it.setSpecAbsorb(r.getSpecAbsorb() == null ? 0 : r.getSpecAbsorb());
        it.setSpecDefence(nz(r.getSpecDefence()));
        it.setSpecSpeed(r.getSpecSpeed() == null ? 0 : r.getSpecSpeed());
        it.setSpecBlockRating(r.getSpecBlockRating() == null ? 0 : r.getSpecBlockRating());
        it.setSpecAttackSpeed(nz(r.getSpecAttackSpeed()));
        it.setSpecCritical(nz(r.getSpecCritical()));
        it.setSpecShootingRange(nz(r.getSpecShootingRange()));
        it.setSpecMagicMastery(r.getSpecMagicMastery() == null ? 0 : r.getSpecMagicMastery());
        it.setSpecResBionic(nzS(r.getSpecResBionic()));
        it.setSpecResEarth(nzS(r.getSpecResEarth()));
        it.setSpecResFire(nzS(r.getSpecResFire()));
        it.setSpecResIce(nzS(r.getSpecResIce()));
        it.setSpecResLighting(nzS(r.getSpecResLighting()));
        it.setSpecResPoison(nzS(r.getSpecResPoison()));
        it.setSpecResWater(nzS(r.getSpecResWater()));
        it.setSpecResWind(nzS(r.getSpecResWind()));
        it.setSpecLevMana(nz(r.getSpecLevMana()));
        it.setSpecLevLife(nz(r.getSpecLevLife()));
        it.setSpecLevAttackRating(nz(r.getSpecLevAttackRating()));
        it.setSpecLevDamageMax(nz(r.getSpecLevDamageMax()));
        it.setSpecLevResBionic(nzS(r.getSpecLevResBionic()));
        it.setSpecLevResEarth(nzS(r.getSpecLevResEarth()));
        it.setSpecLevResFire(nzS(r.getSpecLevResFire()));
        it.setSpecLevResIce(nzS(r.getSpecLevResIce()));
        it.setSpecLevResLighting(nzS(r.getSpecLevResLighting()));
        it.setSpecLevResPoison(nzS(r.getSpecLevResPoison()));
        it.setSpecLevResWater(nzS(r.getSpecLevResWater()));
        it.setSpecLevResWind(nzS(r.getSpecLevResWind()));
        it.setSpecPerManaRegen(r.getSpecPerManaRegen() == null ? 0 : r.getSpecPerManaRegen());
        it.setSpecPerLifeRegen(r.getSpecPerLifeRegen() == null ? 0 : r.getSpecPerLifeRegen());
        it.setSpecPerStaminaRegen(r.getSpecPerStaminaRegen() == null ? 0 : r.getSpecPerStaminaRegen());

        it.setAgingNum(r.getAgingNum() == null ? 0 : r.getAgingNum());
        it.setAgingNum2(r.getAgingNum2() == null ? 0 : r.getAgingNum2());
        it.setAgingExp(nz(r.getAgingExp()));
        it.setAgingExpMax(nz(r.getAgingExpMax()));
        it.setDeleted(r.getDeleteTime() != null);
        return it;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static int nzS(Short v) {
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /** 装载某玩家全部活物品（delete_time IS NULL）。 */
    public List<Item> loadActiveRows(int characterId) {
        return itemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Item>()
                        .eq(Item::getCharacterId, characterId)
                        .isNull(Item::getDeleteTime));
    }

    /**
     * 装载某玩家**指定容器**的活物品（delete_time IS NULL）。
     * <p>
     * ⚠ 软删除条件必须只有这一处实现：`userdb.item` 的**换下的装备不删行、只写 delete_time**
     * （W 换武器/换甲都会留下旧行），漏掉这个条件就会把已经换掉的装备也读进来 ——
     * 选角列表的外观曾因此按"最后一条胜出"取到旧弓/旧甲（用户 2026-09-12 报的 test_fs_40）。
     */
    public List<Item> loadActiveRows(int characterId, int location) {
        return itemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Item>()
                        .eq(Item::getCharacterId, characterId)
                        .eq(Item::getLocation, (short) location)
                        .isNull(Item::getDeleteTime));
    }

    /** 插入新实例（掷点/拾取/发放）；写回自增 id。 */
    @Transactional
    public void insert(ItemInstance it) {
        Item r = toRow(it);
        itemMapper.insert(r);
        it.setId(r.getId());
    }

    /** 更新一行（位置移动/属性/堆叠等）。 */
    @Transactional
    public void update(ItemInstance it) {
        if (it.getId() == null) {
            insert(it);
            return;
        }
        itemMapper.updateById(toRow(it));
    }

    /** 软删（丢弃/扫地销毁）。 */
    @Transactional
    public void softDelete(long uid) {
        Item r = new Item();
        r.setId(uid);
        r.setDeleteTime(LocalDateTime.now());
        itemMapper.updateById(r);
    }

    /** 恢复软删行（地面物被拾回）：清 delete_time 并回写全部字段（位置/归属等）。 */
    @Transactional
    public void restore(ItemInstance it) {
        Item r = toRow(it);
        itemMapper.update(r, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Item>()
                .eq("id", it.getId())
                .set("delete_time", null));
    }
}
