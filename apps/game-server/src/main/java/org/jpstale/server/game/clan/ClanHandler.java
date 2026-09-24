package org.jpstale.server.game.clan;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.clan.ClanManager;
import org.jpstale.common.service.model.Player;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionErrors;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.GoldService;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.S2C_ClanCreateResult;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.stereotype.Component;

/**
 * 公会（血盟）的**游戏内**入口。
 *
 * <h3>为什么公会的写操作在 game-server，而读在 web-server</h3>
 * 建会要扣 500,000 金币，而**金币的权威在 game-server 的内存里**（DB 那一列只是存档，
 * 且 `CombatService.doRespawn` 之类的路径会先改内存、稍后才由周期存档落库）。
 * 跨进程"先查后扣"既是 TOCTOU 竞态，也没法把"扣钱 + 建会"收进一个事务。
 * ⇒ 用户 2026-09-25 定案：写走 game-server（`C2S_ClanCreate`），读仍在
 * web-server 的 `/api/clan/*.json`（不需要玩家内存态）。
 *
 * <h3>本类负责的三件事</h3>
 * <ol>
 *   <li><b>需要玩家内存态的校验</b>：等级、金币余额。原版这两条**只在客户端**
 *       （`cE_Cmake.cpp:353`、`clan_entri.cpp:928`），ASP 侧一条都没有 —— 我们搬到服务端。</li>
 *   <li>调 {@link ClanService#createClan} 写公会表（那一头是一个事务）。</li>
 *   <li>通知：扣钱（`GoldService` 自带内存+落库+推送）、建会结果、名牌刷新。</li>
 * </ol>
 *
 * <h3>⚠ 一个**有意保留、并已显式记录**的窗口</h3>
 * 扣钱发生在**建会事务提交之后**，所以"公会已建出、扣款那一步失败"在理论上是可能的
 * （同一连接、同一库、金金币列是普通整数，实际几乎不可能）。之所以不做成一个事务：
 * <ul>
 *   <li>`GoldService.add` 会**同时改内存**，而内存不参与事务回滚 —— 若把它塞进事务，
 *       回滚后 DB 的钱回来了、内存的钱没回来，下一轮周期存档就会把那笔钱真的扣掉
 *       （**静默、且方向相反**的错，比"白送一个公会"更难查）；</li>
 *   <li>反过来"先扣钱再建会"，失败时要退款 —— 补偿逻辑比这个窗口更危险。</li>
 * </ul>
 * 所以取"先建会、后扣钱"，并且**失败时打 error 日志**（不静默）。要彻底关掉这个窗口，
 * 需要把 `GoldService` 拆成"只写库"与"提交后改内存+推送"两半；那是独立的一步。
 */
@Slf4j
@Component
public class ClanHandler {

    /**
     * 建会等级门槛。原版 `tjclan.h:413` `#define CLAN_MAKELEVEL 40`
     * （客户端的死分支 `if (50 < CLAN_MAKELEVEL)` 常量文案还写着 50，别被文案骗了）。
     */
    private static final int CREATE_MIN_LEVEL = 40;

    /** 建会费用。原版 `tjclan.h:409` `#define MAKEMONEY 500000`。 */
    private static final long CREATE_COST = 500_000L;

    /*
     * 原版 `cE_Cmake.cpp:353` 还要求"公会能力 ≥ 10000"（`ABILITY`），那个值来自 ASP 返回的
     * `cldata.ability`，我们库里没有对应字段 ⇒ **不实现**（不猜、也不编一个默认值）。
     */

    private final PlayerService playerService;
    private final GoldService goldService;
    private final ClanManager clanManager;
    private final AOIManager aoiManager;
    private final UserInfoMapper userInfoMapper;

    public ClanHandler(PlayerService playerService, GoldService goldService, ClanManager clanManager,
                       AOIManager aoiManager, UserInfoMapper userInfoMapper) {
        this.playerService = playerService;
        this.goldService = goldService;
        this.clanManager = clanManager;
        this.aoiManager = aoiManager;
        this.userInfoMapper = userInfoMapper;
    }

    // ------------------------------------------------------------------
    // 建会
    // ------------------------------------------------------------------

    @GamePacketHandler(ClientMessage.CLAN_CREATE_FIELD_NUMBER)
    public void handleClanCreate(PlayerSession session, ClientMessage message) {
        Player p = playerService.requirePlayer(session);
        if (p == null) {
            log.warn("[Clan] 会话不在局内/拿不到 Player，忽略建会请求");
            return;
        }
        String requested = message.getClanCreate().getClanName();

        // ---- ① 玩家态校验（内存权威，DB 判不了）----
        if (p.getLevel() < CREATE_MIN_LEVEL) {
            SessionErrors.send(session, "clan.create.levelTooLow");
            return;
        }
        if (p.getGold() < CREATE_COST) {
            SessionErrors.send(session, "clan.create.notEnoughMoney");
            return;
        }

        // ---- ② 公会表（一个事务：cl + ul + clanlist + characterinfo.clanid）----
        String accountName = accountNameOf(session);
        ClanManager.Created created =
                clanManager.createClan(requested, p.getName(), accountName, p.getJob(), p.getLevel());
        if (!created.ok()) {
            SessionErrors.send(session, created.result().key());
            return;
        }

        // ---- ③ 扣钱（事务已提交；`add` 自带余额校验 + 内存 + 落库 + 推送）----
        GoldService.Result paid = goldService.add(session, p, -CREATE_COST, "clan_create");
        if (paid != GoldService.Result.OK) {
            // 上面已判过余额，走到这里说明是异常情况（例如并发把余额用光了）。
            // **不静默**：公会已经建出来了，这笔钱没扣掉，必须让人看见。
            log.error("[Clan] {} 的公会 {} 已建出，但扣款失败（{}）—— 钱没扣，需要人工核对",
                    p.getName(), created.clanName(), paid);
        }

        // ---- ④ 通知 ----
        session.send(ServerMessage.newBuilder()
                .setClanCreateResult(S2C_ClanCreateResult.newBuilder()
                        .setOk(true)
                        .setClanName(created.clanName())
                        .setIconId(created.iconId() != null ? created.iconId() : 0)
                        .build())
                .build());
        PlayerEntity ent = session.getEntity();
        if (ent != null) {
            // 名牌刷新（自己 + 同屏）。不通知的话，刚建会的人要离开视野再回来才看得到自己的公会名。
            aoiManager.broadcastClanUpdate(ent);
        }
        log.info("[Clan] {} 建会成功 clan={} 图标={} 扣款 {}",
                p.getName(), created.clanName(), created.iconId(), CREATE_COST);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 取账号名（活库 `cl.userid` / `ul.userid` 存的是**账号**，不是角色名）。
     * 与 `NpcShopHandler.isGm` 同一取法：`session.accountId` → `userdb.userinfo.accountname`。
     * 取不到返回**空串**（表列可为空），不编一个值。
     */
    private String accountNameOf(PlayerSession session) {
        Long accountId = session.getAccountId();
        if (accountId == null) {
            log.warn("[Clan] 会话没有 accountId，账号名留空");
            return "";
        }
        UserInfo u = userInfoMapper.selectById(accountId);
        if (u == null || u.getAccountName() == null) {
            log.warn("[Clan] 查不到账号 id={} 的账号名，留空", accountId);
            return "";
        }
        return u.getAccountName();
    }
}
