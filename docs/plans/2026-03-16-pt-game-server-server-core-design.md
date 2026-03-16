## pt-game-server / pt-server-core 设计（对齐原版 C++ Server 工程）

### 1. 目标与范围

**本设计的目标：**

- 让 **pt-game-server 能在端口 10007 启动并跑起来**。
- 实现与 C++ 原版 `Server` 工程**一一对应**的服务器框架结构（类名/职责尽量可对照），包括：
  - `Server` / `ServerManager` / `ServerWorld` / `ServerHandler`
  - `AccountServer`、`ChatServer` 等共用子模块
- 建立一个 **单线程的游戏主循环**（tick），驱动 `ServerWorld` 等结构，并为未来 NPC / 怪物 / 地图逻辑预留扩展骨架。
- 实现 **真实的 World Auth / Ticket 校验流程**：
  - Ticket 在 `pt-login-server` 中生成并写入 PostgreSQL（通过 `pt-dao`）。
  - `pt-game-server` 在客户端连接时从 DB 读取并校验 Ticket，完成 World Auth。

**本设计范围内：**

- 模块级架构：新增 `pt-server-core` 模块；调整 `pt-login-server` / `pt-game-server` 的依赖关系。
- 服务器框架结构：对齐 C++ `Server` 工程的核心类与层级。
- 主循环（单线程）与世界结构（World / Channel / Map 骨架）。
- Netty 层对接（复用 `pt-common` 的编解码 / 加解密）。
- World Auth / Ticket 校验流程（基于 DB）。

**本设计暂不实现的内容：**

- 具体地图、NPC、怪物、技能、战斗等游戏逻辑，只预留骨架。
- 复杂的多线程/多进程部署（本轮主循环为单逻辑线程）。
- 与原版完全一致的所有子系统（只抽取本轮需要的部分，后续逐步补齐）。

---

### 2. 模块与依赖关系

#### 2.1 新增模块：pt-server-core

- **定位**：Java 版的「原 C++ `Server` 工程」，承载所有 **Login / Game 公用的服务器核心逻辑**。
- **主要依赖**：
  - `pt-common`：网络包结构、头 ID、编解码、加解密等。
  - `pt-dao`：DAO 层（MyBatis-Plus Mapper），直接注入 Mapper，不再包一层 Repository。
- **不依赖**：
  - `pt-dao-spring-boot-starter`（Spring Boot 自动配置放在应用壳里）。
  - Spring Boot Web 本身（`pt-server-core` 尽量保持无 Spring Web 依赖或仅限基础注入）。

#### 2.2 应用壳模块

- **pt-login-server**
  - 依赖：`pt-common` + `pt-dao-spring-boot-starter` + `pt-server-core`
  - 职责：
    - 提供 Login 进程入口（Spring Boot）。
    - 监听登录端口（8484）。
    - 装配并使用 `pt-server-core` 中的通用 Server 框架 + `AccountServer` 等子模块。
    - 负责账号认证、Ticket 生成/入库、世界/频道列表接口等。

- **pt-game-server**
  - 依赖：`pt-common` + `pt-dao-spring-boot-starter` + `pt-server-core`
  - 职责：
    - 提供 Game 进程入口（Spring Boot）。
    - 监听游戏端口（**10007**）。
    - 使用与 Login 相同的 Server 框架结构（`Server` / `ServerWorld` / `ServerHandler` 等），但组合方式/配置不同。
    - 负责 World Auth / Ticket 校验、玩家进入世界、后续游戏逻辑。

#### 2.3 依赖关系示意（文字版）

- `pt-common`：最底层公共协议 / 编解码。
- `pt-dao`：实体 + Mapper（无 Spring）。
- `pt-server-core`：依赖 `pt-common` + `pt-dao`；实现 C++ `Server` 工程对应的核心类。
- `pt-login-server` / `pt-game-server`：依赖 `pt-server-core` + `pt-dao-spring-boot-starter` + Spring Boot，自身只实现启动与配置。

---

### 3. 类名与原版 C++ 的映射

#### 3.1 命名规则

- **去掉 C 前缀**：原版 C++ 使用 `CServerXXX` 风格，Java 中去掉开头的 `C`，其余部分保持一致。
  - 例如：`CServerWorld` → `ServerWorld`
- 对于本来就没有 `C` 的类（如 `AccountServer`, `ChatServer`），直接沿用原名。
- 在极少数与 Java/Spring 冲突或表意不清的场景，可以增加后缀（如 `ServerNettyHandler`），并在文档中说明。

#### 3.2 初步映射表示例（不完全，后续可补充）

> 以下以示意为主，具体以原 C++ 工程中的类名为准。

