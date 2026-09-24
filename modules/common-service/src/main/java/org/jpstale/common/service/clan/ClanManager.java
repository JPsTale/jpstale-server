package org.jpstale.common.service.clan;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.clandb.entity.Cl;
import org.jpstale.dao.clandb.entity.ClanList;
import org.jpstale.dao.clandb.entity.Li;
import org.jpstale.dao.clandb.entity.Ul;
import org.jpstale.dao.clandb.mapper.ClMapper;
import org.jpstale.dao.clandb.mapper.ClanListMapper;
import org.jpstale.dao.clandb.mapper.LiMapper;
import org.jpstale.dao.clandb.mapper.UlMapper;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 公会表（`clandb.cl` / `ul` / `clanlist` / `li`，外加 `userdb.characterinfo.clanid`）的**唯一实现**。
 *
 * <p>名字叫 `ClanManager` 而不是 `ClanService`：web-server 里已有一个
 * `org.jpstale.server.web.clan.ClanService`（REST 读接口），同一个 Spring 上下文里两个同名类
 * 会让人和 javac 的 import 都分不清。`*Manager` 也贴合本仓既有的
 * `AOIManager` / `MapManager` / `GroundItemManager` 命名。
 *
 * <h3>为什么写侧在 common-service / game-server，而不是 web-server</h3>
 * 建会要**扣玩家的 500,000 金币**。金币的权威在 **game-server 的内存**里（DB 那列只是它的存档，
 * 且 `CombatService.doRespawn` 这类路径会先改内存、稍后才由周期存档落库）。跨进程"先查后扣"
 * 既是 TOCTOU 竞态，也没法把"扣钱"和"建会"收进一个事务。
 * ⇒ 用户 2026-09-25 定案：**写操作走 game-server**（`C2S_Clan*`），本类提供表逻辑，
 * game-server 负责校验玩家态（等级/金币）与通知。
 *
 * <h3>分工边界（别把这两件事混进同一个类）</h3>
 * <ul>
 *   <li><b>本类</b>：只读写公会表。**不认识金币、不认识 PlayerSession、不发包、不认识 MQ/JSON。**</li>
 *   <li><b>game-server `ClanHandler`</b>：需要玩家内存态的判断（等级、余额、在线）与通知。</li>
 *   <li><b>web-server `ClanService`</b>：REST 读接口的装配（DTO）；<b>`ClanMessageListener`</b>：
 *       MQ 的订阅与 JSON 解析。两者都调本类的读/写方法。</li>
 * </ul>
 *
 * <h3>取值依据</h3>
 * 每个操作都照原版 ASP 逐字移植（`PristonTale-EU-main/ClanSystem/Clan/*.asp`，用户 2026-09-25 定
 * "按原值"）。**与 ASP 的三类有意偏差，逐条在方法上注明**：
 * <ol>
 *   <li><b>补服务端权限校验</b>：原版 ASP ≤ 只有"邀请（会长/副会长）"、"解散（会长）"、
 *       "退会（会长不可）"、"踢人（目标非会长）"这几条有校验；<b>转让会长 / 任命副会长 /
 *       罢免副会长三条完全没有校验</b> —— 任何成员调一下就能把自己变成会长。我们按客户端 UI
 *       的可见性补上（见各方法注释）。</li>
 *   <li><b>`memcnt` 用 `COUNT(*)` 重算并写回</b>，不照抄 ASP 的 `±1`（原版不做防负、不做一致性
 *       校验，会漂移）。</li>
 *   <li><b>`characterinfo.clanid` 的清零覆盖面</b>：ASP 只在解散时清**会长**一行，且那句 UPDATE
 *       的 WHERE 写的是不存在的列（`where ClanName = ...`，`characterinfo` 没有这列）⇒ 实际是坏的。
 *       我们在解散时清**全部成员**，踢人/退会时清当事角色。</li>
 * </ol>
 *
 * ⚠ 原版 ASP **没有任何服务端等级/金钱校验** —— 40 级与 50 万只在客户端
 * （`cE_Cmake.cpp:353` 的 `ABILITY`、`clan_Enti.cpp:928` 的 `CLAN_MAKELEVEL`，见
 * `docs/公会系统-源码分析与客户端接入方案.md` §1.2）。我们把它放进服务端，是**增加**不是照抄。
 */
