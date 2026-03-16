# 合并 Web 服务与用户角色设计

**日期**: 2026-03-16  
**目标**: 将 pt-web-server、pt-admin-server、pt-clan-server 合并为单一 Web 服务，并明确普通用户与系统管理员的权限划分（是否复用 user_info 现有字段或新增角色表）。

**已选方案**：**方案 B**（在 user_info 增加 `web_admin` 列，不单独建角色表）。下文「六、方案 B 实施说明」为据此写定的实施计划，仅写入文档，不涉及代码修改。

---

## 一、现状简要

- **后端路由约定**：所有后端接口均以 **`/api/`** 为前缀，例如 `/api/user/register`、`/api/admin/xxx`、`/api/clan/member`。
- **pt-web-server**：用户注册、登录、改密、角色信息等，路径如 `/api/user/register`（当前或为 `/api/register`，合并后统一为 `/api/user/*`）。
- **pt-admin-server**：管理地图、NPC、道具、活动、玩家等，路径如 `/api/admin/*`。
- **pt-clan-server**：公会相关 HTTP 接口，路径如 `/api/clan/*`。
- **user_info 表**（userdb）：已有 `game_master_type`、`game_master_level`、`game_master_mac_address`，原版用于**游戏内 GM**（客户端登录后是否具备 GM 权限、等级、绑定 MAC）。

---

## 二、合并三个 HTTP 服务

### 2.1 做法

- 保留一个 Web 应用模块（例如保留 **pt-web-server** 名称，或重命名为 **pt-web**），在该应用内：
  - 合并 pt-web-server、pt-admin-server、pt-clan-server 的**所有 Controller / Service**；
  - 保持或统一 URL 前缀，便于路由与权限控制（**所有后端接口均以 `/api/` 为前缀**）：
    - **用户端**：`/api/user/**`（注册、登录、改密、自己的角色信息等，如 `/api/user/register`）
    - **管理端**：`/api/admin/**`（地图、NPC、道具、活动、玩家管理等）
    - **公会**：`/api/clan/**`（公会接口，如 `/api/clan/member`）
- 删除或归档 **pt-admin-server**、**pt-clan-server** 两个独立模块（或先改为空壳，仅依赖 pt-web 并重定向到统一入口，视迁移节奏而定）。
- 单一部署：一个 JAR、一个端口（如 8080），Nginx 将 `/api/` 反代到该服务即可（所有后端接口均在 `/api/` 下，如 `/api/user/*`、`/api/admin/*`、`/api/clan/*`）。

### 2.2 Nginx 与代理（兼容性）

开发/部署时 Nginx 除反代 **`/api/`** 外，需**兼容原版 ClanSystem 客户端**：客户端会请求 `http://<host>:80/Clan/xxx.asp`，应将该类请求转发到同一后端。

- **只代理 Clan 的 .asp 接口**：Nginx 仅对 **`/Clan/*.asp`** 做反向代理（例如 `location ~ ^/Clan/.*\.asp$`），**不要**代理整个 `/Clan/` 前缀，避免误伤可能存在的静态资源（如 `/Clan/` 下的图片、脚本等）。
- **配置示例**（`server/nginx/default.conf`）：`location ~ ^/Clan/.*\.asp$ { proxy_pass http://backend; ... }`，将匹配的请求转发到统一 Web 服务（后端需提供 `/Clan/xxx.asp` 路径）。

### 2.3 依赖与配置

- 该统一 Web 模块依赖：pt-common、pt-dao-spring-boot-starter、spring-boot-starter-web、validation、Flyway 等（即当前三个模块依赖的并集）。
- 数据源与 schema 不变：仍通过 pt-dao 访问 userdb、gamedb、clandb 等。

---

## 三、用户角色与权限划分

需求归纳：

- **普通用户**：登录后只能看到自己的账号信息、修改密码等；看不到管理功能。
- **系统管理员**：能访问管理功能（地图、NPC、道具、活动、玩家等）。

需要决定：**是否在 userdb 增加角色表，还是用 user_info 现有字段表示“系统管理员”**。

### 3.1 user_info 现有与“角色”相关的字段

