package org.jpstale.server.game.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.dao.userdb.entity.Item;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.jpstale.dao.userdb.mapper.ItemMapper;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.game.model.FieldCatalog;
import org.jpstale.server.game.model.FieldInfo;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.SessionState;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 账号服务
 * 处理登录验证、角色管理等
 */
@Slf4j
@Service
public class AccountService {

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private CharacterInfoMapper characterInfoMapper;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AOIManager aoiManager;

    @Autowired
    private MapRegionService mapRegionService;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private ItemMapper itemMapper;

    @Autowired
    private ItemListMapper itemListMapper;

    private Random random = new Random();

    /**
     * 根据账号名查找用户
     */
    public UserInfo findByUsername(String username) {
        return userInfoMapper.selectOneByAccountName(username);
    }

    /**
     * 根据ID查找用户
     */
    public UserInfo findById(Integer userId) {
        return userInfoMapper.selectById(userId);
    }

    /**
     * 根据ID查找角色
     */
    public CharacterInfo getCharacterById(Integer characterId) {
        return characterInfoMapper.selectById(characterId);
    }

    /**
     * 验证密码
     * 与 web 注册/登录约定一致：SHA256(UPPERCASE(account)+":"+明文密码) 十六进制大写
     */
    public boolean verifyPassword(UserInfo user, String password) {
        if (user == null || user.getPassword() == null || user.getAccountName() == null) {
            return false;
        }
        String hashedPassword = hashPassword(user.getAccountName(), password);
        return user.getPassword().equalsIgnoreCase(hashedPassword);
    }

    /**
     * 检查账号是否被封禁
     */
    public boolean isBanned(Integer userId) {
        if (userId == null) return false;
        UserInfo user = userInfoMapper.selectById(userId);
        return user != null && user.getBanStatus() != null && user.getBanStatus() > 0;
    }