| C++ 类名（Server 工程） | Java 类名（pt-server-core） | 说明 |
|------------------------|-----------------------------|------|
| `CServer`              | `Server`                    | 服务器实例抽象，代表一个逻辑服务器（Login/Game 子系统的基础单元）。 |
| `CServerManager`       | `ServerManager`             | 管理多个 `Server` 实例的总控。 |
| `CServerWorld`         | `ServerWorld`               | 世界 / 逻辑场景管理层，后续承载地图/NPC/怪物等。 |
| `CServerHandler`       | `ServerHandler`             | 网络包的顶层分发处理器（底层套在 Netty handler 上）。 |
| `AccountServer`        | `AccountServer`             | 账号相关逻辑（登录、Ticket 产生、在线状态）。 |
| `ChatServer`           | `ChatServer`                | 聊天相关逻辑。 |
| `CServerSocket` 等     | `ServerSocket`（若需要）    | 与连接管理相关的抽象（在 Java 里更多由 Netty 承担，视情况抽象）。 |

> 后续在实现阶段，可在 `pt-server-core` 中增加一个 `docs/` 或注释模块，专门维护「C++ 源文件 ↔ Java 类」的对照表，方便跳转查阅。

---

### 4. Netty 层设计（复用 pt-common，结构对齐 Login）

#### 4.1 复用内容

- `pt-common` 中已存在的：
  - `PacketDecoder` / `PacketEncoder` / 加解密 Handler（如 `PacketCryptHandler`）。
  - `Packet` 抽象、`PacketHeader` 枚举、可能存在的 `PacketFactory`。
- `pt-game-server` **不再自定义一套协议 / 编解码**，完全沿用 `pt-common`。

#### 4.2 GameServer 的 Netty 启动

- 在 `pt-game-server` 中提供类似 Login 的 Netty 启动壳，例如：
  - `GameServerNettyBootstrap`（Spring Bean）
  - `GameServerChannelInitializer`（配置 pipeline）
- 在 pipeline 中按顺序加入：
  1. 编解码与加解密 Handler（来自 `pt-common`）。
  2. `ServerHandler`（来自 `pt-server-core`，负责分发到具体逻辑）。
- 监听端口：
  - `server.game.tcp-port: 10007`（配置项名可按实际确定）。

#### 4.3 与 Login 的结构一致性

- `pt-login-server` 和 `pt-game-server` 的 Netty 结构应尽量对齐：
  - 都使用 `ServerHandler` 作为顶层业务入口，只是内部根据“当前 Server 类型（Login/Game）”以及包头 ID 分派到不同逻辑。
  - 便于将来复用/迁移更多 C++ 原版逻辑到 `pt-server-core`。

---

### 5. 主循环（Server 主循环）与世界结构

#### 5.1 主循环模型（单线程）

- 在 `pt-server-core` 中提供一个通用的 **服务器主循环** 实现，对应原版 `CServer` / `CServerManager` 的行为：

- 线程模型：
  - 使用一个单独的逻辑线程（或 `ScheduledExecutorService` 单线程），周期性调用 `tick()`。
  - tick 间隔可配（如 50ms 或 100ms），通过配置项控制。

- `ServerManager` 负责：
  - 管理所有 `Server` 实例（LoginServer / GameServer / 其它子服务器）。
  - 在每次 tick 时遍历所有 `Server` 调用其 `tick()` 方法。

- `Server` 负责：
  - 管理自身的队列、会话、`ServerWorld` 等资源。
  - 实现在本服务器维度内的 `tick()`：
    - 处理接收队列中的网络请求（由 Netty/`ServerHandler` 投递进来）。
    - 驱动 `ServerWorld` 进行世界级 tick。

#### 5.2 世界结构骨架（对齐 C++）

在 `pt-server-core` 中抽象世界层级（类名示意，实际以 C++ 工程为准）：

- `ServerWorld`
  - 代表一个逻辑世界/大区，内部再细分频道/地图。
- 若原版存在 Zone/Channel 等中间层：
  - 例如 `ServerZone` 或 `ServerChannel` 类，对应频道/分线。
- `ServerMap`（或与 C++ 一致的命名）
  - 对应一张具体游戏地图，持有该地图内玩家/NPC/怪物集合。

**tick 调用链：**

- `ServerManager.tick()`  
  → 遍历每个 `Server` 调用 `Server.tick()`  
  → 每个 `Server` 内部调用其持有的 `ServerWorld.tick()`  
  → `ServerWorld.tick()` 再向下分发到 `ServerMap.tick()`（以及未来的 NPC/怪物 AI 等）。

**本轮实现期望：**

- 先搭建结构和空实现：
  - `ServerWorld.tick()` / `ServerMap.tick()` 内部暂时只做占位：例如统计在线数、简单日志。
  - 保持类名和结构尽量与 C++ 同步，后续逐渐把具体逻辑迁移进来。

---

### 6. Session / 连接管理与 ServerHandler

#### 6.1 Session 概念

- 在 `pt-server-core` 中定义统一会话类型（名称示意）：
  - `ServerClient` 或 `GameClient`（视 C++ 命名而定）。
