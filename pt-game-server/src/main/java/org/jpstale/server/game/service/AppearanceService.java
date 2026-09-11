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

import java.util.ArrayList;
import java.util.List;

/**
 * 角色外观计算（装备→3D 模型挂载）。
 * <p>
 * 唯一权威推导 {@link #derive(int, int, int, List)}：输入装备条目（槽位 + itemlist 定义），
 * 登录/选角（读 userdb.item）与在线换装（读内存 {@link PlayerItems}）共用，避免两套逻辑漂移。
 * <ul>
 *   <li>主手(slot 1)武器（单手/双手）→ weaponDorp/weaponIdcode/weaponPos</li>
 *   <li>副手(slot 2)盾/匕首 → offHandDorp/offHandIdcode/offHandKind/offHandPos=2</li>
 *   <li>躯干甲（classItem=ARMOR）→ bodyModel/bodyModelIdcode</li>
 * </ul>
 */
@Slf4j
@Service
public class AppearanceService {

    /** 装备条目：装备槽号 + 物品定义（登录读 DB / 在线读内存共用）。 */
    public record EquipEntry(int slot, ItemList def) {
    }

    /**
     * 从装备条目推导外观（唯一权威实现）。
     */
    public CommonProto.CharacterAppearance derive(int classId, int head, int rank, List<EquipEntry> equips) {
        CommonProto.CharacterAppearance.Builder b = CommonProto.CharacterAppearance.newBuilder()
                .setClassId(classId)
                .setHead(head)
                .setRank(rank);

        String weaponDorp = null;
        Integer weaponIdcode = 0;
        Integer weaponPos = 0;
        String offDorp = null;
        int offIdcode = 0;
        int offKind = 0;
        int offPos = 0;
        String bodyModel = null;
        Integer bodyIdcode = 0;

        if (equips != null) {
            for (EquipEntry e : equips) {
                if (e == null || e.def() == null) {
                    continue;
                }
                ItemList def = e.def();
                int slot = e.slot();
                Integer c = def.getClassItem();

                if (slot == ItemLocations.SLOT_MAIN_HAND && c != null && ItemClass.isWeapon(c)) {
                    // 主手(槽1)武器决定主手外观；双手(6)/单手(4)
                    weaponDorp = def.getCodeImg1();
                    weaponIdcode = def.getIdCode();
                    weaponPos = def.getModelPosition();
                } else if (slot == ItemLocations.SLOT_OFF_HAND) {
                    // 副手(槽2)：盾(Shields)/匕首(Dagger)；念珠/法球等不挂
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

        return b.build();
    }

    /**
     * 在线重算外观（装备变化后调用）：从 Player.items 计算并缓存到 Player.appearance。
     */
    public CommonProto.CharacterAppearance recalc(Player p) {
        List<EquipEntry> equips = new ArrayList<>();
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
                equips.add(new EquipEntry(it.getSlot(), def));
            }
        }
        CommonProto.CharacterAppearance app = derive(p.getJob(), p.getHead(), p.getRank(), equips);
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
