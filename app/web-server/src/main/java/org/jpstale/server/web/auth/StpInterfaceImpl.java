package org.jpstale.server.web.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色/权限：角色来自登录时写入 Session 的 webAdmin，admin 可访问 /api/admin/**。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Boolean webAdmin = StpUtil.getSessionByLoginId(loginId).getModel("webAdmin", Boolean.class);
        return Boolean.TRUE.equals(webAdmin) ? List.of("admin") : List.of("user");
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
