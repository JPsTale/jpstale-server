package org.jpstale.server.web.service;

import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.common.enums.account.AccountFlag;
import org.jpstale.server.common.enums.account.BanStatus;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 注册：写入 userdb.user_info。密码由前端按 SHA256(UPPERCASE(account)+":"+明文) 算出十六进制大写后传入，后端直接存库。
 */
@Service
public class RegisterService {

    private static final int PASSWORD_HEX_LENGTH = 64;

    private final UserInfoMapper userInfoMapper;

    public RegisterService(UserInfoMapper userInfoMapper) {
        this.userInfoMapper = userInfoMapper;
    }

    public void register(String account, String email, String passwordHash) {
        String accountName = account.trim();
        String emailTrimmed = email != null ? email.trim() : "";
        // 三种"格式不对"合成同一口径：具体是哪个字段由 @Valid 的字段注解（RegisterRequest）负责，
        // 这里只兜住"能过校验但值不合法"的情况，没必要把中文写进 Service。
        if (accountName.isEmpty()
                || emailTrimmed.isEmpty()
                || passwordHash == null
                || passwordHash.length() != PASSWORD_HEX_LENGTH
                || !passwordHash.matches("[0-9A-Fa-f]{64}")) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        if (userInfoMapper.selectOneByAccountName(accountName) != null) {
            throw new BusinessException(ResultCode.ACCOUNT_EXISTS);
        }
        if (userInfoMapper.selectOneByEmail(emailTrimmed) != null) {
            throw new BusinessException(ResultCode.EMAIL_EXISTS);
        }
        UserInfo user = new UserInfo();
        user.setAccountName(accountName);
        user.setPassword(passwordHash);
        user.setRegisDay(java.time.LocalDate.now().toString());
        user.setFlag(AccountFlag.ACTIVATED.getValue() | AccountFlag.SUPPORTER.getValue()
                | AccountFlag.ACCEPTED_LATEST_TOA.getValue() | AccountFlag.APPROVED.getValue()); // 114，与登录兼容
        user.setActive(1);
        user.setActiveCode("0");       // 与 SQL Server 原版一致
        user.setCoins(0);
        user.setEmail(emailTrimmed);
        user.setGameMasterType(0);
        user.setGameMasterLevel(0);
        user.setGameMasterMacAddress("0");  // 与 SQL Server 原版一致
        user.setCoinsTraded(0);
        user.setBanStatus(BanStatus.NOT_BANNED.getValue());
        user.setUnbanDate(null);
        user.setIsMuted(0);
        user.setMuteCount(0);
        user.setUnmuteDate(null);
        user.setWebAdmin(false);
        userInfoMapper.insert(user);
    }
}
