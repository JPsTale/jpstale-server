# Agent 指令：完成三项任务的详细说明

**用途**：将本文档交给新的 Agent，由其按顺序完成以下三项任务。执行前请通读本指令及所引用的设计文档。

**工作区**：JPsTale 仓库根目录为 `server` 的上一级（即包含 `server/` 的目录）。C++ 参考项目与 JPsTale 同级的 **PristonTale-EU-main**。

---

## 一、参考路径速查

| 类型 | 路径 | 说明 |
|------|------|------|
| **JPsTale 服务端** | `server/` | 统一 Web、login、game、dao 等模块所在目录 |
| **统一 Web 模块** | `server/pt-web-server/` | 合并后保留的 Web 应用（当前为 pt-web-server） |
| **待合并模块** | `server/pt-admin-server/`、`server/pt-clan-server/` | 将 Controller/Service 迁入 pt-web-server 后删除或归档 |
| **前端 www** | `server/www/` | 静态站点，含注册页及 `js/register.js` |
| **Nginx 配置** | `server/nginx/default.conf` | 需反代 `/api/` 与 `/Clan/*.asp` |
| **pt-dao clandb** | `server/pt-dao/.../clandb/` | ClMapper、UlMapper、ClanListMapper、CtMapper、LiMapper（entity + mapper） |
| **数据库初始化** | `server/postgres-init/` | userdb、clandb 等建表与数据脚本 |
| **C++ 参考项目** | **`../PristonTale-EU-main/`** 或 **`/Users/yanmaoyuan/repo/PristonTale-EU-main`** | 与 JPsTale 同级的 PristonTale-EU 仓库 |
| **C++ ClanSystem ASP** | `PristonTale-EU-main/ClanSystem/Clan/*.asp` | 原版公会接口 ASP 源码，用于对照逻辑与响应格式 |
| **C++ Server** | `PristonTale-EU-main/Server/server/*.cpp` | 登录、角色、游戏等 C++ 服务端（协议与 DB 参考） |

---

## 二、任务一：把三个 Web 端合并成一个

### 2.1 目标

将 **pt-web-server**、**pt-admin-server**、**pt-clan-server** 合并为**单一 Web 应用**（保留 pt-web-server 或重命名为 pt-web），单端口（如 8080）部署，所有后端接口统一在 `/api/` 下：`/api/user/*`、`/api/admin/*`、`/api/clan/*`。

### 2.2 必读文档

- **`docs/plans/2026-03-16-merge-web-and-role-design.md`**  
  合并做法（§二）、Nginx 与代理（§2.2，仅代理 `/Clan/*.asp`）、方案 B（user_info.web_admin）、实施顺序（§五、§6.5）。

### 2.3 实施要点（按文档顺序）

1. **数据库**：在 userdb.user_info 增加列 `web_admin boolean NOT NULL DEFAULT false`；若已有建表脚本，在 `server/postgres-init/` 中相应 SQL 增加该列。
2. **pt-dao**：在 UserInfo 实体中增加 `webAdmin` 字段（对应 `web_admin`）。
3. **鉴权**：在统一 Web 应用中实现登录态（Session 或 JWT），并对 `/api/admin/**` 校验 `user_info.web_admin == true`，否则 403。
4. **合并代码**：  
   - 将 pt-admin-server、pt-clan-server 的 **Controller、Service** 迁入 pt-web-server；  
   - 路径统一为 **`/api/admin/**`**、**`/api/clan/**`**（当前 admin 为 `/admin`、clan 为 `/clan`，需改为带 `/api` 前缀）；  
   - 依赖合并：pt-web-server 的 pom 引入三个模块依赖的并集（pt-common、pt-dao、web、validation 等）。
5. **Nginx**：确认 `server/nginx/default.conf` 中除 `/api/` 外，有 **`location ~ ^/Clan/.*\.asp$`** 反代到同一后端，且不要代理整个 `/Clan/`。
6. **文档**：更新 `server/README.md`、架构文档中的模块列表与端口说明。

### 2.4 参考目录

- 现有 Controller/Service 位置：  
  `server/pt-web-server/src/main/java/.../controller/`、`.../service/`  
  `server/pt-admin-server/src/main/java/.../controller/`  
  `server/pt-clan-server/src/main/java/.../controller/`

---

## 三、任务二：改写注册接口路由并改好前端

### 3.1 目标

