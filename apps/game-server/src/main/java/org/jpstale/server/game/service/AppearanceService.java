package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.ItemClass;
import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.item.PlayerItems;
import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.common.model.CharacterAppearance;
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
     *
     * 返回的是**纯模型** {@link CharacterAppearance}（不碰 protobuf）；上线前由
     * `AppearanceCodec.toProto` 转一次。字符串一律归一成空串、数值归一成 0 ——
     * 与旧版"proto3 未设置字段的默认值"等价，保证 `equals` 判"外观是否真变了"仍然准确。
     */
    public CharacterAppearance derive(int classId, int head, int rank, List<EquipEntry> equips) {
        CharacterAppearance a = new CharacterAppearance();
        a.setClassId(classId);
        a.setHead(head);
        a.setRank(rank);

        String weaponDorp = "";
        int weaponIdcode = 0;
        int weaponPos = 0;
        String offDorp = "";
        int offIdcode = 0;
        int offKind = 0;
        int offPos = 0;
        String bodyModel = "";
        int bodyIdcode = 0;

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
                    weaponDorp = nz(def.getCodeImg1());
                    weaponIdcode = nz(def.getIdCode());
                    weaponPos = nz(def.getModelPosition());
                } else if (slot == ItemLocations.SLOT_OFF_HAND) {
                    // 副手(槽2)：盾(Shields)/匕首(Dagger)；念珠/法球等不挂
                    int kind = offHandKind(def);
                    if (kind != 0) {
                        offDorp = nz(def.getCodeImg1());
                        offIdcode = nz(def.getIdCode());
                        offKind = kind;
                        offPos = 2;
                    }
                } else if (c != null && ItemClass.isTorsoArmor(c)) {
                    // 防具（铠甲/法袍）
                    bodyModel = nz(def.getCodeImg1());
                    bodyIdcode = nz(def.getIdCode());
                }
            }
        }

        a.setBodyModel(bodyModel);
        a.setBodyModelIdcode(bodyIdcode);
        a.setWeaponDorp(weaponDorp);
        a.setWeaponIdcode(weaponIdcode);
        a.setWeaponPos(weaponPos);
        a.setOffHandDorp(offDorp);
        a.setOffHandIdcode(offIdcode);
        a.setOffHandKind(offKind);
        a.setOffHandPos(offPos);
        return a;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 在线重算外观（装备变化后调用）：从 Player.items 计算并缓存到 Player.appearance。
     */
    public CharacterAppearance recalc(Player p) {
        List<EquipEntry> equips = new ArrayList<>();
        PlayerItems items = p.getItems();
        if (items != null) {
            // 当前装备套固定在 EQUIP(location=0)：W 交换后当前套总在 EQUIP。主手+副手+身体都从这里读。
            for (ItemInstance it : items.equippedItems()) {   // 排除鼠标位：手上那件不驱动外观（原版拿起即 sinSetCharItem FALSE）
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
        CharacterAppearance app = derive(p.getJob(), p.getHead(), p.getRank(), equips);
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
