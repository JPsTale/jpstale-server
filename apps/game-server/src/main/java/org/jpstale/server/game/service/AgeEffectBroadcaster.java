package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.AgeService;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.item.ItemProtos;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.S2C_ItemUpdate;
import org.jpstale.server.game.network.MessageSender;
import org.jpstale.server.proto.base.S2C_AgeUpBroadcast;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * **锻造成功的广播**（原版 `smCOMMNAD_USER_AGINGUP`）—— 附近的人都播 aging 特效 + 升级音。
 *
 * <p>依据（ex-machina `netplay.cpp:7290-7298`）：
 * <pre>
 *   case smCOMMNAD_USER_AGINGUP:
 *       lpChar = FindChrPlayer(WxParam);
 *       StartEffect(lpChar-&gt;pX, pY, pZ, EFFECT_AGING);
 *       esPlaySound(7, GetDistVolume(...), 1600);
 * </pre>
 * 其中 `EFFECT_AGING`(5001) = `SetDynLight(255,255,255,255,200,1)` + `g_NewParticleMgr.Start("aging", pos)`
 * 且 `pos.y += 10000`（`HoEffect.cpp:5979`）；音效编号 7 与**升级**是同一记音（所以客户端复用 `playLevelUp`）。
 *
 * <p><b>只有升级才播</b>：源码里这条命令只在晋升时发；降级/破坏没有对应命令（破坏另有一记播报）。
 * 广播半径与升级一样取 {@link AOIManager#VIEW_RANGE} —— 同一条"看得见才播"的口径。
 *
 * <p>放在这一个小类里而不是散在四个调用点：锻造结果有 4 个入口
 * （投石 `handleAgeItem`、拉满石 `handleUseItem`、战斗养 `CombatService`/`AiEngine`），
 * 各写一份广播会漂移（AGENTS #15）。
 */
@Slf4j
@Service
public class AgeEffectBroadcaster {

    @Autowired
    private MessageSender messageSender;

    @Autowired
    private PlayerService playerService;

    /** 外观重算 + 广播（呼吸发光的色表行随锻造等级变，见 `broadcastAgingUp` 的说明） */
    @Autowired
    private AppearanceService appearanceService;

    /**
     * 若这次锻造**升了级**，就广播特效；否则什么都不做（不静默吞异常，只是没有事件可播）。
     *
     * @param p 装备的主人
     * @param r {@code AgeService} 的返回（可为 null —— 战斗路径没触发进度时就是 null）
     */
    /** 熟练度**练满**的广播（区域）：附近的人都播 aging 特效 + 升级音（原版 `smCOMMNAD_USER_AGINGUP`）。 */
    public void broadcastAgingUp(Player p, AgeService.Result r) {
        if (p == null || r == null || !r.ok() || r.broke || r.roll == null || r.roll.levelDelta() <= 0) {
            return;
        }
        // ★ **呼吸发光也是外观的一部分**（色表行由 `kindCode`/`agingNum` 定，见
        //   `AppearanceService.derive` 的四个字段）⇒ 升级后重算并广播，否则旁观者要等
        //   本人再动一次背包才看到光换了颜色（原版是随装备数据立刻反映）。
        //   判据与广播都在那一个方法里（`CharacterAppearance.equals`），别在这里另写。
        appearanceService.recalcAndBroadcast(p);
        long uid = r.target == null ? 0L : r.target.getId();
        PlayerEntity ent = playerService.entityOf(p);
        if (ent == null) {
            log.info("[Age] {} 锻造升级到 +{}，但找不到实体（离线？）⇒ 不广播特效", p.getName(),
                    r.target == null ? -1 : r.target.getAgingNum());
            return;
        }
        log.info("[Age] {} 的 uid={} 熟练度练满 → 广播 aging 特效",
                p.getName(), r.target == null ? 0 : r.target.getId());
        messageSender.broadcastToArea(ent.getMapId(), (float) ent.getX(), (float) ent.getZ(), AOIManager.VIEW_RANGE,
                ServerMessage.newBuilder()
                        .setAgeUpBroadcast(S2C_AgeUpBroadcast.newBuilder()
                                .setPlayerId(p.getCharacterId())
                                .setUid(uid)
                                .setLevel(r.target == null ? 0 : r.target.getAgingNum())
                                .build())
                        .build());
        log.info("[Age] {} 锻造升级到 +{}（uid={}）→ 已广播特效", p.getName(),
                r.target == null ? -1 : r.target.getAgingNum(), uid);
    }
    /**
     * **战斗养**出结果后的收尾（战斗路径专用；投石/拉满石那两条在 `ItemNetworkHandler` 里已有自己的收尾）。
     *
     * <p>为什么必须有：战斗养打的是**装备中**的物品，而锻造升级会让需求等级 +1（每 2 级、
     * EU `GetLevelItemEachAge`）。一旦玩家不再满足需求，`EquipSummary` 会把这份装备的加成
     * **整件剔除**（原版 `NotUseFlag → continue`，`sinInvenTory.cpp:7355`）——
     * 玩家属性就该**掉下来**，血/蓝/耐还可能**超过新上限**。所以这里必须：
     * ① 重算面板（`recalcPanel` 内含夹血/蓝/耐）② 把物品推给客户端（否则工具提示还显示旧的 +N 与需求）
     * ③ 推一次状态（面板/HUD 显示新的上限）。
     *
     * ⚠ 顺带也解释了"为什么属性要在读时算"：这件装备**没有**被任何代码改过属性，
     * 只是它的锻造等级变了、需求不再满足 —— 若属性是烘焙存下来的，这里就会出现
     * "穿不上了但加成还在"（原版那种反向抵消写法根本管不到这一路）。
     */
    public void wrapUpBattleAging(Player p, AgeService.Result r) {
        if (p == null || r == null || !r.ok()) {
            return;
        }
        // ★ 判据只有一个：**`ageNum` 上升**时广播（原版 `smCOMMNAD_USER_AGINGUP`）。
        //   而"熟练度满"正是 `ageNum` 上升的那一刻（用户 2026-09-22 澄清：
        //   "它本质上就是 ageNum up 时的粒子，因为熟练度满时才会 ageNum up"）——
        //   所以这里不判"满没满"，只判"等级有没有 +1"，两条路共用同一条规则。
        if (r.roll != null && r.roll.levelDelta() > 0) {
            broadcastAgingUp(p, r);
        }
        if (r.roll == null) {
            // 只是累积了进度（没掷点）：把物品推给客户端，进度条才会**走**
            PlayerSession ss = playerService.sessionOf(p);
            if (ss != null && r.target != null) {
                ss.send(ServerMessage.newBuilder()
                        .setItemUpdate(S2C_ItemUpdate.newBuilder()
                                .setItem(ItemProtos.toProto(r.target)).build())
                        .build());
            }
            return;
        }
        playerService.recalcPanel(p);           // 重算上限 + 夹住血/蓝/耐（需求不满足 ⇒ 属性下降）
        PlayerSession s = playerService.sessionOf(p);
        if (s == null) {
            log.info("[Age] {} 战斗养出结果，但拿不到会话（离线？）⇒ 只重算了内存属性", p.getName());
            return;
        }
        if (r.target != null) {
            s.send(ServerMessage.newBuilder()
                    .setItemUpdate(S2C_ItemUpdate.newBuilder()
                            .setItem(ItemProtos.toProto(r.target))
                            .build())
                    .build());
        }
        playerService.sendPlayerStatus(s, p);
    }
}
