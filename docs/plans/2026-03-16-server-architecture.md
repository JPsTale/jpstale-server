# JPsTale 服务端架构文档

**日期**: 2026-03-16  
**状态**: 架构整理  
**参考**: PristonTale-EU-main（C++ 服务端）、JPsTale server 模块与既有设计文档

---

## 1. 概述与目标

- **项目**: JPsTale 在 `server` 目录下用 Java 实现 Priston Tale 服务端能力，与 C++ 原版（PristonTale-EU-main）在协议与数据库层面对齐。
- **目标**: 本文档整理当前服务端模块划分、职责、与 C++ 的对应关系、依赖与技术栈，便于学习与后续扩展。

---

## 2. 与 C++ 原版的对应关系

| C++ 原版（PristonTale-EU-main） | JPsTale server 模块 | 说明 |
|--------------------------------|---------------------|------|
| **Shared + Server** 中的网络协议包、包头、枚举 | **pt-common** | 协议结构体、包 ID（如 `PacketHeader`）、基于 Netty 的编解码与**通信加解密**（`PtCryptoHandler`、`PtFrameDecoder/Encoder`、`GameXor` 等） |
| 各 Server 对 SQL Server 的访问（UserDB/GameDB/…） | **pt-dao** + **pt-dao-spring-boot-starter** | 按 PostgreSQL schema（userdb、gamedb、clandb、itemdb、eventdb、serverdb、logdb）组织 entity/mapper，Starter 提供多数据源与 MyBatis-Plus |
| **Login Server**（accountserver、packetserver、socketserver 等） | **pt-login-server** | 账号认证、Ticket、世界/频道列表、与 Game Server 的 World Auth；端口 8484，Netty TCP |
| **Game Server**（地图/NPC/怪物/掉落/任务/聊天等） | **pt-game-server** | 游戏逻辑服务；端口 8485，Netty TCP |
| **ClanSystem**（ASP .NET 项目，公会接口） | **pt-clan-server** | 公会相关 HTTP 接口，替代原 ASP，使用 pt-dao 访问 clandb（及 userdb 的 CharacterInfo 等） |
| 无直接对应（运维/配置管理） | **pt-admin-server** | 管理地图、NPC、道具、活动、玩家（用户）等数据的 Web 管理端，Spring Boot Web + pt-dao |
| 无直接对应（用户面向 Web） | **pt-web-server** | 用户注册、登录、修改密码、查看角色信息等 HTTP 用户功能，与 pt-login-server 共享密码哈希约定 |

- **密码与登录**: 登录/注册采用与 C++ 客户端一致的公式：`SHA256(UPPERCASE(账号) + ":" + 明文密码)` 的 64 位十六进制大写；pt-login-server 与 DB 中存储值直接比较；pt-web-server 注册时前端按同一公式哈希后提交，后端只校验格式并存库。详见 `docs/plans/2026-03-16-login-password-hash.md`。
- **登录响应加密**: 与 C++ 一致，登录成功后的 UserInfo、ServerList **不加密** 发出；详见 `docs/plans/2026-03-13-login-response-no-encrypt-design.md`。

---

## 3. 模块一览与职责

### 3.1 基础与数据层

| 模块 | 职责 | 技术 |
|------|------|------|
| **pt-common** | 定义原 C++ shared/server 中的网络协议包、枚举；基于 Netty 实现原版通信加解密与帧编解码 | 无 Spring；Netty、SLF4J、Lombok |
| **pt-dao** | 按 PostgreSQL 各 schema 实现数据库访问：entity、Mapper（含 XML），供各应用通过 starter 复用 | MyBatis-Plus、PostgreSQL；代码生成器在 test 下，按需手动运行 |
| **pt-dao-spring-boot-starter** | 聚合 dynamic-datasource、MyBatis-Plus（Spring Boot 4）、Mapper 扫描与多数据源配置 | Spring Boot 4、dynamic-datasource-spring-boot4、mybatis-plus-spring-boot4-starter |

### 3.2 游戏协议服务（TCP / Netty）

| 模块 | 职责 | 端口 |
|------|------|------|
| **pt-login-server** | 实现原版 Login Server：账号登录、选服/选角、Ticket、World Auth | 8484 |
| **pt-game-server** | 实现原版 Game Server：玩家上线、地图、NPC、怪物、战斗、任务、聊天等 | 8485 |

### 3.3 Web 与运维服务（HTTP / Spring Web）

**后端路由约定**：所有后端 HTTP 接口均以 **`/api/`** 为前缀，例如 `/api/user/register`、`/api/admin/xxx`、`/api/clan/member`。

| 模块 | 职责 |
|------|------|
| **pt-web-server** | 面向用户的注册、登录、修改密码、查看角色信息等（路径如 `/api/user/*`） |
| **pt-admin-server** | 管理游戏数据：地图、NPC、道具、活动、玩家（用户）信息等（路径如 `/api/admin/*`） |
| **pt-clan-server** | 实现原版 ClanSystem 提供的公会相关接口（路径如 `/api/clan/*`；另保留 `/Clan/xxx.asp` 供客户端兼容） |

### 3.4 网站前端（www）

