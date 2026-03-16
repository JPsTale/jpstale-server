# Server 模块（Priston Tale 服务端）

本目录是 **JPsTale** 的 Java 服务端实现，对原版 **PristonTale-EU**（C++）在协议与数据库层面对齐，便于与现有客户端或后续 Java 客户端联调。下文先简述原版架构，再说明本仓库模块与运行方式，便于新人快速理解项目。

**要求**：**JDK 21**。

---

## 一、原版 PristonTale-EU 架构（参考仓库）

参考项目通常位于 **`../PristonTale-EU-main`**（或仓库说明中的路径）。原版为 **C++**，主要组成如下。

### 1. 双服架构

- **Login Server**（与 Game Server 共用同一套 `Server` 代码，通过配置/宏区分进程）
  - 职责：账号认证（查 **UserDB.UserInfo**）、世界/频道列表、**Ticket** 分发、在线玩家记录。
  - 与 Game Server 通过内网协议做 **World Auth**（`PKTHDR_WorldLoginAuth`）。
  - 核心文件：`accountserver.cpp`、`packetserver.cpp`、`socketserver.cpp` 等，位于 `Server/server/`。
- **Game Server**
  - 职责：地图、NPC、怪物、掉落、任务、聊天等游戏逻辑。
  - 客户端先连 Login 拿到 Ticket，再凭 Ticket 与 Game Server 做 World Auth 后进入游戏。

**启动顺序**：先启 Login Server，再启 Game Server。

### 2. 协议与登录

- **包格式**：常见为 `iLength`（包长）+ `iHeader`（包 ID）+ 包体。登录包为 `PacketLoginUser`（账号、密码哈希、分辨率、版本、硬件 ID 等）。
- **密码**：客户端发送 **SHA256( UPPERCASE(账号) + ":" + 明文密码 )** 的 64 位十六进制**大写**；服务端与 UserDB 中存储的哈希直接比较。数据库存哈希，不存明文。
- **登录成功**：服务端下发 UserInfo、ServerList 时**不加密**（仅部分包加密）；Ticket 用于后续选服/选角与 World Auth。

### 3. 数据库（原版为 SQL Server）

| 库名 | 用途 |
|------|------|
| **UserDB** | 账号 UserInfo、角色 CharacterInfo、任务进度 CharacterQuest、称号、奖励箱等 |
| **GameDB** | 职业、道具/掉落、怪物、地图、NPC、任务定义 QuestList/QuestRewardList/QuestWindowList 等 |
| **LogDB** | 账号/角色/作弊/物品/金币等日志、在线记录 |
| **SkillDB** | 各技能表（按技能等级的参数） |
| **ServerDB** | 在线奖励、活动配置、GM 命令队列、元数据、UsersOnline 等 |
| **EventDB** | 活动相关（Bellatra、Fury、Wanted 等） |
| **ClanDB** | 公会 ClanList、成员 UL/CL、公告、BBS 等 |

### 4. ClanSystem（原版单独项目）

- 原版为 **ASP .NET** 项目，提供公会相关的 **HTTP 接口**（创建/解散公会、成员、族长等），连接 ClanDB（及 UserDB 中角色公会字段）。本仓库用 **pt-clan-server**（Java Spring Web）替代。

---

## 二、本仓库（JPsTale server）结构

### 1. 模块与端口一览

| 模块 | 对应原版 | 说明 | 端口 |
|------|----------|------|------|
| **pt-common** | Shared + Server 的协议/枚举/加解密 | 网络包结构、包 ID、Netty 编解码与通信加解密（与 C++ 一致） | — |
| **pt-dao** | 各 Server 对 DB 的访问 | 按 PostgreSQL schema 的 entity/mapper，无 Spring | — |
| **pt-dao-spring-boot-starter** | — | 多数据源 + MyBatis-Plus 自动配置，供各应用复用 | — |
| **pt-login-server** | Login Server | 账号认证、Ticket、世界/频道列表、World Auth | **8484** (TCP) |
| **pt-game-server** | Game Server | 地图、NPC、怪物、战斗、任务、聊天等 | **8485** (TCP) |
| **pt-web-server** | 无（新增） | **统一 Web**：用户注册/登录（`/api/user/*`，如 `POST /api/user/register`、`POST /api/user/login`、`POST /api/user/logout`）、管理端（`/api/admin/*`）、公会（`/api/clan/*` 与客户端兼容路径 `/Clan/xxx.asp`）；单端口部署。**登录鉴权**：Sa-Token（Cookie 模式）+ Redis 存储会话；`/api/admin/**` 仅允许角色 admin（`user_info.web_admin == true`）访问（`@SaCheckRole("admin")`） | **8080** (HTTP) |
| **pt-admin-server** | — | 已合并入 pt-web-server，不再单独部署 | — |
| **pt-clan-server** | ClanSystem (ASP) | 已合并入 pt-web-server，不再单独部署 | — |

- **网站前端**：`server/www/`（首页、注册页、静态资源）。开发环境下由 Docker 中的 Nginx 提供，见下文「开发运行时环境」。

### 2. 数据库在本仓库中的形态

- 原版多库（UserDB、GameDB、…）在本仓库中对应为 **PostgreSQL 单库多 schema**（如 `userdb`、`gamedb`、`clandb`、`itemdb`、`eventdb`、`serverdb`、`logdb`、`skilldb`、`chatdb`）。
- 默认开发库名为 **`pt`**，由 Docker Compose 或本机 PostgreSQL 提供；pt-dao 通过 **pt-dao-spring-boot-starter** 访问，实体类带 `@TableName(schema="userdb", value="...")` 等。

