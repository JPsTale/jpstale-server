package org.jpstale.server.game.item;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.*;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.game.entity.GroundItem;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.common.service.item.ItemStat;
import org.jpstale.server.proto.base.*;
import org.springframework.stereotype.Component;

/**
 * 物品网络入口：进图下发背包快照 + C2S 背包/装备/丢弃操作。
 * <p>
 * 所有操作走 {@link ItemService}（服务端权威：位图校验 + DB 事务），
 * 成功后向客户端推送增量（S2C_ItemUpdate / ItemRemove）+ 属性重算推送。
 */
@Slf4j
@Component
public class ItemNetworkHandler {
    private final org.jpstale.server.game.network.MessageSender messageSender;
    private final org.jpstale.server.game.service.GoldService goldService;


    private final ItemService itemService;
    private final PlayerService playerService;
    private final org.jpstale.server.game.service.AppearanceService appearanceService;
    private final org.jpstale.server.game.service.AOIManager aoiManager;
    private final GroundItemManager groundItems;
    private final org.jpstale.server.game.service.TeleportService teleportService;
    private final org.jpstale.server.game.service.MapManager mapManager;
    /** 锻造：投石判定 + 一键拉满（熟练度道具走**使用道具**这条链，目标由服务端自己找）。 */
    private final org.jpstale.common.service.item.AgeService ageService;
    /** 力量石：吃 buff（走 USE）+ 力量大师转化。 */
    private final org.jpstale.common.service.item.ForceOrbService forceOrbService;
    private final org.jpstale.common.service.skill.SkillMasteryService skillMasteryService;
    private final org.jpstale.server.game.service.SkillPointService skillPoints;
    /** 合成：配方匹配 + 效果应用（走 C2S_MixItem）。 */
    private final org.jpstale.common.service.item.MixService mixService;
    /** buff 状态的唯一生产者（见 `BuffStateService`）。 */
    private final org.jpstale.server.game.service.BuffStateService buffStateService;
    /** 锻造升级的广播（原版 `smCOMMNAD_USER_AGINGUP`） */
    private final org.jpstale.server.game.service.AgeEffectBroadcaster ageEffectBroadcaster;
    /** 怪物水晶：召唤体解析 + 落点 + 归属（`CrystalService` 只管"哪颗水晶召哪只怪"这张表）。 */
    private final org.jpstale.server.game.service.SummonService summonService;


    public ItemNetworkHandler(ItemService itemService, PlayerService playerService,
                              org.jpstale.server.game.service.AppearanceService appearanceService,
                              org.jpstale.server.game.service.AOIManager aoiManager,
                              GroundItemManager groundItems,
                              org.jpstale.server.game.service.TeleportService teleportService,
                              org.jpstale.server.game.service.MapManager mapManager,
                              org.jpstale.server.game.network.MessageSender messageSender,
                              org.jpstale.server.game.service.GoldService goldService,
                              org.jpstale.common.service.item.AgeService ageService,
                              org.jpstale.common.service.item.ForceOrbService forceOrbService,
                              org.jpstale.common.service.item.MixService mixService,
                              org.jpstale.server.game.service.BuffStateService buffStateService,
                              org.jpstale.server.game.service.AgeEffectBroadcaster ageEffectBroadcaster,
                              org.jpstale.server.game.service.SummonService summonService,
                              org.jpstale.common.service.skill.SkillMasteryService skillMasteryService,
                              org.jpstale.server.game.service.SkillPointService skillPoints) {
        this.itemService = itemService;
        this.playerService = playerService;
        this.appearanceService = appearanceService;
        this.aoiManager = aoiManager;
        this.groundItems = groundItems;
        this.teleportService = teleportService;
        this.mapManager = mapManager;
        this.messageSender = messageSender;
        this.goldService = goldService;
        this.ageService = ageService;
        this.forceOrbService = forceOrbService;
        this.mixService = mixService;
        this.buffStateService = buffStateService;
        this.ageEffectBroadcaster = ageEffectBroadcaster;
        this.summonService = summonService;
        this.skillMasteryService = skillMasteryService;
        this.skillPoints = skillPoints;
    }

    // ------------------------------------------------------------------
    // 消耗品使用（背包右键 / 药水槽快捷键，同一入口）
    // ------------------------------------------------------------------

    /** idcode 家族（原版 `sinITEM_MASK2` 口径）：高 16 位 */
    private static int familyOf(int idCode) {
        return (idCode >>> 16) & 0xFFFF;
    }

    /** 药水族（`sinPL1 = 0x0401`，PL101/PL102… 生命/魔法/体力药水） */
    private static final int FAMILY_POTION = 0x0401;

    /**
     * `C2S_UseItem`：使用背包里的一个消耗品。客户端只报 **uid（+数量）**，其余全服务端判。
     *
     * 流程：查物品 → **按 idcode 家族分发**（对齐原版 `cINVENTORY::RButtonDown` 的
     * `CODE & sinITEM_MASK2` 分派）→ 能落地效果**才**扣道具 → 推送背包变化。
     *
     * 落地判据（按家族）：
     *   · 传送类（目的地表里有这个 idcode）→ `TeleportService`：先 `canTeleportTo`（含等级门槛），
     *     再解析落点，都通过才扣；任何一步不过 → **可见提示**且不扣道具
     *   · 药水族 → 回复 HP/MP/SP（数值待补：`items-11job.json` 目前没保留 `*생명력상승` 等列）
     *   · 其它家族 → 明确回"暂未实现"，**不静默、不错扣**
     */
    /** 客户端 STATE 枚举里的 EAT（`anim-state-machine.ts` 的 `EAT = 0x0140`） */
    private static final int ANIM_STATE_EAT = 0x0140;

    /**
     * 吃药冷却（毫秒）= 原版 `sinUsePotionDelayFlag` 的 **50 帧**（`sinInvenTory.cpp:794`
     * `dwUsePotionDelayTime > 50` 才清零），按原版 70Hz 主循环换算 ⇒ 50/70 ≈ 714ms。
     * 客户端 `WorldView.EAT_COOLDOWN_MS` 是同一个值（那边负责"动画不重播"，这边负责"不被绕"）。
     */
    private static final long USE_ITEM_COOLDOWN_MS = Math.round(50.0 / 70.0 * 1000);

    /**
     * 会进 EAT 的 idcode 家族 —— **必须与客户端 `useEffectKindOf` 的集合逐项一致**
     * （`jpstale-client/src/game/useEffect.ts`）。不一致的后果是
     * "自机没播、旁观者播了"（或反过来）——`npm run verify-useeffect` 会红。
     *
     * 原版对应两个入口：`sinActionPotion`（三种药水）与 `ActionEtherCore`（以太核心）。
     * 出处：`sinItem.h:75-82`（`sinPM1/sinPL1/sinPS1/sinEC1`）。
     */
    private static final java.util.Set<Integer> EAT_FAMILIES =
            java.util.Set.of(0x0401, 0x0402, 0x0403, 0x0601);

