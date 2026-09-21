# common-service

game-server 与 web-server **共用**的业务逻辑与基础设施。

> 这个模块的存在理由是"两边功能一致"：凡是两个进程都要遵守的规则（物品掷点、装备需求、
> 属性计算、地图数据、消息队列契约），实现必须只有一份。此前它们要么各写一份、
> 要么只存在于其中一个 app 里（web 端算不了离线角色属性、模拟器的掷点与游戏内不一致）。

## 铁律（加任何类之前先读）

### 1. 不许出现的类型

- `extends BaseEntity` 的**运行时实体**：`PlayerEntity` / `Monster` / `Npc` / `GroundItem`
- `io.netty.*`（连接与 IO 归 app 层）
- `org.jpstale.server.proto.*`（protobuf 在 `protocol` 模块，只有 game-server 依赖）

判据一句话：**本模块要能在一个没有网络、没有 Spring 容器的进程里被实例化并调用。**

`Player` 是允许的 —— 它是玩家的**数据面**（等级/职业/属性/物品），
已经与 `PlayerSession` 解耦。要发消息给某个玩家时用 `PlayerService.sessionOf(player)`，不要反过来持有会话。

### 2. 接受 Player，不接受 PlayerSession

会话代表"某个人的一条连接"，属于 app 层。共享逻辑只认数据。

### 3. 每个 `@Service` 都会在**两个进程**里被实例化

两个 app 都是 `@SpringBootApplication(scanBasePackages = "org.jpstale")`，
所以本模块里的每个 stereotype 注解都会同时出现在 game-server 与 web-server 的容器里。结论：

- **构造期不许有 I/O**（查库、读文件、连网络），惰性化或用 `@Lazy`；
- **`@Value` 必须有默认值**，或确认两侧配置都存在（无默认值的 key 会让启动直接失败）；
- **依赖要由使用它的 app 显式声明** —— 本模块的依赖即使声明成 `optional` 也不会传递给 app，
  而 IDE 常把同 reactor 模块的 optional 依赖也放进类路径，于是"IDE 里能跑、Maven 里启动即崩"。
  这条是踩过的坑，见下面"已踩过的坑"。

### 4. 不许自持生命周期

物品/装备的逻辑是**规则**，不是状态机的所有者：状态在 app 侧（`Player` / `PlayerItems`），
本模块只提供判定与计算。别在这里开线程、建缓存表却不给失效入口、或注册全局监听器。

## 已踩过的坑

### `RedisMsgDispatcher` 启动即崩（NoClassDefFoundError: tools/jackson/databind/DeserializationContext）

这个 bean 当时住在 `common-model`，而 `common-model` 把 `spring-boot-starter-json`（提供 **Jackson 3**，
`tools.jackson.*`）与 `spring-boot-starter-data-redis` 声明成了 `optional` —— 对"纯模型模块"这是对的，
但 **optional 不传递**，于是严格 Maven 类路径下 game-server 拿不到 Jackson 3，启动即崩。
更坑的是 IDE 常把同 reactor 模块的 optional 依赖也放进类路径，所以"IDE 里能跑"掩盖了它，
直到清掉项目缓存、按严格语义重新解析才暴露。

**现在的定式（也是本模块的铁律 3 的落地方式）**：**bean 住哪个模块，依赖就由哪个模块的 pom 声明，且声明成非 optional。**
Redis 消息层搬进来之后，那两个 starter 就写在 `common-service/pom.xml` 里，两个 app 透过它传递获得 ——
不重复声明、不可能漂移。`common-model` 因此只剩 Lombok 一个依赖。