    /**
     * 获取角色列表
     */
    public List<CharacterInfo> getCharacters(String accountName) {
        return characterInfoMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterInfo>()
                .eq(CharacterInfo::getAccountName, accountName)
        );
    }

    /**
     * 获取角色数量
     */
    public long getCharacterCount(String accountName) {
        return characterInfoMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterInfo>()
                .eq(CharacterInfo::getAccountName, accountName)
        );
    }

    /**
     * 检查角色名是否已存在
     */
    public boolean isCharacterNameExists(String name) {
        return characterInfoMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterInfo>()
                .eq(CharacterInfo::getName, name)
        ) > 0;
    }

    /**
     * 创建角色
     */
    public CharacterInfo createCharacter(String accountName, String name, int jobCode) {
        return createCharacter(accountName, name, jobCode, null);
    }

    /**
     * 创建角色（带初始装备外观，head=头型编号 0-2，rank=转职阶级，默认 0）
     */
    public CharacterInfo createCharacter(String accountName, String name, int jobCode, String headModel, int head) {
        CharacterInfo character = new CharacterInfo();
        character.setAccountName(accountName);
        character.setName(name);
        character.setJobCode(jobCode);
        character.setLevel(1);
        character.setExperience(0L);
        character.setGold(0);
        character.setOldHead(headModel != null ? headModel : "");
        character.setHead(head);
        character.setRank(0);
        // 出生地图按种族（对齐原版 START_FIELD_NUM/MORYON）：坦普族→ric(3)，魔灵族→pilai(21)
        character.setLastStage(isTempscronJob(jobCode) ? START_FIELD_NUM : START_FIELD_MORYON);
        character.setIsOnline(0);
        character.setSeasonal(0);
        character.setGmLevel(0);
        character.setBanned(0);
        character.setClanId(0);
        character.setClanPermission(0);
        character.setClanLeaveDate(0);
        character.setBlessCastleScore(0);
        character.setFsp(0);
        character.setLastSeenDate(java.time.OffsetDateTime.now());

        // 1 级职业初始属性（99 点固定分配）
        int[] init = statCalculator.getInitialStats(jobCode);
        character.setStrength(init[0]);
        character.setSpirit(init[1]);
        character.setTalent(init[2]);
        character.setAgility(init[3]);
        character.setHealth(init[4]);
        character.setStatePoint(0);

        characterInfoMapper.insert(character);
        log.info("Created character: {} (job={}, head={}, rank={}, headModel={}) for account: {}",
            name, jobCode, head, character.getRank(), character.getOldHead(), accountName);
        return character;
    }

    /**
     * 创建角色（对齐原版创建逻辑）：
     * <ul>
     *   <li>1 级属性 = 职业初始值（99 点固定分配，TempNewCharacterInit/MorNewCharacterInit）</li>
     *   <li>StatePoint = 0（1 级 99 点已分配完）</li>
     *   <li>头像模型路径存 OldHead（每职业 3 个头）</li>
     * </ul>
     */
    public CharacterInfo createCharacter(String accountName, String name, int jobCode, String headModel) {
        return createCharacter(accountName, name, jobCode, headModel, 0);
    }

    /**
     * JSON 创建角色：完整校验（登录态/名字/重名/数量上限）+ 创建（属性=职业初始值）。
     *
     * @return 错误码（0 = 成功）
     */
    public int createCharacterForSession(PlayerSession session, String name, int classId, String head) {
        if (session == null || !session.isLoggedIn()) {
            return CommonProto.ErrorCode.NOT_LOGIN.getNumber();
        }
        if (!session.getState().atLeast(SessionState.SERVER_SELECTED)) {
            return CommonProto.ErrorCode.NOT_LOGIN.getNumber();
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return CommonProto.ErrorCode.INVALID_NAME.getNumber();
        }
        if (isCharacterNameExists(name)) {
            return CommonProto.ErrorCode.NAME_EXISTS.getNumber();
        }
        String accountName = getAccountName(session);
        if (accountName == null) {
            return CommonProto.ErrorCode.NOT_LOGIN.getNumber();
        }
        if (getCharacterCount(accountName) >= MAX_CHARACTERS) {
            return CommonProto.ErrorCode.CHARACTER_LIMIT.getNumber();
        }
        createCharacter(accountName, name, classId, head);
        return 0;
    }

    /**
     * JSON 角色列表（带属性/属性点/头像），供前端角色选择页展示
     */
    public List<java.util.Map<String, Object>> listCharactersForSession(PlayerSession session) {
        List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        String accountName = getAccountName(session);
        if (accountName == null) {
            return list;
        }
        for (CharacterInfo c : getCharacters(accountName)) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("characterId", c.getId());
            m.put("name", c.getName());
            m.put("classId", c.getJobCode() != null ? c.getJobCode() : 0);
            m.put("level", c.getLevel() != null ? c.getLevel() : 1);
            m.put("gold", c.getGold() != null ? c.getGold() : 0);
            m.put("mapId", c.getLastStage() != null ? c.getLastStage() : 1);
            m.put("strength", c.getStrength() != null ? c.getStrength() : 0);
            m.put("spirit", c.getSpirit() != null ? c.getSpirit() : 0);
            m.put("talent", c.getTalent() != null ? c.getTalent() : 0);
            m.put("agility", c.getAgility() != null ? c.getAgility() : 0);
            m.put("health", c.getHealth() != null ? c.getHealth() : 0);
            m.put("statePoint", c.getStatePoint() != null ? c.getStatePoint() : 0);
            m.put("head", c.getOldHead() != null ? c.getOldHead() : "");
            list.add(m);
        }
        return list;
    }

    /**
     * 检查角色是否属于该账号
     */
    public boolean isCharacterOwned(String accountName, Integer characterId) {
        CharacterInfo character = characterInfoMapper.selectById(characterId);
        return character != null && accountName.equals(character.getAccountName());
    }

    /**
     * 密码哈希（与 web 端一致）：SHA256(UPPERCASE(account)+":"+password) 十六进制大写
     */
    private String hashPassword(String account, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((account.toUpperCase() + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    // ======== 报文入口（方案1：handler 并入 service） ========

    /**
     * 报文入口：登录
     */
    @GamePacketHandler(MessageProto.ClientMessage.LOGIN_REQUEST_FIELD_NUMBER)
    public void handleLogin(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_LoginRequest request = message.getLoginRequest();

        if (session == null) {
            log.error("No session for login request");
            return;
        }

        // 检查是否已登录
        if (session.isLoggedIn()) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.ALREADY_LOGIN, "Already logged in"));
            return;
        }

        String username = request.getUsername();
        String password = request.getPassword();

        log.info("Login attempt from account: {}", username);

        // 查找账号
        UserInfo user = findByUsername(username);
        if (user == null) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.INVALID_PASSWORD, "Invalid credentials"));
            return;
        }

        // 验证密码
        if (!verifyPassword(user, password)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.INVALID_PASSWORD, "Invalid credentials"));
            return;
        }

        // 检查封禁
        if (isBanned(user.getId())) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.ACCOUNT_BANNED, "Account is banned"));
            return;
        }

        // 检查是否已在线（顶号）
        PlayerSession existingSession = sessionManager.getSessionByAccountId(user.getId().longValue());
        if (existingSession != null && existingSession != session) {
            // 顶号：禁止旧会话断线重连
            existingSession.setAllowReconnect(false);
            existingSession.send(MessageProto.ServerMessage.newBuilder()
                .setDisconnect(MessageProto.S2C_Disconnect.newBuilder()
                    .setReason("Account logged in from another location")
                    .build())
                .build());
            existingSession.close();
        }

        // 绑定账号
        session.setAccountId(user.getId().longValue());
        session.setState(SessionState.LOGGED_IN);
        sessionManager.bindAccountId(session.getChannel(), user.getId().longValue());

        // 发送登录成功
        MessageProto.S2C_LoginResponse.Builder responseBuilder = MessageProto.S2C_LoginResponse.newBuilder()
            .setSuccess(true)
            .setAccountId(user.getId());

        session.send(MessageProto.ServerMessage.newBuilder()
            .setLoginResponse(responseBuilder.build())
            .build());

        // 登录成功 → 下发服务器列表（进入"选择服务器"阶段）
        sendServerList(session);

        log.info("Login successful for account: {}, waiting server selection", username);
    }

    /**
     * 发送服务器列表（Web JSON 命令 auth.serverList）
     */
    public void sendServerList(PlayerSession session) {
        if (session == null || !session.isLoggedIn()) {
            return;
        }
        session.sendText("{\"type\":\"auth.serverList\",\"data\":{\"servers\":["
            + "{\"id\":1,\"name\":\"Local Game Server\",\"online\":true}"
            + "]}}");
    }

    /**
     * 发送角色列表（选服成功后进入"选择角色"阶段）
     */
    public void sendCharacterList(PlayerSession session) {
        if (session == null || !session.isLoggedIn()) {
            return;
        }
        String accountName = getAccountName(session);
        if (accountName == null) {
            return;
        }
        List<CharacterInfo> characters = getCharacters(accountName);

        MessageProto.S2C_CharacterList.Builder characterListBuilder = MessageProto.S2C_CharacterList.newBuilder();
        for (CharacterInfo character : characters) {
            characterListBuilder.addCharacters(MessageProto.CharacterInfo.newBuilder()
                .setCharacterId(character.getId())
                .setName(character.getName())
                .setClassId(character.getJobCode() != null ? character.getJobCode() : 0)
                .setLevel(character.getLevel() != null ? character.getLevel() : 1)
                .setMapId(character.getLastStage() != null ? character.getLastStage() : 1)
                .setAppearance(buildAppearance(character))
                .build());
        }

        session.send(MessageProto.ServerMessage.newBuilder()
            .setCharacterList(characterListBuilder.build())
            .build());
    }

    /**
     * 构建角色外观（服务端算好最终外貌值，前端不查表）。
     * <p>
     * 联表：userdb.item(location=1 装备栏) → gamedb.itemlist(codeImg1=模型 dorpItem, modelPosition=武器挂点, classItem=物品大类)。
     * 分类用 classItem：4/6=武器（单手/双手），8=防具（身体）。
     * <p>
     * ponytail: 每个角色两条查询（N+1），角色选择列表每账号上限 MAX_CHARACTERS 个，量级极小；将来若做世界同步批量出现再改批量 IN 查询。
     */
    private CommonProto.CharacterAppearance buildAppearance(CharacterInfo character) {
        int classId = character.getJobCode() != null ? character.getJobCode() : 0;
        int head = character.getHead() != null ? character.getHead() : 0;
        int rank = character.getRank() != null ? character.getRank() : 0;

        String bodyModel = null;
        int bodyModelIdcode = 0;
        String weaponDorp = null;
        int weaponIdcode = 0;
        int weaponPos = 0;

        List<org.jpstale.dao.userdb.entity.Item> items = itemMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<org.jpstale.dao.userdb.entity.Item>()
                .eq(org.jpstale.dao.userdb.entity.Item::getCharacterId, character.getId())
                .eq(org.jpstale.dao.userdb.entity.Item::getLocation, (short) 1));

        for (org.jpstale.dao.userdb.entity.Item item : items) {
            org.jpstale.dao.gamedb.entity.ItemList def = findItemDef(item);
            if (def == null) {
                continue;
            }
            Integer classItem = def.getClassItem();
            if (classItem != null && (classItem == 4 || classItem == 6)) {
                // 武器（4=单手, 6=双手）
                weaponDorp = def.getCodeImg1();
                weaponIdcode = def.getIdCode() != null ? def.getIdCode() : 0;
                weaponPos = def.getModelPosition() != null ? def.getModelPosition() : 0;
            } else if (classItem != null && classItem == 8) {
                // 身体（防具）：同时下发 dorpItem（时装查表用）与 idcode（普通防具算 armorNum 用）
                bodyModel = def.getCodeImg1();
                bodyModelIdcode = def.getIdCode() != null ? def.getIdCode() : 0;
            }
        }

        CommonProto.CharacterAppearance.Builder b = CommonProto.CharacterAppearance.newBuilder()
            .setClassId(classId)
            .setHead(head)
            .setRank(rank);
        if (bodyModel != null) {
            b.setBodyModel(bodyModel);
        }
        if (bodyModelIdcode != 0) {
            b.setBodyModelIdcode(bodyModelIdcode);
        }
        if (weaponDorp != null) {
            b.setWeaponDorp(weaponDorp);
        }
        if (weaponIdcode != 0) {
            b.setWeaponIdcode(weaponIdcode);
        }
        b.setWeaponPos(weaponPos);
        return b.build();
    }

    /**
     * 解析物品定义（gamedb.itemlist 唯一行）。
     * <p>
     * 优先按 itemlist_id（唯一主键）精确匹配；旧数据无 itemlist_id 时回退到
     * idcode + QuestID=0 + 最小 ID（对齐原版 CreateItemMemoryTable 规则）。
     */
    private ItemList findItemDef(Item item) {
        if (item.getItemListId() != null) {
            return itemListMapper.selectOne(
                new LambdaQueryWrapper<ItemList>()
                    .eq(ItemList::getId, item.getItemListId()));
        }
        if (item.getItemCode() == null) {
            return null;
        }
        List<ItemList> defs = itemListMapper.selectList(
            new LambdaQueryWrapper<ItemList>()
                .eq(ItemList::getIdCode, item.getItemCode())
                .eq(ItemList::getQuestId, 0)
                .orderByAsc(ItemList::getId));
        return (defs == null || defs.isEmpty()) ? null : defs.get(0);
    }

    /**
     * 报文入口：创建角色
     */
    @GamePacketHandler(MessageProto.ClientMessage.CREATE_CHARACTER_FIELD_NUMBER)
    public void handleCreateCharacter(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_CreateCharacter request = message.getCreateCharacter();

        if (session == null || !session.isLoggedIn()) {
            return;
        }

        // 状态校验：须先选择服务器
        if (!session.getState().atLeast(SessionState.SERVER_SELECTED)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.NOT_LOGIN, "Select a server first"));
            return;
        }

        String name = request.getName();
        int classId = request.getClassId();
        if (!NAME_PATTERN.matcher(name).matches()) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.INVALID_NAME, "Invalid character name"));
            return;
        }

        // 检查名称是否已存在
        if (isCharacterNameExists(name)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.NAME_EXISTS, "Character name already exists"));
            return;
        }

        // 检查角色数量限制
        String accountName = getAccountName(session);
        long characterCount = getCharacterCount(accountName);
        if (characterCount >= MAX_CHARACTERS) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.CHARACTER_LIMIT, "Character limit reached"));
            return;
        }

        // 创建角色（head=头型编号 0-2，headModel=null 走旧版路径仅作占位）
        int head = request.getHead();
        CharacterInfo character = createCharacter(accountName, name, classId, null, head);

        // 发送创建成功
        session.send(MessageProto.ServerMessage.newBuilder()
            .setCreateCharacterResult(MessageProto.S2C_CreateCharacterResult.newBuilder()
                .setSuccess(true)
                .setCharacterId(character.getId())
                .build())
            .build());

        // 刷新角色列表
        sendCharacterList(session);

        log.info("Character created: {} for account: {}", name, accountName);
    }

    /**
     * 报文入口：选择角色
     */
    @GamePacketHandler(MessageProto.ClientMessage.SELECT_CHARACTER_FIELD_NUMBER)
    public void handleSelectCharacter(PlayerSession session, MessageProto.ClientMessage message) {
        MessageProto.C2S_SelectCharacter request = message.getSelectCharacter();

        if (session == null || !session.isLoggedIn()) {
            return;
        }

        // 状态校验：须先选择服务器，且不能在游戏中重复选角
        if (!session.getState().atLeast(SessionState.SERVER_SELECTED)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.NOT_LOGIN, "Select a server first"));
            return;
        }
        if (session.getState().is(SessionState.PLAYING)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.ALREADY_LOGIN, "Already in game"));
            return;
        }

        long characterId = request.getCharacterId();

        // 获取账号名
        String accountName = getAccountName(session);
        if (accountName == null) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.NOT_LOGIN, "Not logged in"));
            return;
        }

        // 验证角色归属
        if (!isCharacterOwned(accountName, (int) characterId)) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.CHARACTER_NOT_FOUND, "Character not found"));
            return;
        }

        // 获取角色信息
        CharacterInfo character = getCharacterById((int) characterId);
        if (character == null) {
            session.send(buildErrorResponse(CommonProto.ErrorCode.CHARACTER_NOT_FOUND, "Character not found"));
            return;
        }

        // 绑定角色到 Session
        session.setCharacterId(characterId);
        session.setCharacterName(character.getName());
        session.setHp(100);
        session.setMaxHp(100);
        session.setMp(50);
        session.setMaxMp(50);
        session.setLevel(character.getLevel() != null ? character.getLevel() : 1);
        sessionManager.bindCharacterId(session.getChannel(), characterId, character.getName());

        // 出生地图/位置（权威：fields.json startPoints 是玩家出生点，非刷怪点）
        int mapId = character.getLastStage() != null ? character.getLastStage() : 1;
        session.setCurrentMapId(mapId);
        float sx = 0, sz = 0;
        FieldInfo fm = FieldCatalog.get().get(mapId);
        if (fm != null) {
            java.util.List<int[]> pts = fm.getStartPoints();
            if (pts != null && !pts.isEmpty()) {
                int idx = random.nextInt(pts.size());
                sx = pts.get(idx)[0];
                sz = pts.get(idx)[1];
            } else if (fm.getCenter() != null) {
                log.warn("当前地图没有start point:{}", mapId);
                sx = fm.getCenter()[0];
                sz = fm.getCenter()[1];
            } else {
                log.warn("当前地图没有startPoint:{}", mapId);
            }
        }
        double sy = mapRegionService.getHeight(mapId, sx, sz);
        session.setX(sx);
        session.setZ(sz);
        session.setY(sy);

        // 发送进入游戏：出生地图/位置 + 完整外观 + 出生朝向（客户端据此进图渲染自机）
        MessageProto.S2C_EnterGame.Builder enterGame = MessageProto.S2C_EnterGame.newBuilder()
            .setPlayerId(characterId)
            .setMapId(mapId)
            .setPosition(CommonProto.Position.newBuilder()
                .setX(sx).setY((float) sy).setZ(sz))
            .setRotation(CommonProto.Rotation.newBuilder()
                .setX(0).setY((float) -Math.PI).setZ(0))
            .setAppearance(buildAppearance(character));

        session.send(MessageProto.ServerMessage.newBuilder()
            .setEnterGame(enterGame)
            .build());

        log.info("Character selected: {} ({}) for account: {}, spawn at map {} ({}, {}, {})",
            character.getName(), characterId, accountName, mapId, sx, sy, sz);
    }

    /**
     * 报文入口：登出
     */
    @GamePacketHandler(MessageProto.ClientMessage.LOGOUT_FIELD_NUMBER)
    public void handleLogout(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null) {
            return;
        }

        String characterName = session.getCharacterName();
        Long accountId = session.getAccountId();

        // TODO: 保存角色数据
        // playerSaveService.saveOnLogout(session);

        // 移出 AOI（若在游戏中）
        if (session.isPlaying()) {
            aoiManager.onPlayerLeave(session);
            aoiManager.removePlayer(session);
        }

        // 存档角色数据（属性/属性点/经验/金币落库）
        if (session.getCharacterId() != null) {
            playerService.persistAndRemove(session.getCharacterId());
        }

        // 清除账号/角色绑定，状态回 CONNECTED
        sessionManager.unbind(session.getChannel());

        // 通知客户端登出成功
        session.sendText("{\"type\":\"auth.logout\",\"data\":{\"success\":true}}");

        log.info("Character logged out: {}, account: {}", characterName, accountId);
    }