    /**
     * 使用道具 → 向 AOI 广播一次 EAT 动作 **+ 表现信息**（位置/朝向取当前实体，只作动作载体）。
     *
     * `use_item_idcode` 原样透传，旁观者用**与自机同一个** `useEffectKindOf` 推表现种类
     * （服务端不解释表现，与 `anim_index`/`anim_clip` 同一原则）。
     * `use_seq` 每次使用自增 → 旁观者据此去重（站着连喝两瓶时前两项完全相同）。
     */
    private void broadcastUseItem(PlayerSession session, Player p, long uid) {
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null) {
            return;
        }
        ItemInstance it = p.getItems().byUid(uid);
        if (it == null || it.getTemplate() == null) {
            return;
        }
        int idCode = it.getItemCode() != null ? it.getItemCode() : 0;
        if (!EAT_FAMILIES.contains(familyOf(idCode))) {
            return;
        }
        S2C_PlayerMove move = S2C_PlayerMove.newBuilder()
                .setPlayerId(p.getId())
                .setPosition(org.jpstale.server.proto.base.CommonProto.Position.newBuilder()
                        .setX((float) ent.getX()).setY((float) ent.getY()).setZ((float) ent.getZ()).build())
                .setAngle((float) ent.getAngle())
                .setAnimState(ANIM_STATE_EAT)
                .setTimestamp(System.currentTimeMillis())
                .setUseSeq(ent.nextUseSeq())
                .setUseItemIdcode(idCode)
                .build();
        messageSender.broadcastToArea(ent.getMapId(), (float) ent.getX(), (float) ent.getZ(), 50,
                ServerMessage.newBuilder().setPlayerMove(move).build());
    }

    @GamePacketHandler(ClientMessage.USE_ITEM_FIELD_NUMBER)
    public void handleUseItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        // 死亡躺下期间不能吃药/用消耗品（与攻击/技能同一判据：PlayerEntity.isDead → Player.dead）
        org.jpstale.server.game.entity.PlayerEntity deadEnt = session.getEntity();
        if (deadEnt != null && deadEnt.isDead()) {
            return;
        }
        C2S_UseItem req = message.getUseItem();
        ItemInstance it = p.getItems().byUid(req.getUid());
        // 可使用的位置：**背包**，以及**药水快捷槽**（ITEMSLOT 11/12/13）——
        // 后者就是"按数字键 1/2/3 吃药"的链路（docs/pt-core-gameplay.md 19 节）。
        if (it == null || it.isDeleted() || !isUsableLocation(it)) {
            sendErrorKey(session, "chat.cmd.useItemNotInBag");
            return;
        }
        // 锻造"熟练度道具"（一键拉满）：**走使用道具这条链**，目标由**服务端自己判断**（该类别的已装备件）——
        // 照原版 `UsePremiumItem(73/74/75)` → `UseAgingMaster(k)` → `sinCheckAgingLevel(kind, true)`，
        // 判据在服务端（`sinInvenTory.cpp:3296-3330`），客户端不需要传目标 uid。
        if (org.jpstale.common.service.item.AgeService.maxAgeKindOf(it.getItemCode()) >= 0) {
            var aged = ageService.useMaxAgeStone(p, req.getUid());
            ageEffectBroadcaster.broadcastAgingUp(p, aged);
            if (!aged.ok()) {
                sendErrorKey(session, aged.reason.key());
                return;
            }
            pushRemove(session, req.getUid());          // 石头被消耗
            if (aged.target != null) {
                pushUpdate(session, aged.target);       // 目标等级/属性变了
            }
            refreshPlayerStats(session, p);
            return;
        }
        // 技能熟练度石（**Skill Master 1st/2nd/3rd**，我们库里 idcode 0x080B3700/3800/3900）：
        // 一整档（4 个槽）的已学技能计数直接拉满 —— 原版 `UsePremiumItem(76/77/78)` → `UseSkillMaster(n)`
        // （`HaPremiumItem.cpp:1877`，入口与门槛见 `sinInvenTory.cpp:2425-2456` + `CheckMaturedSkill`）。
        // 判据（哪一档）**只由石头自己**决定，客户端不传目标；门槛不过时**不消耗**石头。
        if (org.jpstale.common.service.skill.SkillMasteryService.tierIndexOf(it.getItemCode()) > 0) {
            var sm = skillMasteryService.matureTier(p, req.getUid());
            if (!sm.ok()) {
                sendErrorKey(session, sm.reason().key());
                return;
            }
            pushRemove(session, req.getUid());              // 石头被消耗
            playerService.persistStats(p);                  // 计数落库（是 props）
            skillPoints.sendSkillTables(session, p);        // 面板熟练度条 + HUD 的 CD 立刻刷新
            return;
        }
        // 力量石：**吃 buff** —— 同样走 USE、由服务端判断（EU `netplay.cpp:2233` 的物品使用分支）
        if (org.jpstale.common.service.item.ForceOrb.tierIndexOf(it.getItemCode()) >= 0) {
            var orb = forceOrbService.activate(p, req.getUid());
            if (!orb.ok()) {
                sendErrorKey(session, orb.reason.key());
                return;
            }
            pushRemove(session, req.getUid());          // 力量石被消耗
            buffStateService.push(session, p);                  // buff 图标 UI 的数据来源（服务端权威）
            refreshPlayerStats(session, p);             // 攻击力面板/伤害立刻变（buff 已生效）
            return;
        }
        int qty = Math.max(1, req.getQuantity());
        int idCode = it.getItemCode() != null ? it.getItemCode() : 0;
        int family = familyOf(idCode);

        // ---- ⓪ 怪物水晶（`sinGP1` 的已实现档）：在主人身边召唤一只怪 ----
        //
        // 与原版**入口**的差别是有意的（见 docs/召唤物系统-源码分析.md §10.1 #3）：原版是
        // "丢到地上 + 把 `PotionCount` 改成 100 当协议标记"（`sinThrowItemToFeild`），
        // 本服按用户要求走**右键使用**这条链 —— 也就是这条 `C2S_UseItem`，不引入魔数标记。
        //
        // 所以这里也**不广播 EAT**（原版水晶没有吃药用动作，它是"丢出去"的），并且必须放在
        // 冷却与 `broadcastUseItem` **之前** —— 否则右键水晶会被那套 EAT 闸门吞掉（同一个坑在
        // 客户端侧也踩过一次：`useWithoutAnimation` 就是为它存在的）。
        CrystalService.CrystalDef crystal = CrystalService.defOf(idCode);
        if (crystal != null) {
            useCrystal(session, p, idCode, crystal, req.getUid());
            return;
        }

        // ---- 吃药冷却（先于任何副作用）----
        // 原版 `sinUsePotionDelayFlag`（`sinInvenTory.cpp:791-798`：50 帧 @70Hz ≈ 714ms）在**客户端**，
        // 但那层改包就能绕；"连按刷药"直接改战斗节奏，所以服务端同样挡一道。
        // 只对**会进 EAT 的两类**（药水/以太核心）生效 —— 与 `broadcastUseItem` 同一判据。
        if (EAT_FAMILIES.contains(family)) {
            long since = System.currentTimeMillis() - p.getLastUseItemAt();
            if (since < USE_ITEM_COOLDOWN_MS) {
                log.info("[UseItem] {} 距上次使用 {}ms < {}ms（原版 sinUsePotionDelayFlag 50 帧）→ 拒绝，未消耗",
                        p.getName(), since, USE_ITEM_COOLDOWN_MS);
                sendErrorKey(session, "chat.cmd.useItemTooFast");
                return;
            }
            p.setLastUseItemAt(System.currentTimeMillis());
        }

        // 使用**药水/以太核心**时把 EAT 动作 + 道具 idcode 广播给 AOI
        // （原版 sinActionPotion / ActionEtherCore → CHRMOTION_STATE_EAT）。
        // 收到请求即发：原版是点击瞬间本地切动作，服务端不等效果结算；
        // 旁观者按 anim_state 播自己那套 EAT 条目、按 use_item_idcode 推粒子/音
        // （服务端不解释表现，只透传"用了哪件"）。
        broadcastUseItem(session, p, req.getUid());

        // ---- ① 传送类：固定目的地写在 TeleportService 的代码表里（照原版 switch；不依赖 DB）----
        org.jpstale.server.game.service.TeleportService.ItemDestination dest = teleportService.destinationOf(idCode);
        if (dest != null) {
            org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
            if (ent == null) {
                return;
            }
            if (!teleportService.canTeleportTo(p, dest.destMap(), org.jpstale.server.game.service.TeleportService.Reason.ITEM)) {
                return;   // 门槛/目标非法：canTeleportTo 已给可见提示，且**没扣道具**
            }
            double[] pos = teleportService.resolveLanding(dest.destMap(), dest.landing(),
                    null, null, ent.getX(), ent.getZ());
            if (pos == null) {
                log.warn("[UseItem] {} idCode={} 目标图 {} 无可用落点（landing={}）→ 拒绝且不扣道具",
                        p.getName(), idCode, dest.destMap(), dest.landing());
                sendErrorKey(session, "chat.cmd.teleportNoLanding");
                return;
            }
            ItemInstance used = itemService.consumeAt(p, req.getUid(), qty);
            if (used == null) {
                sendErrorKey(session, "chat.cmd.useItemFailed");
                return;
            }
            pushAfterUse(session, used);
            boolean ok = teleportService.teleport(p, dest.destMap(), pos[0], pos[1],
                    org.jpstale.server.game.service.TeleportService.Reason.ITEM);
            log.info("[UseItem] {} idCode=0x{} → 传送 map {} ({},{}) ok={} 依据: {}",
                    p.getName(), Integer.toHexString(idCode), dest.destMap(),
                    (int) pos[0], (int) pos[1], ok, dest.note());
            return;
        }

        // ---- ② 回复类（药水）：数值就是 gamedb.itemlist 的 recovery* 三列（ItemList 已映射）----
        // 判据用**数据**（recovery 非零）而不是 idcode 族：`items-11job.json` 里 Life/Mana 的
        // idcode 家族与 DB 相反（见 docs/传送系统.md §6），按数据判就不会被那处冲突传染。
        int[] rec = rollRecovery(it.getTemplate());
        if (rec != null) {
            ItemInstance used = itemService.consumeAt(p, req.getUid(), qty);
            if (used == null) {
                sendErrorKey(session, "chat.cmd.useItemFailed");
                return;
            }
            pushAfterUse(session, used);
            applyRecovery(session, p, rec, it);
            return;
        }

        // ---- ②b 占药水槽但没有任何回复数值：说明是别的使用类（增益/力量石…）→ 可见拒绝 ----
        if (family == FAMILY_POTION || ItemClass.isPotion(it.getTemplate() != null ? it.getTemplate().getClassItem() : 0)) {
            sendErrorKey(session, "chat.cmd.useItemPotionPending");
            log.info("[UseItem] {} 药水槽物品 idCode={} 无 recovery 数值 → 视作未实现的增益类，未消耗",
                    p.getName(), idCode);
            return;
        }

        // ---- ③ 其它：明确回"暂未实现"，把 idcode 打进日志便于逐个补 ----
        sendErrorKey(session, "chat.cmd.useItemUnsupported");
        log.info("[UseItem] {} 未支持的消耗品 family=0x{} idCode={} name={}（未消耗）",
                p.getName(), Integer.toHexString(family), idCode,
                it.getTemplate() != null ? it.getTemplate().getName() : "?");
    }

    /**
     * 怪物水晶：**一颗水晶 → 一只召唤物**。
     *
     * <p>
     * 顺序遵循 `ItemService.consumeFromBag` 的约定："调用方先确认**效果能落地**，再扣" ——
     * 所以三条判据（等级、城市、模板）全部在扣之前返回，不需要"扣了再退"的回滚路径。
     *
     * <p>
     * 判据出处：
     * <ul>
     *   <li>**等级** = `gamedb.itemlist.reqlevel`（GP102=8 … GP113=80；对照表见源码分析 §1）。
     *       原版只在**客户端**拦这一道，而且是静默 `return`（`sinInvenTory.cpp:1272`），
     *       改包就能绕 ⇒ 服务端权威，并给可见提示（原版连提示都没有）；</li>
     *   <li>**城市禁召唤** = 原版 `lpField->State == FIELD_STATE_VILLAGE`（`OnSever.cpp:3595`
     *       里 `return FALSE`）—— 我们的对应判据就是既有的 `GameMap.isSafe()`
     *       （`maplist.typemap = 'Cities'`，实测 5 张：3/21/29/45/49）；</li>
     *   <li>**模板缺失** = 原版按名字比对失败后 `return FALSE`，水晶被**静默消耗**。这个失败模式
     *       我们**不复制**：回一条可见错误且**不扣物品**（另外 `SummonService.validateCrystalTable`
     *       会在启动时先喊一次）。</li>
     * </ul>
     */
    private void useCrystal(PlayerSession session, Player p, int idCode,
                            CrystalService.CrystalDef def, long uid) {
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null) {
            return;
        }
        ItemInstance it = p.getItems().byUid(uid);
        if (it == null || it.isDeleted()) {
            sendErrorKey(session, "chat.cmd.useItemNotInBag");
            return;
        }

        // ① 等级门
        int reqLevel = it.getReqLevel();
        if (p.getLevel() < reqLevel) {
            sendErrorKey(session, "item.op.crystal.level", java.util.Map.of("level", String.valueOf(reqLevel)));
            log.info("[UseItem] {} 等级 {} < {} → 不能使用水晶 0x{}（未消耗）",
                p.getName(), p.getLevel(), reqLevel, Integer.toHexString(idCode));
            return;
        }

        // ② 城市禁召唤（原版 FIELD_STATE_VILLAGE）
        org.jpstale.server.game.model.GameMap map = mapManager.getMap(ent.getMapId());
        if (map != null && map.isSafe()) {
            sendErrorKey(session, "item.op.crystal.town");
            log.info("[UseItem] {} 在城市图 {}（{}）试图召唤 → 拒绝，未消耗",
                p.getName(), ent.getMapId(), map.getName());
            return;
        }

        // ③ 掷出召唤体（神秘水晶按权重池抽；单怪水晶的池里只有一项）
        int roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(100);   // 原版 `rand() % 100`
        org.jpstale.dao.gamedb.entity.MonsterList template = summonService.resolveSummon(def, roll);
        if (template == null) {
            sendErrorKey(session, "item.op.crystal.noTemplate");
            return;   // resolveSummon 已把"缺哪个 id、依据是哪条"打进 error 日志
        }

        // ④ 到这里才扣：效果已经确定能落地
        ItemInstance used = itemService.consumeAt(p, uid, 1);
        if (used == null) {
            sendErrorKey(session, "chat.cmd.useItemFailed");
            return;
        }
        pushAfterUse(session, used);
        summonService.spawn(idCode, template, ent, p);
    }

    // ---------------- 回复类（药水）效果 ----------------

    /** 掷一次回复量 [hp, mp, stm]；三者全 0 → null（不是回复类，别乱扣） */
    private static int[] rollRecovery(org.jpstale.dao.gamedb.entity.ItemList t) {
        if (t == null) {
            return null;
        }
        int hp = roll(t.getRecoveryHpMin(), t.getRecoveryHpMax());
        int mp = roll(t.getRecoveryMpMin(), t.getRecoveryMpMax());
        int stm = roll(t.getRecoveryStmMin(), t.getRecoveryStmMax());
        return (hp == 0 && mp == 0 && stm == 0) ? null : new int[]{hp, mp, stm};
    }

    /** [min,max] 闭区间掷点（原版药水是区间随机；min==max 取该值；max<=0 → 0） */
    private static int roll(Integer min, Integer max) {
        int a = min == null ? 0 : min;
        int b = max == null ? 0 : max;
        if (b <= 0) return 0;
        if (a > b) a = b;
        if (a == b) return b;
        return a + java.util.concurrent.ThreadLocalRandom.current().nextInt(b - a + 1);
    }

    /** 应用到角色（clamp 到上限）→ 推权威状态刷 HUD。满值时不特殊处理（原版也照喝照扣） */
    private void applyRecovery(PlayerSession session, Player p, int[] rec, ItemInstance src) {
        int beforeHp = p.getHp();
        int beforeMp = p.getMp();
        if (rec[0] > 0) p.setHp(Math.min(p.getMaxHp(), p.getHp() + rec[0]));
        if (rec[1] > 0) p.setMp(Math.min(p.getMaxMp(), p.getMp() + rec[1]));
        if (rec[2] > 0) p.setSp(Math.min(p.getMaxSp(), p.getSp() + rec[2]));
        playerService.sendPlayerStatus(session, p);
        // 回复飘字：只报**实际**回复量（满值那一项是 0 → 不飘，免得"什么都没加却显示 +N"）。
        // 形状与 AiEngine 的 S2C_Damage 一致：AOI 广播 + 权威 current 值。
        int gainedHp = p.getHp() - beforeHp;
        int gainedMp = p.getMp() - beforeMp;
        if (gainedHp > 0 || gainedMp > 0) {
            broadcastRecovery(session, p, gainedHp, gainedMp);
        }
        log.info("[UseItem] {} 使用 {} → HP+{} MP+{} STM+{}（现 {}/{} {} {}）",
                p.getName(),
                src.getTemplate() != null ? src.getTemplate().getName() : "?",
                rec[0], rec[1], rec[2],
                p.getHp(), p.getMaxHp(), p.getMp(), p.getSp());
    }

    /**
     * 资源回复广播（HP/MP 各为 0 表示该项没回复）—— 客户端在目标头顶飘字（HP 绿 / MP 蓝）。
     *
     * 与 {@code AiEngine} 的 S2C_Damage 广播**同一形状**（AOI 范围 + 权威 current 值），
     * 旁观者也能看到别人吃药/被治疗。技能（治疗、生命转换）落地时也走这里。
     *
     * ⚠ **被动缓慢回复不走这里**（`RegenerationService` 每秒结算一次，广播会把飘字刷爆）。
     * 本入口只给"一次性回复事件"用：药水 / 治疗技能 / 生命转换。
     */
    private void broadcastRecovery(PlayerSession session, Player p, int hp, int mp) {
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null) {
            return;
        }
        messageSender.broadcastToArea(ent.getMapId(), (float) ent.getX(), (float) ent.getZ(), 50,
                ServerMessage.newBuilder()
                        .setRecovery(S2C_Recovery.newBuilder()
                                .setTargetId(p.getId())
                                .setHpAmount(hp)
                                .setCurrentHp(p.getHp())
                                .setMpAmount(mp)
                                .setCurrentMp(p.getMp())
                                .build())
                        .build());
    }

    /** 用掉之后把背包变化推给客户端：堆叠没耗尽推 update、耗尽推 remove */
    private void pushAfterUse(PlayerSession session, ItemInstance used) {
        if (used.getCount() > 0) {
            pushUpdate(session, used);
        } else {
            pushRemove(session, used.getId());
        }
    }

    // ------------------------------------------------------------------
    // 进图快照（selectCharacter 完成后由 PlayerService/AccountService 调用）
    // ------------------------------------------------------------------

    public void sendInventorySnapshot(PlayerSession session, Player player) {
        S2C_InventorySnapshot.Builder snap = S2C_InventorySnapshot.newBuilder()
                .setGold(player.getGold());
        PlayerItems items = player.getItems();
        if (items != null) {
            // EQUIP 段含**鼠标位**（slot=-1）→ 断线重连/重登时把"手上还拿着的那件"一并下发，
            // 客户端据此原样恢复手持（用户 2026-09-14 定：重登必须还原）。
            for (int loc : new int[]{ItemLocations.BAG, ItemLocations.WAREHOUSE,
                    ItemLocations.EQUIP, ItemLocations.BACKUP_WEAPON}) {
                for (ItemInstance it : items.itemsIn(loc)) {
                    snap.addItems(toProto(it));
                }
            }
        }
        session.send(ServerMessage.newBuilder()
                .setInventorySnapshot(snap)
                .build());
    }

    public S2C_ItemUpdate toItemUpdate(ItemInstance it) {
        return S2C_ItemUpdate.newBuilder()
                .setItem(toProto(it))
                .build();
    }

    public void pushUpdate(PlayerSession session, ItemInstance it) {
        session.send(ServerMessage.newBuilder()
                .setItemUpdate(toItemUpdate(it))
                .build());
    }

    public void pushRemove(PlayerSession session, long uid) {
        session.send(ServerMessage.newBuilder()
                .setItemRemove(S2C_ItemRemove.newBuilder().setUid(uid).build())
                .build());
    }

    // ------------------------------------------------------------------
    // 转换 ItemInstance → ItemProto
    // ------------------------------------------------------------------

    public static CommonProto.ItemProto toProto(ItemInstance it) {
        return ItemProtos.toProto(it);

    }

    // ------------------------------------------------------------------
    // C2S Handlers
    // ------------------------------------------------------------------

    private Player requirePlayer(PlayerSession session) {
        return playerService.requirePlayer(session);   // 判据收敛在 PlayerService（各 handler 共用一份）
    }

    /** 背包内移动/换格（含背包↔仓库） */
    /** 该物品当前位置是否允许"使用"：背包，或药水快捷槽。
     *  （无注解 —— 只被 handleUseItem 调用；注解必须紧贴 public 入口方法，
     *   否则注册器 getMethods() 扫不到，消息会静默变成 "No handler registered"。） */
    private static boolean isUsableLocation(ItemInstance it) {
        if (it.getLocation() == ItemLocations.BAG) {
            return true;
        }
        return it.getLocation() == ItemLocations.EQUIP
                && org.jpstale.common.service.item.EquipSlots.isPotionSlot(it.getSlot());
    }

    @GamePacketHandler(ClientMessage.INVENTORY_MOVE_FIELD_NUMBER)
    public void handleInventoryMove(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_InventoryMove req = message.getInventoryMove();
        boolean ok = itemService.moveOnCanvas(p, req.getUid(), req.getToLocation(), req.getToSlot());
        if (!ok) {
            sendError(session, "move failed");
            return;
        }
        ItemInstance it = p.getItems().byUid(req.getUid());
        if (it != null) {
            pushUpdate(session, it);
        }
    }

    /** 背包布局上报（客户端网格权威，全量快照 + seq）：seq<=lastSeq 乱序丢弃；失败不回推旧快照 */
    @GamePacketHandler(ClientMessage.BAG_LAYOUT_FIELD_NUMBER)
    public void handleBagLayout(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_BagLayout req = message.getBagLayout();
        int seq = req.getSeq();
        var list = req.getEntriesList();
        if (list.isEmpty() && seq <= p.getItems().lastSeq()) {
            return; // 空且乱序：直接丢弃
        }
        java.util.List<ItemService.BagLayoutEntry> entries = new java.util.ArrayList<>(list.size());
        boolean touchesEquip = false;
        for (var e : list) {
            final long uid = e.getUid();
            final int toLocation = e.getLocation();
            final int slot = e.getSlot();
            ItemInstance src = p.getItems().byUid(uid);
            if (src != null && (src.getLocation() == ItemLocations.EQUIP
                    || src.getLocation() == ItemLocations.BACKUP_EQUIP)) {
                touchesEquip = true;
            }
            entries.add(new ItemService.BagLayoutEntry() {
                @Override public Long uid() { return uid; }
                @Override public int location() { return toLocation; }
                @Override public int slot() { return slot; }
            });
        }
        int lastSeqBefore = p.getItems().lastSeq();
        boolean ok = itemService.applyBagLayout(p, seq, entries);
        if (!ok) {
            // ⚠ 非乱序的失败必须**回错**：过去这里什么都不发 → 客户端乐观改动永不回滚，
            // 屏幕上留下"两件叠在同一格"（用户 2026-09-14 实测）。乱序/重放是正常丢弃，不该报错。
            if (seq > lastSeqBefore) {
                sendErrorKey(session, "item.op.failed");
            }
            // 乱序（seq<=lastSeq）已由 applyBagLayout 内部记录；其余失败事件记日志
            if (seq > p.getItems().lastSeq()) {
                log.warn("[BagLayout] {} seq={} 校验失败（不裁决、不改格）", session.getCharacterName(), seq);
            }
        } else if (touchesEquip) {
            refreshPlayerStats(session, p);
        }
    }

    /** 药水堆叠合并 */
    @GamePacketHandler(ClientMessage.STACK_MERGE_FIELD_NUMBER)
    public void handleStackMerge(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_StackMerge req = message.getStackMerge();
        ItemInstance dst = itemService.mergeStack(p, req.getSrcUid(), req.getDstUid());
        if (dst == null) {
            // 拒绝：回推双方当前权威态
            ItemInstance s = p.getItems().byUid(req.getSrcUid());
            ItemInstance d = p.getItems().byUid(req.getDstUid());
            if (s != null) {
                pushUpdate(session, s);
            }
            if (d != null) {
                pushUpdate(session, d);
            }
            return;
        }
        pushUpdate(session, dst);
        pushRemove(session, req.getSrcUid());
    }

    /**
     * **换手**：手上那件 ↔ 背包/仓库里某件，原子互换（原版 `ChangeInvenItem` 的换手）。
     * 客户端在"拿着 A 点已占格上的 B"时发这条；服务端用两行互换的写库原语落地。
     */
    @GamePacketHandler(ClientMessage.BAG_SWAP_FIELD_NUMBER)
    public void handleBagSwap(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_BagSwap req = message.getBagSwap();
        ItemService.OpResult r = itemService.swapWithHand(p, req.getHandUid(), req.getTargetUid(),
                req.getToLocation(), req.getToSlot());
        if (r.reason != ItemService.OpReason.OK) {
            sendErrorKey(session, "item.op." + opKeySuffix(r.reason));   // 客户端据前缀回滚两件
            return;
        }
        // 换到手上那件（目标）+ 落到目标格那件（原手上）都要推
        ItemInstance nowHeld = r.instance;
        pushUpdate(session, nowHeld);
        ItemInstance moved = p.getItems().byUid(req.getHandUid());
        if (moved != null && !moved.isDeleted()) {
            pushUpdate(session, moved);
        }
        refreshPlayerStats(session, p);   // 换下来的若原本在装备位？不会（目标必须在画布上）→ 但负重/外观保持一致
        log.info("[Swap] {} hand={} target={} 完成", session.getCharacterName(), req.getHandUid(), req.getTargetUid());
    }

    /**
     * **拿起**：任意容器 → 鼠标位（装备栏 `slot = -1`）。见 `ItemLocations.HELD_SLOT`。    /**
     * **拿起**：任意容器 → 鼠标位（装备栏 `slot = -1`）。见 `ItemLocations.HELD_SLOT`。
     * 放下不需要配套消息：放到装备槽/药水槽走 `EquipItem`、放到背包/仓库格走 `BagLayout`、丢地上走 `DropItem`，
     * 这些路径现在都接受"来源 = 鼠标位"。
     */
    @GamePacketHandler(ClientMessage.TAKE_TO_HAND_FIELD_NUMBER)
    public void handleTakeToHand(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        long uid = message.getTakeToHand().getUid();
        // 0 = 整堆拿起（旧行为）；>0 且 < 现有量 = **拆分**（只拿 n 个，用户 2026-09-14）
        int count = message.getTakeToHand().getCount();
        ItemService.OpResult r = itemService.takeToHand(p, uid, count);
        if (r.reason != ItemService.OpReason.OK) {
            sendErrorKey(session, "item.op." + opKeySuffix(r.reason));
            return;
        }
        pushUpdate(session, r.instance);
        // 拿起装备 → 立刻掉属性/换外观（原版拿起即 sinSetCharItem(FALSE) + CheckWeight）
        refreshPlayerStats(session, p);
    }

    /** 穿装备：背包 → 装备槽 */
    @GamePacketHandler(ClientMessage.EQUIP_ITEM_FIELD_NUMBER)
    public void handleEquipItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_EquipItem req = message.getEquipItem();
        long srcUid = req.getUid();
        ItemService.OpResult r = itemService.equipFromBag(p, srcUid, req.getEquipSlot());
        if (r.reason != ItemService.OpReason.OK) {
            // 按**原因**回 key：原版 CheckSetOk 也是按原因分别弹 MESSAGE_OVER_WEIGHT / MESSAGE_NO_USE_ITEM。
            // key 前缀 `item.op.` 同时是客户端的"回滚乐观更新"信号（不再靠字符串匹配）。
            sendErrorKey(session, "item.op." + opKeySuffix(r.reason));
            return;
        }
        ItemInstance equipped = r.instance;
        pushUpdate(session, equipped);
        // **拆堆搬入**（药水槽：超出容量的部分留在背包，`putPotionToSlot`）走的是"新建槽内记录 +
        // 扣减源堆"，所以**源堆不是** r.instance → 必须单独推一次，否则客户端背包里那堆
        // 数量停在旧值（服务端只推了新记录）。整堆搬入时 r.instance 就是源记录，跳过。
        if (equipped.getId() == null || equipped.getId() != srcUid) {
            ItemInstance src = p.getItems().byUid(srcUid);
            if (src != null && !src.isDeleted() && src.getCount() > 0) {
                pushUpdate(session, src);
            } else {
                // 源那件**整件被合并掉**（同种药水入已有槽：`putPotionToSlot` 把源堆并进槽内那行后
                // `softDelete(srcUid)`）⇒ 它在服务端已经不存在了，必须明确告诉客户端"这个 uid 没了"。
                // 否则客户端表里还留着它、且位置仍是鼠标位 ⇒ 手上一直显示一瓶幽灵药水，
                // 拿去放背包会被 `applyBagLayout` 拒（"操作失败"）、再点拿起回"该物品不在背包里"
                //（用户 2026-09-14 实测的整条日志就是它）。客户端 `net/bridge.ts` 已处理 ItemRemove。
                pushRemove(session, srcUid);
            }
        }
        refreshPlayerStats(session, p);
        // 被换下的件（同槽旧件 / 双手武器另一只手那件）已进背包 → 推它的新位置。
        // 这一步不能省：客户端只知道"新件进槽了"，被换下那件就成了界面上的幽灵 ——
        // 既不在原来的槽（被占了）也不在背包（没收到通知），用户看到的是"直接消失了"。
        for (ItemInstance d : r.displaced) {
            pushUpdate(session, d);
        }
    }

    /** OpReason → i18n key 后缀（客户端 `locales/*.json` 的 `item.op.*`） */
    private static String opKeySuffix(ItemService.OpReason r) {
        switch (r) {
            case NOT_IN_BAG: return "notInBag";
            case JOB_NOT_ALLOWED: return "jobNotAllowed";
            case SLOT_MISMATCH: return "slotMismatch";
            case REQ_NOT_MET: return "reqNotMet";
            case OVER_WEIGHT: return "overWeight";
            case TWO_HAND_SLOT: return "twoHandSlot";
            case NOT_POTION: return "notPotion";
            case SLOT_FULL: return "slotFull";
            case DIFFERENT_POTION: return "differentPotion";
            case BAG_FULL: return "bagFull";
            case HAND_BUSY: return "handBusy";
            case NOT_HOLDABLE: return "notHoldable";
            default: return "failed";
        }
    }

    /** 脱装备：装备槽 → 背包 */
    @GamePacketHandler(ClientMessage.UNEQUIP_ITEM_FIELD_NUMBER)
    public void handleUnequipItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_UnequipItem req = message.getUnequipItem();
        ItemService.OpReason reason = itemService.unequipToBag(p, req.getEquipSlot());
        if (reason != ItemService.OpReason.OK) {
            sendErrorKey(session, "item.op." + opKeySuffix(reason));
            return;
        }
        // 推送装备槽清空 + 背包新位置
        for (ItemInstance it : p.getItems().itemsIn(ItemLocations.BAG)) {
            if (it.getSlot() >= 0) {
                pushUpdate(session, it);
            }
        }
        refreshPlayerStats(session, p);
    }

    /** 拾取地面物品（C2S_PickupItem）：服务端距离裁决 + 入背包 + 同图消失广播。 */
    @GamePacketHandler(ClientMessage.PICKUP_ITEM_FIELD_NUMBER)
    public void handlePickupItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (p == null || ent == null || ent.getMapId() < 0) {
            return;
        }
        long gid = message.getPickupItem().getGroundItemId();
        // 按**全局唯一 id** 取（不按图）：可见性已统一为坐标口径，边界另一侧的东西现在看得见，
        // 若查找还按图就会"看得见却捡不到"。真正的门槛是下面的 PICKUP_RANGE 距离判定。
        GroundItem gi = groundItems.byIdAnyMap(gid);
        if (gi == null) {
            log.info("[Pickup] {} (mapId={}) gid={} : not found/expired", session.getCharacterName(), ent.getMapId(), gid);
            return; // 已消失/过期（幂等）
        }
        Long cid = session.getCharacterId();
        // 私有掉落只在**私有窗口内**拦非归属者；窗口一过它就是公共掉落（可见性与拾取同一把尺子，
        // 见 GroundItem.privateUntil / GroundItemManager.PRIVATE_WINDOW_MS）。
        //
        // ⚠ 原版（经典三棵树）拾取端**完全没有归属校验** —— 它的"私有"只靠"不告诉别人物品在哪"
        // 实现，5 秒后批量补发就变公共。我们这里多留了一道窗口内校验：对正常客户端**等价**
        // （看不见就点不到），但对"照坐标瞎发包"的客户端更严。这是有意的收紧，不是照抄。
        if (gi.ownerId != 0 && gi.isPrivateAt(System.currentTimeMillis()) && (cid == null || gi.ownerId != cid)) {
            log.info("[Pickup] {} gid={} : 私有窗口内非归属者（owner={}）", session.getCharacterName(), gid, gi.ownerId);
            return;
        }
        double dx = gi.getX() - ent.getX();
        double dz = gi.getZ() - ent.getZ();
        if (dx * dx + dz * dz > PICKUP_RANGE * PICKUP_RANGE) {
            log.info("[Pickup] {} gid={} : too far dist={} (range {})",
                session.getCharacterName(), gid, Math.sqrt(dx * dx + dz * dz), PICKUP_RANGE);
            return; // 距离裁决：太远不拾
        }
        // 高度差裁决（对齐原版 ay ≤ 64·fONE）：不能隔层(屋顶/桥上)拾取
        if (Math.abs(gi.getY() - ent.getY()) > PICKUP_HEIGHT_DIFF) {
            log.info("[Pickup] {} gid={} : too high diff={} (limit {})",
                session.getCharacterName(), gid, Math.abs(gi.getY() - ent.getY()), PICKUP_HEIGHT_DIFF);
            return;
        }
        // **金币掉落物**：原版 `SetInvenToItemInfo`（`sinInvenTory.cpp:7808`）在金币分支里
        // `sinPlusMoney` + `SIN_SOUND_COIN` 后**直接 return** —— 不入背包、不占格、**不负重**。
        // 所以这一支要放在"① 负重"**之前**，且不参与后面的药水槽/上手/背包四步。
        // 判据 = **家族 + 带金额**（`ItemRules.isGoldDrop`，两条件缺一不可，见该方法的注释）。
        Integer giCode = gi.item.getItemCode();
        if (ItemRules.isGoldDrop(giCode == null ? 0 : giCode, gi.money)) {
            org.jpstale.server.game.service.GoldService.Result r = goldService.add(session, p, gi.money, "pickup");
            if (r != org.jpstale.server.game.service.GoldService.Result.OK) {
                // 超等级上限：**金币留在地上**（原版行为：不发放、不截断），并给出明确原因
                log.info("[Pickup] {} gid={} : 金币 {} 入账被拒（{}）→ 保持原地",
                    session.getCharacterName(), gid, gi.money, r);
                if (r == org.jpstale.server.game.service.GoldService.Result.OVER_LIMIT) {
                    sendErrorKey(session, "item.pickup.overMoney");
                }
                return;
            }
            groundItems.remove(ent.getMapId(), gid);
            broadcastDisappear(ent.getMapId(), gi.getX(), gi.getZ(), gid);
            log.info("[Pickup] {} 拾取金币 {}（gid={}）", session.getCharacterName(), gi.money, gid);
            return;
        }
        // ① 负重：原版在拾取入口就查，超重则整次拾取拒绝（物品留在地上）
        if (itemService.pickupOverWeight(p, gi.item)) {
            log.info("[Pickup] {} gid={} : over weight → 保持原地", session.getCharacterName(), gid);
            sendSystemMessageKey(session, "chat.pickup.overWeight");
            return;
        }
        // ② 药水优先填药水槽（原版 AutoSetPotion）：灌不完的余数才继续走"上手 / 进背包"
        java.util.List<ItemInstance> potionSlots = itemService.pourIntoPotionSlots(p, gi.item);
        for (ItemInstance t : potionSlots) {
            pushUpdate(session, t);
        }
        ItemInstance granted = null;
        if (gi.item.getCount() > 0) {
            // ③ 余数：拾取上手（原版背包窗口开着时 `memcpy(&MouseItem, ...)`，不走背包也不需要空格）
            if (message.getPickupItem().getToHand()) {
                granted = itemService.grantToHand(p, gi.item);
            }
            // ④ 还落不下就进背包（手位被占 / 未要求上手）；背包满则保持原地
            if (granted == null) {
                ItemService.GrantResult result = itemService.grantInstanceToBag(p, gi.item);
                if (result.reason == ItemService.GrantReason.BAG_FULL) {
                    log.info("[Pickup] {} gid={} : bag full（药水槽已收 {}）→ 余数留在地上",
                            session.getCharacterName(), gid, potionSlots.size());
                    sendSystemMessageKey(session, "chat.pickup.bagFull");
                    return;
                }
                granted = result.instance;
                // **被触碰的所有行都要推**（并入的堆 + 新落下的行）—— 只推主件会让另一行的
                // 数量在客户端停旧值（AGENTS #37 的纪律：让某行变化的路径必须显式通知）
                for (ItemInstance t : result.touched) {
                    if (t != granted) {
                        pushUpdate(session, t);
                    }
                }
            }
        }
        // 走完上面几步，这件地面物**已经整件被消耗**了：一部分进了药水槽、余数进了手上/背包
        //（背包满会在上面直接 return，那时余数确实还留在原地）。
        // ⚠ 这里曾经写成 `if (gi.item.getCount() > 0)` —— 条件正好**反了**：整堆被药水槽吃掉时
        // `count == 0`，于是地面物**永远留在原地**（用户 2026-09-14 实测："即使药水槽空着，
        // 拾取药水后地上的药水依然显示在地板上"），而残留的 count=0 地面物再点也没有任何反应
        //（"似乎只要第 1 格有药水就会阻止拾取"其实是这个残影造成的错觉）。
        groundItems.remove(ent.getMapId(), gid);
        broadcastDisappear(ent.getMapId(), gi.getX(), gi.getZ(), gid);
        if (granted != null) {
            log.info("[Pickup] {} gid={} granted id={} itemListId={} name={} @loc={}/slot={}",
                session.getCharacterName(), gid, granted.getId(), granted.getItemListId(),
                granted.getTemplate() != null ? granted.getTemplate().getName() : "?",
                granted.getLocation(), granted.getSlot());
            pushUpdate(session, granted);
        }
        refreshPlayerStats(session, p); // 负重/属性（拿起装备会撤效果）需要更新
    }

    /** 向地面物品所在位置周围玩家广播消失 */
    private void broadcastDisappear(int mapId, double x, double z, long gid) {
        ServerMessage disappear = ServerMessage.newBuilder()
                .setGroundItemDisappear(S2C_GroundItemDisappear.newBuilder().setGroundItemId(gid).build())
                .build();
        for (org.jpstale.server.game.entity.PlayerEntity pe : aoiManager.getNearbyPlayers(x, z, AOIManager.VIEW_RANGE)) {
            if (pe.getSession() != null) {
                pe.getSession().send(disappear);
            }
        }
    }

    /**
     * 拾取判定范围（世界单位）——**这是我们的反外挂参数，取值有意偏离原版**。
     *
     * 原版数值是 32：拾取动作走 `PlayAttackFromPosi(..., Dist=8000, ...)` 且
     * `GetDistanceDbl(>>8)` 平方比较 ⇒ 水平 ≤ `8000>>8 = 31.25`；高度差上限 64·fONE。
     * ⚠ 但**原版这一侧没有服务端校验**（拾取判定完全在客户端），我们加它是为了防外挂；
     * 而我们的架构是"客户端位置权威 + 服务端实体由核心循环按 tick 消费"，于是服务端手上的
     * 坐标**天然滞后于客户端**（用户 2026-09-14 实测：客户端已到跟前，服务端仍按 33.27 / 35.03 /
     * 51.94 判超距）。所以这里的 32 不再是"原版范围"，而是"我们给滞后留的容差" ⇒ 放宽到 64。
     *
     * 为什么只放宽服务端：客户端的 `PICK_ACT_RANGE = 32` 是**玩法手感**（点得动就点、点不到就走过去），
     * 保持不动；两边一起放大才会变成"隔空取物"。
     */
    private static final double PICKUP_RANGE = 64.0d;

    /** 拾取高度差上限（世界单位，原版 ay ≤ 64·fONE）：防隔层拾取（屋顶/桥上） */
    private static final double PICKUP_HEIGHT_DIFF = 64.0d;

    private void sendErrorKey(PlayerSession session, String key) {
        session.send(ServerMessage.newBuilder()
                .setError(S2C_Error.newBuilder()
                        .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                        .setKey(key)
                        .build())
                .build());
    }

    /** 带模板参数的错误（客户端 `t(key, params)`；如"需要 {level} 级"）。 */
    private void sendErrorKey(PlayerSession session, String key, java.util.Map<String, String> params) {
        session.send(ServerMessage.newBuilder()
                .setError(S2C_Error.newBuilder()
                        .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                        .setKey(key)
                        .putAllParams(params)
                        .build())
                .build());
    }

    /** 丢弃（软删） */
    @GamePacketHandler(ClientMessage.DROP_ITEM_FIELD_NUMBER)
    public void handleDropItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_DropItem req = message.getDropItem();
        org.jpstale.server.game.entity.PlayerEntity ent = session.getEntity();
        if (ent == null || ent.getMapId() < 0) {
            sendErrorKey(session, "item.op.failed");
            return;
        }
        // 禁丢清单（原版 NotDrow_Item_*，见 ItemRules）：任务物品等不许丢到地面。
        // 权威判定在这里；客户端也有一份同样的预校验（免得本地先移除、服务端却拒绝）。
        ItemInstance toDrop = p.getItems().byUid(req.getUid());
        if (toDrop == null) {
            // 精确原因（用户要求"不允许模糊"）：这一支过去回笼统的 `item.op.failed`，
            // 于是"客户端拿一个服务端已经没有的 uid 来丢"（幽灵物品，见本文件 ItemRemove 那段）
            // 显示成"操作失败"，看不出是什么原因。日志同时留档。
            log.info("[DropItem] {} 拒绝丢弃 uid={}：服务端没有这件物品（客户端状态可能已过期）",
                    p.getName(), req.getUid());
            sendErrorKey(session, "item.op.notInBag");
            return;
        }
        int dropCode = toDrop.getItemCode() != null ? toDrop.getItemCode() : 0;
        if (!ItemRules.isDroppable(dropCode)) {
            log.info("[DropItem] {} 拒绝丢弃 uid={} idCode=0x{}（禁丢清单：任务物品）",
                    p.getName(), req.getUid(), Integer.toHexString(dropCode));
            sendErrorKey(session, "item.op.notDroppable");
            return;
        }
        // 丢到地面（对齐原版 ThrowItem）：从背包/装备取出 → 玩家附近生成地面物
        ItemInstance dropped = itemService.removeToGround(p, req.getUid());
        if (dropped == null) {
            sendErrorKey(session, "item.op.failed");
            return;
        }
        // ownerId = 0：**玩家主动丢到地上的东西立即对所有人可见**，没有私有窗口。
        //
        // 依据（原文玩家丢弃分支，ex-machina gameserver/Legacy/Server/OnSever.cpp:18784）：
        //   `lpStgItem = lpStgArea->AddItem(lpsItem, x, y, z);`
        //   `if (lpStgItem) lpStgArea->SendStgItemToNearUsers(lpStgItem);`   // 直接广播给视野内所有人
        // 全程**没有** `dwCreateTime += 5000`，也**没有**归属赋值（`STG_ITEMS` 里根本没有 owner 字段）。
        // 那 5 秒私有窗口只属于**怪物掉落**里 `dropispublic = 0` 的那部分（击杀者的战利品）。
        // ⚠ 曾经把两条路统一套上 5 秒窗口（用户 2026-09-16 纠正："玩家丢弃原版是立即看到，没有 5 秒限制"）。
        // 落点：与 GM /@get 同一随机散布（0.5~30 世界单位 + 地形高度 + 隔层重选），不再挤在脚边同一坐标。
        GroundItem gi = groundItems.addScattered(dropped, ent.getMapId(), ent.getX(), ent.getY(), ent.getZ());
        if (gi == null) {
            // 地图已满且无可挤兑（全 Level=1）：原版 return FALSE 亦丢弃 → 背包物品已被取出，无法原地放回，直接告知
            log.warn("[DropGround] {} uid={} 地图满({}) 掉落被丢弃", session.getCharacterName(), req.getUid(), GroundItemManager.STG_ITEM_MAX);
            pushRemove(session, req.getUid());
            refreshPlayerStats(session, p);
            sendSystemMessageKey(session, "chat.cmd.dropOverLimit");
            return;
        }
        pushRemove(session, req.getUid());
        refreshPlayerStats(session, p);
        log.info("[DropGround] {} uid={} → groundItem id={} @({},{})",
            session.getCharacterName(), req.getUid(), gi.getId(), (float) gi.getX(), (float) gi.getZ());
    }

    /** W 武器切换 */
    @GamePacketHandler(ClientMessage.SWITCH_WEAPON_FIELD_NUMBER)
    public void handleSwitchWeapon(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        itemService.switchWeaponSet(p);
        // 推送主/备武器槽变化
        for (ItemInstance it : p.getItems().equippedItems()) {   // 排除鼠标位（推送装备槽变化，不该推手上那件）
            pushUpdate(session, it);
        }
        for (ItemInstance it : p.getItems().itemsIn(ItemLocations.BACKUP_WEAPON)) {
            pushUpdate(session, it);
        }
        refreshPlayerStats(session, p);
    }

    private void refreshPlayerStats(PlayerSession session, Player p) {
        playerService.recalcPanel(p);
        // 属性重算后就地推状态：抗性也走同一条链（`EquipSummary.res` → `PlayerStatCalculator.resistances`）。
        // 这里曾另写一份求和，且**没有**需求校验门槛（与 `PlayerService.loadItems` 那份口径不同）——
        // 已删（用户 2026-09-22：一个判定只留一份实现）。
        playerService.sendPlayerStatus(session, p);
        // 外观重算 + 广播（自机 + 视野玩家），驱动 3D 换装与锻造/合成呼吸发光。
        // ⚠ **只在真的变了才推**：本方法被 8 个入口调用（含整理背包/拿起/拾取/丢弃），
        // 无条件推的话客户端每次都会重建模型 + `reselectForCurrentState()` 重选动画
        // ⇒ "随便整理一下背包，角色动画就重播一次"（用户 2026-09-16 实测）。
        // 判据（`CharacterAppearance.equals` 逐字段比较）与广播一起收在
        // `AppearanceService.recalcAndBroadcast` —— 锻造那条链路（`AgeEffectBroadcaster`）
        // 用的是同一条判据，别在这里再写一份（AGENTS #15）。
        appearanceService.recalcAndBroadcast(p);
    }

    private void sendError(PlayerSession session, String msg) {
        session.send(ServerMessage.newBuilder()
                .setSystemMessage(S2C_SystemMessage.newBuilder()
                        .setMessage(msg)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }

    private void sendSystemMessageKey(PlayerSession session, String key) {
        if (session == null) {
            return;
        }
        session.send(ServerMessage.newBuilder()
                .setSystemMessage(S2C_SystemMessage.newBuilder()
                        .setKey(key)
                        .setTimestamp(System.currentTimeMillis())
                        .build())
                .build());
    }

    // ==================================================================
    // 锻造 / 合成 / 力量石（2026-09-22）
    // 三个窗口的"确定"各发一条消息；**"一键拉满"与"吃力量石"走 use_item**（见 handleUseItem）
    // 依据：docs/锻造与合成-源码分析.md（EU）与 docs/合成配方全表.md
    // ==================================================================

    /** 合成（Mix）：目标装备 + 材料石 → 服务端按 gamedb.mixlist 匹配配方并一次性加属性。 */
    @GamePacketHandler(ClientMessage.MIX_ITEM_FIELD_NUMBER)
    public void handleMixItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_MixItem req = message.getMixItem();
        org.jpstale.common.service.item.MixService.Result r =
                mixService.mix(p, req.getTargetUid(), req.getStoneUidsList());
        if (!r.ok()) {
            sendErrorKey(session, r.reason.key());
            return;
        }
        for (long uid : r.consumedStoneUids) {
            pushRemove(session, uid);            // 石头被消耗
        }
        pushUpdate(session, r.target);           // 目标属性变了
        refreshPlayerStats(session, p);
    }

    /** 锻造投石：目标装备 + 一颗材料石（"一键拉满"那颗不走这里，走 use_item）。 */
    @GamePacketHandler(ClientMessage.AGE_ITEM_FIELD_NUMBER)
    public void handleAgeItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_AgeItem req = message.getAgeItem();
        // ★ **先收金币**（EU `ItemServer::GetItemAgingPrice` = round(售价 × (等级+1) / 2)）——
        //   原版是"投进去就没了"，所以**掷点之前**扣、失败/破坏都不退；钱不够则整体拒绝（石头不动）。
        org.jpstale.common.service.item.ItemInstance target =
                p.getItems() == null ? null : p.getItems().byUid(req.getTargetUid());
        if (target != null) {
            int price = org.jpstale.common.service.item.AgeService.agingGoldPrice(target);
            if (price > 0) {
                if (p.getGold() < price) {
                    sendErrorKey(session, "item.op.age.noGold");
                    return;
                }
                var paid = goldService.add(session, p, -price, "aging");
                if (paid != org.jpstale.server.game.service.GoldService.Result.OK) {
                    sendErrorKey(session, "item.op.age.noGold");
                    return;
                }
                log.info("[Age] {} 交养成费 {} 金币（uid={}，+{} 级）", p.getName(), price,
                        target.getId(), target.getAgingNum());
            }
        }
        org.jpstale.common.service.item.AgeService.Result r =
                ageService.ageWithStone(p, req.getTargetUid(), req.getStoneUid());
        ageEffectBroadcaster.broadcastAgingUp(p, r);   // ageNum 上升 → 同一记粒子（满即升级那条规则）
        if (!r.ok()) {
            sendErrorKey(session, r.reason.key());
            return;
        }
        pushRemove(session, req.getStoneUid());  // 石头投进去就没了（原版亦然）
        if (r.broke) {
            pushRemove(session, req.getTargetUid());   // 破坏：目标销毁
            // 原版是全服播报（`SendChatAllEx "X broke 'item' going to +N!"`）——
            // 我们暂时只写日志 + 给本人一条系统提示，全服播报等聊天频道的"全服"档位接上再开。
            log.warn("[Age] {} 的 {} 投石破坏（掷点={} 目标uid={}）", p.getName(),
                    r.target == null ? "?" : r.target.name(), r.roll, req.getTargetUid());
        } else {
            pushUpdate(session, r.target);
        }
        refreshPlayerStats(session, p);
    }

    /** 力量大师：材料石 → 力量石（我们定的规则：每颗换同档一颗）。 */
    @GamePacketHandler(ClientMessage.FORCE_ORB_ITEM_FIELD_NUMBER)
    public void handleForceOrbItem(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_ForceOrbItem req = message.getForceOrbItem();
        java.util.List<org.jpstale.common.service.item.ItemInstance> created =
                forceOrbService.convert(p, req.getStoneUidsList());
        if (created.isEmpty()) {
            sendErrorKey(session, org.jpstale.common.service.item.ForceOrbService.Reason.NO_STONES.key());
            return;
        }
        for (long uid : req.getStoneUidsList()) {
            pushRemove(session, uid);            // 石头被消耗
        }
        for (org.jpstale.common.service.item.ItemInstance it : created) {
            pushUpdate(session, it);             // 新生成的力量石（pushUpdate 对新件也成立）
        }
        refreshPlayerStats(session, p);
    }
    /**
     * **合成预览**：客户端报当前投入的 uid 列表，服务端匹配配方后把"会得到什么"发下去。
     *
     * <p>客户端不持有配方、不做算术 —— 预览与真合成共用 `MixService.match()` 与同一张
     * `applyEffect` switch（干跑），所以窗口上的数**就是**真加的数。
     * 这是唯一能避免"显示 +15、实际 +12"那种两边都不报错的分叉的做法。
     */
    @GamePacketHandler(ClientMessage.MIX_PREVIEW_FIELD_NUMBER)
    public void handleMixPreview(PlayerSession session, ClientMessage message) {
        Player p = requirePlayer(session);
        if (p == null) {
            return;
        }
        C2S_MixPreview req = message.getMixPreview();
        org.jpstale.common.service.item.MixService.Preview pv =
                mixService.preview(p, req.getTargetUid(), req.getStoneUidsList());
        S2C_MixPreview.Builder b = S2C_MixPreview.newBuilder().setMatched(pv.ok());
        if (!pv.ok()) {
            b.setReasonKey(pv.reason.key());
        } else {
            b.setRecipeName(pv.recipeName == null ? "" : pv.recipeName);
            for (org.jpstale.common.service.item.MixEffect.Applied a : pv.effects) {
                b.addEffects(MixPreviewEffect.newBuilder()
                        .setBit(a.bit())
                        .setKey(a.key())
                        .setValue(a.value())
                        .setFlat(a.flat())
                        .setBefore(a.before())
                        .setAfter(a.after())
                        .setIntField(a.intField()));
            }
        }
        session.send(ServerMessage.newBuilder().setMixPreview(b.build()).build());
    }
}
