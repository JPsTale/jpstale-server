package org.jpstale.server.game.service;

import static org.junit.Assert.assertEquals;

import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.item.ItemClass;
import org.jpstale.server.game.item.ItemLocations;
import org.jpstale.server.proto.base.CommonProto;
import org.junit.Test;

import java.util.List;

/**
 * 外观推导：主手/副手/防具区分（登录与在线换装共用 derive）。
 */
public class AppearanceServiceTest {

    private final AppearanceService svc = new AppearanceService();

    private static ItemList def(int classItem, String codeImg1, int idCode, String category) {
        ItemList d = new ItemList();
        d.setClassItem(classItem);
        d.setCodeImg1(codeImg1);
        d.setIdCode(idCode);
        d.setCategory(category);
        d.setModelPosition(0);
        return d;
    }

    @Test
    public void offHandDaggerDoesNotOverrideMainHandWeapon() {
        ItemList sword = def(ItemClass.ONE_HAND_WEAPON, "wa105", 16844032, "Axes");
        ItemList dagger = def(ItemClass.OFF_HAND, "wd122", 111, "Dagger");
        ItemList armor = def(ItemClass.ARMOR, "da105", 33621248, "Armors");
        CommonProto.CharacterAppearance app = svc.derive(1, 0, 0, List.of(
                new AppearanceService.EquipEntry(ItemLocations.SLOT_MAIN_HAND, sword),
                new AppearanceService.EquipEntry(ItemLocations.SLOT_OFF_HAND, dagger),
                new AppearanceService.EquipEntry(ItemLocations.SLOT_ARMOR, armor)));
        assertEquals("wa105", app.getWeaponDorp());
        assertEquals("wd122", app.getOffHandDorp());
        assertEquals(2, app.getOffHandKind());
        assertEquals("da105", app.getBodyModel());
    }

    @Test
    public void offHandShieldKind() {
        ItemList shield = def(ItemClass.OFF_HAND, "ws106", 222, "Shields");
        CommonProto.CharacterAppearance app = svc.derive(1, 0, 0,
                List.of(new AppearanceService.EquipEntry(ItemLocations.SLOT_OFF_HAND, shield)));
        assertEquals("ws106", app.getOffHandDorp());
        assertEquals(1, app.getOffHandKind());
    }
}
