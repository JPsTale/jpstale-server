package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.game.item.ItemInstance;
import org.jpstale.server.game.item.ItemLocations;
import org.jpstale.server.game.item.PlayerItems;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.proto.base.CommonProto;
import org.springframework.stereotype.Service;

/**
 * 角色外观计算（装备→3D 模型挂载）。
 * <p>
 * 从玩家已装备掷点实例（location=2 装备栏 + location=6 备用武器）推导：
 * 武器（classItem 4/6）→ weaponDorp/weaponIdcode/weaponPos；防具（8）→ bodyModel/bodyModelIdcode。
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
        String bodyModel = null;
        Integer bodyIdcode = 0;

        PlayerItems items = p.getItems();
        if (items != null) {
            // 装备栏优先；若主手未装备再查备用武器（W 切到的那套穿在身上）
            for (int loc : new int[]{ItemLocations.EQUIP, ItemLocations.BACKUP_WEAPON}) {
                for (ItemInstance it : items.itemsIn(loc)) {
                    if (it.isDeleted()) {
                        continue;
                    }
                    ItemList def = it.getTemplate();
                    if (def == null) {
                        continue;
                    }
                    Integer c = def.getClassItem();
                    // 主手(槽1)武器才决定外观；双手(6)/单手(4)
                    if ((c != null && (c == 4 || c == 6)) && it.getSlot() == ItemLocations.SLOT_MAIN_HAND) {
                        weaponDorp = def.getCodeImg1();
                        weaponIdcode = def.getIdCode();
                        weaponPos = def.getModelPosition();
                    } else if (c != null && c == 8) {
                        // 防具（铠甲/法袍）
                        bodyModel = def.getCodeImg1();
                        bodyIdcode = def.getIdCode();
                    }
                }
                if (weaponDorp != null) {
                    break; // 装备栏已找到武器即可
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

        CommonProto.CharacterAppearance app = b.build();
        p.setAppearance(app);
        return app;
    }
}
