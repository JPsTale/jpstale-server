package org.jpstale.server.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 管理端**页面路由**：把 `/admin/{列表}` 与 `/admin/{实体}/{主键}` 映射到静态页，
 * 并**注入 `&lt;base&gt;`**。
 *
 * <p>
 * 为什么需要它（两条硬约束，缺一不可）：
 * <ol>
 *   <li>**路径式路由下相对路径会错**：页内 `./js/i18n.js`、`./admin-items.html` 这类写法是相对
 *       **当前目录**解析的。页面在 `/pt/admin/item/123` 时它们会指向 `/pt/admin/item/js/…` ⇒ 全 404。
 *       注入 `&lt;base href="{contextPath}/"&gt;` 后，相对路径统一回到 context 根 —— 与列表页
 *       （`/pt/admin-items.html`）行为一致，页面里已有的相对链接**一个都不用改**。</li>
 *   <li>**context-path 部署时可配**（`PT_WEB_CONTEXT_PATH`，默认 `/pt`，docker 那套是空字符串），
 *       所以 base **不能写死**，必须由请求现算。</li>
 * </ol>
 *
 * <p>
 * 约定：**URL 里传的是数据库主键**（`itemlist.id` / `monsterlist.id` / `maplist.id`），
 * 不是 `idcode` 这类语义字段 —— 一个语义码可能对应多行（本库实测有同名同码的重复行），
 * 只有主键能唯一定位一行。
 *
 * <p>
 * 鉴权与静态页一致：**页面本身不需要登录**（里面有数据的是 `/api/admin/**`，那些才校验角色）；
 * 匿名打开页面时会由页面的 `/api/user/me` 拿到 401 并跳登录页。
 */
@Slf4j
@RestController
public class AdminPageController {

    /** 页头里的占位符，由本控制器替换成 `<base href="…">`。 */
    public static final String BASE_PLACEHOLDER = "<!--PT_BASE-->";

    /** 列表页：`/admin/items` → `admin-items.html`。**这张表也是路径白名单。** */
    private static final Map<String, String> LIST_PAGES = Map.of(
            "items", "admin-items.html",
            "monsters", "admin-monsters.html",
            "npcs", "admin-npcs.html",
            "maps", "admin-maps.html");

    /** 详情页：`/admin/item/{id}` → `admin-item.html`。 */
    private static final Map<String, String> DETAIL_PAGES = Map.of(
            "item", "admin-item.html",
            "monster", "admin-monster.html",
            "npc", "admin-npc.html",
            "map", "admin-map.html");

    /** 列表页路由。 */
    @GetMapping("/admin/{entity}")
    public ResponseEntity<String> listPage(@PathVariable("entity") String entity, HttpServletRequest req) {
        String file = LIST_PAGES.get(entity);
        return file == null ? notFound(entity) : page(file, req);
    }

    /**
     * 子页路由：`/admin/map/{id}/edit` → `admin-map-edit.html`（地图可视化编辑器）。
     *
     * <p>
     * ⚠ 单独一条是因为它是**三段**路径，与 {@link #detailPage} 的两段形状不同。
     * `edit` 这个尾段当前只有地图编辑器用；将来别的东西要加，在这里加一行白名单。
     */
    @GetMapping("/admin/map/{id}/edit")
    public ResponseEntity<String> mapEditPage(@PathVariable("id") String id, HttpServletRequest req) {
        if (!id.matches("\\d{1,12}")) {
            return notFound("map/" + id + "/edit");
        }
        return page("admin-map-edit.html", req);
    }

    /** 详情页路由。`id` 必须是数字（主键），否则 404 —— 不让 `/admin/item/foo` 落到页面上。 */
    @GetMapping("/admin/{entity}/{id}")
    public ResponseEntity<String> detailPage(@PathVariable("entity") String entity,
                                            @PathVariable("id") String id,
                                            HttpServletRequest req) {
        String file = DETAIL_PAGES.get(entity);
        if (file == null || !id.matches("\\d{1,12}")) {
            return notFound(entity + "/" + id);
        }
        return page(file, req);
    }

    private ResponseEntity<String> notFound(String what) {
        log.warn("[AdminPage] 未知的页面路由：{}", what);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** 读静态页 → 注入 base → 作为 HTML 返回（页面仍是纯静态文件，只是换个发法）。 */
    private ResponseEntity<String> page(String file, HttpServletRequest req) {
        String html;
        try (InputStream in = new ClassPathResource("static/" + file).getInputStream()) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[AdminPage] 读取静态页失败：{}", file, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (!html.contains(BASE_PLACEHOLDER)) {
            // 占位符没了就是页面被改坏（相对路径会静默 404）—— 报出来，不静默
            log.error("[AdminPage] {} 里没有 {} 占位符，注入 base 失败", file, BASE_PLACEHOLDER);
        }
        String base = "<base href=\"" + req.getContextPath() + "/\">";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html.replace(BASE_PLACEHOLDER, base));
    }
}
