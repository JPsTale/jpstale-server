package org.jpstale.server.core.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.io.FileUtils;
import org.jpstale.dao.logdb.entity.AccountLog;
import org.jpstale.dao.logdb.mapper.AccountLogMapper;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.common.enums.map.MapId;
import org.jpstale.server.common.enums.packets.PacketHeader;
import org.jpstale.server.common.codec.PacketSender;
import org.jpstale.server.common.enums.account.AccountFlag;
import org.jpstale.server.common.enums.account.AccountLogId;
import org.jpstale.server.common.enums.account.AccountLogin;
import org.jpstale.server.common.enums.account.BanStatus;
import org.jpstale.server.common.model.UserData;
import org.jpstale.server.common.struct.Server;
import org.jpstale.server.common.struct.TransCharInfo;
import org.jpstale.server.common.struct.account.PacketAccountLoginCode;
import org.jpstale.server.common.struct.character.CharacterData;
import org.jpstale.server.common.struct.character.CharacterSave;
import org.jpstale.server.common.struct.packets.*;
import org.jpstale.server.core.AccountServer;
import org.jpstale.server.core.NetServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AccountServer 实现，对齐 C++ accountserver.cpp 的 ProcessAccountLogin 流程，
 * 使用 pt-dao 的 UserInfoMapper / AccountLogMapper 访问数据库。
 * 账号标志、封禁状态、日志类型均使用 pt-common 的枚举。
 */
@Service
public class AccountServerImpl implements AccountServer {

    private static final Logger log = LoggerFactory.getLogger(AccountServerImpl.class);

    @Autowired
    private NetServer netServer;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private AccountLogMapper accountLogMapper;
    @Autowired
    private CharacterInfoMapper characterInfoMapper;

    private static int generateLoginTicket() {
        return ThreadLocalRandom.current().nextInt(1, 1001);
    }