| 路径 | 说明 |
|------|------|
| **server/www/** | 与 pt-web-server 配套的静态站点，供用户注册、登录等；由开发环境中的 Nginx 提供访问 |
| `www/index.html` | 首页，引导至注册页 |
| `www/register.html` | 注册页：账号、邮箱、密码（含确认）；前端按与 C++ 一致的公式做密码 SHA256 后提交后端 |
| `www/css/style.css` | 站点样式 |
| `www/js/register.js` | 注册表单校验与提交（调用 pt-web-server 的 `/api/` 接口） |

- 访问方式：在 Docker Compose 开发环境中，通过 **http://localhost** 访问；Nginx 将 `/api/` 反代到宿主机 **pt-web-server**（默认 8080），静态资源直接由 Nginx 从 `./www` 挂载目录提供。

---

## 4. 开发运行时环境（Docker Compose）

`server/docker-compose.yml` 定义**开发用**的运行时环境，包含：

| 服务 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| **nginx** | nginx:alpine | 80 | 提供 `server/www` 静态站点；将 **`/api/`** 与 **`/Clan/`**（原 ClanSystem 客户端兼容）反向代理到宿主机 `host.docker.internal:8080`（统一 Web 服务需在本机启动并监听 8080） |
| **postgres** | postgres:16-alpine | 5432 | PostgreSQL 16；默认库 `pt`，通过 `server/postgres-init/` 下脚本在首次启动时创建多 schema（userdb、gamedb、clandb、itemdb、eventdb、serverdb、logdb、skilldb、chatdb 等） |

- **启动**：`cd server && docker compose up -d`
- **用途**：本地开发时无需在本机安装 PostgreSQL 和 Nginx；前端改 `www/` 后刷新即可，后端 API 需在本机运行 pt-web-server（及其他需要的服务）。
- **注意**：Windows 下若 Nginx 启动异常，多为绑定挂载路径导致，可将项目放在 WSL 内再执行 compose。
- **数据库账号**：`POSTGRES_USER=postgres`，`POSTGRES_PASSWORD=123456`；应用配置需与此一致（或通过环境变量覆盖）。

---

## 5. 依赖关系

- **pt-common**：无 server 内模块依赖，被所有需要协议/编解码的模块依赖。
- **pt-dao**：仅基础依赖（MyBatis-Plus 注解、PostgreSQL、Lombok 等），不依赖其他 server 子模块。
- **pt-dao-spring-boot-starter**：依赖 pt-dao，提供自动配置；被所有需要访问数据库的应用依赖。
- **pt-login-server**：pt-common + pt-dao-spring-boot-starter（+ Netty、Spring Boot、Flyway 等）。
- **pt-game-server**：pt-common（当前未接入 pt-dao，可按需接入）。
- **pt-web-server**：pt-common + pt-dao-spring-boot-starter + spring-boot-starter-web/validation + Flyway。
- **pt-admin-server**：pt-common + pt-dao-spring-boot-starter + spring-boot-starter-web + Flyway。
- **pt-clan-server**：pt-common + pt-dao-spring-boot-starter + spring-boot-starter-web + Flyway。

```
                    ┌─────────────────┐
                    │   pt-common     │
                    └────────┬────────┘
                             │
     ┌───────────────────────┼───────────────────────┐
     │                       │                       │
     ▼                       ▼                       ▼
pt-login-server        pt-game-server         pt-web-server
pt-dao-spring-boot-starter ◄── pt-dao         pt-admin-server
     │                       │                pt-clan-server
     └───────────────────────┴────────────────────────┘
```

---

## 6. 技术栈与数据流要点

- **JDK**: 21（server 父 POM 统一）。
- **构建**: Maven 多模块；父 POM 在 `server/pom.xml`，管理 Spring Boot 4.0.3、Netty、Flyway、pt-dao 等版本。
- **数据库**: PostgreSQL；单库多 schema（userdb、gamedb、clandb、itemdb、eventdb、serverdb、logdb）或按需多数据源，经 pt-dao-spring-boot-starter 配置。
- **协议与加解密**: 包格式为「长度 + 包头 + 包体」；加解密与 C++ 一致，由 pt-common 的 `PtCryptoHandler` 等实现；登录成功后的 UserInfo/ServerList 不加密。
- **启动顺序**: 与 C++ 一致，先启 pt-login-server，再启 pt-game-server；pt-web-server、pt-admin-server、pt-clan-server 为独立 Web 应用，无强顺序。

---

## 7. 参考文档索引

| 文档 | 说明 |
|------|------|
| **PristonTale-EU-main** | |
| `docs/pt-eu-architecture-and-db-notes.md` | C++ 双服架构、协议与登录、各 DB 表结构、Java 实现路径建议 |
| `docs/source-vs-knowledge-analysis.md` | 发布帖与源码对照（登录、任务、命令等） |
| `docs/plans/2026-03-06-java-server-framework-design.md` | Java 服务端框架设计（common / login / game） |
| **JPsTale** | |
| `server/README.md` | 无库运行、PostgreSQL 部署、子模块列表、编译命令 |
| `server/pt-dao/README.md` | pt-dao 配置、代码生成方式 |
| `docs/plans/2026-03-15-pt-dao-design.md` | pt-dao 与 starter 设计、schema 与多数据源 |
| `docs/plans/2026-03-15-clansystem-asp-sql-migration.md` | ClanSystem ASP → clandb + pt-dao Mapper 对应 |
| `docs/plans/2026-03-13-login-response-no-encrypt-design.md` | 登录成功包不加密设计 |
| `docs/plans/2026-03-16-login-password-hash.md` | 登录/注册密码哈希约定与 C++ 位置 |

---

**文档结束**。后续可在此基础上补充各模块的 API 清单、端口与配置项表、或与 C++ 包 ID 的详细映射。
