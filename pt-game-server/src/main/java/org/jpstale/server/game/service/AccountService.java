package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
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
        CharacterInfo character = new CharacterInfo();
        character.setAccountName(accountName);
        character.setName(name);
        character.setJobCode(jobCode);
        character.setLevel(1);
        character.setExperience(0L);
        character.setGold(0);
        character.setLastStage(1); // 默认地图
        character.setIsOnline(0);
        character.setSeasonal(0);
        character.setGmLevel(0);
        character.setBanned(0);

        characterInfoMapper.insert(character);
        log.info("Created character: {} for account: {}", name, accountName);
        return character;
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
                .setGold(character.getGold() != null ? character.getGold() : 0)
                .build());
        }

        session.send(MessageProto.ServerMessage.newBuilder()
            .setCharacterList(characterListBuilder.build())
            .build());
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
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
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

        // 创建角色
        CharacterInfo character = createCharacter(accountName, name, classId);

        // 发送创建成功
        session.send(MessageProto.ServerMessage.newBuilder()
            .setCreateCharacterResult(MessageProto.S2C_CreateCharacterResult.newBuilder()
                .setSuccess(true)
                .setCharacterId(character.getId())
                .build())
            .build());

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

        // 发送角色状态
        session.send(MessageProto.ServerMessage.newBuilder()
            .setPlayerState(MessageProto.S2C_PlayerState.newBuilder()
                .setPlayerId(characterId)
                .setMapId(character.getLastStage() != null ? character.getLastStage() : 1)
                .setHp(100) // 默认HP
                .setMp(50)  // 默认MP
                .setMaxHp(100)
                .setMaxMp(50)
                .setLevel(character.getLevel() != null ? character.getLevel() : 1)
                .setGold(character.getGold() != null ? character.getGold() : 0)
                .setExp(character.getExperience() != null ? character.getExperience() : 0)
                .build())
            .build());

        log.info("Character selected: {} ({}) for account: {}",
            character.getName(), characterId, accountName);
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
            aoiManager.removePlayer(session);
        }

        // 清除账号/角色绑定，状态回 CONNECTED
        sessionManager.unbind(session.getChannel());

        // 通知客户端登出成功
        session.sendText("{\"type\":\"auth.logout\",\"data\":{\"success\":true}}");

        log.info("Character logged out: {}, account: {}", characterName, accountId);
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9]{2,12}$");
    private static final int MAX_CHARACTERS = 4;

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
