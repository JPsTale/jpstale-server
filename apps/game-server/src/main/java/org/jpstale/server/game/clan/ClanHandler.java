package org.jpstale.server.game.clan;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.clan.ClanManager;
import org.jpstale.common.service.model.Player;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.MessageSender;
import org.jpstale.server.game.network.SessionErrors;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.service.AOIManager;
import org.jpstale.server.game.service.GoldService;
import org.jpstale.server.game.service.PlayerService;
import org.jpstale.server.proto.base.ClientMessage;
import org.jpstale.server.proto.base.S2C_ClanInviteAsk;
import org.jpstale.server.proto.base.S2C_SystemMessage;
import org.jpstale.server.proto.base.S2C_ClanCreateResult;
import org.jpstale.server.proto.base.ServerMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final SessionManager sessionManager;
    private final MessageSender messageSender;

    public ClanHandler(PlayerService playerService, GoldService goldService, ClanManager clanManager,
                       AOIManager aoiManager, UserInfoMapper userInfoMapper,
                       SessionManager sessionManager, MessageSender messageSender) {
        this.playerService = playerService;
        this.goldService = goldService;
        this.clanManager = clanManager;
        this.aoiManager = aoiManager;
        this.userInfoMapper = userInfoMapper;
        this.sessionManager = sessionManager;
        this.messageSender = messageSender;
    }

    /**
     * 待应答的邀请：targetId → (邀请者, 公会, 时间)。
     * 照 {@code PartyService.PendingInvite} 的模式：**同一个 target 只留最新一份**（新邀请顶旧的），
     * TTL 60s（与组队一致）；应答时对"会长身份仍然有效"做**复判**（见 handleClanInviteAccept）。
     */
    private record PendingClanInvite(long inviterId, String inviterName, String clanName, long at) {}

    private static final long INVITE_TTL_MS = 60_000;

    private final Map<Long, PendingClanInvite> pendingInvites = new ConcurrentHashMap<>();

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

        // ---- ③ 公会显示刷新（必须**先于**扣钱）：broadcastClanUpdate 会重查缓存并写回
        // Player.clanName，而下面 goldService.add 会顺带推一次 characterStatus ——
        // 顺序反了，那次推送带的就是旧公会（空），客户端面板要等下一次状态推送才对。
        PlayerEntity ent = session.getEntity();
        if (ent != null) {
            // 名牌刷新（自己 + 同屏）。不通知的话，刚建会的人要离开视野再回来才看得到自己的公会名。
            aoiManager.broadcastClanUpdate(ent);
        }

        // ---- ④ 扣钱（事务已提交；`add` 自带余额校验 + 内存 + 落库 + 推送）----
        GoldService.Result paid = goldService.add(session, p, -CREATE_COST, "clan_create");
        if (paid != GoldService.Result.OK) {
            // 上面已判过余额，走到这里说明是异常情况（例如并发把余额用光了）。
            // **不静默**：公会已经建出来了，这笔钱没扣掉，必须让人看见。
            log.error("[Clan] {} 的公会 {} 已建出，但扣款失败（{}）—— 钱没扣，需要人工核对",
                    p.getName(), created.clanName(), paid);
        }

        // ---- ⑤ 结果 ----
        session.send(ServerMessage.newBuilder()
                .setClanCreateResult(S2C_ClanCreateResult.newBuilder()
                        .setOk(true)
                        .setClanName(created.clanName())
                        .setIconId(created.iconId() != null ? created.iconId() : 0)
                        .build())
                .build());
        log.info("[Clan] {} 建会成功 clan={} 图标={} 扣款 {}",
                p.getName(), created.clanName(), created.iconId(), CREATE_COST);
    }

    // ------------------------------------------------------------------
    // 邀请入会（会长/副会长发起 → 对方弹窗同意 → 才入会；原版 OPCODE_CLAN_SERVICE 1/2 的流程）
    // ------------------------------------------------------------------

    @GamePacketHandler(ClientMessage.CLAN_INVITE_FIELD_NUMBER)
    public void handleClanInvite(PlayerSession session, ClientMessage message) {
        Player p = playerService.requirePlayer(session);
        if (p == null) {
            return;
        }
        var req = message.getClanInvite();

        // ---- ① 发起者资格：在会 + 会长/副会长（服务端判，原版 ASP 有这条而其余操作没有）----
        String inviterClan = clanManager.clanNameOf(p.getName());
        if (inviterClan == null) {
            SessionErrors.send(session, "clan.op.notInClan");
            return;
        }
        if (!clanManager.isLeaderOrSub(inviterClan, p.getName())) {
            SessionErrors.send(session, "clan.op.noPermission");
            return;
        }

        // ---- ② 定位目标：id 优先，名字兜底（公会面板输名字 / 目标窗按钮给 id）----
        PlayerSession targetSession;
        if (req.getTargetId() > 0) {
            targetSession = sessionManager.getSessionByCharacterId(req.getTargetId());
        } else if (!req.getTargetName().isBlank()) {
            targetSession = sessionManager.getSessionByCharacterName(req.getTargetName().trim());
        } else {
            SessionErrors.send(session, "clan.op.targetNotFound");
            return;
        }
        Player target = targetSession != null && targetSession.isPlaying()
                ? playerService.byId(targetSession.getCharacterId()) : null;
        if (target == null) {
            SessionErrors.send(session, "clan.op.targetOffline");
            return;
        }
        Long targetId = targetSession.getCharacterId();
        if (targetId == null || targetId == p.getId()) {
            SessionErrors.send(session, "clan.op.cannotInviteSelf");
            return;
        }

        // ---- ③ 目标态：未入会；本会未满 ----
        if (clanManager.clanNameOf(target.getName()) != null) {
            SessionErrors.send(session, "clan.op.targetAlreadyInClan");
            return;
        }
        if (clanManager.membersOf(inviterClan).size() + 1 > ClanManager.MAX_MEMBERS) {
            SessionErrors.send(session, "clan.op.clanFull");
            return;
        }

        // ---- ④ 挂 pending，推弹窗给目标（新邀请顶旧的，TTL 60s，与组队同口径）----
        pendingInvites.put(targetId, new PendingClanInvite(p.getId(), p.getName(), inviterClan, System.currentTimeMillis()));
        messageSender.sendToPlayer(targetId, ServerMessage.newBuilder()
                .setClanInviteAsk(S2C_ClanInviteAsk.newBuilder()
                        .setInviterId(p.getId())
                        .setInviterName(p.getName())
                        .setClanName(inviterClan))
                .build());
        log.info("[Clan] 邀请: {} -> {}（clan={}）", p.getName(), target.getName(), inviterClan);
    }

    @GamePacketHandler(ClientMessage.CLAN_INVITE_ACCEPT_FIELD_NUMBER)
    public void handleClanInviteAccept(PlayerSession session, ClientMessage message) {
        Player acceptor = playerService.requirePlayer(session);
        if (acceptor == null) {
            return;
        }
        var acc = message.getClanInviteAccept();
        PendingClanInvite invite = pendingInvites.remove(acceptor.getId());
        if (invite == null || invite.inviterId() != acc.getInviterId()
                || System.currentTimeMillis() - invite.at() > INVITE_TTL_MS) {
            SessionErrors.send(session, "clan.op.noInvite");
            return;
        }
        if (!acc.getAccept()) {
            // 拒绝：原版不发通知，就地清 pending 即可
            return;
        }

        // ---- 应答时刻的复判（邀请发出后到同意之间，态势可能全变了）----
        Player inviter = playerService.byId(invite.inviterId());
        if (inviter == null || sessionManager.getSessionByCharacterId(invite.inviterId()) == null) {
            SessionErrors.send(session, "clan.op.inviteExpired");
            return;
        }
        if (!clanManager.isLeaderOrSub(invite.clanName(), inviter.getName())) {
            // 会长已转让/退会：原邀请不再有效
            SessionErrors.send(session, "clan.op.noPermission");
            return;
        }
        if (clanManager.clanNameOf(acceptor.getName()) != null) {
            SessionErrors.send(session, "clan.op.targetAlreadyInClan");
            return;
        }

        // ---- 入会（ClanManager.invite 自己还有一遍完整校验，这里过了只是省一趟）----
        String accountName = accountNameOf(session);
        ClanManager.Result r = clanManager.invite(invite.clanName(), inviter.getName(),
                acceptor.getName(), accountName, acceptor.getJob(), acceptor.getLevel());
        if (r != ClanManager.Result.OK) {
            SessionErrors.send(session, r.key());
            return;
        }

        // ---- 通知：双方系统消息 + 名牌/面板刷新（clanUpdate 广播给 acceptor 的视野含自己）----
        sendSystemKey(session, "clan.invite.welcome", Map.of("clan", invite.clanName()));
        PlayerSession inviterSession = sessionManager.getSessionByCharacterId(invite.inviterId());
        if (inviterSession != null) {
            sendSystemKey(inviterSession, "clan.invite.joined", Map.of("name", acceptor.getName()));
        }
        PlayerEntity ent = session.getEntity();
        if (ent != null) {
            aoiManager.broadcastClanUpdate(ent);
        }
        log.info("[Clan] 邀请入会完成: {} 加入 {}（会长 {}）", acceptor.getName(), invite.clanName(), inviter.getName());
    }

    private void sendSystemKey(PlayerSession session, String key, Map<String, String> params) {
        var b = S2C_SystemMessage.newBuilder().setKey(key).setTimestamp(System.currentTimeMillis());
        params.forEach(b::putParams);
        session.send(ServerMessage.newBuilder().setSystemMessage(b).build());
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
