package org.jpstale.server.web.service;

import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.common.enums.account.BanStatus;
import org.springframework.stereotype.Service;

/**
 * Web 登录：校验账号密码（与注册同格式 SHA256 十六进制），返回用户信息供写入 Session。
 */
@Service
public class LoginService {

    private final UserInfoMapper userInfoMapper;

    public LoginService(UserInfoMapper userInfoMapper) {
        this.userInfoMapper = userInfoMapper;
    }

    /**
     * 校验账号密码，通过则返回 UserInfo（含 webAdmin），否则返回 null。
     */
    public UserInfo validate(String accountName, String passwordHash) {
        if (accountName == null || accountName.isBlank() || passwordHash == null || passwordHash.length() != 64) {
            return null;
        }
        UserInfo user = userInfoMapper.selectOneByAccountName(accountName.trim());
        if (user == null) {
            return null;
        }
        if (user.getBanStatus() != null && user.getBanStatus() != BanStatus.NOT_BANNED.getValue()) {
            return null;
        }
        if (user.getActive() == null || user.getActive() != 1) {
            return null;
        }
        if (!passwordHash.equalsIgnoreCase(user.getPassword())) {
            return null;
        }
        return user;
    }
}
