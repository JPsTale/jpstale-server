package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PlayerStatCalculator;
import org.junit.Test;

/**
 * L0：**魔法职业空手施法的攻击距离**（用户 2026-09-16 定："法师、祭司、萨满的徒手攻击距离应该有 140"）。
 *
 * 四档规则（见 `PlayerStatCalculator.shootingRangeOf`）：
 *   ① 远程/魔法武器自带射程（弓/弩/标枪/法杖/图腾，`gamedb.itemlist.range` 有值）→ 用它的；
 *   ② **魔法职业（7 法师 / 8 祭司 / 10 萨满）空手** → 140（= 最低阶魔法武器的射程）；
 *   ③ 近战双手 → 60；④ 近战单手 / 其它职业徒手 → 30。
 *
 * ⚠ ②里的"哪几个职业"客户端也有一份（`jpstale-client/src/render/projectile.ts` 的 `MAGIC_JOBS`），
 * 跨语言无法共享常量 ⇒ 由客户端的 `npm run verify-projectile` 读本类所在的服务器源文件断言两处一致。
 */
public class PlayerStatCalculatorRangeTest {

    private static final int JOB_FIGHTER = 1;
    private static final int JOB_ARCHER = 3;
    private static final int JOB_MAGICIAN = 7;
    private static final int JOB_PRIESTESS = 8;
    private static final int JOB_SHAMAN = 10;

    /** 主手武器模板：`classItem` 4=单手 / 6=双手；`range` 只有远程/魔法族有值。 */
    private static ItemList weapon(int classItem, int range) {
        ItemList t = new ItemList();
        t.setId(300 + classItem);
        t.setName("test weapon");
        t.setClassItem(classItem);
        t.setWidth(44);
        t.setHeight(88);
        t.setWeight(10);
        t.setRange(range);
        return t;
    }

    private static Player player(int job, ItemList mainHandTemplate) {
        // ⚠ `new Player(session, slotIndex)` 的第二个参数是**角色槽位**，不是职业 —— 职业要 `setJob`
        Player p = new Player(null, 0);
        p.setJob(job);
        p.setItems(new PlayerItems());
        p.setCharacterId(1L);
        p.setName("tester");
        p.setLevel(50);
        p.setStrength(50);
        if (mainHandTemplate != null) {
            ItemInstance it = ItemTestSupport.instance(
                    1L, mainHandTemplate, 1, ItemLocations.EQUIP, ItemLocations.SLOT_MAIN_HAND);
            // 判据读**实例**的射程值（由 `ItemRollService` 从模板 `range` 填入，测试里直接给）
            it.setShootingRange(mainHandTemplate.getRange() == null ? 0 : mainHandTemplate.getRange());
            p.getItems().index(it);
        }
        return p;
    }

    private static int rangeOf(int job, ItemList mainHand) {
        PlayerStatCalculator calc = new PlayerStatCalculator();
        Player p = player(job, mainHand);
        calc.invalidate(p);
        return calc.shootingRange(p);
    }

    @Test
    public void magicJobsUnarmedUseSpellRange() {
        assertEquals("法师空手 = 最低阶魔法武器射程", PlayerStatCalculator.MAGIC_UNARMED_RANGE, rangeOf(JOB_MAGICIAN, null));
        assertEquals("祭司空手", PlayerStatCalculator.MAGIC_UNARMED_RANGE, rangeOf(JOB_PRIESTESS, null));
        assertEquals("萨满空手（图腾之外也按施法）", PlayerStatCalculator.MAGIC_UNARMED_RANGE, rangeOf(JOB_SHAMAN, null));
    }

    @Test
    public void otherJobsUnarmedStayMelee() {
        assertEquals("战士空手仍是近战", PlayerStatCalculator.MELEE_RANGE_ONE_HAND, rangeOf(JOB_FIGHTER, null));
        assertEquals("弓手空手仍是近战", PlayerStatCalculator.MELEE_RANGE_ONE_HAND, rangeOf(JOB_ARCHER, null));
    }

    @Test
    public void magicWeaponUsesItsOwnRange() {
        ItemList wand = weapon(ItemClass.ONE_HAND_WEAPON, 190);
        assertEquals("持法杖 → 用物品自己的射程", 190, rangeOf(JOB_MAGICIAN, wand));
    }

    @Test
    public void meleeTiersUnchanged() {
        assertEquals("近战单手", PlayerStatCalculator.MELEE_RANGE_ONE_HAND,
                rangeOf(JOB_FIGHTER, weapon(ItemClass.ONE_HAND_WEAPON, 0)));
        assertEquals("近战双手", PlayerStatCalculator.MELEE_RANGE_TWO_HAND,
                rangeOf(JOB_FIGHTER, weapon(ItemClass.TWO_HAND_WEAPON, 0)));
    }

    /** 魔法职业**拿着近战武器**时不该走施法档（只有空手才补 140）—— 手别档位照旧。 */
    @Test
    public void magicJobWithMeleeWeaponIsNotSpellRange() {
        assertEquals(PlayerStatCalculator.MELEE_RANGE_ONE_HAND,
                rangeOf(JOB_MAGICIAN, weapon(ItemClass.ONE_HAND_WEAPON, 0)));
    }
}