| 字段 | 含义（原版） | 说明 |
|------|----------------|------|
| `game_master_type` | 游戏内 GM 类型（0=非GM，1=GM 等） | 原版 GM 在 UserInfo 里配置，客户端据此显示 GM 能力 |
| `game_master_level` | GM 等级（如 1–4） | 原版用于游戏内 GM 权限等级 |
| `game_master_mac_address` | GM 绑定 MAC | 安全绑定 |

这些字段描述的是**游戏内 GM**，不是“网站后台管理员”。但可以约定复用，也可以与 Web 管理员解耦。

### 3.2 方案 A：复用 user_info 现有字段（不增表、不增列）

- **约定**：把“系统管理员”与“游戏内 GM”绑定——例如 `game_master_type >= 1`（或再要求 `game_master_level >= 1`）的账号视为** Web 系统管理员**，允许访问 `/api/admin/**`。
- **优点**：无需改表、无需迁移；实现快；原版已有 GM 账号可直接当管理员用。
- **缺点**：游戏 GM 与 Web 管理员强绑定，无法单独配置“仅游戏 GM”或“仅 Web 管理员”。

**实现要点**：登录后查 user_info，若 `game_master_type >= 1` 则在 session/Token 中标记为 admin，前端根据该标记显示/隐藏管理入口；对 `/api/admin/**` 做拦截器/过滤器，非 admin 返回 403。

### 3.3 方案 B：在 user_info 增加一列（不单独建角色表）

- **新增列**：例如 `web_admin boolean NOT NULL DEFAULT false`（或 `role varchar(20) DEFAULT 'user'`，取值 `user` / `admin`）。
- **语义**：`web_admin = true`（或 `role = 'admin'`）表示该账号为** Web 系统管理员**，可访问 `/api/admin/**`；与游戏内 GM 解耦，可独立配置。
- **优点**：语义清晰；游戏 GM 与 Web 管理员可独立开；仍只需一张表，无需 join 角色表。
- **缺点**：需要一次 DDL 与数据迁移（可为现有账号默认 `web_admin = false`）；若未来角色类型很多，单列会不够用，但“普通用户 / 系统管理员”两种足够。

**实现要点**：注册时默认 `web_admin = false`；管理员账号由现有数据或脚本将指定账号设为 `true`。鉴权逻辑同方案 A，只是判断字段改为 `web_admin`（或 `role`）。

### 3.4 方案 C：userdb 增加角色表（多角色、可扩展）

- **新建表**：如 `user_role(id, user_id, role_code)`，或 `account_role(account_name, role_code)`，其中 `role_code` 可取 `user`、`admin` 等。
- **语义**：一个账号可有多个角色；后续若增加“客服”“运营”等，只需加 `role_code` 与权限配置。
- **优点**：扩展性好，适合多种角色、细粒度权限。
- **缺点**：当前仅两种角色时略显重；需要维护角色表、登录时查 role 或缓存。

**建议**：若短期只有“普通用户 + 系统管理员”，**不优先**采用方案 C；等真正出现第三种角色或权限矩阵再引入角色表更合适。

---

## 四、推荐结论

- **合并 Web 服务**：推荐将三个 HTTP 服务合并为一个 Web 应用（保留一个模块名，统一 `/api/user`、`/api/admin`、`/api/clan` 前缀），单端口部署，便于部署与 Nginx 配置。
- **角色与权限**：已选用 **方案 B**（在 user_info 增加 `web_admin` 列）；游戏 GM 与 Web 管理员解耦，可独立配置。

---

## 五、实现时建议顺序（通用）

1. **角色与权限**：按方案 B 执行 DDL 与 pt-dao 实体更新（见第六节）。
2. **统一鉴权**：在统一 Web 应用中实现登录态（Session 或 JWT），并在访问 `/api/admin/**` 时校验 `user_info.web_admin = true`。
3. **合并代码**：将 pt-admin-server、pt-clan-server 的 Controller/Service 迁入 pt-web-server（或新 pt-web），路径保持 `/api/admin`、`/api/clan`；删除或废弃另外两个模块的入口与打包。
4. **注册接口与前端**：将现有注册接口路径从 **`/api/register`** 调整为 **`POST /api/user/register`**（后端 Controller 使用 `@RequestMapping("/api/user")` 或等价方式）；同步修改前端 www 注册页（如 `server/www/js/register.js`）的请求 URL 为 **`/api/user/register`**。合并后用户端接口统一在 `/api/user/*` 下。
5. **前端**：www 中根据登录后返回的“是否管理员”标记，显示或隐藏“管理功能”入口；或管理端单独页面（如 `/admin.html`），由后端对未授权访问返回 403。
6. **文档与部署**：更新 server/README、架构文档中的模块列表与端口说明，并确认 Docker Compose 与 Nginx 只反代到该单一 Web 服务。