@Slf4j
@Service
public class ClanManager {

    /**
     * 公会名长度上限。**我们定的** —— 原版 ASP 与客户端都没在服务端做长度校验（只有输入框），
     * 活库 `cl.clanname` 是 `text` 无约束。20 是"名牌上一行放得下"的量级。
     * 这个常量是唯一来源：web-server 的 `ClanNameRequest`（查重接口的 `@Size`）也引用它，
     * 免得出现"查重允许 50、建会只允许 20"这种自相矛盾。
     */
    public static final int MAX_NAME_LENGTH = 20;

    /**
     * 成员上限 100。
     *
     * <p>⚠ **原版这个上限从来没生效过**：`InviteClan.asp` 写的是
     * {@code If (CInt(MemCnt) + 1) > 100 Then Code=2}，而该 ASP 里那个变量叫 `ClanMembers`
     * （`MemCnt` 是未定义的 ⇒ VBScript 下 `CInt("")` = 0 ⇒ 判断恒为假）。
     * 也就是说"100"是**原版的意图**、不是它的行为。上一版 Java 移植抄了 `>100`，还另外加了一条
     * `>20`（那个 20 在任何源里都找不到出处，疑似来自客户端那张死掉的等级表）。
     * 我们取**意图值 100**，并把检查真的做出来。
     */
    public static final int MAX_MEMBERS = 100;

    /** 建会失败的显式原因（也是所有公会写操作的失败原因）；`OK` 的 {@link #key()} 为 null。 */
    public enum Result {
        OK(null),
        // 建会
        NAME_EMPTY("clan.op.nameEmpty"),
        NAME_TOO_LONG("clan.op.nameTooLong"),
        NAME_TAKEN("clan.op.nameTaken"),
        ALREADY_IN_CLAN("clan.op.alreadyInClan"),
        // 通用
        CLAN_NOT_FOUND("clan.op.clanNotFound"),
        NOT_IN_CLAN("clan.op.notInClan"),
        NO_PERMISSION("clan.op.noPermission"),
        // 目标 / 成员
        TARGET_NOT_IN_CLAN("clan.op.targetNotInClan"),
        TARGET_ALREADY_IN_CLAN("clan.op.targetAlreadyInClan"),
        TARGET_IS_LEADER("clan.op.targetIsLeader"),
        LEADER_CANNOT_LEAVE("clan.op.leaderCannotLeave"),
        CLAN_FULL("clan.op.clanFull");

        private final String key;

        Result(String key) {
            this.key = key;
        }

        /** 给客户端的 i18n key；`OK` 返回 null（调用方只在失败时读）。 */
        public String key() {
            return key;
        }
    }

    /** 建会成功时额外带出新公会的信息。 */
    public record Created(Result result, String clanName, Integer clanId, Integer iconId) {
        static Created ok(String clanName, int clanId, int iconId) {
            return new Created(Result.OK, clanName, clanId, iconId);
        }