/**
     * 报文入口：游戏内退出到角色选择界面（回选角，保留账号会话）
     * 与登出不同：保留 accountId 绑定，仅清除角色绑定、状态回 SERVER_SELECTED。
     * 客户端可直接继续 characterList / selectCharacter。
     */
    @GamePacketHandler(MessageProto.ClientMessage.BACK_TO_CHARACTER_SELECT_FIELD_NUMBER)
    public void handleBackToCharacterSelect(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null) {
            return;
        }

        String characterName = session.getCharacterName();

        // 移出 AOI（若在游戏中）
        if (session.isPlaying()) {
            aoiManager.onPlayerLeave(session);
            aoiManager.removePlayer(session);
        }

        // 存档角色数据
        if (session.getCharacterId() != null) {
            playerService.persistAndRemove(session.getCharacterId());
        }

        // 仅清除角色绑定、状态回 SERVER_SELECTED，保留账号绑定与连接
        sessionManager.unbindCharacter(session.getChannel());

        // 通知客户端回选角成功
        session.sendText("{\"type\":\"auth.backToCharacterSelectResult\",\"data\":{\"success\":true}}");

        log.info("Character back to character select: {}, account: {}", characterName, session.getAccountId());
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9]{2,12}$");
    /** 角色数量上限（对齐原版 EU CharacterCreate：6 个） */
    private static final int MAX_CHARACTERS = 6;

    public int getMaxCharacters() {
        return MAX_CHARACTERS;
    }

    /** 出生地图（对齐 exm START_FIELD_NUM/MORYON）：坦普族→3 ric，魔灵族→21 pilai */
    private static final int START_FIELD_NUM = 3;
    private static final int START_FIELD_MORYON = 21;

    /** 坦普族职业（1,2,3,4,9），其余为魔灵族（5,6,7,8,10） */
    private static boolean isTempscronJob(int jobCode) {
        return jobCode == 1 || jobCode == 2 || jobCode == 3 || jobCode == 4 || jobCode == 9;
    }

    private String getAccountName(PlayerSession session) {
        if (session.getAccountId() != null) {
            UserInfo user = findById(session.getAccountId().intValue());
            return user != null ? user.getAccountName() : null;
        }
        return null;
    }

    private MessageProto.ServerMessage buildErrorResponse(CommonProto.ErrorCode errorCode, String message) {
        return MessageProto.ServerMessage.newBuilder()
            .setError(MessageProto.S2C_Error.newBuilder()
                .setErrorCode(errorCode)
                .setErrorMessage(message)
                .build())
            .build();
    }
}
