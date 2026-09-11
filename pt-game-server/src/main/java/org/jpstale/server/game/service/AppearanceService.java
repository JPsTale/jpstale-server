package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.item.ItemClass;
import org.jpstale.server.game.item.ItemInstance;
import org.jpstale.server.game.item.ItemLocations;
import org.jpstale.server.game.item.PlayerItems;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.proto.base.CommonProto;
import org.springframework.stereotype.Service;

/**
 * 角色外观计算（装备→3D 模型挂载）。
 * <p>
 * 从玩家已装备掷点实例（location=EQUIP 装备栏 + location=BACKUP_EQUIP 副装备栏）推导：
 * 武器（{@link ItemClass#isWeapon}）→ weaponDorp/weaponIdcode/weaponPos；躯干甲（{@link ItemClass#isTorsoArmor}）→ bodyModel/bodyModelIdcode。
 * 计算结果写回 {@link Player#getAppearance()}，供 AOI Appear / 外观更新广播使用。
 */
@Slf4j
@Service
public class AppearanceService {

    /**
     * 在线重算外观（装备变化后调用）：从 Player.items 计算并缓存到 Player.appearance。
     */
    public CommonProto.CharacterAppearance recalc(Player p) {
        CommonProto.CharacterAppearance.Builder b = CommonProto.CharacterAppearance.newBuilder()
            .setClassId(p.getJob())
            .setHead(p.getHead())
            .setRank(p.getRank());

        String weaponDorp = null;
        Integer weaponIdcode = 0;
        Integer weaponPos = 0;
        String offDorp = null;
        int offIdcode = 0;
        int offKind = 0;
        int offPos = 0;
        String bodyModel = null;
        Integer bodyIdcode = 0;

        PlayerItems items = p.getItems();
        if (items != null) {
            // 当前装备套固定在 EQUIP(location=0)：W 交换后当前套总在 EQUIP。主手+副手+身体都从这里读。
            for (ItemInstance it : items.itemsIn(ItemLocations.EQUIP)) {
                if (it.isDeleted()) {
                    continue;
                }
                ItemList def = it.getTemplate();
                if (def == null) {
                    continue;
                }
                Integer c = def.getClassItem();
                if (c != null && ItemClass.isWeapon(c) && it.getSlot() == ItemLocations.SLOT_MAIN_HAND) {
                    // 主手(槽1)武器决定主手外观；双手(6)/单手(4)
                    weaponDorp = def.getCodeImg1();
                    weaponIdcode = def.getIdCode();
                    weaponPos = def.getModelPosition();
                } else if (it.getSlot() == ItemLocations.SLOT_OFF_HAND) {
                    // 副手(槽2)：盾(Shields)/匕首(Dagger)；念珠/法球(Orbs) 不挂
                    int kind = offHandKind(def);
                    if (kind != 0) {
                        offDorp = def.getCodeImg1();
                        offIdcode = def.getIdCode();
                        offKind = kind;
                        offPos = 2;
                    }
                } else if (c != null && ItemClass.isTorsoArmor(c)) {
                    // 防具（铠甲/法袍）
                    bodyModel = def.getCodeImg1();
                    bodyIdcode = def.getIdCode();
                }
            }
        }

        if (bodyModel != null) {
            b.setBodyModel(bodyModel);
        }
        if (bodyIdcode != null && bodyIdcode != 0) {
            b.setBodyModelIdcode(bodyIdcode);
        }
        if (weaponDorp != null) {
            b.setWeaponDorp(weaponDorp);
        }
        if (weaponIdcode != null && weaponIdcode != 0) {
            b.setWeaponIdcode(weaponIdcode);
        }
        b.setWeaponPos(weaponPos != null ? weaponPos : 0);
        if (offDorp != null) {
            b.setOffHandDorp(offDorp);
        }
        if (offIdcode != 0) {
            b.setOffHandIdcode(offIdcode);
        }
        b.setOffHandKind(offKind);
        b.setOffHandPos(offPos);

        CommonProto.CharacterAppearance app = b.build();
        p.setAppearance(app);
        return app;
    }

    /**
     * 副手类型：0=无(不挂载) 1=盾 2=匕首。
     * 用模板 category 判定（Shields/Dagger；Orbs/Force Orbs 等不挂载）——比 codeimg1 前缀可靠。
     */
    private int offHandKind(ItemList def) {
        String nc = def.getCategory();
        if (nc == null) {
            return 0;
        }
        if (nc.equalsIgnoreCase("Shields")) {
            return 1;
        }
        if (nc.equalsIgnoreCase("Dagger")) {
            return 2;
        }
        return 0;
    }
}
