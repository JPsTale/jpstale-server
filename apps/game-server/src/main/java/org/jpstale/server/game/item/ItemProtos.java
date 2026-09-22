package org.jpstale.server.game.item;

import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemStat;
import org.jpstale.server.proto.base.CommonProto;

/**
 * `ItemInstance` → 下发用的 `ItemProto`（**唯一实现**）。
 *
 * <p>抽出来的原因（2026-09-22）：锻造（战斗养）会让**已装备**的物品属性/需求等级变化，
 * 战斗路径也必须把物品推给客户端 —— 那条路径没有 `ItemNetworkHandler` 的私有构建器，
 * 若各写一份迟早漂移（漏字段 = 客户端显示错，且不报错）。
 *
 * <p>⚠ 这里取的是**有效值**（`effective*`）：锻造/合成的加成不落库，读的时候才算，
 * 客户端只需要最终结果。
 */
public final class ItemProtos {

    private ItemProtos() {
    }

    public static CommonProto.ItemProto toProto(ItemInstance it) {
        CommonProto.ItemProto.Builder b = CommonProto.ItemProto.newBuilder()
                .setUid(it.getId() == null ? 0 : it.getId())
                .setItemlistId(it.getItemListId() == null ? 0 : it.getItemListId())
                .setItemCode(it.getItemCode() == null ? 0 : it.getItemCode())
                .setLocation(it.getLocation())
                .setSlot(it.getSlot())
                .setCount(it.getCount())
                .setDurability(it.getDurability())
                .setDurabilityMax(it.getDurabilityMax())
                .setDamageMin(it.effectiveInt(ItemStat.DAMAGE_MIN))
                .setDamageMax(it.effectiveInt(ItemStat.DAMAGE_MAX))
                .setAttackRating(it.effectiveInt(ItemStat.ATTACK_RATING))
                .setDefence(it.effectiveInt(ItemStat.DEFENCE))
                .setBlockRating((int) Math.round(it.effective(ItemStat.BLOCK_RATING) * 10))
                .setAbsorb((int) Math.round(it.effective(ItemStat.ABSORB) * 10))
                .setSpeed((int) Math.round(it.effective(ItemStat.SPEED) * 10))
                .setResBionic(it.effectiveInt(ItemStat.RES_BIONIC))
                .setResFire(it.effectiveInt(ItemStat.RES_FIRE))
                .setResIce(it.effectiveInt(ItemStat.RES_ICE))
                .setResLighting(it.effectiveInt(ItemStat.RES_LIGHTING))
                .setResPoison(it.effectiveInt(ItemStat.RES_POISON))
                .setResEarth(it.getResEarth())
                .setResWater(it.getResWater())
                .setResWind(it.getResWind())
                .setIncreaseLife((int) Math.round(it.effective(ItemStat.INCREASE_LIFE)))
                .setIncreaseMana((int) Math.round(it.effective(ItemStat.INCREASE_MANA)))
                .setIncreaseStamina((int) Math.round(it.effective(ItemStat.INCREASE_STAMINA)))
                .setReqLevel(org.jpstale.common.service.item.AgeService.effectiveReqLevel(it))   // 派生：基底 + N/2（无 88 豁免）
                .setReqStrength(it.getReqStrength())
                .setReqSpirit(it.getReqSpirit())
                .setReqTalent(it.getReqTalent())
                .setReqAgility(it.getReqAgility())
                .setReqHealth(it.getReqHealth())
                .setPrice(it.getPrice())
                .setJobCodeMask(it.getJobCodeMask())
                .setAgingLevel(it.getAgingNum())
                // 熟练度进度（原版 `ItemAgingCount[0]/[1]`）—— 客户端画进度条用；不是派生值，直接读
                .setAgingExp(it.getAgingExp())
                .setAgingExpMax(it.getAgingExpMax())
                .setCritical(it.effectiveInt(ItemStat.CRITICAL))
                .setRange(it.getShootingRange())
                .setAttackSpeed(it.getAttackSpeed())
                .setManaRegen((int) Math.round(it.effective(ItemStat.MANA_REGEN) * 10))
                .setLifeRegen((int) Math.round(it.effective(ItemStat.LIFE_REGEN) * 10))
                .setStaminaRegen((int) Math.round(it.effective(ItemStat.STAMINA_REGEN) * 10))
                .setSpecAbsorb((int) Math.round(it.getSpecAbsorb() * 10))
                .setSpecDefence(it.getSpecDefence())
                .setSpecSpeed((int) Math.round(it.getSpecSpeed() * 10))
                .setSpecBlockRating((int) Math.round(it.getSpecBlockRating() * 10))
                .setSpecAttackSpeed(it.getSpecAttackSpeed())
                .setSpecCritical(it.getSpecCritical())
                .setSpecShootingRange(it.getSpecShootingRange())
                .setSpecMagicMastery((int) Math.round(it.getSpecMagicMastery() * 10))
                .setSpecResBionic(it.getSpecResBionic())
                .setSpecResEarth(it.getSpecResEarth())
                .setSpecResFire(it.getSpecResFire())
                .setSpecResIce(it.getSpecResIce())
                .setSpecResLighting(it.getSpecResLighting())
                .setSpecResPoison(it.getSpecResPoison())
                .setSpecResWater(it.getSpecResWater())
                .setSpecResWind(it.getSpecResWind())
                .setSpecLevMana(it.getSpecLevMana())
                .setSpecLevLife(it.getSpecLevLife())
                .setSpecLevAttackRating(it.getSpecLevAttackRating())
                .setSpecLevDamageMax(it.getSpecLevDamageMax())
                .setSpecLevResBionic(it.getSpecLevResBionic())
                .setSpecLevResEarth(it.getSpecLevResEarth())
                .setSpecLevResFire(it.getSpecLevResFire())
                .setSpecLevResIce(it.getSpecLevResIce())
                .setSpecLevResLighting(it.getSpecLevResLighting())
                .setSpecLevResPoison(it.getSpecLevResPoison())
                .setSpecLevResWater(it.getSpecLevResWater())
                .setSpecLevResWind(it.getSpecLevResWind())
                .setSpecPerManaRegen((int) Math.round(it.getSpecPerManaRegen() * 100))
                .setSpecPerLifeRegen((int) Math.round(it.getSpecPerLifeRegen() * 100))
                .setSpecPerStaminaRegen((int) Math.round(it.getSpecPerStaminaRegen() * 100))
                // 锻造/合成状态（原版 ItemKindCode / ItemKindMask / sMixUniqueID1 / ItemAgingProtect[0]）
                .setKindCode(it.getKindCode())
                .setCraftMask(it.getDerivedCraftMask())
                // 配方效果清单（只发 key+值，不发文案）—— 客户端据此拼"配方显示名"并给这些行染色
                .addAllMixEffects(it.getDerivedMixEffects().stream()
                        .map(a -> CommonProto.MixEffectProto.newBuilder()
                                .setKey(a.key())
                                .setValue(a.value())
                                .setFlat(a.flat())
                                .build())
                        .toList())
                .setMixUniqueId(it.getAgingNum2())        // 我们的配方 id 借用 aging_num2（见 MixService 类注释）
                .setAgingProtect(it.getAgingProtect());
        return b.build();
    }
}