---

## 六、方案 B 实施说明（已选，仅写入方案不改代码）

本节将方案 B 的数据库变更、实体与业务约定、鉴权与授权规则写成可执行的实施说明，供后续实现时按步骤执行。**本文档仅描述方案，不直接修改代码或库表。**

### 6.1 数据库变更

- **表**：`userdb.user_info`
- **新增列**：
  - 列名：`web_admin`
  - 类型：`boolean NOT NULL DEFAULT false`
  - 含义：`true` 表示该账号为 Web 系统管理员，可访问 `/api/admin/**`；`false` 表示普通用户。
- **迁移**：
  - 对已有库：执行 `ALTER TABLE userdb.user_info ADD COLUMN web_admin boolean NOT NULL DEFAULT false;`（若需对已有管理员账号赋权，再执行 `UPDATE userdb.user_info SET web_admin = true WHERE account_name = 'xxx';`）。
  - 对新建库（如 Docker postgres-init）：在 `01-create-userdb.sql` 的 `user_info` 建表语句中增加上述列；在 `02-data-userdb.sql` 中若插入初始管理员，则对该行设置 `web_admin = true`（例如现有 admin 账号可在此处或后续脚本中设为 true）。

### 6.2 pt-dao 与实体约定

- **UserInfo 实体**：增加字段 `webAdmin`（Boolean），对应表列 `web_admin`；若使用 MyBatis-Plus 代码生成，重新生成 userdb 的 UserInfo 后保留其他业务修改，或手工在现有 UserInfo 上增加该字段及 `@TableField("web_admin")`。
- **Mapper**：无需新增方法即可在查询 UserInfo 时带出 `web_admin`；若存在按账号查询单条（如登录、鉴权），确保 SELECT 包含 `web_admin` 列（通常 `SELECT *` 或实体全字段即可）。

### 6.3 业务约定

- **注册**：新用户注册时，插入 `user_info` 时 `web_admin` 固定为 `false`。
- **授予管理员**：仅通过数据库或运维脚本将指定账号的 `web_admin` 置为 `true`（不通过公开接口开放，避免越权）；初始管理员可在建表/种子数据或迁移脚本中指定。
- **游戏内 GM**：`game_master_type` / `game_master_level` 仅用于游戏客户端与 pt-login-server / pt-game-server，与 Web 端是否管理员无关；即“游戏 GM”与“Web 管理员”互不绑定。

### 6.4 鉴权与授权规则（实现时须满足）

- **登录**：用户登录成功后，从 user_info 读取 `web_admin`，写入 Session 或 JWT 的“是否管理员”标记（如 `isAdmin` 或 `role: 'admin'/'user'`），并随用户信息接口返回前端（供前端显示/隐藏管理入口）。
- **访问 `/api/admin/**`**：请求进入时校验登录态；若未登录则 401；若已登录但 `user_info.web_admin != true` 则 403；仅 `web_admin = true` 的账号可访问。
- **访问 `/api/user/**`、`/api/clan/**`**：仅校验登录态（若该接口需要登录），不要求 `web_admin`；普通用户与管理员均可按业务规则访问（如普通用户只能看自己的数据）。

### 6.5 实施顺序（与第五节一致）

1. 执行 6.1 的 DDL（及必要的数据更新），并更新 pt-dao 的 UserInfo（6.2）。
2. 在统一 Web 应用中实现登录态与 6.4 的鉴权逻辑。
3. 合并三个 HTTP 服务代码，保留 `/api/user`、`/api/admin`、`/api/clan` 前缀；**注册接口**改为 `POST /api/user/register`，前端 www 注册页请求改为 `/api/user/register`。
4. 前端根据“是否管理员”显示/隐藏管理入口，后端对未授权访问 `/api/admin/**` 返回 403。
5. 更新 README 与架构文档、部署与 Nginx 配置。

---

**文档结束**。方案 B 已写入本节，实现时按第五、六节顺序执行即可。
