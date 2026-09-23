package org.jpstale.server.game.service;

import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.common.model.CharacterAppearance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AppearanceService#derive} 的**呼吸发光输入**（原版 `ItemKindCode` + `ItemAgingNum[0]`）特征测试。
 *
 * <p>钉住三件事，都是"错了不会报错、只是别人看不到光 / 光不对"的那类：
 * <ol>
 *   <li>四个字段确实来自**物品实例**（`kindCode`/`agingNum`）而不是 itemlist；</li>
 *   <li>副手那对只在**真的挂了模型**时才带（念珠/法球不挂 ⇒ 保持 0，与客户端"没模型就不发光"一致）；</li>
 *   <li>它们**参与 {@code equals}** —— 这是"锻造升级后旁观者能立刻看到换色"的唯一前提
 *       （{@code AppearanceService.recalcAndBroadcast} 只在 `!equals` 时广播）。</li>
 * </ol>
 * 客户端侧的对应实现与色表：`jpstale-client/src/game/agingBlink.ts`（`blinkRowOf`）。
 */
class AppearanceServiceTest {

    private final AppearanceService service = new AppearanceService();

    /** 一把单手剑（classItem=4 = RHAND，见 `ItemClass`）：idCode 借 0x0101_0000 段（WA1） */
    private static ItemList sword() {
        ItemList def = new ItemList();
        def.setIdCode(0x01010100);
        def.setCodeImg1("WA101");
        def.setClassItem(4);
        def.setModelPosition(4);
        def.setCategory("Swords");
        return def;
    }

    /** 一面盾（classItem=2 = LHAND + folder=defense ⇒ `offHandKind`=1） */
    private static ItemList shield() {
        ItemList def = new ItemList();
        def.setIdCode(0x04010100);
        def.setCodeImg1("DS101");
        def.setClassItem(2);
        def.setCategory("Shields");
        return def;
    }

    /** 念珠/法球：classItem=2（副手位）但 category 不是 Shields/Dagger ⇒ **不挂模型** */
    private static ItemList orb() {
        ItemList def = new ItemList();
        def.setIdCode(0x03030100);
        def.setCodeImg1("OM101");
        def.setClassItem(2);
        def.setCategory("Orbs");
        return def;
    }

    @Test
    void 锻造等级与合成标记进入外观的发光字段() {
        // 主手：锻造物（ItemKindCode=2）+ 等级 12
        CharacterAppearance a = service.derive(1, 0, 0, List.of(
            new AppearanceService.EquipEntry(ItemLocations.SLOT_MAIN_HAND, sword(), 2, 12),
            new AppearanceService.EquipEntry(ItemLocations.SLOT_OFF_HAND, shield(), 1, 0)));

        assertEquals("WA101", a.getWeaponDorp());
        assertEquals(2, a.getWeaponKindCode(), "主手 ItemKindCode 应原样进入外观");
        assertEquals(12, a.getWeaponAgingLevel(), "主手 ItemAgingNum[0] 应原样进入外观");
        // 合成物：ItemKindCode=1、ItemAgingNum[0] = **材料槽+1**（原版 `sinTrade.cpp:5008`，不是等级）
        assertEquals(1, a.getOffHandKindCode());
        assertEquals(0, a.getOffHandAgingLevel());
        assertEquals(1, a.getOffHandKind(), "盾仍按老口径挂副手");
    }

    @Test
    void 副手不挂模型时发光字段保持0() {
        CharacterAppearance a = service.derive(1, 0, 0, List.of(
            new AppearanceService.EquipEntry(ItemLocations.SLOT_OFF_HAND, orb(), 2, 9)));

        assertEquals(0, a.getOffHandKind(), "念珠不挂载（AppearanceService.offHandKind 的老口径）");
        assertEquals(0, a.getOffHandKindCode(), "没挂模型就不该带发光输入（否则客户端会去找一件不存在的模型）");
        assertEquals(0, a.getOffHandAgingLevel());
    }

    @Test
    void 只有锻造等级变化时外观也判为变了() {
        CharacterAppearance before = service.derive(1, 0, 0, List.of(
            new AppearanceService.EquipEntry(ItemLocations.SLOT_MAIN_HAND, sword(), 2, 11)));
        CharacterAppearance after = service.derive(1, 0, 0, List.of(
            new AppearanceService.EquipEntry(ItemLocations.SLOT_MAIN_HAND, sword(), 2, 12)));

        assertNotEquals(before, after,
            "锻造 +1 只改 agingNum：外观必须判为**变了**，否则 recalcAndBroadcast 不广播、旁观者看不到换色");
        // 反向：一切相同 ⇒ 不广播（整理背包/拿起放下这些入口高频调用 derive，别制造无谓广播）
        assertEquals(before, service.derive(1, 0, 0, List.of(
            new AppearanceService.EquipEntry(ItemLocations.SLOT_MAIN_HAND, sword(), 2, 11))));
    }

    @Test
    void 非武器占主手时不产生武器发光字段() {
        ItemList ring = new ItemList();
        ring.setIdCode(0x06010100);
        ring.setCodeImg1("OR101");
        ring.setClassItem(192);          // LRING|RRING：不是武器
        CharacterAppearance a = service.derive(1, 0, 0, List.of(
            new AppearanceService.EquipEntry(ItemLocations.SLOT_MAIN_HAND, ring, 2, 7)));

        assertEquals(0, a.getWeaponIdcode());
        assertEquals(0, a.getWeaponKindCode(), "槽位里有东西 ≠ 主手有武器（只看 ItemClass.isWeapon）");
        assertEquals(0, a.getWeaponAgingLevel());
    }
}