- **后端**：将注册接口从 **`POST /api/register`** 改为 **`POST /api/user/register`**（即 Controller 使用 `@RequestMapping("/api/user")` 或等价方式，方法为 `@PostMapping("/register")`）。  
- **前端**：将 `server/www` 中注册页的请求 URL 从 **`/api/register`** 改为 **`/api/user/register`**（主要修改 `server/www/js/register.js` 中的 `fetch` URL）。

### 3.2 必读文档

- **`docs/plans/2026-03-16-merge-web-and-role-design.md`**  
  §一（路由约定）、§五 第 4 条、§6.5 第 3 条（注册接口与前端修改说明）。

### 3.3 实施要点

1. 在 pt-web-server 中找到 **RegisterController**（当前应为 `@RequestMapping("/api")` + `@PostMapping("/register")`）。
2. 将基础路径改为 **`/api/user`**，使完整路径为 **`/api/user/register`**；请求体与响应格式不变。
3. 在 **`server/www/js/register.js`** 中，将 `fetch('/api/register', ...)` 改为 **`fetch('/api/user/register', ...)`**。
4. 合并后若存在其他引用 `/api/register` 的地方（如测试、文档），一并改为 `/api/user/register`。

### 3.4 参考目录

- 注册后端：`server/pt-web-server/src/main/java/.../controller/RegisterController.java`、`.../service/RegisterService.java`、`.../dto/RegisterRequest.java`、`RegisterResponse.java`  
- 注册前端：`server/www/js/register.js`、注册页 HTML（若存在）

---

## 四、任务三：参考原版 ClanSystem 实现 Java 版完全兼容的 17 个接口

### 4.1 目标

在**合并后的统一 Web 服务**（pt-web-server）中，实现与原版 ASP 行为与响应格式一致的 **17 个 Clan 接口**：  
- 对外提供**双路径**：**`/Clan/xxx.asp`**（客户端兼容，文本响应 `\r` 分隔、Key=Value）与 **`/api/clan/xxx`**（Web 端，可 JSON 或同结构）。  
- 参数与 Code/Key 与原版一致，便于原版 C++ 客户端直接连到 Java 服务。

### 4.2 必读文档（按阅读顺序）

1. **`docs/plans/2026-03-16-clansystem-api-list.md`**  
   17 个接口的路由表（.asp 与 /api/clan 对照）、请求方式、功能描述、参数、响应要点（Code 含义）、双路径约定（§〇、〇.1）、客户端生效方式（§八）。
2. **`docs/plans/2026-03-16-clansystem-asp-implementation-notes.md`**  
   原版 ASP 的逐步逻辑摘录（参数校验、分支、返回 Code 与 Key）、与 pt-dao 的对应与缺口（已补充 selectClanNameByUserId）。
3. **`docs/plans/2026-03-16-clansystem-java-detailed-design.md`**  
   Java 实现详细设计：总体架构、pt-dao 复用说明（§二，含 Mapper 方法一览）、各接口实现要点（§三）、实施顺序（§四）、测试与兼容性（§五）。
4. **`docs/plans/2026-03-15-clansystem-asp-sql-migration.md`**  
   ASP 使用的 SQL 与 pt-dao Mapper 方法对应（CL/UL/CT/LI/ClanList、userdb.CharacterInfo）。

### 4.3 C++ / ASP 参考路径（用于对照逻辑与响应）

- **原版 ClanSystem ASP 源码**：  
  **`PristonTale-EU-main/ClanSystem/Clan/`**  
  关键文件：`settings.asp`、`InviteClan.asp`、`NewClan.asp`、`DeleteClan.asp`、`LeavePlayer.asp`、`LeavePlayerSelf.asp`、`ClanMember.asp`、`ChangeLeader.asp`、`SubLeaderRelease.asp`、`SubLeaderUpdate.asp`、`GetClanMembers.asp`、`CheckClanLeader.asp`、`CheckDate.asp`、`CheckUnknown.asp`、`CheckClanPlayer.asp`、`CheckClanID.asp`、`CheckClanName.asp`、`SodScore.asp`、`SODsettings.asp`。  
  若 PristonTale-EU-main 不在默认路径，请在 JPsTale 同级目录查找或询问用户。
- **C++ 服务端（协议/DB 参考）**：  
  **`PristonTale-EU-main/Server/server/`**  
  公会相关逻辑可参考（但 ClanSystem HTTP 由客户端调用，服务端不直接请求 ASP）。

### 4.4 实施要点（按详细设计文档 §四）