    @Override
    public int processAccountLogin(ChannelHandlerContext ctx, PacketLoginUser login) {
        String accountName = login.getUserId() != null ? login.getUserId().trim() : "";
        String password = login.getPassword() != null ? login.getPassword().trim() : "";
        String clientIp = getClientIp(ctx);

        Channel ch = ctx.channel();
        UserData userData = UserData.get(ch);
        if (userData == null) {
            userData = UserData.create(ch);
            userData.setClientIp(clientIp);
        }

        log.info("accountLogin accountName=[{}] ip=[{}]", accountName, clientIp);

        AccountLogin code = AccountLogin.LOGIN_PENDING;

        // 1) 空账号 / 空密码（C++ ProcessAccountLogin 1007-1016）
        if (accountName.isEmpty()) {
            code = AccountLogin.INCORRECT_NAME;
        } else if (password.isEmpty()) {
            code = AccountLogin.INCORRECT_PASSWORD;
        }

        UserInfo userInfo = null;
        if (code == AccountLogin.LOGIN_PENDING) {
            userInfo = userInfoMapper.selectOneByAccountName(accountName);
            if (userInfo == null) {
                code = AccountLogin.INCORRECT_NAME;
            }
        }

        if (code == AccountLogin.LOGIN_PENDING) {
            int flag = userInfo.getFlag() != null ? userInfo.getFlag() : AccountFlag.NOT_EXIST.getValue();

            if (flag == AccountFlag.NOT_EXIST.getValue()) {
                code = AccountLogin.INCORRECT_NAME;
            } else if ((AccountFlag.ACTIVATED.getValue() & flag) == 0) {
                code = AccountLogin.ACCOUNT_NOT_ACTIVE;
            } else if ((AccountFlag.ACCEPTED_LATEST_TOA.getValue() & flag) == 0) {
                code = AccountLogin.ACCOUNT_NOT_ACTIVE;
            } else if ((AccountFlag.APPROVED.getValue() & flag) == 0) {
                code = AccountLogin.ACCOUNT_NOT_ACTIVE;
            } else if ((AccountFlag.MUST_CONFIRM.getValue() & flag) != 0) {
                code = AccountLogin.ACCOUNT_NOT_ACTIVE;
            }

            if (code == AccountLogin.LOGIN_PENDING) {
                BanStatus banStatus = BanStatus.fromValue(userInfo.getBanStatus() != null ? userInfo.getBanStatus() : BanStatus.NOT_BANNED.getValue());
                if (banStatus == BanStatus.BANNED) {
                    code = AccountLogin.BANNED;
                } else if (banStatus == BanStatus.TEMP_BANNED) {
                    LocalDateTime unbanDate = userInfo.getUnbanDate();
                    if (unbanDate != null && LocalDateTime.now().isBefore(unbanDate)) {
                        code = AccountLogin.TEMP_BANNED;
                    } else {
                        if (userInfo.getId() != null) {
                            userInfoMapper.updateUnbanById(userInfo.getId());
                        }
                    }
                }
            }

            if (code == AccountLogin.LOGIN_PENDING && userInfo.getIsMuted() != null && userInfo.getIsMuted() != 0) {
                LocalDateTime unmuteDate = userInfo.getUnmuteDate();
                if (unmuteDate != null && LocalDateTime.now().isBefore(unmuteDate)) {
                    // 仍处于禁言期，C++ 会设置 pcUser->bMuted，登录服仅放行
                } else {
                    if (userInfo.getId() != null) {
                        userInfoMapper.updateUnmuteById(userInfo.getId());
                    }
                }
            }

            if (code == AccountLogin.LOGIN_PENDING) {
                String dbPassword = userInfo.getPassword();
                if (dbPassword == null) {
                    dbPassword = "";
                }
                if (password.equals(dbPassword)) {
                    code = AccountLogin.SUCCESS;
                } else {
                    code = AccountLogin.INCORRECT_PASSWORD;
                }
            }
        }

        String message;
        AccountLogin sendCode = code;
        if (code == AccountLogin.BANNED || code == AccountLogin.TEMP_BANNED) {
            if (code == AccountLogin.TEMP_BANNED && userInfo.getUnbanDate() != null) {
                message = "Account is banned until " + userInfo.getUnbanDate().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")) + " GMT";
            } else {
                message = messageForLoginCode(AccountLogin.BANNED);
            }
            sendCode = AccountLogin.BANNED;
        } else {
            message = messageForLoginCode(code);
        }

        if (code == AccountLogin.SUCCESS) {
            // ========= 绑定 UserData =========
            userData.setAccountName(accountName);
            if (userInfo.getId() != null) {
                userData.setAccountId(userInfo.getId());
            }
            // GM 等级（如果你有 GameMasterLevel 字段的话）
            if (userInfo.getGameMasterLevel() != null) {
                userData.setGmLevel(userInfo.getGameMasterLevel());
            }
            // 静音状态（如果当前仍然处于 mute 期间）
            if (userInfo.getIsMuted() != null && userInfo.getIsMuted() != 0) {
                userData.setMuted(true);
                if (userInfo.getUnmuteDate() != null) {
                    userData.setUnmuteExpiry(userInfo.getUnmuteDate().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                }
            } else {
                userData.setMuted(false);
                userData.setUnmuteExpiry(null);
            }
            // ========= 生成 ticket（对齐 C++：pcUserData->iTicket = Dice::RandomI(1,1000)）=========
            int ticket = generateLoginTicket();
            userData.setTicket(ticket);
            // ========= 发送登录成功 / 角色列表 / 服务器列表 =========
            sendAccountLoginCode(ctx, AccountLogin.SUCCESS, "OK");
            // 这里改成用 UserData，而不是只传 accountName，方便后面你扩展更多字段
            sendUserInfo(ctx, userData);
            sendServerList(ctx, userData);
            logAccountLogin(clientIp, accountName, AccountLogId.LOGIN_SUCCESS.getValue(), "Login Success", ctx);
        } else {
            int logId = code == AccountLogin.INCORRECT_NAME ? AccountLogId.INCORRECT_ACCOUNT.getValue()
                : code == AccountLogin.INCORRECT_PASSWORD ? AccountLogId.INCORRECT_PASSWORD.getValue()
                : AccountLogId.BLOCKED_ACCOUNT.getValue();
            logAccountLogin(clientIp, accountName, logId, message, ctx);
            sendAccountLoginCode(ctx, sendCode, message);
        }

        if (log.isTraceEnabled()) {
            log.trace("processAccountLogin result code={} for account={}", code, accountName);
        }
        return code.getValue();
    }

    private void logAccountLogin(String ip, String accountName, int logId, String description, ChannelHandlerContext ctx) {
        try {
            AccountLog logEntity = new AccountLog();
            logEntity.setIp(ip != null ? ip : "");
            logEntity.setAccountName(accountName != null ? accountName : "");
            logEntity.setLogId(logId);
            logEntity.setDescription(description != null ? description : "");
            logEntity.setServerId(1);
            accountLogMapper.insertAccountLog(logEntity);
        } catch (Exception e) {
            log.warn("insertAccountLog failed: {}", e.getMessage());
        }
    }

    private static String getClientIp(ChannelHandlerContext ctx) {
        if (ctx == null || ctx.channel() == null || ctx.channel().remoteAddress() == null) {
            return "";
        }
        return ctx.channel().remoteAddress().toString().replaceFirst("/", "");
    }

    private static String messageForLoginCode(AccountLogin code) {
        switch (code) {
            case INCORRECT_NAME:
            case ACCOUNT_NAME_NOT_FOUND:
                return "Account does not exist in the selected world";
            case INCORRECT_PASSWORD:
                return "Incorrect Password";
            case BANNED:
                return "Account is Banned";
            case LOGGED_IN:
                return "Account is already logged in";
            case IP_BANNED:
                return "Your IP is Banned";
            case YOU_ARE_BANNED:
                return "You are Banned";
            case TRY_AGAIN:
                return "Game will automatically retry";
            case MAINTENANCE:
                return "Server is in Maintenance";
            case ACCOUNT_NOT_ACTIVE:
                return "Account not active, see User Management Panel";
            case WRONG_VERSION:
                return "Version does not Match";
            case TEMP_BANNED:
                return "Temporarily Banned";
            default:
                return "Login failed";
        }
    }

    @Override
    public void sendAccountLoginCode(ChannelHandlerContext ctx, AccountLogin code, String message) {
        PacketAccountLoginCode packet = new PacketAccountLoginCode();
        packet.setPktHeader(PacketHeader.PKTHDR_AccountLoginCode);
        packet.setReserved(0);
        packet.setCode(code);
        packet.setFailCode(code == AccountLogin.LOGIN_PENDING ? 1 : (code.getValue() < 0 ? 2 : 0));
        packet.setMessage(message != null ? message : "");
        if (log.isTraceEnabled()) {
            log.trace("sendAccountLoginCode {}", packet);
        }
        PacketSender.sendPacket(ctx, packet);
    }

    /**
     * 更接近 C++ OnLoginSuccess(UserData* pcUserData) 的版本：
     * 通过 UserData 拿账号名，然后查询 CharacterInfo 表，填充 PacketUserInfo。
     */
    @Override
    public void sendUserInfo(ChannelHandlerContext ctx, UserData userData) {
        String accountName = userData.getAccountName() != null ? userData.getAccountName() : "";
        PacketUserInfo packet = new PacketUserInfo();
        packet.setPktHeader(PacketHeader.PKTHDR_UserInfo);
        packet.setUserId(accountName);

        // TODO: 这里按照 C++ AccountServer::OnLoginSuccess 的逻辑，
        // 去 CharacterInfo 表查出该账号最多 6 个角色，填充 packet.getCharacterData()[i]
        int charCount = 0;
        List<CharacterInfo> charList = characterInfoMapper.selectTop6CharacterByAccountNameAndSeason(accountName, 999);
        if (!charList.isEmpty()) {
            for (CharacterInfo characterInfo : charList) {
                String charName = characterInfo.getName();

                String path = "Data" + File.pathSeparator + "Character" + File.pathSeparator + charName + ".chr";
                File file = new File(path);
                if (file.exists()) {
                    try {
                        byte[] filedata = FileUtils.readFileToByteArray(file);
                        // 读取二进制文件，保存到 PacketCharacterRecordData
                        PacketCharacterRecordData recordData = new PacketCharacterRecordData();
                        ByteBuffer bb = ByteBuffer.wrap(filedata).order(ByteOrder.LITTLE_ENDIAN);
                        recordData.readFrom(bb);

                        TransCharInfo charInfo = packet.getCharacterData()[charCount];
                        CharacterData charData = recordData.getCharacterData();
                        CharacterSave saveData = recordData.getCharacterSaveData();

                        charInfo.setName(charData.getName());
                        charInfo.setModelName(charData.getPlayerBodyModel());
                        charInfo.setModelName2(charData.getPlayerHeadModel());
                        charInfo.setLevel(charData.getLevel());
                        charInfo.setJobCode(charData.getClazz().getValue());
                        charInfo.setArmorCode(0);
                        charInfo.setBrood(charData.getMonsterType().getValue());
                        charInfo.setStartField(saveData.getMapId().getValue());
                        charInfo.setPosX(saveData.getCameraPositionX());
                        charInfo.setPosZ(saveData.getCameraPositionZ());

                        // Is in SOD?
                        if (saveData.getMapId() == MapId.BELLATRA) {
                            charInfo.setStartField(MapId.NAVISKO_TOWN.getValue());
                        }
                        // Is in Fury Arena ( Quest )?
                        else if (saveData.getMapId() == MapId.QUEST_ARENA) {
                            charInfo.setStartField(MapId.PILLAI_TOWN.getValue());
                        }
                        // Is in T5 Arena ( Quest )?
                        else if (saveData.getMapId() == MapId.T5_QUEST_ARENA) {
                            charInfo.setStartField(MapId.ATLANTIS.getValue());
                        }

                        charCount++;
                    } catch (Exception e) {
                        log.error("read character file error, path:{}", path, e);
                    }
                }
            }
        }

        // 当前先占位：
        packet.setCharCount(charCount);
        PacketSender.sendPacket(ctx, packet);

        /***
         * TODO
         * 	SENDPACKET( USERDATATOUSER(pcUserData), &sPacketUserInfoLogin );
         *
         * 	AccountLogin al;
         * 	STRINGCOPY( al.szAccountName, pcUserData->szAccountName );
         *
         * 	// Log data
         * 	if ( pcUserData )
         * 		LogAccountLogin( pcUserData, al, ACCLOGID_CharacterSelectSend );
         */
    }

    @Override
    public void sendServerList(ChannelHandlerContext ctx, UserData userData) {
        int ticket = userData.getTicket() != null ? userData.getTicket() : 0;

        PacketServerList packet = new PacketServerList();
        packet.setPktHeader(PacketHeader.PKTHDR_ServerList);
        PacketServerListHeader header = new PacketServerListHeader();
        header.setServerName("Local"); // TODO: 从配置或 DB 读取
        header.setTime((int) (System.currentTimeMillis() / 1000L));
        header.setTicket(ticket);
        header.setUnknown(0);
        header.setClanServerIndex(0);
        header.setGameServers(1);
        packet.setHeader(header);

        Server[] servers = new Server[4];
        for (int i = 0; i < servers.length; i++) {
            servers[i] = new Server();
        }

        // TODO: 这里将来可以从配置/DB 读出多个 game 服信息，现在先保留你原来的写死版本
        Server s0 = servers[0];
        s0.setName("A Dedicated Java Server");
        s0.getIp()[0] = "127.0.0.1";
        s0.getIp()[1] = "127.0.0.1";
        s0.getIp()[2] = "127.0.0.1";
        s0.getPort()[0] = 10007;
        s0.getPort()[1] = 10007;
        s0.getPort()[2] = 10007;
        s0.getPort()[3] = 0;
        packet.setServers(servers);
        PacketSender.sendPacket(ctx, packet);
    }
}
