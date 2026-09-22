package org.jpstale.server.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS：允许前端跨域。鉴权由 Sa-Token（如 @SaCheckRole）负责，不再使用 WebAuthInterceptor。
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 客户端资产根（`pt.assets.root`，默认服务器上的 `/data/PristonTale/apps/client/`）。
     *
     * <p>
     * ⚠ 原先 `/exm-run/**` 把 Ubuntu 的绝对路径**写死在代码里**，本地（Windows PC）必然 404，
     * 而管理端的地图可视化要读同一批资产 —— 所以这里收成一个可配项，两个用途共用。
     */
    @Value("${pt.assets.root:/data/PristonTale/apps/client/}")
    private String assetsRoot;

    /**
     * 资产根 → Spring 资源位置。
     *
     * <p>
     * ⚠ **必须走 `File.toURI()`**：手拼 `"file:" + "E:/x/"` 得到的是 `file:E:/x/` —— 那不是
     * 「file 协议 + 绝对路径」的规范写法，Spring 会当相对位置解析 ⇒ **静默 404**（踩过：
     * 老的写死路径是 `file:/data/...`（有前导斜杠）所以能用，改成可配后把斜杠弄丢了）。
     * `File.toURI()` 给的是 `file:/E:/x/`（Windows）或 `file:/data/x/`（Linux），两种平台都对。
     */
    private static String asLocation(String root) {
        String r = root == null || root.isBlank() ? "/data/PristonTale/apps/client/" : root.trim();
        java.io.File dir = new java.io.File(r);
        String uri = dir.toURI().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 资产根解析结果**必须在启动日志里看得见**（不静默）：指错地方的表现是页面图全 404，
        // 而 404 的成因（根不存在 / 环境变量没传进 fork 出来的 JVM）从现象上完全看不出来。
        java.io.File rootDir = new java.io.File(
                assetsRoot == null || assetsRoot.isBlank() ? "/data/PristonTale/apps/client/" : assetsRoot.trim());
        if (rootDir.isDirectory()) {
            log.info("[Assets] 客户端资产根 = {}（/res/** 与 /exm-run/** 都从这里取）", rootDir.getAbsolutePath());
        } else {
            log.warn("[Assets] 客户端资产根不存在：{} —— /res/** 与 /exm-run/** 会 404（"
                    + "用环境变量 PT_ASSET_ROOT 或 -Dpt.assets.root= 指到客户端目录）",
                    rootDir.getAbsolutePath());
        }

        registry.addResourceHandler("/exm-run/**")
                .addResourceLocations(asLocation(assetsRoot));

        // `/res/**` = 客户端里由 Vite 插件 devAssets 虚拟出来的路径（`/res/image/planemap/0.webp`、
        // `/res/image/npc.tga`、`/res/field/**.smd`…）。管理端的地图可视化照着客户端的取图方式写，
        // 于是这里给同一批资产开同一个前缀 —— 组件侧与页面侧的 URL 写法完全一致。
        registry.addResourceHandler("/res/**")
                .addResourceLocations(asLocation(assetsRoot));
    }
}