### 3. 依赖关系（简要）

- **pt-common**：无 server 内依赖，被所有需要协议/编解码的模块依赖。
- **pt-dao** → **pt-dao-spring-boot-starter**：后者被 pt-login-server、pt-web-server 等依赖；pt-game-server 当前仅依赖 pt-common，可按需接入 pt-dao。pt-admin-server、pt-clan-server 若仍存在为占位模块，运行时不依赖。
- **pt-web-server**：除 pt-dao-spring-boot-starter 外，依赖 **Spring Data Redis** 与 **Sa-Token**（sa-token-spring-boot4-starter、sa-token-redis-jackson）做登录态与鉴权；运行需可用 Redis（开发环境见 Docker Compose 的 redis 服务）。

---

## 三、开发运行时环境（Docker Compose）

**用途**：本地开发时提供 PostgreSQL + Nginx，无需本机单独安装；便于 AI 或新人一键起环境。

- **文件**：`server/docker-compose.yml`
- **启动**：`cd server && docker compose up -d`

| 服务 | 端口 | 说明 |
|------|------|------|
| **postgres** | 5432 | 库 `pt`，用户/密码 `postgres` / `123456`。首次启动执行 `server/postgres-init/` 下脚本，创建多 schema（userdb、gamedb、clandb 等）。 |
| **redis** | 6379 | pt-web-server 的 Sa-Token 会话存储；启动 pt-web-server 前需保证 Redis 可用（`docker compose up -d` 会一并启动）。 |
| **nginx** | 80 | 静态根目录挂载 `server/www`；**`/api/`** 反代到宿主机 pt-web-server（8080）；**`/Clan/*.asp`** 反代到同一后端（原版客户端兼容，不代理整个 `/Clan/` 以免误伤静态资源）。 |

- **访问**：浏览器打开 **http://localhost** 为本站首页/注册页；注册接口为 **`POST /api/user/register`**，由 Nginx 转发到本机 pt-web-server。原版游戏客户端若将 Clan 地址配为 Nginx 所在主机:80，则 **`/Clan/xxx.asp`** 请求会被 Nginx 转发到同一后端（仅匹配 `.asp`，不代理 `/Clan/` 下其他资源）。
- **注意**：Windows 下若 Nginx 启动异常，多为挂载路径问题，可将项目放在 WSL 内再执行 compose。

---

## 四、编译、运行与无库联调

### 编译与打包

```bash
mvn -pl server -am clean package -DskipTests
```

### 使用 Docker 时的数据源

- URL：`jdbc:postgresql://localhost:5432/pt`
- 用户/密码：`postgres` / `123456`
- 各应用 `application.yml` 需与此一致（或通过环境变量覆盖）。

### 无数据库运行（仅联调协议）

不连 PostgreSQL 时，pt-login-server 对所有登录返回「密码错误」：

```bash
# 命令行示例
java -jar pt-login-server/target/pt-login-server-1.0.0-SNAPSHOT.jar --spring.profiles.active=no-db
```

IDEA 中在 Run Configuration 的 Program arguments 或 VM options 中添加：`--spring.profiles.active=no-db`。

### 启动顺序（与原版一致）

1. 先启 **pt-login-server**（8484）
2. 再启 **pt-game-server**（8485）
3. **pt-web-server** 为统一 Web 应用（含原 admin、clan 功能）；依赖 **Redis** 存储登录会话。若使用 Docker，需先 `docker compose up -d`（会启动 postgres、redis、nginx），再在本机启 pt-web-server 以便 http://localhost 的 `/api/` 与 `/Clan/*.asp` 可用。

---

## 五、不使⽤ Docker 时（本机 PostgreSQL / Redis）

- 在本机安装 PostgreSQL，创建库（如 `pt`），并按需执行建表/schema 脚本。
- 脚本可参考：`server/postgres-init/`，或参考仓库 **PristonTale-EU-main** 的 `docs/postgresql-scripts/`（若使用其 SQL Server 版脚本，需按 PG 语法调整或使用本仓库已有 PG 脚本）。
- 启用数据库后，运行 pt-login-server 时**不要**加 `no-db` profile。
- **pt-web-server** 还需本机 **Redis**（默认 localhost:6379），或通过环境变量 `REDIS_HOST`、`REDIS_PORT` 指定。

---

## 六、进一步阅读

- **完整架构、与 C++ 逐项对应、密码与加密约定**：`docs/plans/2026-03-16-server-architecture.md`
- **三端合并与用户角色（web_admin、鉴权）**：`docs/plans/2026-03-16-merge-web-and-role-design.md`
- **pt-web-server 登录鉴权（Sa-Token + Redis）**：`docs/plans/2026-03-16-pt-web-satoken-redis-design.md`
- **ClanSystem 接口清单与 Java 实现设计**：`docs/plans/2026-03-16-clansystem-api-list.md`、`docs/plans/2026-03-16-clansystem-java-detailed-design.md`
- **pt-dao 配置与代码生成**：`server/pt-dao/README.md`
- **原版 C++ 架构与 DB 说明**：参考仓库 `PristonTale-EU-main/docs/pt-eu-architecture-and-db-notes.md`