- 职责：
  - 封装底层 Netty `Channel`。
  - 记录当前账号 ID、角色 ID、登录状态（未鉴权 / 已鉴权 / 在世界中）。
  - 记录当前所在 `ServerWorld` / 地图等信息。

#### 6.2 ServerHandler 职责

- `ServerHandler`（位于 `pt-server-core`）作为顶层包分发处理器：
  - 挂在 Netty pipeline 中（在 `pt-game-server` / `pt-login-server` 的 ChannelInitializer 中注入）。
  - 收到解码后的 `Packet` + 对应 `ServerClient`：
    - 识别包头（`PacketHeader`）。
    - 根据当前进程类型（Login / Game）和包头，将请求封装为内部任务对象（如 `ServerTask`），投入对应 `Server` 的队列。
- `Server` 在自己的逻辑线程中消费这些任务并执行具体业务逻辑。

---

### 7. World Auth / Ticket 校验流程（基于 DB）

#### 7.1 Ticket 生命周期概览

1. 登录阶段（`pt-login-server`）：
   - 用户在 Login 端完成账号密码校验。
   - `AccountServer`（pt-server-core）：
     - 在登录成功时创建 Ticket（可包含账号 ID、时间戳、随机串等）。
     - 通过 pt-dao Mapper 写入 Ticket 表。
   - `pt-login-server` 通过协议将 Ticket 下发给客户端。

2. 进入游戏阶段（`pt-game-server`）：
   - 客户端使用 Ticket 连接 Game 端（端口 10007），发送 WorldAuth 包。
   - `pt-game-server` 通过 `pt-dao` Mapper 查询 DB 中的 Ticket 记录：
     - 存在且未过期 → 通过。
     - 不存在/已过期/已使用 → 拒绝。

3. 使用后的清理：
   - Ticket 一旦被 Game 成功消费，可以：
     - 标记为已使用；或标记为某一角色当前所在 Game 进程。
     - 供后续在线列表、踢人、切服等逻辑使用。

#### 7.2 时序细节（简化）

- **Login 端：**
  - `AccountServer`（pt-server-core）：
    - 在登录成功时创建 Ticket（可包含账号 ID、时间戳、随机串等）。
    - 通过 pt-dao Mapper 写入 Ticket/在线表。
  - `pt-login-server` 通过协议将 Ticket 下发给客户端。

- **Game 端：**
  1. 客户端连接 `GameServer:10007`。
  2. Netty pipeline 收到 WorldAuth 包 → `ServerHandler` 将其封装为任务，投入对应 `Server` 的任务队列。
  3. `Server` 的逻辑线程在 tick 中处理该任务：
     - 调用 `AccountServer`（或命名更准确的 Auth 相关类）中的方法：
       - 利用 Mapper 访问 Ticket/用户表，进行校验。
     - 校验通过：
       - 将 `ServerClient` 标记为已鉴权。
       - 把玩家放入 `ServerWorld`（默认世界/地图）。
       - 回发「WorldAuth 成功/进入世界 OK」包。
     - 校验失败：
       - 回发错误码并关闭连接。

- **超时控制：**
  - 对连接建立后长时间不发 WorldAuth 包的客户端：
    - 在 `Server` 的 tick 中扫描未鉴权会话，超时（如 10 秒）则主动断开。

---

### 8. 配置与运行方式

#### 8.1 配置建议（示意）

在 `pt-game-server` 的 `application.yml` 中：

- 基本配置：
  - `server.game.tcp-port: 10007`  
  - `server.game.loop.tick-interval-ms: 50`（或 100）
- 数据源配置：
  - 与 README 中描述的 PostgreSQL 开发库保持一致（`jdbc:postgresql://localhost:5432/pt`，用户/密码 `postgres` / `123456`）。
- 其他：
  - 可以增加 profile 控制（如 `no-db` 模式下 Ticket 校验走假实现），但本轮目标以连真实 DB 为主。

#### 8.2 启动顺序

1. 启动 Docker 环境：`cd server && docker compose up -d`（Postgres + Redis + Nginx）。
2. 启动 `pt-login-server`（8484），确认登录/Ticket 流程正常。
3. 启动 `pt-game-server`（10007），确认能连接 DB。
4. 用原版客户端或测试工具：
   - 走 Login → 获取 Ticket。
   - 使用 Ticket 连接 `GameServer:10007` 发送 WorldAuth 包。
   - 观察 `pt-game-server` 日志与 DB 中 Ticket 状态变化。

---

### 9. 后续扩展方向（非本轮必做）

- 将 C++ `Server` 工程中的更多子系统（如 `ChatServer`、`ItemServer`、`GuildServer` 等）逐步迁移到 `pt-server-core` 中，沿用相同命名规则。
- 在保持单线程逻辑的前提下，为未来的多线程/分区（按 World / Channel / Map 分片）预留扩展点。
- 在 `pt-server-core` 内增加一份「C++ 源代码路径 ↔ Java 类」的对照清单，辅助对照阅读与迁移。