1. **pt-dao**：ClanSystem 所需方法已在 clandb 的 ClMapper、UlMapper、ClanListMapper、CtMapper、LiMapper 中暴露；仅 CheckClanLeader 使用的 **ClMapper.selectClanNameByUserId** 已补充，无需再改 pt-dao。
2. **公共层**：实现参数解析（Query + Form 统一）、黑名单校验（`--`、`;`）、统一响应构建（Key=Value、`\r` 分隔）。
3. **接口实现顺序建议**：先实现简单接口（CheckClanPlayer、CheckDate、CheckUnknown、CheckClanName、CheckClanID、CheckClanLeader），再实现 NewClan、DeleteClan、LeavePlayerSelf、LeavePlayer、InviteClan、ChangeLeader、SubLeaderRelease、SubLeaderUpdate、GetClanMembers、ClanMember、SodScore。
4. **双路径**：每个接口在 Controller 中同时声明 **`/Clan/xxx.asp`** 与 **`/api/clan/xxx`**，共用同一 Service 方法；.asp 路径返回原版文本，/api/clan 可返回 JSON 或同结构。
5. **userdb**：NewClan/DeleteClan 需更新 **userdb.CharacterInfo** 的 ClanId/ClanName，通过 **userdb 的 CharacterInfoMapper** 完成（若尚无按 Name/ClanName 更新的方法，需在 pt-dao 的 userdb 部分补充）。

### 4.5 参考目录

- **pt-dao clandb**：  
  `server/pt-dao/src/main/java/org/jpstale/dao/clandb/mapper/`（ClMapper、UlMapper、ClanListMapper、CtMapper、LiMapper）  
  `server/pt-dao/src/main/resources/org/jpstale/dao/clandb/mapper/`（对应 XML）  
  `server/pt-dao/src/main/java/org/jpstale/dao/clandb/entity/`（Cl、Ul、ClanList、Ct、Li）
- **统一 Web 中放置 Clan 代码的位置**：  
  `server/pt-web-server/src/main/java/.../controller/`（Clan 双路径 Controller）  
  `server/pt-web-server/src/main/java/.../service/`（Clan Service）  
  包名建议与现有 web 风格一致，例如 `org.jpstale.server.web.clan`。

---

## 五、建议执行顺序与验收

1. **先完成任务一**：合并三端、统一鉴权、Nginx、README；确保单端口启动后 `/api/user/*`、`/api/admin/*`、`/api/clan/*` 路由存在（clan 可先返回占位）。
2. **再完成任务二**：在合并后的 pt-web-server 中改注册路径与 www 前端；验收：注册页提交后请求为 `POST /api/user/register` 且功能正常。
3. **最后完成任务三**：按详细设计实现 17 个 Clan 接口（双路径、文本响应、pt-dao 调用）；验收：用脚本或客户端请求 `/Clan/xxx.asp` 与 `/api/clan/xxx`，响应 Code 与 Key 与文档一致。

**验收命令建议**：  
- 编译：`mvn -q clean compile -pl server/pt-web-server -am`（或从 server 目录按模块编译）。  
- 若存在集成测试或 Postman/curl 脚本，跑一遍注册与至少一个 Clan 接口（如 CheckClanName、ClanMember）。

---

## 六、文档与路径汇总表

| 文档 | 路径（相对于 JPsTale 仓库根） | 用途 |
|------|------------------------------|------|
| 合并与角色设计 | `docs/plans/2026-03-16-merge-web-and-role-design.md` | 任务一、二 |
| Clan 接口清单 | `docs/plans/2026-03-16-clansystem-api-list.md` | 任务三：路由、参数、响应 |
| Clan ASP 实现笔记 | `docs/plans/2026-03-16-clansystem-asp-implementation-notes.md` | 任务三：ASP 逻辑摘录 |
| Clan Java 详细设计 | `docs/plans/2026-03-16-clansystem-java-detailed-design.md` | 任务三：实现步骤与 Mapper 一览 |
| Clan SQL 迁移 | `docs/plans/2026-03-15-clansystem-asp-sql-migration.md` | 任务三：SQL 与 Mapper 对应 |
| 服务端架构 | `docs/plans/2026-03-16-server-architecture.md` | 整体架构与模块对照 |
| Server README | `server/README.md` | 运行方式与模块说明 |

**C++ / ASP 参考（仓库外，与 JPsTale 同级）**：

| 内容 | 路径 | 说明 |
|------|------|------|
| ClanSystem ASP | `PristonTale-EU-main/ClanSystem/Clan/*.asp` | 原版公会接口源码 |
| C++ Server | `PristonTale-EU-main/Server/server/*.cpp` | 登录、游戏等服务端 |

若本机路径不同（例如 Windows 或不同用户目录），以用户环境为准；**PristonTale-EU-main 与 JPsTale 在同一父目录下**即可。
