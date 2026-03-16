# pt-web-server Sa-Token + Redis 登录鉴权 — 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 pt-web-server 中接入 Spring Data Redis 与 Sa-Token，用 Cookie 模式实现登录鉴权，会话存 Redis；/api/admin/** 仅允许带 admin 角色的用户访问（通过 @SaCheckRole("admin") 实现）。

**Architecture:** 登录成功后使用 StpUtil.login(accountName)，在 Sa-Token Session 中写入 accountName、webAdmin；实现 StpInterface 根据 Session 的 webAdmin 返回角色 "admin" 或 "user"；AdminController 类上使用 @SaCheckRole("admin")；移除对 /api/admin/** 的 WebAuthInterceptor，统一用 Sa-Token 异常返回 401/403。

**Tech Stack:** Spring Boot 4, Spring Data Redis (Lettuce), Sa-Token v1.45.0 (sa-token-spring-boot4-starter, sa-token-redis-jackson), commons-pool2。参考 [Sa-Token v1.45.0 支持 Spring Boot 4](https://zhuanlan.zhihu.com/p/2014309009423881861)。

**设计文档:** [2026-03-16-pt-web-satoken-redis-design.md](./2026-03-16-pt-web-satoken-redis-design.md)

---

## Task 1: 添加 Maven 依赖

**Files:**
- Modify: `server/pt-web-server/pom.xml`

**Step 1: 在 `<dependencies>` 内追加以下依赖**

在 `</dependencies>` 前增加：

```xml
        <!-- Sa-Token + Redis 登录鉴权（Spring Boot 4 使用 sa-token-spring-boot4-starter，v1.45.0） -->
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-spring-boot4-starter</artifactId>
            <version>1.45.0</version>
        </dependency>
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-redis-jackson</artifactId>
            <version>1.45.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-pool2</artifactId>
        </dependency>
```

**Step 2: 编译验证**

Run: `cd /Users/yanmaoyuan/repo/JPsTale && mvn -pl server/pt-web-server -am compile -q`  
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add server/pt-web-server/pom.xml
git commit -m "chore(pt-web-server): add sa-token and spring-data-redis dependencies"
```

---

## Task 2: 配置 Redis 与 Sa-Token

**Files:**
- Modify: `server/pt-web-server/src/main/resources/application.yml`

**Step 1: 在 `spring:` 下增加 redis 配置；在文件末尾增加 sa-token 配置**

在现有 `spring:` 块中增加 `redis:`（与 `datasource` 同级）；在文件末尾新增 `sa-token` 顶级 key。示例：

```yaml
# 在 spring: 下增加（与 datasource、flyway 同级）:
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

# 文件末尾新增:
sa-token:
  token-name: satoken
  timeout: 86400
  is-concurrent: true
  is-share: false
```

**Step 2: 启动验证（需本地 Redis，如 docker compose up -d redis）**

Run: `cd /Users/yanmaoyuan/repo/JPsTale && mvn -pl server/pt-web-server spring-boot:run -q`（可 Ctrl+C 停掉，仅验证能启动）  
Expected: 应用启动无报错（若 Redis 未起会连不上，可先跳过或先起 Redis）

**Step 3: Commit**

```bash
git add server/pt-web-server/src/main/resources/application.yml
git commit -m "config(pt-web-server): add redis and sa-token config"
```

---

## Task 3: 实现 StpInterface（角色来自 Session 的 webAdmin）

**Files:**
- Create: `server/pt-web-server/src/main/java/org/jpstale/server/web/auth/StpInterfaceImpl.java`
- Modify: 无（新建即可）

**Step 1: 新建类，实现 cn.dev33.satoken.stp.StpInterface**

- `getRoleList(Object loginId, String loginType)`：根据 loginId 获取其 Session（如 `StpUtil.getSessionByLoginId(loginId)`），从中取 `webAdmin`（Boolean）；若为 true 返回 `List.of("admin")`，否则 `List.of("user")`。Session 在登录时已写入 accountName、webAdmin。
- `getPermissionList(Object loginId, String loginType)`：返回 `Collections.emptyList()`。

**Step 2: 注册为 Spring Bean**

类上加 `@Component`。

**Step 3: 编译**

Run: `mvn -pl server/pt-web-server compile -q`  
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add server/pt-web-server/src/main/java/org/jpstale/server/web/auth/StpInterfaceImpl.java
git commit -m "feat(pt-web-server): add StpInterface for admin/user role from Session"
```

---

## Task 4: 登录改为 Sa-Token 并写入 Session

**Files:**
- Modify: `server/pt-web-server/src/main/java/org/jpstale/server/web/controller/LoginController.java`

**Step 1: 去掉 HttpSession 参数与 session.setAttribute**

- 登录成功分支：调用 `StpUtil.login(user.getAccountName())`；再 `StpUtil.getSession().set("accountName", user.getAccountName())`、`StpUtil.getSession().set("webAdmin", Boolean.TRUE.equals(user.getWebAdmin()))`。
- 返回仍为 `LoginResponse.ok(webAdmin)`，不变。

**Step 2: 添加 import**

- `cn.dev33.satoken.stp.StpUtil`

**Step 3: 编译**

Run: `mvn -pl server/pt-web-server compile -q`  
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add server/pt-web-server/src/main/java/org/jpstale/server/web/controller/LoginController.java
git commit -m "feat(pt-web-server): use Sa-Token login and Session for webAdmin"
```

---

## Task 5: 新增登出接口 POST /api/user/logout

**Files:**
- Modify: `server/pt-web-server/src/main/java/org/jpstale/server/web/controller/LoginController.java`

**Step 1: 新增方法**

```java
@PostMapping("/logout")
public ResponseEntity<?> logout() {
    StpUtil.logout();
    return ResponseEntity.ok().build();
}
```

**Step 2: 编译**

Run: `mvn -pl server/pt-web-server compile -q`  
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add server/pt-web-server/src/main/java/org/jpstale/server/web/controller/LoginController.java
git commit -m "feat(pt-web-server): add POST /api/user/logout"
```

---

## Task 6: AdminController 使用 @SaCheckRole("admin") 并移除拦截器

**Files:**
- Modify: `server/pt-web-server/src/main/java/org/jpstale/server/web/controller/AdminController.java`
- Modify: `server/pt-web-server/src/main/java/org/jpstale/server/web/WebMvcConfig.java`

**Step 1: AdminController 类上添加注解**

- 添加 import: `cn.dev33.satoken.annotation.SaCheckRole`
- 在类上添加 `@SaCheckRole("admin")`

**Step 2: 移除对 /api/admin/** 的 WebAuthInterceptor**

- 在 WebMvcConfig 的 addInterceptors 中，删除对 `WebAuthInterceptor` 的 `addInterceptor` 及 `addPathPatterns("/api/admin/**")`（或整段移除该 interceptor 注册）。若项目中再无其他地方使用 WebAuthInterceptor，可保留类文件仅不再注册。

**Step 3: 编译**

Run: `mvn -pl server/pt-web-server compile -q`  
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add server/pt-web-server/src/main/java/org/jpstale/server/web/controller/AdminController.java server/pt-web-server/src/main/java/org/jpstale/server/web/WebMvcConfig.java
git commit -m "feat(pt-web-server): protect /api/admin with @SaCheckRole(admin), remove WebAuthInterceptor"
```

---

## Task 7: 全局异常处理 401/403（Sa-Token NotLoginException 与无权限）

**Files:**
- Create: `server/pt-web-server/src/main/java/org/jpstale/server/web/exception/GlobalExceptionHandler.java`（或放入现有 exception 包）
- 若已有全局异常类，则 Modify 该类

**Step 1: 处理 NotLoginException**

- 捕获 `cn.dev33.satoken.exception.NotLoginException`，返回 HTTP 401，body 可为 `{"code":401,"message":"未登录"}` 或与现有项目统一格式。

**Step 2: 处理无权限异常**

- 捕获 `cn.dev33.satoken.exception.NotRoleException`（或 Sa-Token 用于角色校验的异常），返回 HTTP 403，body 可为 `{"code":403,"message":"无权限"}`。

**Step 3: 使用 @RestControllerAdvice 并在方法上 @ExceptionHandler**

**Step 4: 编译**

Run: `mvn -pl server/pt-web-server compile -q`  
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add server/pt-web-server/src/main/java/org/jpstale/server/web/exception/GlobalExceptionHandler.java
git commit -m "feat(pt-web-server): global handler for Sa-Token 401/403"
```

---

## Task 8: 手动回归验证

**Steps:**

1. 启动 Redis（如 `cd server && docker compose up -d redis`）。
2. 启动 pt-web-server（`mvn -pl server/pt-web-server spring-boot:run` 或 IDE 运行）。
3. 登录：`curl -X POST http://localhost:8080/api/user/login -H "Content-Type: application/json" -d '{"account":"已有账号","password":"对应64位SHA256十六进制"}' -c cookies.txt -v`，检查响应 200 且 Set-Cookie 含 satoken。
4. 访问管理接口（带 Cookie）：`curl -b cookies.txt http://localhost:8080/api/admin/info`，管理员应 200；非管理员账号登录后同请求应 403。
5. 未登录访问：`curl http://localhost:8080/api/admin/info` 应 401。
6. 登出：`curl -X POST -b cookies.txt http://localhost:8080/api/user/logout` 应 200；再访问 /api/admin/info 应 401。

**Step: Commit（若有测试脚本或文档变更）**

若无新增文件可跳过 commit；若有则提交。

---

**Plan complete and saved to `docs/plans/2026-03-16-pt-web-satoken-redis-implementation-plan.md`.**

**执行方式二选一：**

1. **Subagent-Driven（本会话）** — 按任务派发子 agent，每步完成后你做 review，再继续下一任务，迭代快。  
2. **并行会话（新会话）** — 在新会话中打开执行计划的 worktree，用 executing-plans 技能按检查点批量执行。

你更倾向哪种？若选 1，我会在本会话用 subagent-driven-development 按任务派发并做代码审查。
