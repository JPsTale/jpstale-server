package org.jpstale.server.web.service;

import cn.dev33.satoken.stp.StpUtil;
import org.jpstale.dao.userdb.entity.UserInfo;
import org.jpstale.dao.userdb.mapper.UserInfoMapper;
import org.jpstale.server.common.enums.account.BanStatus;
import org.jpstale.server.web.dto.ChangePasswordResponse;
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

    public ChangePasswordResponse changePassword(String oldPasswordHash, String newPasswordHash) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return ChangePasswordResponse.fail("未登录");
        }
        // 登录时已在 Session 中写入 accountName，修改密码时以 accountName 为准，避免 loginId 类型差异。
        String accountName = StpUtil.getSession().getString("accountName");
        if (accountName == null || accountName.isBlank()) {
            return ChangePasswordResponse.fail("会话信息异常，账号名为空");
        }
        UserInfo user = userInfoMapper.selectOneByAccountName(accountName.trim());
        if (user == null) {
            return ChangePasswordResponse.fail("用户不存在");
        }
        if (user.getBanStatus() != null && user.getBanStatus() != BanStatus.NOT_BANNED.getValue()) {
            return ChangePasswordResponse.fail("账号已被封禁，无法修改密码");
        }
        if (user.getActive() == null || user.getActive() != 1) {
            return ChangePasswordResponse.fail("账号未激活或已停用");
        }
        if (!oldPasswordHash.equalsIgnoreCase(user.getPassword())) {
            return ChangePasswordResponse.fail("当前密码不正确");
        }
        if (oldPasswordHash.equalsIgnoreCase(newPasswordHash)) {
            return ChangePasswordResponse.fail("新密码不能与当前密码相同");
        }
        UserInfo update = new UserInfo();
        update.setId(user.getId());
        update.setPassword(newPasswordHash);
        userInfoMapper.updateById(update);
        return ChangePasswordResponse.ok("密码修改成功");
    }
}


