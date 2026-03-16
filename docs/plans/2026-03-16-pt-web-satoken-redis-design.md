# pt-web-server 接入 Sa-Token + Redis 登录鉴权设计

**日期**: 2026-03-16  
**目标**: 在 pt-web-server 中引入 Spring Data Redis、Sa-Token，基于 Sa-Token 实现 Cookie 模式登录鉴权；`/api/admin/**` 使用 `@SaCheckRole("admin")` 限制仅管理员可访问。

**前置约定**: 与 [2026-03-16-merge-web-and-role-design.md](./2026-03-16-merge-web-and-role-design.md) 一致，角色来自 `user_info.web_admin`（方案 B）。

---

## 一、依赖与配置

### 1.1 Maven 依赖（pt-web-server/pom.xml）

本项目为 **Spring Boot 4**（server 父 POM 中 `spring-boot.version` 为 4.0.3），需使用 Sa-Token 对 Spring Boot 4 的集成包（v1.45.0 起支持，参考 [Sa-Token v1.45.0 发布说明](https://zhuanlan.zhihu.com/p/2014309009423881861)）。

新增：

- `spring-boot-starter-data-redis`：Spring Redis 支持，Sa-Token Redis 扩展会复用其连接。
- `sa-token-spring-boot4-starter`：Sa-Token 与 **Spring Boot 4** 集成（WebMVC，含 Cookie 等）。
- `sa-token-redis-jackson`：Sa-Token 使用 Redis 存储会话，Jackson 序列化（可读、便于调试）。
- `commons-pool2`：Redis 连接池（Lettuce 需此依赖）。

版本：Sa-Token 相关统一使用 **1.45.0**（与 Spring Boot 4 配套）。

### 1.2 application.yml

**Redis**（必配，若希望使用 Redis 存储会话）：

```yaml
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    database: ${REDIS_DATABASE:0}
    timeout: 10s
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

**Sa-Token**：

- `sa-token.token-name`：Cookie 名，如 `satoken`（默认即可）。
- `sa-token.timeout`：登录态超时（秒），如 `86400`（24 小时）或按需调整。
- `sa-token.is-concurrent`：是否允许同账号并发登录，如 `true`。
- `sa-token.is-share`：多端是否共享 token，如 `false`（浏览器 Cookie 单端即可）。
- （可选）`sa-token.replaced-login-exit-mode`：同账号不允许多端登录时的策略。v1.45.0 支持 `OLD_DEVICE`（默认，新登录顶掉旧会话）或 `NEW_DEVICE`（拦截本次登录、旧设备保持在线）；按安全需求配置。

开发环境若暂不启用 Redis：不配 `spring.redis` 或使用 Sa-Token 内存存储（不引入 `sa-token-redis-jackson` 则默认内存）；若已引入 Redis 扩展，则需提供可用 Redis，否则启动报错。建议本地开发也起一个 Redis（如 Docker），与生产行为一致。

---

## 二、登录与登出

### 2.1 loginId 与 Session 数据

- **loginId**：使用 `user_info.account_name`（字符串），保证唯一且与现有业务一致。
- 登录成功后：
  - 调用 `StpUtil.login(loginId)` 建立 Sa-Token 登录态（Cookie 由框架写入）。
  - 在 Sa-Token 的 Session 中写入：
    - `accountName`：账号名（与 loginId 一致，便于业务读取）；
    - `webAdmin`：Boolean，来自 `user_info.web_admin`。

这样后续鉴权与角色均基于 Session 中的 `webAdmin`，无需再查库。

### 2.2 登录接口（现有 POST /api/user/login）

- 保持现有入参、密码校验逻辑（`LoginService.validate`）与返回体（如 `LoginResponse`）不变。
- 校验通过后：不再写 HttpSession；改为调用 `StpUtil.login(user.getAccountName())`，并将 `accountName`、`webAdmin` 写入当前 Sa-Token Session。
- 返回体仍可包含“是否管理员”等前端所需字段（与现有一致）。

### 2.3 登出接口

- 新增或复用 **POST /api/user/logout**：调用 `StpUtil.logout()` 即可，Sa-Token 会清除服务端会话并让 Cookie 失效。

---

## 三、鉴权与 @SaCheckRole("admin")

### 3.1 实现 StpInterface（角色来源）

- 实现 Sa-Token 的 `StpInterface`，注入为 Spring Bean。
- **getRoleList(Object loginId, String loginType)**：
  - 从当前 Sa-Token Session 中读取 `webAdmin`（登录时已写入）；
  - 若 `webAdmin == true` 返回 `List.of("admin")`，否则返回 `List.of("user")`（或仅非管理员返回空列表，只要管理员有 `"admin"` 即可）。
- **getPermissionList**：当前不涉及权限点，可返回空列表。

这样 `@SaCheckRole("admin")` 会调用上述接口，仅当角色列表包含 `"admin"` 时放行。

### 3.2 /api/admin/** 使用 @SaCheckRole("admin")

- 在 **AdminController** 类上添加 `@SaCheckRole("admin")`，该类下所有接口均要求管理员角色；若后续有部分接口需区分，再在方法级使用注解或单独 Controller。
- 移除对 `/api/admin/**` 的 **WebAuthInterceptor** 注册（不再用拦截器做 admin 校验），鉴权完全由 Sa-Token 注解承担。

### 3.3 未登录与无角色时的处理

- 未登录访问需登录接口：Sa-Token 会抛 `NotLoginException`，需配置全局异常处理返回 401 及统一 JSON 体（与现有风格一致）。
- 已登录但无 `admin` 角色访问 `/api/admin/**`：Sa-Token 会抛权限相关异常，全局异常处理返回 403 及统一 JSON 体。

### 3.4 其他需登录的接口（如 /api/user/me、/api/clan/* 部分）

- 若仅需“已登录”不区分角色：使用 `@SaCheckLogin` 或路由级别配置“仅登录即可”；不需要 `@SaCheckRole("admin")`。
- 本设计不改变现有 `/api/user/**`、`/api/clan/**` 的路径规划，只把“登录态”从 HttpSession 改为 Sa-Token，并在需要处加注解。

---

## 四、Redis 与可选能力

- **会话存储**：使用 Redis 后，Sa-Token 将 token/Session 存 Redis，支持多实例共享、重启不丢；与现有“方案 1”一致。
- **开发环境**：建议本地也启用 Redis（如 Docker 同一 compose 中增加 redis 服务），避免与生产行为不一致；若确需无 Redis 启动，可仅用 Sa-Token 内存模式（不引入 redis-jackson 依赖）。
- **踢人 / 单点登录**：后续若需要，可调用 `StpUtil.kickout(loginId)` 等；本次设计不实现，仅预留能力。
- **超时与续期**：由 Sa-Token 的 `timeout` 与活动续期策略控制，无需额外实现。

---

## 五、实施顺序建议

1. **依赖与配置**：在 pt-web-server 的 pom.xml 中增加上述依赖；在 application.yml 中增加 Redis 与 Sa-Token 配置；本地 Docker Compose 可增加 Redis 服务（可选但推荐）。
2. **登录/登出**：修改 LoginController 为 Sa-Token 登录并写 Session；新增或暴露 POST /api/user/logout 调用 StpUtil.logout。
3. **StpInterface**：实现并注册 StpInterface，getRoleList 从 Session 的 webAdmin 返回 "admin"/"user"。
4. **Admin 鉴权**：在 AdminController 上添加 @SaCheckRole("admin")；移除 WebMvcConfig 中对 /api/admin/** 的 WebAuthInterceptor；配置 Sa-Token 的 NotLoginException 及无权限异常的统一 401/403 JSON 响应。
5. **回归**：验证登录、登出、仅管理员可访问 /api/admin/**、非管理员访问返回 403、未登录访问返回 401。

---

**文档结束**。实现时按上述顺序执行即可；具体实现计划由 writing-plans 产出。
