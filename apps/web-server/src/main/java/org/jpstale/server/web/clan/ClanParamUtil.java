package org.jpstale.server.web.clan;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Clan 接口参数：从 Query + Form 取参（与 ASP Request 一致），黑名单校验。
 */
public final class ClanParamUtil {

    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList("--", ";"));

    /** 取单个参数（GET 或 POST body 的 form 参数），trim 后返回；不存在返回 null */
    public static String getParam(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        return v != null ? v.trim() : null;
    }

    /** 必参校验：若任一为空或包含黑名单则返回 true（应返回 Code=100） */
    public static boolean hasMissingOrBlacklist(HttpServletRequest request, String... paramNames) {
        for (String name : paramNames) {
            String v = getParam(request, name);
            if (v == null || v.isEmpty()) return true;
            for (String bad : BLACKLIST) {
                if (v.contains(bad)) return true;
            }
        }
        return false;
    }

    /** 仅黑名单校验：若任一参数值包含 -- 或 ; 返回 true */
    public static boolean hasBlacklist(HttpServletRequest request, String... paramNames) {
        for (String name : paramNames) {
            String v = getParam(request, name);
            if (v != null) {
                for (String bad : BLACKLIST) {
                    if (v.contains(bad)) return true;
                }
            }
        }
        return false;
    }
}
