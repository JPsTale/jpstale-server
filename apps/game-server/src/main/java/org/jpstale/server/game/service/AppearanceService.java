package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.ItemClass;
import org.jpstale.common.service.item.ItemInstance;
import org.jpstale.common.service.item.ItemLocations;
import org.jpstale.common.service.item.PlayerItems;
import org.jpstale.common.service.model.Player;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.server.common.model.CharacterAppearance;
import org.jpstale.server.game.entity.PlayerEntity;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 取玩家实体（广播要）。**字段注入**，不进构造函数：`derive` 是纯函数（只吃槽位+定义），
     * 单测可以 `new AppearanceService()` 直接调（见 `AppearanceServiceTest`）；
     * 把这两个依赖塞进构造函数会让每个调用方（登录链路/物品处理器）都多一个必须就绪的前置。
     */
    @Autowired
    private PlayerService playerService;

    @Autowired
    private AOIManager aoiManager;

    /**
     * 装备条目：装备槽号 + 物品定义 + **实例上的两列发光输入**（登录读 DB / 在线读内存共用）。
     *
     * <p>{@code kindCode}/{@code agingNum} 来自物品**实例**（`userdb.item.kind_code` / `aging_num`）
     * 而不是 `gamedb.itemlist` —— 锻造等级是每件物品各自的（`ItemKindCode`/`ItemAgingNum[0]`）。
     */
    public record EquipEntry(int slot, ItemList def, int kindCode, int agingNum) {
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
        int weaponKind = 0;
        int weaponAging = 0;
        String offDorp = "";
        int offIdcode = 0;
        int offKind = 0;
        int offPos = 0;
        int offItemKind = 0;
        int offAging = 0;
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
                    weaponKind = e.kindCode();
                    weaponAging = e.agingNum();
                } else if (slot == ItemLocations.SLOT_OFF_HAND) {
                    // 副手(槽2)：盾(Shields)/匕首(Dagger)；念珠/法球等不挂
                    int kind = offHandKind(def);
                    if (kind != 0) {
                        offDorp = nz(def.getCodeImg1());
                        offIdcode = nz(def.getIdCode());
                        offKind = kind;
                        offPos = 2;
                        // 发光输入只在**真的挂了模型**时才带（与客户端"没模型就不发光"一致）
                        offItemKind = e.kindCode();
                        offAging = e.agingNum();
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
        a.setWeaponKindCode(weaponKind);
        a.setWeaponAgingLevel(weaponAging);
        a.setOffHandDorp(offDorp);
        a.setOffHandIdcode(offIdcode);
        a.setOffHandKind(offKind);
        a.setOffHandPos(offPos);
        a.setOffHandKindCode(offItemKind);
        a.setOffHandAgingLevel(offAging);
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
                equips.add(new EquipEntry(it.getSlot(), def, it.getKindCode(), it.getAgingNum()));
            }
        }
        CharacterAppearance app = derive(p.getJob(), p.getHead(), p.getRank(), equips);
        p.setAppearance(app);
        return app;
    }

    /**
     * 外观重算 + **只在真的变了才**广播（自机一份 + 视野内玩家各一份）—— **唯一实现**。
     *
     * <p>为什么要收成一处：判据"什么算变了"是 {@link CharacterAppearance#equals}（逐字段值比较），
     * 而 {@link #recalc} 会被十来个物品入口调用（含整理背包/拿起/拾取/丢弃）—— 无条件广播会让
     * 客户端每次都重建模型并重播动画（用户 2026-09-16 实测"整理背包角色动画重播"）。
     * 锻造升级/合成完成也走这里：**呼吸发光的四个输入（kindCode/agingNum × 主手/副手）是外观的一部分**，
     * 所以它们变了就同样会广播（在那之前，外观比不出差异 ⇒ 旁观者只能等本人再动一次背包才看到）。
     *
     * <p>⚠ 调用点的口径：**装备/锻造/合成三条链路**都调它，别在各自的处理器里另写一份判空。
     *
     * @return 是否真的广播了（调用方一般无需关心；返回它是为了日志与测试能断言）
     */
    public boolean recalcAndBroadcast(Player p) {
        if (p == null || p.getItems() == null) {
            return false;
        }
        CharacterAppearance before = p.getAppearance();
        CharacterAppearance app = recalc(p);
        PlayerEntity entity = playerService == null ? null : playerService.entityOf(p);
        if (entity == null || app.equals(before)) {
            return false;
        }
        aoiManager.broadcastAppearance(entity, app);
        return true;
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