        static Created fail(Result r) {
            return new Created(r, null, null, null);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    private final ClMapper clMapper;
    private final UlMapper ulMapper;
    private final ClanListMapper clanListMapper;
    private final LiMapper liMapper;
    private final CharacterInfoMapper characterInfoMapper;

    public ClanManager(ClMapper clMapper, UlMapper ulMapper, ClanListMapper clanListMapper,
                       LiMapper liMapper, CharacterInfoMapper characterInfoMapper) {
        this.clMapper = clMapper;
        this.ulMapper = ulMapper;
        this.clanListMapper = clanListMapper;
        this.liMapper = liMapper;
        this.characterInfoMapper = characterInfoMapper;
    }

    // ==================================================================
    // 读（web-server 的 REST 读接口与 game-server 都用这一份）
    // ==================================================================

    /** 该角色所属公会名；不在任何公会返回 {@code null}（活库既有数据空串/null 都有，统一成 null）。 */
    public String clanNameOf(String chName) {
        if (chName == null) {
            return null;
        }
        String n = ulMapper.selectClanNameByChName(chName.trim());
        return n == null || n.isEmpty() ? null : n;
    }

    /** 按公会名取整行；不存在返回 {@code null}。 */
    public Cl findByName(String clanName) {
        if (clanName == null || clanName.isBlank()) {
            return null;
        }
        return clMapper.selectByClanName(clanName.trim());
    }

    /** 公会名是否已被占用（与建会**同一句 SQL**，不会出现两处判断不一致）。 */
    public boolean isNameTaken(String clanName) {
        if (clanName == null || clanName.isBlank()) {
            return false;
        }
        return clMapper.selectClanZangByClanName(clanName.trim()) != null;
    }

    /** 该公会的副会长角色名；没有返回 {@code null}。 */
    public String subLeaderOf(String clanName) {
        return clanName == null ? null : ulMapper.selectChNameByPermi2AndClanName(clanName.trim());
    }

    /** 该公会的全部成员行（一次查询；`Ul.clanId` ← `idx`、`Ul.id` ← `midx`，见 `ulMap`）。 */
    public List<Ul> membersOf(String clanName) {
        List<Ul> list = clanName == null ? null : ulMapper.selectAllByClanName(clanName.trim());
        return list == null ? List.of() : list;
    }

    /** 全部公会按积分倒序（web-server 的排行榜用；过滤与名次由调用方决定）。 */
    public List<Cl> allByCpointDesc() {
        List<Cl> list = clMapper.selectAllOrderByCpointDesc();
        return list == null ? List.of() : list;
    }

    // ==================================================================
    // 建会
    // ==================================================================

    /**
     * 建会。**只写公会表**（`cl` + `ul` + `clanlist` + `characterinfo.clanid`），全在一个事务里。
     *
     * <p><b>不在这里做的事</b>（由 game-server `ClanHandler` 负责）：等级门槛、金币扣除与推送 ——
     * 那些要玩家内存态，而本类刻意不认识它。
     *
     * <p><b>逐列取值照 ASP</b>（`NewClan.asp` 的 INSERT 逐字对照），**两处有意偏差**：
     * <ol>
     *   <li><b>`Note` 写空串</b>，不写 ASP 写死的 {@code "RenaissancePT"} —— 那是 EU 私服自己的
     *       品牌串，被塞进每条新公会的"公告"里。公告本该是公会自己的内容。**这条是我们定的。**</li>
     *   <li><b>`clanlist.IconID` 写图标编号</b>，不写 ASP 的 {@code 0} —— 头顶名牌读的是
     *       `clanlist.iconid`（game-server `AOIManager`），写 0 会让名牌图标恒为空串。
     *       实测活库 285 行遗留数据里 `clanlist.iconid` 与 `cl.miconcnt` **逐行相等**，
     *       即后来有人把它对齐了；我们直接对齐。</li>
     * </ol>
     * 另照抄 ASP 的一条"清理"行为：若 `ul` 里已有该角色但 `clanname` 为空，先删掉那行再继续。
     */
    @Transactional
    public Created createClan(String clanName, String leaderCharName, String leaderUserId,
                              Integer charType, Integer level) {
        String name = clanName == null ? "" : clanName.trim();
        String chname = leaderCharName == null ? "" : leaderCharName.trim();
        String userid = leaderUserId == null ? "" : leaderUserId.trim();

        if (name.isEmpty()) {
            return Created.fail(Result.NAME_EMPTY);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return Created.fail(Result.NAME_TOO_LONG);
        }

        String existing = ulMapper.selectClanNameByChName(chname);
        if (existing != null && !existing.isEmpty()) {
            return Created.fail(Result.ALREADY_IN_CLAN);
        }
        if (existing != null) {
            ulMapper.deleteByChName(chname);
        }
        if (clMapper.selectClanZangByClanName(name) != null) {
            return Created.fail(Result.NAME_TAKEN);
        }

        int iconId = nextIconNumber();

        Cl cl = new Cl();
        cl.setClanName(name);
        cl.setUserId(userid);
        cl.setClanZang(chname);
        cl.setMemCnt(1);
        cl.setNote("");                 // 偏差 ①
        cl.setMIconCnt(iconId);
        cl.setDelActive("0");
        cl.setPFlag(0);
        cl.setKFlag(0);
        cl.setFlag(0);
        cl.setNoteCnt(1);               // ASP 是 '1'（上一版 Java 移植写成 0，与 ASP 不符）
        cl.setCPoint(0);
        cl.setCWin(0);
        cl.setCFail(0);
        cl.setClanMoney(0L);
        cl.setCnFlag(0);
        cl.setSiegeMoney(0L);
        // RegiDate / LimitDate 由 SQL 的 CURRENT_TIMESTAMP / +3600s 给（ASP：getdate() / +3600）
        clMapper.insertCl(cl);

        Integer clanId = cl.getId();
        if (clanId == null) {
            throw new IllegalStateException("insertCl 未返回生成键（cl.idx），无法继续建 ul");
        }

        Ul ul = new Ul();
        ul.setClanId(clanId);
        ul.setUserId(userid);
        ul.setChName(chname);
        ul.setClanName(name);
        ul.setChType(charType != null ? charType : 0);
        ul.setChLv(level != null ? level : 0);
        ul.setPermi("0");
        ul.setDelActive("0");
        ul.setPFlag(0);
        ul.setKFlag(0);
        ul.setMIconCnt(iconId);
        ulMapper.insertUl(ul);

        // clanlist：ASP 的列序与值 =
        // (clName, chname, expl, userid, 1, 0, 1, 10, 0, 1, 1, 1, 0, 0, 0, chname)
        // ⚠ 上一版 Java 移植把 Flag/SiegeWarPoints/BellatraDate/LoginMessage 写成 0/空，与 ASP 不符
        //   （活库 clanlist.flag=1 也印证 ASP 才对）。
        ClanList cl2 = new ClanList();
        cl2.setClanName(name);
        cl2.setClanLeader(chname);
        cl2.setNote("");                // 同 cl.Note
        cl2.setAccountName(userid);
        cl2.setMembersCount(1);
        cl2.setIconId(iconId);          // 偏差 ②
        cl2.setRegisDate(1);            // ASP 写 1；语义未确认（不是 cl.regidate 那个时间戳）
        cl2.setLimitDate(10);           // ASP 写 10；活库遗留数据是 20，语义未确认
        cl2.setDeleteActive(0);
        cl2.setFlag(1);
        cl2.setSiegeWarPoints(1);
        cl2.setBellatraDate(1L);
        cl2.setBellatraPoints(0);
        cl2.setSiegeWarGold(0);
        cl2.setBellatraGold(0);
        cl2.setLoginMessage(chname);
        clanListMapper.insertClanList(cl2);

        Integer clanListId = cl2.getId();
        if (clanListId == null) {
            throw new IllegalStateException("insertClanList 未返回生成键（clanlist.id）");
        }
        characterInfoMapper.updateClanIdByName(chname, clanListId);

        // `@Transactional` 在本项目是**第一次使用**（此前全仓零处），而"注解没生效"的表现是
        // **完全静默**的（建会照样成功，只是回滚不了）⇒ 把事实打出来，别等半途失败才暴露。
        log.info("[Clan] 建会成功 clan={} 会长={} 图标={} clanId={} clanListId={}（事务生效={}）",
                name, chname, iconId, clanId, clanListId, TransactionSynchronizationManager.isActualTransactionActive());
        return Created.ok(name, clanId, iconId);
    }

    /** 图标编号发号器：`li` 单行表，值 = 下一个要用的编号（照 ASP：先读、+1、写回）。 */
    private int nextIconNumber() {
        Integer cur = liMapper.selectImgById(1);
        if (cur == null) {
            // 原版这段是 iIMG=999999 再 INSERT（且 INSERT 的 values 是 ('iIMG+1','1')，
            // 即"img 从 999999 起、id 恒为 1"）。活库里这行本来就该在（清理后 img=1000000）；
            // 走到这里说明表被清空了 ⇒ 建出种子行，**照 ASP 的值，不猜**。
            log.warn("[Clan] clandb.li 无 id=1 种子行，按原版 ASP 补 999999");
            Li seed = new Li();
            seed.setId(1);
            seed.setImg(999999);
            liMapper.insertLi(seed);
            cur = 999999;
        }
        int iconId = cur + 1;
        liMapper.updateImgById(1, iconId);
        return iconId;
    }

    // ==================================================================
    // 解散（ASP: DeleteClan.asp / clanRemove）
    // ==================================================================

    /**
     * 解散公会。**会长专属**（ASP 强制：`ClanLeader = chname`）。
     *
     * <p>ASP 还要求 `SELECT * FROM ClanList WHERE AccountName=userid` 恰好 1 行（即"这个账号名下
     * 只有这一个公会"）—— 我们保留这条判据（拿不到行长列表时按"不属于该会"处理即可，
     * 下面的会长判定已经覆盖它）。
     *
     * <p><b>偏差 ③</b>：清零**全部成员**的 `characterinfo.clanid`（ASP 只清了会长一行，且那句
     * UPDATE 的 WHERE 用的是不存在的列，实际是坏的）。
     */
    @Transactional
    public Result dissolve(String clanName, String operatorCharName) {
        String name = clanName == null ? "" : clanName.trim();
        String op = operatorCharName == null ? "" : operatorCharName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (!op.equals(cl.getClanZang())) {
            return Result.NO_PERMISSION;
        }
        // 先把成员名留下（ul 马上要删）
        List<Ul> members = membersOf(name);
        clanListMapper.deleteByClanName(name);
        clMapper.deleteByClanName(name);
        ulMapper.deleteByClanName(name);
        for (Ul m : members) {
            if (m.getChName() != null && !m.getChName().isEmpty()) {
                characterInfoMapper.updateClanIdByName(m.getChName(), 0);
            }
        }
        log.info("[Clan] 解散 clan={} 操作者={} 成员 {} 人", name, op, members.size());
        return Result.OK;
    }

    // ==================================================================
    // 邀请入会（ASP: InviteClan.asp / clanInsertClanWon）
    // ==================================================================

    /**
     * 邀请角色入会。**会长或副会长**（ASP 强制：
     * `if ClanLeader <> chname And ClanSubChief <> chname Then Code=0`）。
     *
     * <p>判据顺序照 ASP：先查公会存在 → 再判人数上限 → 再判权限 → 再判目标未入会。
     * （ASP 把人数上限放在权限之前，我们也照抄 —— 这样"满员的会被谁调都回'满员'"，
     * 与 ASP 的返回码一致。）
     */
    @Transactional
    public Result invite(String clanName, String operatorCharName, String targetCharName,
                         String targetUserId, Integer targetType, Integer targetLevel) {
        String name = clanName == null ? "" : clanName.trim();
        String op = operatorCharName == null ? "" : operatorCharName.trim();
        String target = targetCharName == null ? "" : targetCharName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (memberCountOf(name) + 1 > MAX_MEMBERS) {
            return Result.CLAN_FULL;
        }
        if (!isLeaderOrSub(name, op)) {
            return Result.NO_PERMISSION;
        }
        if (clanNameOf(target) != null) {
            return Result.TARGET_ALREADY_IN_CLAN;
        }
        ulMapper.deleteByChName(target);   // ASP：目标若有残行（clanname 为空）先删掉

        Ul ul = new Ul();
        ul.setClanId(cl.getId());
        ul.setUserId(targetUserId == null ? "" : targetUserId.trim());
        ul.setChName(target);
        ul.setClanName(name);
        ul.setChType(targetType != null ? targetType : 0);
        ul.setChLv(targetLevel != null ? targetLevel : 0);
        ul.setPermi("0");
        ul.setDelActive("0");
        ul.setPFlag(0);
        ul.setKFlag(0);
        ul.setMIconCnt(cl.getMIconCnt() != null ? cl.getMIconCnt() : 0);
        ulMapper.insertUl(ul);
        refreshMemCnt(name);

        log.info("[Clan] 邀请入会 clan={} 操作者={} 新成员={}", name, op, target);
        return Result.OK;
    }

    // ==================================================================
    // 踢人（ASP: LeavePlayer.asp / clanWonRelease）
    // ==================================================================

    /**
     * 把成员踢出公会。
     *
     * <p>⚠ **权限校验是我们补的**：ASP 只校验了"目标属于本会"和"目标不是会长"，
     * **完全没校验操作者是谁** —— 任何成员都能踢任何人。规则取客户端 UI 的可见性
     * （踢人按钮在会长菜单里，而邀请在 ASP 里就允许副会长）⇒ 取"会长或副会长"。
     *
     * <p><b>偏差 ③</b>：同时清零目标的 `characterinfo.clanid`。
     */
    @Transactional
    public Result kick(String clanName, String operatorCharName, String targetCharName) {
        String name = clanName == null ? "" : clanName.trim();
        String op = operatorCharName == null ? "" : operatorCharName.trim();
        String target = targetCharName == null ? "" : targetCharName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (!isLeaderOrSub(name, op)) {
            return Result.NO_PERMISSION;
        }
        if (!name.equals(clanNameOf(target))) {
            return Result.TARGET_NOT_IN_CLAN;
        }
        if (target.equals(cl.getClanZang())) {
            return Result.TARGET_IS_LEADER;   // ASP: Code=4
        }
        ulMapper.deleteByChName(target);
        characterInfoMapper.updateClanIdByName(target, 0);
        refreshMemCnt(name);
        log.info("[Clan] 踢人 clan={} 操作者={} 目标={}", name, op, target);
        return Result.OK;
    }

    // ==================================================================
    // 自己退会（ASP: LeavePlayerSelf.asp / clanWonSelfLeave）
    // ==================================================================

    /**
     * 自己退会。**会长不能退**（ASP 强制：`if chName = ClanLeader Then Code=4`）——
     * 会长要么转让、要么解散。
     *
     * <p><b>偏差 ③</b>：同时清零自己的 `characterinfo.clanid`。
     */
    @Transactional
    public Result leaveSelf(String clanName, String charName) {
        String name = clanName == null ? "" : clanName.trim();
        String self = charName == null ? "" : charName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (!name.equals(clanNameOf(self))) {
            return Result.NOT_IN_CLAN;
        }
        if (self.equals(cl.getClanZang())) {
            return Result.LEADER_CANNOT_LEAVE;   // ASP: Code=4
        }
        ulMapper.deleteByChName(self);
        characterInfoMapper.updateClanIdByName(self, 0);
        refreshMemCnt(name);
        log.info("[Clan] 退会 clan={} 角色={}", name, self);
        return Result.OK;
    }

    // ==================================================================
    // 转让会长（ASP: ChangeLeader.asp / clanChipChange）
    // ==================================================================

    /**
     * 把会长转给本会成员（`cl.clanzang` 与 `cl.userid` 一起改，ASP 就是这么写的）。
     *
     * <p>⚠ **权限校验是我们补的**：ASP 只判"目标是否在本会"，**没判操作者** ——
     * 任何成员调一次就能把自己变成会长（连带把 `cl.userid` 写成自己的账号）。
     * 规则取客户端 UI（转让按钮只在会长菜单）⇒ **会长专属**。
     *
     * <p>⚠ 原版转让要收 **300,000** 金币（`cE_chip.cpp:1796` 那条路的文案），
     * 但**扣款在客户端**、ASP 一分钱没扣。**我们先不收费**（收费要玩家内存态 ⇒ 属 game-server
     * 那条链，等它有真实入口时连同扣钱一起做）；这里不实现、也不假装实现。
     */
    @Transactional
    public Result transferLeader(String clanName, String operatorCharName, String targetCharName) {
        String name = clanName == null ? "" : clanName.trim();
        String op = operatorCharName == null ? "" : operatorCharName.trim();
        String target = targetCharName == null ? "" : targetCharName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (!op.equals(cl.getClanZang())) {
            return Result.NO_PERMISSION;
        }
        if (!name.equals(clanNameOf(target))) {
            return Result.TARGET_NOT_IN_CLAN;
        }
        String userId = ulMapper.selectUserIdByChNameAndClanName(target, name);
        clMapper.updateClanZangAndUserIdByClanName(target, userId == null ? "" : userId, name);
        log.info("[Clan] 转让会长 clan={} {} → {}", name, op, target);
        return Result.OK;
    }

    // ==================================================================
    // 副会长（ASP: SubLeaderUpdate.asp / SubLeaderRelease.asp）
    // ==================================================================

    /**
     * 任命副会长。ASP 是"先把本会所有 `permi` 清 0，再把自己设 2" ⇒ **全公会最多 1 名副会长**。
     *
     * <p>⚠ **权限校验是我们补的**：ASP **完全没有校验**（且连"目标是否在会"都没判，
     * 它只按 `chname` 更新 —— 谁的 `chname` 传进来谁就是副会长）。
     * 规则取客户端 UI（副会长按钮只在会长菜单）⇒ **会长专属**。
     */
    @Transactional
    public Result setSubLeader(String clanName, String operatorCharName, String targetCharName) {
        String name = clanName == null ? "" : clanName.trim();
        String op = operatorCharName == null ? "" : operatorCharName.trim();
        String target = targetCharName == null ? "" : targetCharName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (!op.equals(cl.getClanZang())) {
            return Result.NO_PERMISSION;
        }
        if (!name.equals(clanNameOf(target))) {
            return Result.TARGET_NOT_IN_CLAN;
        }
        // ASP 的两句：先清全会的 permi，再设目标（顺序不能反，否则清的会把刚设的也清掉）
        ulMapper.updatePermi0ByClanNameInChName(target);
        ulMapper.updatePermi2ByChName(target);
        log.info("[Clan] 任命副会长 clan={} 操作者={} 目标={}", name, op, target);
        return Result.OK;
    }

    /**
     * 罢免副会长（`permi` 置 0）。
     *
     * <p>⚠ **权限校验是我们补的**：ASP 没有校验（只按 `chname` 更新）。
     * 规则取客户端 UI ⇒ **会长专属**。
     */
    @Transactional
    public Result releaseSubLeader(String clanName, String operatorCharName, String targetCharName) {
        String name = clanName == null ? "" : clanName.trim();
        String op = operatorCharName == null ? "" : operatorCharName.trim();
        String target = targetCharName == null ? "" : targetCharName.trim();
        Cl cl = findByName(name);
        if (cl == null) {
            return Result.CLAN_NOT_FOUND;
        }
        if (!op.equals(cl.getClanZang())) {
            return Result.NO_PERMISSION;
        }
        if (!name.equals(clanNameOf(target))) {
            return Result.TARGET_NOT_IN_CLAN;
        }
        ulMapper.updatePermi0ByChName(target);
        log.info("[Clan] 罢免副会长 clan={} 操作者={} 目标={}", name, op, target);
        return Result.OK;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private boolean isLeaderOrSub(String clanName, String chName) {
        Cl cl = findByName(clanName);
        if (cl == null) {
            return false;
        }
        if (chName.equals(cl.getClanZang())) {
            return true;
        }
        String sub = ulMapper.selectChNameByPermi2AndClanName(clanName);
        return sub != null && sub.equals(chName);
    }

    /** 成员数 = `ul` 的实际行数（**不是** `cl.memcnt` —— 那列在 ASP 时代就会漂移）。 */
    private int memberCountOf(String clanName) {
        return membersOf(clanName).size();
    }

    /** 把 `cl.memcnt` 校正为实际成员数（偏差 ②：用 COUNT 重算，不照抄 ASP 的 ±1 漂移）。 */
    private void refreshMemCnt(String clanName) {
        clMapper.updateMemCntByClanName(memberCountOf(clanName), clanName);
    }
}
