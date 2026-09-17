package org.jpstale.server.web.service;

import cn.dev33.satoken.stp.StpUtil;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.common.enums.account.BanStatus;
import org.jpstale.server.web.enums.ResultCode;
import org.jpstale.server.web.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 修改密码：仅允许当前登录用户修改自己的密码。
 */
@Service
public class ChangePasswordService {

    private final UserInfoMapper userInfoMapper;

    public ChangePasswordService(UserInfoMapper userInfoMapper) {
        this.userInfoMapper = userInfoMapper;
    }

    public void changePassword(String oldPasswordHash, String newPasswordHash) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        // 登录时已在 Session 中写入 accountName，修改密码时以 accountName 为准，避免 loginId 类型差异。
        String accountName = StpUtil.getSession().getString("accountName");
        if (accountName == null || accountName.isBlank()) {
            throw new BusinessException(ResultCode.NOT_LOGIN);
        }
        UserInfo user = userInfoMapper.selectOneByAccountName(accountName.trim());
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (user.getBanStatus() != null && user.getBanStatus() != BanStatus.NOT_BANNED.getValue()) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        if (user.getActive() == null || user.getActive() != 1) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        if (!oldPasswordHash.equalsIgnoreCase(user.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_WRONG);
        }
        if (oldPasswordHash.equalsIgnoreCase(newPasswordHash)) {
            throw new BusinessException(ResultCode.PASSWORD_UNCHANGED);
        }
        UserInfo update = new UserInfo();
        update.setId(user.getId());
        update.setPassword(newPasswordHash);
        userInfoMapper.updateById(update);
    }
}
