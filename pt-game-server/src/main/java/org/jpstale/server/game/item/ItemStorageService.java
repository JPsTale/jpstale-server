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
        // **硬门**：新建的行必须属于某个角色。漏写 characterId 会以 0 落库，而唯一索引
        // `uq_item_active_slot(character_id, location, slot)` 会把它当成"0 号角色的这个槽位"——
        // 第一次插入成功、**之后每次同槽插入都撞唯一键**，整笔操作以 DuplicateKeyException 失败
        //（用户 2026-09-14 实测：9 瓶药水放进空药水槽根本放不进去，库里躺着 (0,0,11) 的孤儿行）。
        // 这里**不静默补 0、也不猜**：直接失败并喊出来（写入脏行比失败更糟 —— 它会毒化后续所有同槽操作）。
        Integer cid = it.getCharacterId();
        if (cid == null || cid == 0) {
            log.error("[ItemStorage] 拒绝插入没有 characterId 的实例：uid={} itemListId={} loc={}/{} "
                    + "—— 新建实例的代码必须 setCharacterId(player.getId())",
                    it.getId(), it.getItemListId(), it.getLocation(), it.getSlot());
            throw new IllegalStateException("insert without characterId: uid=" + it.getId());
        }
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

    /**
     * **停车位**：互换位置时临时用的哨兵槽号（`-1` 是鼠标位，所以从 `-2` 起）。
     *
     * ⚠ 只允许出现在事务中间态，**绝不允许提交**：出事务后库里任何 `slot < -1` 的行都说明
     * 写库没收尾（`PlayerService.loadItems` 会把它们捞回背包并报 error，见那里）。
     */
    public static final int PARKING_SLOT = -2;

    /**
     * **多行移动的唯一写库入口** —— 用于"两件/多件物品互换位置"这类写序（换手、全量布局落子、W 换武器套）。
     *
     * 唯一键 `uq_item_active_slot (character_id, location, slot) WHERE delete_time IS NULL` 是
     * **逐语句立即检查**的：互换时"先把 A 写到 X"而 B 的行还占着 X，就会直接报重复键。
     * 所以这里把写库拆成**先全部停到哨兵槽、再逐个落到最终位置**：
     * <ol>
     *   <li>**停车**：把每行 `slot` 写成一个**各不相同的**哨兵槽（`-2, -3, ...`）→ 它不再占着任何真实槽位；</li>
     *   <li>**落地**：把每行写回它最终的位置。</li>
     * </ol>
     * 于是任何时刻都不存在"两行抢同一个槽位"，而且**对任意批量都成立**（不需要分析环：
     * 批量是任意置换时，"逐行停在不同哨兵"等价于一次性把整个置换拆开）。
     *
     * **三档**（按真正要动的件数走，见下）：
     * <ul>
     *   <li>**1 件**（最常见：拖一件到空格）→ **1 条 UPDATE**，不停车（目标格是空的）；</li>
     *   <li>**2 件**（互换：换装 / W 切换武器套 / 拖到已占格）→ **3 条 UPDATE**：停 A → 写 B 的最终位置
     *       （A 的原格已空出）→ 写 A 的最终位置（B 刚腾出来的）。**不需要知道旧槽位**
     *       （用户 2026-09-14 指正：这一步我原先多虑了）；</li>
     *   <li>**3 件及以上** → 逐行停到各不相同的哨兵槽（2N 条）。三件轮换 A→B 格 / B→C 格 / C→A 格时，
     *       停 A 之后写 B 会撞上还占着那个格的 C —— 要少停就得知道"谁的目标格刚被腾出来"（旧槽位/环分解），不值。</li>
     * </ul>
     * **现实中的件数边界 = 1 / 2 / 3**（用户 2026-09-14 追问后逐条核实）：
     * 客户端手势每次只移动 1 件（互换时被撞那件"拿起"进鼠标位，而鼠标位不进布局上报）；
     * 换手（`moveOnCanvas`）= 2；W 切套 = 1~2；**双手武器换装 = 3**（同槽旧件 + 另一只手那件 + 新件，
     * 两只手都占用时）—— 所以第三档**不是死代码，是双手换装会走到的路径**（6 条语句，同一事务）。
     * <p>
     * 什么时候才需要"算好布局再按环逐个落地"（N + 环数）：等出现**一次改很多件的程序**
     * （整理背包 / 一键换套装 / 批量存仓）时。那时把本方法的入参从"已改好的实例"换成
     * **(from, to) 移动计划**、由写入方做环分解即可 —— `from` 必须在**改内存之前**采集
     * （`applyBagLayout` 的校验循环、`moveOnCanvas` 的 `oldItSlot/oldDisSlot` 都已经有）。
     * 现在不为它插桩：4 个调用点都要多传 `from`，传错会错序，收益只有"双手换装 6→4 条"。
     * <p>
     * ⚠ 另注：早先 `applyBagLayout` 会把客户端上报的**整包快照**（背包+仓库全部）都写一遍
     * （拖一次格子 = 写 144+81 行），现已改成**只写位置真的变了的行**（见那里的 skip）。
     *
     * ⚠ 为什么不用 `softDelete` 停车（早先版本）：那会**借 `delete_time` 当锁** ——
     * 一个业务字段（=已删除）在事务里被临时设成"已删除"，语义被污染，也把写序绑死在
     * "索引必须是 `WHERE delete_time IS NULL` 的部分唯一索引"上。用户 2026-09-14 提的
     * "用一个哨兵槽先停一下"更干净：只碰 `slot`，索引怎么定义都成立。
     *
     * ⚠ 只该用于**互换/调换**；落到空位的普通移动用 {@link #update} 即可（一次 UPDATE，别绕这一圈）。
     * 依据与踩坑记录见 `docs/pt-core-gameplay.md` §19.2⑨⑩、`AGENTS.md` 纠错 #25/#26/#27。
     */
    @Transactional
    public void writeMoved(java.util.List<ItemInstance> moved) {
        if (moved == null || moved.isEmpty()) {
            return;
        }
        // 去重（同一个实例被传两次会自己跟自己抢哨兵），保持调用方给的顺序
        java.util.LinkedHashSet<ItemInstance> rows = new java.util.LinkedHashSet<>();
        for (ItemInstance it : moved) {
            if (it != null) {
                rows.add(it);
            }
        }
        // 已落库的行（新件没有行可停，最后 insert）
        java.util.List<ItemInstance> persisted = new java.util.ArrayList<>(rows.size());
        java.util.List<ItemInstance> fresh = new java.util.ArrayList<>();
        for (ItemInstance it : rows) {
            (it.getId() == null ? fresh : persisted).add(it);
        }
        final int n = persisted.size();
        if (n == 1) {
            // **单件落格**（最常见：拖一件放到空格）→ 一条 UPDATE 就够：目标格是空的（调用方已校验），
            // 不需要停车，也不该为了停车多写一条。
            update(persisted.get(0));
        } else if (n == 2) {
            // **两件互换** → 停一件（3 条）：停 A → 写 B 的最终位置（A 的原格已空出）→ 写 A（B 刚腾出的）。
            // 不需要知道旧槽位：两件时"另一件要去的格"必然是停下的那件让出来的。
            ItemInstance parked = persisted.get(0);
            parkRow(parked.getId(), PARKING_SLOT);
            update(persisted.get(1));
            update(parked);
        } else if (n > 2) {
            // **三件及以上**（现实里只有一处：`equipFromBag` 双手武器换装 = 同槽旧件 + 另一只手那件 + 新件）
            // → 逐行停到**各不相同**的哨兵槽，再逐行落地。
            // 为什么不能只停一件：三件轮换 A→B 格 / B→C 格 / C→A 格时，停 A 之后写 B 会撞上还占着
            // 那个格的 C —— 要少停就得知道"谁的目标格刚被腾出来"（= 旧槽位 / 环分解），不值。
            int park = PARKING_SLOT;
            for (ItemInstance it : persisted) {
                parkRow(it.getId(), park);
                park--;
            }
            for (ItemInstance it : persisted) {
                update(it);
            }
        }
        for (ItemInstance it : fresh) {
            insert(it);
        }
        if (n > 1) {
            log.debug("[WriteMoved] {} 件调换：停车 {} 条哨兵槽后落地", n, n == 2 ? 1 : n);
        }
    }

    /** 把一行临时停到哨兵槽（只改 location 不动，slot = 哨兵）。见 {@link #PARKING_SLOT}。 */
    private void parkRow(long uid, int parkingSlot) {
        Item r = new Item();
        r.setId(uid);
        r.setSlot((short) parkingSlot);
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
