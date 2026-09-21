package org.jpstale.server.web.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色/权限：`admin` 角色的**唯一来源**是登录时写入 Session 的
 * {@code webAdmin}（键名见 {@code LoginController.SESSION_KEY_WEB_ADMIN}）。
 *
 * <p>
 * 而 {@code webAdmin} 的值由 {@link org.jpstale.common.service.account.GameMasterRule}
 * 按原版 GM 两列（{@code userinfo.gamemastertype} / {@code gamemasterlevel}）判定后写入。
 *
 * <p>
 * ⚠ 它**不是** {@code user_info.web_admin}：活库没有那一列，实体也是
 * {@code @TableField(exist = false)}，登录 SQL 根本不取它 —— 旧写法下本类恒返回
 * {@code ["user"]}，`admin` 角色谁都拿不到（问题记录见设计文档 §5.0）。
 *
 * <p>
 * 会话里没有该键（或为 false）一律按非管理员处理（fail-closed）。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Boolean webAdmin = StpUtil.getSessionByLoginId(loginId).getModel(SessionKeys.WEB_ADMIN, Boolean.class);
        return Boolean.TRUE.equals(webAdmin) ? List.of("admin") : List.of("user");
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
