package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.ItemList;

/**
 * 测试用物品模板（**只在测试作用域**）。
 *
 * 取值覆盖 {@link ItemRollService#roll} 的每一条分支，所以任何一条区间被读错、
 * 或某段逻辑被搬动后取错字段，掷点签名都会变。
 */
final class ItemFixtures {

    private ItemFixtures() {
    }

    /** 一把"什么区间都有"的单手剑：用于覆盖掷点的全部分支。 */
    static ItemList sampleWeapon() {
        ItemList def = new ItemList();
        def.setId(1001);
        def.setIdCode(0x01010100);
        def.setQuestId(0);
        def.setName("Test Sword");
        def.setWidth(44);
        def.setHeight(88);
        def.setClassItem(ItemClass.ONE_HAND_WEAPON);

        def.setReqLevel(20);
        def.setReqStrengh(30);
        def.setReqSpirit(10);
        def.setReqTalent(12);
        def.setReqAgility(15);
        def.setReqHealth(25);

        def.setIntegrityMin(20);
        def.setIntegrityMax(60);

        def.setOrganicMin(5);
        def.setOrganicMax(15);
        def.setFireMin(10);
        def.setFireMax(30);
        def.setFrostMin(0);
        def.setFrostMax(8);
        def.setLightningMin(3);
        def.setLightningMax(9);
        def.setPoisonMin(1);
        def.setPoisonMax(5);

        def.setAtkPow1Min(10);
        def.setAtkPow2Min(20);
        def.setAtkPow1Max(30);
        def.setAtkPow2Max(50);

        def.setAtkRatingMin(100);
        def.setAtkRatingMax(200);
        def.setAbsorbMin(1.5);
        def.setAbsorbMax(4.5);
        def.setDefenseMin(5);
        def.setDefenseMax(15);
        def.setBlockMin(0.0);
        def.setBlockMax(3.0);
        def.setRunSpeedMin(0.5);
        def.setRunSpeedMax(2.5);
        def.setCritical(7);
        def.setRange(0);
        def.setAtkSpeed(3);

        def.setRegenerationHpMin(0.5);
        def.setRegenerationHpMax(2.5);
        def.setRegenerationMpMin(1.0);
        def.setRegenerationMpMax(3.0);
        def.setRegenerationStmMin(0.5);
        def.setRegenerationStmMax(1.5);

        def.setAddHpMin(10);
        def.setAddHpMax(30);
        def.setAddMpMin(5);
        def.setAddMpMax(20);
        def.setAddStmMin(0);
        def.setAddStmMax(10);

        def.setPrice(1000);

        // 两个可选职业 → 走"随机挑一个"分支（而非 size==1 的直取）
        def.setAddSpecClass1(1);
        def.setAddSpecClass2(8);
        def.setAddSpecRunSpeedMin(0.5);
        def.setAddSpecRunSpeedMax(2.0);
        def.setAddSpecAbsorbMin(1.0);
        def.setAddSpecAbsorbMax(3.0);
        def.setAddSpecDefenseMin(2);
        def.setAddSpecDefenseMax(8);
        def.setAddSpecAtkSpeed(2);
        def.setAddSpecCritical(3);
        def.setAddSpecAtkPowerMin(5);
        def.setAddSpecAtkPowerMax(15);
        def.setAddSpecAtkRatingMin(10);
        def.setAddSpecAtkRatingMax(40);
        def.setAddSpecHpRegen(1.0);
        def.setAddSpecMpRegenMin(0.5);
        def.setAddSpecMpRegenMax(2.5);
        def.setAddSpecStmRegen(0.5);
        def.setAddSpecBlock(2.0);
        def.setAddSpecRange(30);
        return def;
    }

    /**
     * 掷点结果的可比较签名：把**所有由随机决定**的字段按固定顺序拼成一个字符串。
     * 只收随机量，不收模板直通量（critical/range/atkSpeed 等直接抄模板的列不在这里）。
     */
    static String rollSignature(ItemInstance it) {
        return String.join("|",
                "dur=" + it.getDurability() + "/" + it.getDurabilityMax(),
                "resBio=" + it.getResBionic(),
                "resFire=" + it.getResFire(),
                "resIce=" + it.getResIce(),
                "resLight=" + it.getResLighting(),
                "resPoison=" + it.getResPoison(),
                "dmg=" + it.getDamageMin() + "-" + it.getDamageMax(),
                "atkRating=" + it.getAttackRating(),
                "absorb=" + it.getAbsorb(),
                "defence=" + it.getDefence(),
                "block=" + it.getBlockRating(),
                "speed=" + it.getSpeed(),
                "manaRegen=" + it.getManaRegen(),
                "lifeRegen=" + it.getLifeRegen(),
                "stmRegen=" + it.getStaminaRegen(),
                "incLife=" + it.getIncreaseLife(),
                "incMana=" + it.getIncreaseMana(),
                "incStm=" + it.getIncreaseStamina(),
                "price=" + it.getPrice(),
                "jobMask=" + it.getJobCodeMask(),
                "req=L" + it.getReqLevel() + "/S" + it.getReqStrength() + "/P" + it.getReqSpirit()
                        + "/T" + it.getReqTalent() + "/A" + it.getReqAgility() + "/H" + it.getReqHealth(),
                "specAbsorb=" + it.getSpecAbsorb(),
                "specDefence=" + it.getSpecDefence(),
                "specSpeed=" + it.getSpecSpeed(),
                "specPerManaRegen=" + it.getSpecPerManaRegen(),
                "specLevAtkRating=" + it.getSpecLevAttackRating(),
                "specLevDmgMax=" + it.getSpecLevDamageMax(),
                "specAtkSpeed=" + it.getSpecAttackSpeed(),
                "specCritical=" + it.getSpecCritical(),
                "specRange=" + it.getSpecShootingRange(),
                "specBlock=" + it.getSpecBlockRating(),
                "specPerLifeRegen=" + it.getSpecPerLifeRegen(),
                "specPerStmRegen=" + it.getSpecPerStaminaRegen());
    }
}
