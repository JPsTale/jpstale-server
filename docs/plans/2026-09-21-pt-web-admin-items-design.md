# pt-web-server 管理端：物品管理（物品定义查询与编辑）设计

**日期**: 2026-09-21
**状态**: 设计（仅文档，不含实现；§四 的部分清理已在 2026-09-21 执行）
**接续**: `2026-03-16-pt-web-admin-maps-npc-monsters-design.md` —— 该文把「道具与掉落配置」显式列为
*不在本次范围内（可作为后续扩展）*，本文是其中的**物品定义**部分，同时也是
「用一套完整的管理系统收纳前期临时工具」这件事的第一块。

---

## 〇、决策记录（本文的约束）

| # | 决策项 | 结论 |
|---|---|---|
| 1 | 改动范围 | 只读 + **能改物品定义** |
| 2 | 列表返回字段 | **数据库全部列原样返回**；表格默认展开常用列，其余可勾选 |
| 3 | 筛选条件 | **只做四组固定条件**：身份 / 数值门槛 / 装备语义 / 攻防数值 |
| 4 | 表格默认列 | `idcode` `name` **`codeimg1`** `category` `reqlevel` `price` `weight` `weaponclass` `classitem` `modelposition`（用户 2026-09-22 追加 `codeimg1`：重要参数） |
| 5 | 前端落点 | **全部搬进 jar（`resources/static/`），页内路径用相对**（§6.1）；`www/` + nginx 那套退休 |
| 6 | 可改的列 | **全部列 − 主键 − 时间戳类列**（§5.4.1） |
| 7 | 改动留痕 | **不做审计** |
| 8 | 详情形态 | **弹窗浮层** |
| 9 | 详情内容 | 不用模拟器那个详情框，管理端自建；分**五段**承载全部列（§三） |
| 10 | 三项能力 | Spec / Mix / Age 与职业需求修正**搬到管理端**（§6.2） |
| 11 | 详情与编辑 | 详情只读，**点「编辑」才可改**；编辑界面按详情**同构的分段** |
| 12 | 攻防数值筛选 | **区间相交**（§5.2）：一个数值一组 `_min`/`_max` |
| 13 | `null` 清空某列 | 不考虑 |
| 14 | 模拟器 | 页面**已删**；后端接口已标 `@Deprecated`，**整块删除等物品管理做完**（§四） |
| 15 | 收纳范围 | 新系统容纳 `maps`（地图查看）+ `spawn-debug`（**只做刷怪配置编辑**）+ 物品管理；**`pviewer` 丢弃**（3D 能力已在 jpstale-client / efria） |
| 16 | 实时那链路 | **不吸收** `spawn-debug` 连游戏服的 WebSocket；实时观察不再作为管理端能力 |
| 17 | 顺序 | **先清理（`pviewer` 已删），再开工（先做物品管理）** |
| 18 | 权限门 | 已定并实施（§5.0）：判据 = 原版 GM 两列（唯一实现 `GameMasterRule`，取更严的「与」）+ 注册 `SaInterceptor` 让注解真正生效 |

---

## 一、数据源

- 表：`gamedb.itemlist`（`@TableName(schema="gamedb", value="itemlist")`）。
- 实体：`modules/dao/src/main/java/org/jpstale/dao/gamedb/entity/ItemList.java`（主键 `id` + **107 个 `@TableField`**）。
- Mapper：`modules/dao/.../gamedb/mapper/ItemListMapper.java`。

**为什么"全部列"能忠实做到**：`ItemList` 是 **MyBatis-Plus `FastAutoGenerator` 从库结构反向生成**的
（`docs/plans/2026-03-15-pt-dao-design.md:42`、`:75-99`、`:103`）。所以**实体字段集合 = 表的列集合**。

### 1.1 ✅ 列比对已完成（2026-09-21，连的生产库，只读 SELECT）

```sql
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'gamedb' AND table_name = 'itemlist'
ORDER BY ordinal_position;
```

拿实体（`grep -o '@TableField("[a-z0-9_]*")'` 出来的 107 个）与库列**用 `comm` 双向比对**，结果：

| 项 | 结果 |
|---|---|
| 实体列数 / 库列数（不含 `id`） | **107 / 107** |
| 只在实体里（库中没有） | **0 个** |
| 只在库里（实体没映射） | **0 个** |
| 库列总数（含 `id`） | **108** ⇒ §三 的 108 成立 |
| 表行数 | **1036** |
| `idcode` 重复的行组 | **17 组**（⇒ 主键必须用 `id`，`/items/{id}` 的设计正确） |
| 索引 | 只有主键 `idx_16886_pk_itemlist (id)` |

⇒ **"全部列"是核实过的，不是假设。** 无需补实体。
另：1036 行 + 只有主键索引 ⇒ 筛选/排序全表扫无妨，**不必加索引**。

**附带确认**：`ItemList` 里唯一的 `*time` 列是 `questflashingtime`（业务字段），库里**没有**
`create_time` / `update_time`；`category` 是 `text NOT NULL`（无空值）。
所以 §5.4.1 的时间戳排除规则在本表上**当前没有可排除对象**。

---

## 二、命名口径：对外一律用数据库列名

库列名全小写（`idcode` / `reqlevel` / `classitem` / `reqstrength`），实体属性是驼峰
（`idCode` / `reqLevel` / `classItem` / `reqStrengh`）。本接口**对外一律用数据库列名**。

- 启动时**反射**遍历 `ItemList` 字段，用 `@TableField` 的值建一张 `列名 ⇄ 属性名` 映射。这张表机械生成。
- **出参**：行按这张表转成 `LinkedHashMap<String,Object>`（键 = 列名）。
- **入参**：筛选参数与更新字段的键也是列名，按同一张表反射回属性。

好处：内部仍用强类型 `ItemList` + `LambdaQueryWrapper`，**不需要 `${}` 动态列名**，没有拼接 SQL 的注入面。

⚠ 不在表内的键一律 **HTTP 400 + 日志**，**不得静默忽略**。

---

## 三、详情浮层的五段（承载全部 108 列）

列数核对：**13 + 6 + 51 + 31 + 7 = 108** = 实体 107 个 `@TableField` + 主键 `id`。

**1. Identity 身份（13）**
`id` · `idcode` · `name` · `category` · `weaponclass` · `classitem` · `modelposition` ·
`width` · `height` · `sound` · `codeimg1` · `codeimg2` · `dropfolder`

**2. Requirements 需求（6）**
`reqlevel` · `reqstrength` · `reqspirit` · `reqtalent` · `reqagility` · `reqhealth`

**3. Base Stats 基础属性（51）**
- 成对区间（22 对 = 44）：`integrity` · `atkpow1` · `atkpow2` · `atkrating` · `block` ·
  `absorb` · `defense` · `runspeed` · `organic` · `fire` · `frost` · `lightning` · `poison` ·
  `addhp` · `addmp` · `addstm` · `regenerationhp` · `regenerationmp` · `regenerationstm` ·
  `recoveryhp` · `recoverymp` · `recoverystm`（每对 = `*min` + `*max`）
- 单值（7）：`atkspeed` · `critical` · `range` · `potionspace` · `potioncount` · `weight` · `price`

**4. Spec 职业特效（31）**
`primaryspec` · `addspecclass1..12`（12）· `addspecrunspeed*`(2) · `addspecabsorb*`(2) ·
`addspecdefense*`(2) · `addspecatkspeed` · `addspeccritical` · `addspecatkpower*`(2) ·
`addspecatkrating*`(2) · `addspechpregen` · `addspecmpregen*`(2) · `addspecstmregen` ·
`addspecblock` · `addspecrange`

**5. Misc 其他（7）**
`questid` · `questr` · `questg` · `questb` · `questtransparency` · `questflashingtime` · `cannotdrop`

---

## 四、临时工具的处置与清理台账

### 4.1 已执行（2026-09-21）

| 对象 | 处置 | 依据 |
|---|---|---|
| `static/simulator/index.html` | **已删**（连同空的 `simulator/` 目录） | 决策 #14 |
| `static/pviewer/`（18 个文件，281K） | **已删** | 决策 #15。整个工作区搜 `pviewer` 只命中 **注释**（efria `job-lab.ts` / `char-stage.ts`、client `extract-models.ts` / `anim-match.ts` 的"吸收自 / 对齐 pviewer…"），**没有任何代码读它的文件** |
| `SimulatorController` 类 + 4 个方法 | 加 `@Deprecated` + `@deprecated` 说明 | 过渡态 |
| `SimulatorService` javadoc | 写明它随模拟器废弃 | 未加注解（见下） |
| 编译 | **BUILD SUCCESS** | `mvn -o -pl apps/web-server -am compile` |

`SimulatorService` 刻意**没加** `@Deprecated`：调用它的只有那个已废弃的 controller，
加注解只会让 javac 的过时告警在调用处铺一层，没有额外信息。

### 4.2 等物品管理做完再删（后端侧）

**为什么等**：pviewer 是 `GET /api/simulator/items` 与 `/api/simulator/skills` 的**唯一消费者**
（`static/pviewer/index.html:993,1096,1627`，现已删）。所以下面这些现在都无消费者了 ——
**但** `ItemCategory`（idcode → wartale 分类）与 `mixTypeNameOf` + mixlist 查询
是管理端 Mix 下拉要用的（§5.5），必须**先搬进 `AdminItemService` 再删**。

| 待删 | 备注 |
|---|---|
| `SimulatorController` | 连同那 4 个端点 |
| `SimulatorService` | `ItemCategory` 与 mix 相关的部分先搬走 |
| `ItemSummary` | pviewer 读它的 `dorpItem` / `reqLevel`，pviewer 没了即无消费者 |
| `ItemDetail` | 只被 `SimulatorController.item` 与 `SimulatorService` 用 |
| `SkillController` + `SkillService` | `/api/simulator/skills` 的唯一消费者是 pviewer |
| `data/skills.json`（25K） | `SkillService` 读它；随 `SkillService` 一起删 |

### 4.3 保留待定：4 个数据文件（服务端副本均无运行时消费者）

| 文件 | 服务端消费者 | 别处有无同名 | 说明 |
|---|---|---|---|
| `data/items-11job.json`（523K） | **无** | 客户端**另有一份**（`jpstale-client/src/game/data/source/items-11job.json`，被 `extract-items-from-11job.ts` 读） | 两处是**两份文件**，不是同一份 |
| `data/skill-mapping.json` | **无** | 客户端另有一份（被 `merge-skill-index.ts`、`skillIndexByIcon.ts` 用） | 同上 |
| `data/skills-eu.json` | **无** | 只在 `skill-mapping.json` 的 `_comment` 里被提到（生成时的来源，2026-08-24） | |
| `data/skill-index-comparison.json` | **无** | 精确文件名全仓零命中；有 `docs/chars/skill-index-comparison.md` 在讲它 | |

**建议先留着**：`items-11job.json` 是「重扫 OpenItem 提 `**특화` 字段」（AGENTS #8(d)）那件待办工作的参照物，
删了会让那件事少一个对照基准。它们都随 jar 打包 ⇒ 白占约 600K（想要 jar 干净可以删，git 里有，可恢复）。

> ⚠ **更正我先前的一处错误**：我曾说"`items-11job.json` 不是孤儿，client 的脚本在用它" ——
> 那句话**指错了对象**。客户端读的是**它自己的副本**；服务端这份仍是零消费者。同名 ≠ 同一份。

### 4.4 公会图标资产 `www/ClanImage/`（**不是孤儿**）

我先前把它列成"全仓零引用"是错的 —— 它是**公会系统的图标资产**。机制已查清：

- **键是编号**：`clandb.CL.MIconCnt`（int，公会图标编号）。`ClanService` 只把编号当 `CIMG`
  暴露出去（`ClanService.java:341`），**服务端不发 URL**。
- **文件名 = 编号**：`www/ClanImage/<MIconCnt>.bmp`。实测 81 个文件：`999999.bmp`
  （看着像默认/占位）与 `1000001`…`1000115`（**不连号**，有空洞）。
- **URL 形状由"拼 URL 的那一方"决定** —— 原版 ASP 页面、原版客户端、或将来我们自己的页面。
  这三者都不在本仓库：原版 ASP 已退役（被 `/api/clan/*` + `ClanResponse` 取代）；
  新客户端 `jpstale-client` 里搜 `ClanImage` / `CIMG` / `clanImage` **零命中**。
  本仓库里确实没有代码读它 —— 但它不是孤儿，它是那条 URL 契约背后的**资产库**。

⚠ **迁移裁决（2026-09-21）**：`/ClanImage/<n>.bmp` 是**根绝对路径**，与 `www/` 其余部分同一套假设。
若按决策 #5 把 www 搬进 jar 而部署仍用 context-path `/pt`，应用**无法**服务 context-path 之外的路径。
已确认**现在没有人在按 `/ClanImage/<n>.bmp` 取**（本仓库零消费者、新客户端零引用、原版那侧也不再取），
所以**路径跟随新约定**：目录原样搬进 `resources/static/ClanImage/`，
URL 变成 `${context-path}/ClanImage/<n>.bmp`（页面内引用写相对 `./ClanImage/<n>.bmp`）。

⇒ **`ClanImage` 不再是 nginx 退休的阻碍**（它曾是唯一可能的阻碍）。

**要留给将来做公会页面的人的知识**：图标 URL = `./ClanImage/<CL.MIconCnt>.bmp`，
文件名就是编号；`999999.bmp` 看着是默认/占位。服务端**只发编号**（`CIMG`），URL 由渲染方拼。

> 附注（可忽略）：目录名 `ClanImage` 是 PascalCase，与 `maps` / `spawn-debug` 的小写风格不一致。
> 要统一命名，现在是唯一无成本的时机（没人取）；不改也无妨。

### 4.5 其余

- `spawn-debug/maps/`（44 张 `.png`，5.1M）：**预渲染产物**，不是源数据。新系统若自己渲染地图，
  它们可再生 —— 收纳 `spawn-debug` 时再定。

> ⚠ 服务器上 nginx 有 `/pt/simulator/`、`/pt/pviewer/` 两条静态路由，现已指向空目录（预期 404）。
> 那份 nginx 配置不在本仓库里，需要你那边同步清掉。

---

## 五、API

路径都在 `/api/admin/items/**`。沿用**已实现**的约定：响应体 `Result<T>` = `{code, msg, data}`、
**`code == 200` 为成功**；失败抛 `BusinessException(ResultCode.XXX)`；`msg` 是 i18n key；
鉴权 `@SaCheckRole("admin")` **并且**方法内显式 `StpUtil.checkLogin()` / `checkRole("admin")`；只用 GET/POST。

新增错误码：`ITEM_NOT_FOUND(10406, 404, "error.web.itemNotFound")`。

### 5.0 ⚠ 前置：`admin` 角色源当前是坏的（2026-09-21 实测）

**实测证据**（真启服务、真发请求）：

| 请求 | 结果 | 说明 |
|---|---|---|
| 匿名 `GET /pt/api/admin/info` | **200** | 该方法**只有** `@SaCheckRole("admin")` 注解 ⇒ **注解没拦任何东西** |
| 匿名 `GET /pt/api/admin/maps` | **401** | 该方法有注解**且**方法体里显式 `StpUtil.checkLogin()` / `checkRole("admin")` ⇒ 拦住了 |

⇒ **结论一：注解鉴权在本项目完全不生效。** 全仓没有 `SaInterceptor`、没有 `addInterceptors`、
pom 里也没有 `sa-token-spring-aop`（`WebMvcConfig` 只注册了 CORS 与资源映射）。
真正起作用的只有**方法体里显式的 `StpUtil.checkXxx()`**。
所以下文的"`@SaCheckRole("admin")` **并且**显式 `StpUtil.checkRole("admin")`"里，
那个"并且"是**必需项**，不是冗余保险。

⇒ **结论二：`admin` 角色谁都拿不到，链路是断的。**

```
仓库 DDL 声明 user_info.web_admin
  └─ 但活库那张表叫 userdb.userinfo，且【没有 web_admin 列】（实测：
     select web_admin from userdb.userinfo → column "web_admin" does not exist）
     └─ 实体把 webAdmin 标成 @TableField(exist = false)（UserInfo.java:61）
        └─ selectOneByAccountName 的 SQL 与 resultMap 都不含它（UserInfoMapper.xml）
           └─ user.getWebAdmin() 恒为 null（LoginController.java:42）
              └─ Session 里 webAdmin 恒为 false（LoginController.java:44）
                 └─ StpInterfaceImpl 恒返回 List.of("user")（:19）
                    └─ checkRole("admin") 恒失败
```

当前后果：**带显式检查的 admin 接口对所有人 401（包括真管理员）；只带注解的 admin 接口对所有人 200。**

⇒ **结论三：仓库的 `postgres-init` 与活库不是一套 schema（结论二的根因）。**

| | 表名 | 账号列 | 有 `web_admin` 吗 |
|---|---|---|---|
| 仓库 `postgres-init/01-create-userdb.sql:155` | `userdb.user_info` | `account_name` | **有**（`:174`，另 `02-data-userdb.sql` 种子了一个 `admin` 账号） |
| **活库** | `userdb.userinfo` | `accountname` | **没有** |
| 实体 / XML 对齐的是 | `userdb.userinfo` | `accountname` | —— |

docker 的 entrypoint 只在**空数据卷**时执行 `postgres-init`，所以已在跑的库永远不会因此获得 `web_admin`；
反过来，**新装环境会建出 `user_info`，与实体/XML 的 `userinfo` 对不上** —— 这是个待爆的雷。

⇒ **对本设计的影响：那批 `/api/admin/items/**` 现在无法被 admin 门保护。** 要落地必须先修角色源。

### 5.0.1 ✅ 已修（2026-09-21，用户裁决）

| 项 | 决定 |
|---|---|
| 判据 | **原版 GM 两列** `userinfo.gamemastertype` / `gamemasterlevel` |
| 谓词 | `type != 0 && level > 0`（**「与」**，更严 = fail-closed）。收编前 `NpcShopHandler` 用「与」、`ChatService` 用「或」；活库实测只有 `(1,4)×78` 与 `(0,0)×1` 两种组合，**两种判据当前放行同一批 78 个账号**，分歧只在"单边非零"时显现 |
| 唯一实现 | 新增 `modules/common-service/.../service/account/GameMasterRule.java`（+ 5 条单测）。⚠ 两个 game-server 调用点**尚未**改为委托它（改它会动游戏内 GM 行为，不属本次范围）⇒ 目前是"一份规范化实现 + 两份旧的"，收敛待你发话 |
| 注解 | 新增 `apps/web-server/.../config/SaTokenConfig.java`，注册 `new SaInterceptor()`（不传 auth 函数 ⇒ 只做注解校验，不另起一张路径表） |
| Session 键 | 新增 `web/auth/SessionKeys.java`。`"webAdmin"` / `"accountName"` 原先在写方（登录）与读方（鉴权 / `/me` / 改密）**各写一份字面量**，已收成一处 |

**实测结果（真启服务）**：

| 请求（匿名） | 改前 | 改后 |
|---|---|---|
| `GET /api/admin/info`（只带 `@SaCheckRole`） | **200** | **401** ✅ |
| `POST /api/clan/ranking.json`（只带 `@SaCheckLogin`） | **200**（匿名可调） | **401** ✅ |
| `GET /api/user/me` | 401 | 401 |
| `POST /api/user/login`（密码错） | 401 `code 10101` | 401 `code 10101`（**登录未被锁死** ✅） |
| `OPTIONS /api/user/login`（CORS 预检） | 200 | 200 ✅ |
| `GET /pt/`（静态页） | 200 | 200 ✅ |

单测：`GameMasterRuleTest` 5/5 通过。

> ⚠ **仍未验证：admin 的"正向"路径**（用 GM 账号登录 → `webAdmin=true` → `admin` 角色 →
> `/api/admin/**` 返 200）。这需要那 78 个 GM 测试账号之一的密码，我手上没有。
> 复现方式：用任一 GM 账号登录，确认 `/api/user/me` 返回 `"webAdmin": true`，再 GET `/api/admin/info` 应为 200。
> ⚠ 注意：活库 **79 个账号里 78 个命中该判据**（测试账号全量灌过 GM 字段）⇒
> **知道任一测试账号密码的人都是 Web 管理员**。这是选定的方案（B），不是漏洞，但值得记住。

### 5.1 `GET /api/admin/items/columns`

`data` = `[{ "column": "idcode", "javaType": "Integer", "section": "Identity",
"editable": true, "primaryKey": false, "filterable": false }, …]`
由 §二 的反射表 + §三 的分段表 + §5.4.1 的排除规则生成，不查库。

### 5.2 `GET /api/admin/items`

`data` = `{ "total": …, "page": …, "size": …, "totalPages": …, "items": [ {列名: 值, …}, … ] }`

**筛选 = 下列四组，仅此四组**：

| 组 | 参数 | 列 | 操作 |
|---|---|---|---|
| 身份 | `name_like` | `name` | 模糊，**大小写不敏感**（PG 的 `LIKE` 区分大小写，而库里是 "Sword" 这种写法 —— 用 `LIKE` 搜 "sword" 一条都不中）；`%` `_` 按字面量 |
| | `idcode` | `idcode` | 精确 |
| | `category` | `category` | 精确 |
| 数值门槛 | `reqlevel_min` / `reqlevel_max` | `reqlevel` | 区间 |
| | `price_min` / `price_max` | `price` | 区间 |
| | `weight_min` / `weight_max` | `weight` | 区间 |
| 装备语义 | `weaponclass` · `classitem` · `modelposition` | 同名列 | 精确（数据库原值，不翻译语义） |
| 攻防数值 | `defense_min` / `defense_max` | `defensemin` + `defensemax` | **区间相交** |
| | `atkpow1_min` / `atkpow1_max` | `atkpow1min` + `atkpow1max` | **区间相交** |
| | `atkpow2_min` / `atkpow2_max` | `atkpow2min` + `atkpow2max` | **区间相交** |
| | `atkrating_min` / `atkrating_max` | `atkratingmin` + `atkratingmax` | **区间相交** |
| | `absorb_min` / `absorb_max` | `absorbmin` + `absorbmax` | **区间相交** |
| | `block_min` / `block_max` | `blockmin` + `blockmax` | **区间相交** |

⚠ **`category` 的实际取值（实测：1036 行、46 个不同值）**：
`Premium 107 · Costume 66 · Swords 39 · Socket 38 · Wands 33 · Bows 33 · Dagger 33 · Scythes 32 ·
Phantom 32 · Javelins 32 · Rings 30 · Amulets 29 · Axes 28 · Boots 28 · Armors 28 · Hammers 28 ·
Gauntlets 27 · Orbs 27 · Robes 27 · quests 27 · Claws 27 · Bracelets 26 · Shields 26 · Respec 24 ·
event 24 · Suit 24 · Crystals 22 · Christmas 22 · BC 16 · Sheltom 15 · Potions 15 · Quest 14 ·
Force Orbs 14 · Halloween 8 · SoD 7 · wings 6 · Cores 5 · Event 4 · costumes 3 · Rune 2 · Hat 2 ·
Easter 2 · Gold 1 · Exp 1 · GrandFury 1 · SOD 1`

取值可枚举 ⇒ **下拉多选**是对的。但有一处必须注意：**同义项大小写不一致**
（`event 24` / `Event 4`、`costumes 3` / `Costume 66`、`SOD 1` / `SoD 7`、`quests 27` / `Quest 14`）。
按数据库原值做下拉就会拆成两个选项，用户只选一个就**漏掉另一拨**。
**做法**：下拉列**原值**（不归并 —— 归并就不是"忠实于数据库"了），但**每个选项带计数**，
让 `event (24)` 与 `Event (4)` 并排出现。是否归并属于数据侧的决定，不在本设计里做。

**区间相交判据**（决策 #12）：给定 `X_min` / `X_max`，命中条件为

```
Xmax >= :min  AND  Xmin <= :max
```

**不是**"两端都落在范围内"。后者会把区间宽的基础装整批漏掉，而它看起来很像对的。

⚠ `atkpow1` 与 `atkpow2` 是**两对独立的列**，所以各开一组参数。

**排序**：`sort` = 数据库列名（白名单 = §二 的表），`order` ∈ {`asc`,`desc`}；非法值 → 400。
**分页**：`page`（≥1，默认 1）+ `size`（1..200，默认 50）。

#### 5.2.1 分页实现：`Page` 不等于有分页

**实测事实**：① 全仓没有 `MybatisPlusInterceptor` / `PaginationInnerInterceptor`；
② 本机 Maven 仓库里 `mybatis-plus-jsqlparser` 与 `com.github.jsqlparser` **都不存在**
（3.5.9+ 起该拦截器在独立 artifact 里）→ 直接用它会**编译不过**；
③ 没有拦截器时 `selectPage` **不报错**：返回全表、`getTotal()` **恒为 0** —— 静默失败。

**采用（零新依赖）**：`mapper.selectCount(wrapper)` 取 total；列表
`wrapper.orderBy(白名单列).last("LIMIT " + n + " OFFSET " + m)`，`n`/`m` 由**已校验并夹紧的 int** 拼出。
⚠ `.last()` 是裸 SQL，"夹紧"（`size ≤ 200`、`page ≥ 1`）**是注入防线，不是可选优化**。

### 5.3 `GET /api/admin/items/{id}`

按主键取一行，返回**全部列**。不存在 → 404 + `error.web.itemNotFound`。

### 5.4 `POST /api/admin/items/{id}`

- 请求体 `{ 列名: 新值, … }`，**部分更新**；响应为更新后的整行。
- 三条机械校验，任一不过 → 400 且日志有记录：键是 §二 表里的列名；键在**可改列**内（§5.4.1）；
  值类型与实体字段类型相容。
- 实现：列名反射回属性、装进只设了这些字段的 `ItemList`，`updateById`。
  ⚠ MyBatis-Plus 默认忽略 `null` ⇒ **传 `null` 等于"不改"**（决策 #13）。

#### 5.4.1 可改列 = 全部列 − 主键 − 时间戳类列

| 排除项 | 内容 | 依据 |
|---|---|---|
| 主键 | `id` | `userdb.item.itemlist_id` 指向它，改了就割断引用 |
| 时间戳类 | `create_time` / `update_time` / `delete_time` / `created_at` / `updated_at` | 不含业务意义 |

⚠ **排除清单用精确列名匹配，禁止用 `*time` / `*_at` 后缀通配**：本表有一列 `questflashingtime`
（原版物品的**闪烁时间**）是业务字段，后缀通配会把它误排除 —— 而"某列改不动"这种症状很难查。

### 5.5 `GET /api/admin/items/{id}/mixes`

供浮层的 **Mix 下拉**。**不是**复用 `SimulatorService`（它待删），而是把
分类→`typemixname` 映射与 mixlist 查询**搬进 `AdminItemService`**（§4.2）。
服务端用 `ItemCategory.of(idcode)` 求 wartale 的 `type`/`subtype` 再查 —— 派生只发生在服务端一处，
下发的行数据保持全部列原样。

---

## 六、前端

### 6.1 落点：全部搬进 jar，页内路径用相对（决策 #5）

- `www/*` → `apps/web-server/src/main/resources/static/`（含 `ClanImage/`，见 §4.4）
- 页内路径：`/css/style.css` → `./css/style.css`、`/js/api.js` → `./js/api.js`、
  页间链接 `/login.html` → `./login.html`
- 访问地址随之变成 `http://localhost:8080/pt/login.html`（context-path 下）
- **收益**：`mvn spring-boot:run` 一个进程开整套，nginx 容器退休；页面与后端**同版本原子发布**，
  不会再出现"www 是旧的、后端是新的"这种分叉
- 现状：`www/` **100% 是根绝对路径**（逐条查过 5 个 html + 4 个 js），所以这是一次机械但面广的改动

**✅ 已执行并验证（2026-09-21）**：`www/*` 已用 `git mv` 搬进 `static/` —— **92 项全部记为 rename**
（5 个 html + `css/` + 5 个 js + 81 个 `ClanImage`；内容逐字节未变，`git status` 里是 `R` 而非 delete+add）。
25 处 html 属性路径 + 3 处 `location.href` + 2 处绕过 `PT.request` 的裸 `fetch` 已改；
`api.js` 加了 `PT_API_BASE`（默认 `'./'`）与 `PT.apiUrl()`。站内引用 **25/25 可达**，5 个 js 全部 `node --check` 通过。

**两种 context-path 都实测通过**（真启服务、真发请求）：

| 请求 | context-path `/pt` | context-path 空 |
|---|---|---|
| 站点根 | `/pt/` **200** | `/` **200** |
| 登录页 | `/pt/login.html` **200** | `/login.html` **200** |
| `./js/api.js` 解析结果 | `/pt/js/api.js` **200** | `/js/api.js` **200** |
| `./css/style.css` 解析结果 | `/pt/css/style.css` **200** | `/css/style.css` **200** |
| `ClanImage` | `/pt/ClanImage/1000001.bmp` **200** | `/ClanImage/1000001.bmp` **200** |
| `./api/...` 解析到的端点 | `/pt/api/user/login` **405**（存在，只收 POST） | —— |
| 未登录 API | `/pt/api/user/me` **401** | `/api/user/me` **401** |

⇒ §6.1.1 的约定成立：**同一份页面在两种 context-path 下都通**，`/api` vs `/pt/api` 的老问题消除。
顺带把"目录形式是否落到 `index.html`"这个疑点也验了（`/pt/` **能**出首页）。

**本地怎么跑（你的原始痛点）**：

```bash
cd apps/web-server
mvn spring-boot:run        # 离线可用全限定 goal：org.springframework.boot:spring-boot-maven-plugin:4.0.3:run
```

- **不再需要 nginx 容器**：`http://localhost:8080/pt/` 直接出首页。启动约 5 秒。
- ⚠ 环境配置是**构建期由 Maven profile 选的**：根 `pom.xml:185` 的 `<resources>` 把
  `src/main/resources/${profiles.active}` 提升成 classpath 根的 `application.yml`，
  并排除 `dev/`、`test/`、`prod/` 三个源目录。默认激活 `dev`。
  ⇒ **不需要 `--spring.profiles.active=dev`**（`dev/application.yml` 里没有 `spring.profiles` 键）；
  换环境用 **`-Ptest` / `-Pprod`**。切错只会**静默连错库**，不报错。
- ⚠ 我一开始想当然地加了 `--spring.config.additional-location=classpath:/dev/`，启动直接失败
  （`Config data location 'classpath:/dev/' does not exist`）—— 那个目录被 pom 刻意排除，
  **根本不在 classpath 上**。别重蹈。

#### 6.1.1 路径约定（`api.js` 的 base 怎么表达）

`www/js/api.js` 现在写死 `'/api'`。搬进 jar 后要能被两种部署同时用
（Ubuntu 的 context-path `/pt`、docker 那套的空 context-path），所以约定：

> `api.js` 读 `window.PT_API_BASE`，**默认 `'./'`**；每个页面按自己相对 context 根的深度**显式**覆盖一行。

- 根层页面（`/pt/login.html`）：`window.PT_API_BASE = './'` → `./api/user/login` 解析为 `/pt/api/user/login` ✓
- 子目录页面（`static/maps/index.html` → `/pt/maps/`）：写 `'../'`

⚠ **为什么不"自动算"**：从 `location.pathname` 推 context 根只在"页面与 `/api` 同层"时成立；
页面一嵌深就错，而且是**静默**错（`./api/...` 被解析成 `/pt/maps/api/...` → 404）。

**迁移范围只做 `www/`**：`maps` 与 `spawn-debug` 本来就写死 `/pt/`，但它们的功能要重整，
现在改一遍将来还要改 —— 留到**收纳它们时一起改**，避免改两遍。

### 6.2 三项能力：从已删的模拟器页面**原样搬**到 `static/js/item-effects.js`

| 能力 | 符号 | 数据依赖 |
|---|---|---|
| **Spec** 职业特效 + 需求修正 | `SPEC_JOBS`（11 职业名/简称）、`REQ_MOD`、`specClassValues` / `specApplies` / `specClassList` / `reqAdjust` | **零接口**；只需行数据里的 `primaryspec` / `addspecclass1..12` / `req*` |
| **Mix** 合成属性叠加 | `applyMixEffects`（按 `e.attr` 把 `e.value` 加到对应列） | §5.5 |
| **Age** 强化倍率 | `applyAge`：`rate = 1 + level*0.02`，等级 0..+20 | **零接口** |

⚠ **Age 只是倍率，不含锻造成功率**：`applyAge` 用的就是这个公式，
原文件**没有引用 `gamedb.age_list` 表**（grep 零命中），而 `age_list` 里存的正是
`fail_chance / plus2_chance / broken_chance` 这些成功率。所以 Age 下拉**只演示属性倍率**。

另外 `range(a, b)` 与 `lvDiv(min, max)` 也在搬迁清单里。

**两条要求**：

1. **原样搬，不得重写。** 这套逻辑是逐行对齐过的（项目里"从源码移植逻辑必须逐分支照抄"的教训）。
   页面已删，基准从 git 取：
   ```bash
   git show HEAD:apps/web-server/src/main/resources/static/simulator/index.html > /tmp/simulator-orig.html
   # 若删除已提交：git show <删除提交>^:<path>
   ```
2. **输入口径改为数据库列名** —— 这就是搬迁中**唯一**要动的地方：驼峰 `d.atkPow1Min` → 列名 `d.atkpow1min`。
   逻辑一行不改。漏改一处的症状是取到 `undefined` → 算出 `NaN` 并**静默**显示成 `NaN`。

#### 6.2.1 显示语义（也只在那个页面里，别丢）

| 语义 | 规则 |
|---|---|
| `range(a, b)` | 两者都 0/null → 不显示；`b` 为 0/null → 只显示 `a`；否则 `a - b` |
| 伤害的合成显示 | `atkpow1min` + `atkpow2min` 合成 **Min Damage**；`atkpow1max` + `atkpow2max` 合成 **Max Damage** |
| `lvDiv` | `addspecatkpowermin/max`、`addspecatkratingmin/max` 显示成 `Lv/x`，不是裸数字 |
| Spec 命中时价格 | 显示 `Math.round(price * 1.2)`（职业特效价格 +20%） |
| 颜色标记 | `valWrap(v, cls)` 包 `<span class>`：`mod-mix` 紫 / `mod-age` 蓝 / `mod-spec` 绿 / `mod-req-up` 红 / `mod-req-down` 绿 |
| 需求修正方向 | 比较修正后与原值：变大 → 红，变小 → 绿，无变化 → 无色 |

⚠ **这些颜色类的取值原先也在被删页面的内联 `<style>` 里**（不在 `static/css/style.css`），同样要重建：

```css
.mod        { font-weight: bold; }
.mod-mix    { color: #c77dff; font-weight: bold; }  /* 紫：被 Mix 改过 */
.mod-age    { color: #6ea8ff; font-weight: bold; }  /* 蓝：被 Age 改过 */
.mod-spec   { color: #7cd47c; font-weight: bold; }  /* 绿：职业特效贡献 */
.mod-req-up { color: #f85149; font-weight: bold; }  /* 红：需求升高 */
.mod-req-down { color: #7cd47c; font-weight: bold; }/* 绿：需求降低 */
```

原选择器是 `.item_box_table .val .mod-*`（绑在模拟器的 DOM 上），按管理端容器重新限定作用域，
**只改选择器、不改颜色**。

⚠ **一处可见差异**：模拟器那套**隐藏值为 0 的行**；五段的决策是"全部列原样"，所以 **0 值行也显示**。

---

### 6.3 ✅ 侧栏菜单：唯一菜单定义 + 按角色动态渲染（2026-09-21 已实施）

原先菜单在每页 HTML 里**各抄一份**（`me.html` 3 项、`admin-maps.html` 2 项），
加页面要记得改所有页，漏一个的症状是"某页菜单里没有它"；管理员项还靠 `me.js` 单独
`style.display = 'block'` 去 reveal —— 而 `webAdmin` 此前恒为 false（§5.0），所以**它从未出现过**。

现在：

- 新增 `static/js/nav.js`：**唯一的 `MENU` 定义** + 渲染器 + **记忆化的 `PTNav.me()`**。
  - 支持两种条目：`{label, href}` 与 `{group, adminOnly?, items:[…]}`（组级 `adminOnly` 连组标题一起藏）。
  - 高亮项**按当前路径自动判定**（`basename(location.pathname)`），不再手写 `admin-nav-item-active`。
  - **两段渲染**：先按"非管理员"出一份（不依赖请求，`/me` 慢或失败也不会整块空白），
    拿到身份后按角色补上管理员项 —— 失败方向是"少显示"而不是"多显示"。
- 页面只留占位：`<nav class="admin-nav" id="ptNav"></nav>`，脚本顺序 `api.js → nav.js → 页面脚本`。
- `me.js` / `admin-maps.js` 改用 `PTNav.me()`（**全页只发一次 `/api/user/me`**），
  `me.js` 里那段 `adminNavMaps` reveal 已删。

**权限口径（写清以免误解）**：显示哪些项由**服务端** `/api/user/me → webAdmin` 决定
（来源见 §5.0.1），前端不自己判角色。
⚠ **菜单不是权限门**：非管理员手敲 `./admin-maps.html` 仍能打开页面骨架，接口会 403
（`admin-maps.js` 已有该分支显示提示）。刻意不在前端再判一次角色 —— 判角色只能有一处。

**加新页面（例如物品管理）**：在 `nav.js` 的 `MENU` 的「管理功能」组里加一行即可，不需要动任何 HTML
—— 这就是本节的全部目的。

**验证（`nav.js` 装进假 DOM 跑真实渲染逻辑，4 个用例）**：

| 场景 | 菜单项 | 高亮 |
|---|---|---|
| 非管理员 @ `me.html` | 首页 · 用户中心 | 用户中心 |
| 管理员 @ `me.html` | 首页 · 用户中心 · **地图管理** | 用户中心 |
| 管理员 @ `admin-maps.html` | 首页 · 用户中心 · **地图管理** | **地图管理** |
| 未登录 @ `me.html` | 首页 · 用户中心 | 用户中心 |

顺带修掉一处同族问题：`me.js` 的改密流程原先从 **`userLabel.textContent` 里 split 出账号名**
（"用界面文案当数据"，文案一改或还没加载出来时就静默算错哈希），改用 `/me` 返回的
`accountName` 字段，并加了"账号信息未加载"的显式拒绝分支。

> ⚠ 仍未验证：**管理员登录后菜单里真的出现「地图管理」的端到端路径** ——
> 需要 GM 账号密码（同 §5.0.1 的遗留项）。上表验的是渲染逻辑与权限分支，不是真实登录会话。

---

## 七、落地顺序与验收

### 7.1 顺序

1. **跑 §1.1 的 `information_schema` 比对**，确定"全部列"是真是假。
2. **`www/` 迁进 jar**：路径相对化 + `PT_API_BASE` 约定（§6.1、§6.1.1），并实测
   `http://localhost:8080/pt/login.html` 能注册/登录。→ 这一步必须在物品管理页之前，
   因为那页要写在 jar 里、用同一套约定。
3. 后端：列/分段映射表（反射）→ `AdminItemQuery` → `AdminItemService`
   （wrapper / 区间相交 / 排序 / §5.2.1 分页 / 可改列校验 / 更新 / mix 查询）
   → `AdminItemController` → `ResultCode.ITEM_NOT_FOUND`。
4. `GET /items/columns`，前端筛选面板与默认列先跑起来。
5. 按 §6.2 从 `/tmp/simulator-orig.html` 抄出 `static/js/item-effects.js`（含列名改写与显示语义）。
6. 详情浮层（五段，只读）→ `POST /items/{id}` 与编辑态 → Mix 下拉接 §5.5。
7. **最后删后端侧**（§4.2）：`SimulatorController` / `SimulatorService` / `ItemSummary` /
   `ItemDetail` / `SkillController` / `SkillService` / `data/skills.json`。
   （删之前 `ItemCategory` 与 mix 查询应已在 §3 步搬完。）
8. 跑 §7.2 全部自检。

### 7.2 自检清单

**数据层**
- [ ] `/items/columns` 的列名集合 = `information_schema` 查出来的列集合（**逐列比对，不是肉眼看**）。
- [ ] 分段列数合计 = **108**（13+6+51+31+7），每列恰好属于一段（无遗漏、无重复）。
- [ ] 列表每一行的键集合 = 同一份列名集合。

**筛选与分页**
- [ ] **`total` 与筛选后的真实行数一致** —— 专验 §5.2.1 的坑：`total` 恒为 0 就是踩了。
- [ ] 四组筛选逐个单独测 + 组合测；`_min`/`_max` 边界（等于、只给一端、倒置）各一次。
- [ ] **区间相交 vs 区间包含**：构造一件区间很宽的基础装（如 `defensemin=1, defensemax=200`），
      按"防御 100~150"筛，**它必须命中**。
- [ ] 非法 `sort` / 未知列名 / 类型不符的值 → **400 且日志有记录**。
- [ ] `page=-1` / `size=99999` → **夹紧**，不是全量返回、不是 500。

**写入**
- [ ] 只改一列时**其余列旧值不变**；响应整行反映新值；列表里那行也是新值。
- [ ] `id` 在请求体 → 400；`questflashingtime` **可以改**（反例：被拒就说明用了后缀通配）。

**前端迁移**
- [ ] `http://localhost:8080/pt/login.html` 能注册、登录、进用户中心；`/pt/` 根能出首页。
- [ ] **把 context-path 换成空再跑一遍**（`PT_WEB_CONTEXT_PATH=`），页面与 `/api` 仍通 ——
      这是 §6.1.1 那条约定的唯一验证方式。
- [ ] 子目录页面（`/pt/maps/index.html`）的 `PT_API_BASE` 是 `'../'` 而不是默认值。
- [ ] `ClanImage` 搬迁无损：**81 个文件、逐字节不变**（`git status` 里应是 rename 而非 delete+add），
      且 `http://localhost:8080/pt/ClanImage/1000001.bmp` 能取到图。
- [ ] 菜单只有一处来源：`grep -rl "admin-nav-item" static/` **只应命中 `css/style.css`（类定义）与
      `js/nav.js`（渲染器）**，不得再出现在任何 `.html` 里（§6.3）。

**搬过来的三项能力**
- [ ] 与 `/tmp/simulator-orig.html` 对齐：三个下拉各取几个基准用例，数值逐个对上（**先抄基准再改代码**）。
- [ ] 驼峰→列名的改写**没有漏**：三个下拉各下拉一遍，确认**没有任何字段显示 `NaN` / `undefined`**。
- [ ] 全仓 `grep -rn "1 + level \* 0.02" --include=*.js --include=*.html` 只命中 `static/js/item-effects.js` 一处。
- [ ] 颜色标记生效：Mix 紫、Age 蓝、Spec 绿、需求升红降绿。

**清理之后（回归）**
- [ ] `mvn -o -pl apps/web-server -am compile` 仍 BUILD SUCCESS（已验过一次）。
- [ ] 删 `SimulatorController` / `SkillController` 之后没有编译错误、没有残留 `@Deprecated` 引用。
- [ ] maps 与 spawn-debug 页面仍可访问（它们**本次不动**，`/pt/maps/index.html`、`/pt/spawn-debug/index.html`）。

**鉴权**
- [ ] 未登录 → 401；已登录非 admin → 403。

---

### 7.3 ✅ 实施与验证记录（2026-09-21）

**已实现**

| 层 | 内容 |
|---|---|
| 后端 | `item/ItemColumnRegistry`（反射列注册表 + 段序 + 启动自检）/ `item/ItemQueryParams`（四组筛选的白名单解析）/ `service/AdminItemService` / `controller/AdminItemController` / `dto/AdminItemColumn` / `ResultCode.ITEM_NOT_FOUND(10406)`；`ItemCategory` 从 `web.simulator` 移到 `web.item`（`SimulatorService` 改了 import） |
| 前端 | `static/admin-items.html` + `js/admin-items.js`（列表 / 四组筛选 / 列选择器 / 详情浮层 5 段 108 行 / 编辑态）；`js/item-effects.js`（三项能力原样搬运）；`js/nav.js` 的 MENU 加「物品管理」；`css/style.css` 加管理页样式与**来源染色类**（取值照搬已删页面的内联样式） |
| 接口 | **6 个** = 设计 5 个 + 补的 `/facets`（理由见 §5.2 那条"带计数的原值候选"注意事项） |

**后端 39 项断言全通过**（一次性脚本，验证后已删）：

- `/columns`：108 列、五段 13/6/51/31/7、**无未归类**、主键恰一个且不可改
- `/items`：total 1036、默认 50 行、**每行 108 键**、外壳键与既有列表一致
- 筛选：`category=Swords`→39、`name_like` 命中、`reqlevel` 单端区间生效
- **区间相交**：`defense 100~150` 命中 60 行，其中 **27 行的区间比查询区间更宽** ⇒ 证明是"相交"而非"包含"（写成包含会整批漏掉这 27 行）
- 非法参数：未知键 / 非整数 / 区间倒置 / 非法 `sort` / 非法 `order` → **全部 400**（code 10300）
- 分页：`page=-1`→1、`size=99999`→200（**夹紧**）
- 详情 108 列；不存在 id → 404 + code 10406；`/mixes` → 35 条
- 修改：未知列名 / 主键 `id` / 类型不符 / 空请求体 → 全部 400；不存在 id → 404；**等价写入**（把原值写回）→ 200 且其余列原值不变

**`item-effects.js` 与原实现逐值等价**（一次性脚本，验证后已删）：把已删页面的 11 个函数从 git 取回装进 sandbox，用 `Proxy` 把**驼峰读取映射到列名键**上，于是两边能喂同一份数据、直接比最终对象 —— 6 种 mix/age 组合逐列一致、19 个标量用例一致、两个常量一致。

**真浏览器（IAB）验证**：

- 登录 `test_fs_20` → 菜单**自动出现**「管理功能 → 地图管理 / 物品管理」，账号标签显示"（管理员）"
- 点「物品管理」→ 列表 50 行、分页"第 1 / 21 页，共 1036 条"；按名称筛 "Shield Sword" → 1 条
- 详情浮层：**108 行**、段序正确（身份 → 需求 → 基础属性 → 职业特效 → 其他）
- **Spec**：设 Knight(6) → `reqstrength 66 → "75 - 82"` 且 `mod-req-up`（正是 `reqAdjust(66,[15,25])` 的结果）、`price 16000 → 19200` 且 `mod-spec`、`addspec*` 染绿、`addspecatkpowermin` 显示 `Lv/5`（lvDiv）
- **Age**：设 +5（rate = 1.1）→ `atkpow1min 8→9`、`addspecdefensemin 10→11`、`defensemin 6→7` 均带 `mod-age`；`critical`/`firemin` 不参与缩放（与源实现一致）
- **Mix**：选 "Life Ability +5"（Organic Res）→ `organicmin/organicmax 0→5` 带 `mod-mix`；切到 Poison 配方 → `organicmin` **回到 0**（从原值重算、无累加）、`poisonmin → 5`
- 编辑态：**107** 个输入框（主键那列显示"不可改"）、Spec/Age/Mix 选择器被**禁用**（预览不会被误当编辑）、非法数字被**客户端拒绝**并标红、改回原值后保存提示"没有改动"

**过程中改掉的三处缺陷（都是这一轮才暴露的）**

1. **段顺序错**：`/columns` 原先按实体声明顺序输出，而 `quest*`（Misc）在实体里靠前 ⇒「其他」段跑到第二位。改为**列顺序的唯一来源是 `ItemColumnRegistry.SECTION_ORDER`**（接口列清单与数据行的键序都走它）。
2. **`.hidden` 形同虚设**：CSS 里只有 `.msg.hidden` 一条规则，**裸用 `.hidden` 的元素根本不会被隐藏** ⇒ 详情浮层 / 列选择器 / 保存取消按钮在页面加载后**全部可见**。旧页面恰好只把 `.hidden` 用在 `.msg` 上，所以这个坑一直没暴露。已补全局 `.hidden { display:none !important }`（带注释说明为何需要 `!important`）。
3. **`REQ_MOD` 的 10/11 两行我最初是编的**（读源文件时那段被截断，我顺手补了两条且取值都错），等价性比对把它抓了出来，已逐字改回并补上源文件里的出处注释。

**仍未验证（如实列出）**

- **真实鼠标点击**：这个 IAB 里 Playwright 的 `click` 一律超时（`fill` 正常）、`screenshot()` 报 `capture failed for guest`。所以页面内的点击是用**页内派发的 DOM 事件**驱动的 ⇒ 处理器与渲染已验，"鼠标 → 处理函数"这一段只验到 DOM 事件层。
- **从界面点「保存」的那一次 POST**：接口层的写路径已验（等价写入），界面只验到**客户端校验**与**"没有改动"**两个分支 —— 最后一次真写没点，避免未经确认地改运行库数据。
- **非管理员访问本页**：接口会 403、菜单不显示该项，但活库 79 个账号里 78 个是 GM，没有非管理员账号可测。
- 浏览器 console 未捕获（该 IAB 不提供）。

**仍未做（按你的决定保留）**：§4.2 那批后端的删除（`SimulatorController` / `SimulatorService` / `ItemSummary` / `ItemDetail` / `SkillController` / `SkillService` / `data/skills.json`）。现在 `AdminItemService.mixes()` 仍**委托** `SimulatorService.mixes()` 以避免第二份实现；删除时把那几个方法搬进 `AdminItemService` 即可（已在该方法 javadoc 写明）。

### 7.4 关于 `range()` 的一处偏离（知情记录）

原实现的显示语义里，成对属性是**两列合成一个值**的（`range(a,b)`，例如 "Min Damage" = `range(atkpow1min, atkpow2min)`）。本页的浮层是**每列一行**（108 行），所以这种合成显示没有用武之地 —— `range()` 仍按 §6.2 搬了过来（且已验等价），但**当前页面不调用它**。理由：每列一行既满足"全部列原样"，也让每列可编辑；两个变体的值分别显示比合成成 "a - b" 信息更多。若你要原版那种分组呈现，`range()` 就在 `PTItemEffects` 里。

---

### 7.5 掉落职业特效：依据什么、以及一个已修的掷点 bug（2026-09-21 查实）

**用户的问题**：game-server 的掉落物会随机产生职业特效，它依据什么？并要在管理页用**勾选组件**体现与编辑。

**✅ 依据（读我们自己的代码，附出处）**

| 环节 | 实现 | 位置 |
|---|---|---|
| 命中率 | **30% 命中**（`nextInt(10) > 3` → 70% 不产生特效） | `ItemRollService.applyJobEffects` |
| **候选池** | **自身职业（`primaryspec`）∪ 候选位（`addspecclass1..12` 的非零位）** —— 见下"第二个 bug" | `ItemRollService.candidateJobBits()` |
| 选中 | 传了 `jobCodeMask` → 用它；**否则池里只有 1 个就直接用**（若模板只有 `primaryspec` 就恒为该职业，对齐原版"固定专精"）；≥2 个 → 池里随机取 | 同上 |
| 命中后 | 写该实例 `jobCodeMask`、价格 +20%、按 `REQ_MOD` 修正五属性需求、按模板 `add_spec_*` 区间掷 17 个 spec_* 字段 | 同上 |
| 生效 | `EquipSummary.specIfJob(job, jobCodeMask, value)`：`mask & 职业位` 命中才加 | `EquipSummary:104` |

- **掉落路径传 `null`**（→ 走候选池随机）：怪物掉落 `CombatService:537`、商店购买 `NpcShopHandler:138`。
- ⚠ **`primaryspec` 不参与掉落**（全仓只有模拟器的详情展示读它）。所以"主触发职业"**不在掉落池里** ——
  实测样本 `Shield Sword`：Spec 下拉候选 = KS/FS/MS/PS（含 `primaryspec=6` Knight），
  而掉落池勾选 = FS/MS/PS（**没有 Knight**）。这一屏就把两个概念的区别摆明了。
- ⚠ **原版 OpenItem 的 `**특화` / `**특화랜덤` 我们没用**（那是原版机制；我们这套是"从模板候选里随机"）。

**🐞 修掉的 bug：多候选时写的是"下标"不是"职业位"**

```java
} else if (randomJobs.size() == 1) {
    chosen = randomJobs.get(0);              // 取"位" ✅
} else {
    chosen = nextInt(randomJobs.size());     // 取"下标" ❌ —— 应 randomJobs.get(nextInt(size))
}
```
`jobCodeMask` 是**位掩码**（`specJobBits` 返回位、`specIfJob` 用 `mask & bit` 判定），写下标就错：
下标 0 ⇒ `mask=0` ⇒ **特效完全不生效**；下标 1/2/4/8 ⇒ 挂到 Fighter/Mechanician/Archer/Pikeman 上。

**影响面（实测）**：全库 1036 件里 **578 件有 ≥2 个候选**（只有 1 个候选的 91 件走正确分支）；
活库 `userdb.item.job_code_mask` 里查到 **3/5/6/7/9 共 31 条**——这些取值**正确路径不可能产出**
（位只能是 1,2,4,8,16…），是这条 bug 的现场指纹。

**修法**：`chosen = randomJobs.get(nextInt(randomJobs.size()));`（一行，已加注释说明这个陷阱）。
**新增回归**：`ItemRollServiceTest#多候选命中时写的是职业位而不是下标` —— 500 次掷点，逐次断言
"命中（价格≠基础价）时的掩码必须落在候选位集合里"。**修前它红了**（报 `实得 0`），修后全绿；
原有 5 条签名测试未受影响（说明此前没有任何测试覆盖这条分支）。
顺带把那条"固定种子签名"的注释改准：它 `price=1000` ⇒ **本次 30% 未命中**，并不覆盖随机候选分支。

⚠ **已有实例的错掩码不会自愈**：那 31 条是**确定错的**（可识别）；其余 1/2/4/8 这种"看着合法的"
无法与正确结果区分，`mask=0` 的也无法与"70% 未命中"区分。要不要清数据、怎么清，是数据侧决定。

**✅ 新增：详情浮层的「掉落特效候选」勾选组件**

- 位置：详情浮层「职业特效」段标题下，12 个勾选框（标签用 `SPEC_JOBS` 的职业名，槽位 12 无名字则只显示序号）。
- 含义：**勾上 = 这件掉落时可能随机到该职业**（就是 `addspecclass1..12` 的 0/1）。
- 只读态：勾选框禁用，展示当前池；编辑态：12 个勾选框**成为这 12 列的编辑入口**，
  那 12 行不再单列输入框（列输入从 107 降到 95）—— 避免"同一个值两个编辑入口"。
- 写入值 1/0：库里这些列实测**只有 0 和 1**（标志位，不是数量），所以 1/0 是忠实写法。
- 浏览器实测：Shield Sword 显示 FS/MS/PS 勾选；编辑态取消 Pikeman → 保存 → "已保存"、值变 0；
  再勾回 → 保存 → "已保存"、值回 1（**净变化为零**，库已复原）。

**顺带修掉我自己的一个不一致**：`coerceChanges()` 原先假定脏值都是**字符串**（输入框给 `input.value`），
而勾选组件给的是**数字** 1/0 ⇒ 数字走 `.trim()` 抛 `s.trim is not a function`，表现为
"点保存没反应、提示里冒一句 TypeError"（且**不发请求**，所以库没被改）。已统一成先 `String(...)` 再判。

**🐞 第二个 bug：`primaryspec`（自身职业）从没进过候选池 —— 用户实测"弓不会掉落弓特"**

**症状**（用户报告）：弓永远掉不到弓的职业特效。

**根因（实测全库 1036 件）**：`primaryspec` 是"**这件装备自己那一行的职业**"，但掉落代码只把
`addspecclass*` 当候选池，**从没读过 `primaryspec`**：

| 事实 | 数 |
|---|---|
| 同时有 `primaryspec` 与候选位的物品 | 277 件 |
| 其中**自身职业不在候选位里** ⇒ **永远拿不到自己职业的特效** | **212 件** |
| 弓 | **33/33 件**：`primaryspec=Archer(3)`、候选位 = {Mechanician, Atalanta} |
| 斧（同类） | `primaryspec=Fighter(1)`、候选位 = {Mechanician, Pikeman} |

⇒ 不只弓，**斧也掉不到斧特**。用户当时只发现了弓。

**修法**：新增 `candidateJobBits(def)` = **自身职业位（`1<<(primaryspec-1)`）∪ 候选位**，
把自身职业**放在最前并去重**（防加权）；`roll()` 改用它。
若模板只有 `primaryspec` 而无候选位 → 池大小 1 → 走"单候选直取"分支 → **恒为该职业**，
与原版"固定专精"（`**특화` → `JobCodeMask` 单职业）的语义一致。

**语义取舍（如实记下）**：原版把 `**특화`（↔ 我们这列 `primaryspec`）描述为"专精·**固定**"、
把 `**특화랜덤`（↔ `addspecclass*`）描述为"候选清单"。严格照那个读法，`primaryspec` 该是
**恒定命中**而不是"候选之一"。**本次按"并入候选池"实现**，理由是：
① 它同时解释并修掉症状；② 数据支持它 —— 212/277 件"自身职业不在候选里"是**系统性**遗漏，
不像设计如此；③ 语义由你定（本项目的既定前提：来源冲突时按证据选，选不出由我们定并记录）。
⇒ 现状效果：**弓命中时 1/3 概率出 Archer**。若你要"恒定出 Archer"，那是另一处两行改动（在
`applyJobEffects` 里让 `primaryspec` 优先于随机），说一声即可。

**新增回归**：`自身职业必须在候选池里_弓才可能掉到弓特`（300 个种子逐次断言掩码落在
{自身职业位, 候选位} 内，且自身职业位**确实出现过**）+ `只有自身职业时命中即为该职业`。
全量单测 **60 条全绿**。

**页面同步**：详情浮层的勾选组件加一行「**自身职业（primaryspec）：Archer (AS) —— 也在掉落池里**」，
并把组件含义写成"勾选 = 掉落命中时可能随机到该职业"。实测 Short Bow：自身职业 Archer + 候选勾选 MS/ATS。

**⚠ 勾选框改为自绘（用户 2026-09-21 反馈"看不清是否勾选"）**：深色底上原生 `checkbox` 本来就细，
只读态又是 `disabled` —— 浏览器给它套灰化/降对比，勾没勾几乎分不出。现改为 `appearance: none` 自绘：
**未勾 = 深底 + 2px 灰蓝描边；勾选 = 实心绿 + 白对勾 + 整项淡绿底**；
并且 `:disabled { opacity: 1 }` —— **只读态不灰化**（状态本身就是要给人看清的）。
核验方式（截图在此环境不可用）：读**计算样式** —— 勾选态 `bg=rgb(47,125,71)`、`::after="✔"`、
`opacity=1`、label 底 `rgba(70,176,100,.14)`；未勾选态 `bg=rgb(15,20,27)`、`border=2px rgb(113,131,156)`。

---

### 7.6 掉落候选池：来源差异与仍未定的事（知情记录）

- **落地方向修正**：原版机制（OpenItem 文本里的 `**특화` / `**특화랜덤`）**我们并没有使用** ——
  我们的候选池完全来自 `gamedb.itemlist` 的 `primaryspec` + `addspecclass1..12` 两列。
  这一点纠正了本轮早前"去查原版 OpenItem 文本"的方向（那些字段只是我们这两列的数据来源）。
- **本地可交叉印证的三份来源**（`**특화` / `**특화랜덤` 计数各不相同，且**同一目录里有生效/被 `//` 注释两种**）：
  JPT2018 `downloads/.../OpenItem`（981 个文件）有值 202 / 出现 441（其中 321 个被注释）；
  3060 扫描 `_items-3060.json` 285 / 539；11 职业服务端（Ubuntu）189 / 677。
  ⚠ 那些文本是 **EUC-KR**：拿 UTF-8 韩文去 grep 会**静默返回 0**（本轮踩过，报过一次假零）。
- **仍未定**：① 存量实例里已写错的掩码（31 条可识别的 3/5/6/7/9 + 无法区分的）要不要清；
  ② `primaryspec` 到底该"恒定"还是"候选之一"（见 §7.5 末尾）。

### 7.7 列取值语义：让数字有名字、编辑用下拉（2026-09-21 用户反馈）

**反馈原话大意**：页面最大的问题是"一片裸数字没有语义" —— `primaryspec` 该显示职业名、编辑该在
**服务端支持的职业里下拉选择**，而不是手填 1~11；`weaponclass` / `classitem` / `modelposition` 同样难懂。

**做法：语义由服务端给，界面按 `kind` 选控件**（不在前端再写一份取值表）

| 层 | 改动 |
|---|---|
| 新增 | `common-model/enums/character/CharacterJob.java` —— 职业 **1..11 的名字与短名的唯一来源**（原先散在 `CharacterRace` javadoc / `ItemRules` 注释 / 客户端 `SPEC_JOBS` 三处） |
| 新增 | `web/item/ItemColumnSemantics.java` —— 每列的 `Kind`（NUMBER/TEXT/**BOOL**/**ENUM**）与候选项，**每项注明依据** |
| 扩展 | `AdminItemColumn` 加 `kind` + `options`；`AdminItemService.columns()` 填充 |
| 前端 | `editRow` 按 kind 出控件：**ENUM → 下栏、BOOL → 勾选框**、其余 → 文本框；`viewRow`（`showedValue`）把值翻成名字，原值仍由「（库中 X）」带出 |

**覆盖范围（实测 `/columns`：NUMBER 91 / ENUM 4 / BOOL 13）**

| 列 | kind | 依据 |
|---|---|---|
| `primaryspec`、`addspecclass1..12` | ENUM / BOOL | `CharacterJob`（AGENTS #13 + 客户端 SPEC_JOBS） |
| `weaponclass` | ENUM（4 项） | `WeaponClass` 枚举（原版 `EWeaponClass`）+ AGENTS #3 的中文（非武器/近战/远程/魔法） |
| `classitem` | ENUM（16 项） | `ItemClass` 位常量（原版 `sinItem.h:16-32`）；标签按位拼，**组合值也能命名** |
| `modelposition` | ENUM（4 项） | `CharacterAppearance` 注释"2左/4右/0无"；⚠ 库里还有 **8**（159 件）而**代码与注释都没定义它**，故标为「未定义」，**不编含义** |
| `cannotdrop` | BOOL | 0/1 |

**实测效果**（Short Bow）：只读显示 `Archer (AS)（库中 3）` / `RANGED 远程 (2)（库中 2）` /
`双手武器（左|右手）（库中 6）` / `左手（库中 2）` / `否（库中 0）`；而 `reqlevel` 仍是 `0`（没被硬套标签）。
编辑态：这四个 ENUM 列都是**下拉**（`primaryspec` 13 项 = 无 + 11 职业 + 槽位12），`cannotdrop`/`addspecclass*`
是勾选框，`reqlevel` 仍是数字输入。下拉的改存闭环实测：`modelposition 2→4` 保存 → 显示"右手（库中 4）"；
改回 `2` 保存 → "左手（库中 2）"（**净变化为零**）。

**已知的名词分歧（记下，不在本次统一）**：第 11 个职业在本仓有三种写法 ——
`Brawler`（本枚举/客户端/AGENTS #13）、`MartialArtist`（`CharacterRace` 的 javadoc）、
`格斗家`（`ItemRules` 的注释）。三处指同一职业，本次按多数来源取 `Brawler`。

**本次**没做**的（都是"会变成编数据"或低收益的）**：
- `sound`：19 种取值，但它的编号含义**没查清**（AGENTS #3 提醒 `itemlist.sound` 是**拾取音**、
  与 `sfx.ts` 的攻击音**不是一套**）⇒ **不编标签**，保持数字。
- `category`：本来就是文本（46 个可读取值），筛选处已有带计数的 datalist。
- `questr/questg/questb` 是 RGB 分量（可做色块预览）、`questid` 是引用 —— 未做。

**同一套语义覆盖到列表与筛选面板（用户追问"列表也应该这样显示吧"）**：

- **列表**：`renderTable` 用同一个 `optionLabel` —— 有语义的列显示名字，原值移到单元格的 `title`（悬停可见）。
  表格里**不塞**"（库中 X）"：50 行 × 多列会糊，而原值一点开详情就有（详情保留那个淡色括号）。
  实测首行：`MELEE 近战 (1) [原值 1]`、`单手武器（右手）[原值 4]`、`右手 [原值 4]`；数字列（idcode/等级/价格/重量）不变。
- **筛选面板**：`weaponclass` / `classitem` / `modelposition` 三个筛选项从数字输入框改成**下拉**
  （首项"（不限）"= 不过滤），候选项与详情/列表**同一份** `options`；`reqlevel_min/max` 等仍是数字框、`category` 仍是带候选的文本框。
  ⚠ 实现细节：筛选面板原先在 `/columns` 到达**之前**就构建，故现在在建完 `byName` 后**重建一次**
  （首次加载、无输入可丢）。
  实测：选"双手武器（左|右手）"查询 → **206 条**，与库分布（`classitem=6` 206 件）一致 ✓

### 7.8 属性行命名 + 区间合并 + **i18n 化**（2026-09-21 用户两次反馈）

**反馈**：① "游戏内展示的属性都是有名字的，itemlist 作为模板在页面上也应该显示名字" ——
`organicmin/max` 该显示成「魔属性 0-0」、`atkpow1min/max + atkpow2min/max` 该显示成
「攻击力(小) 16-19 / 攻击力(大) 26-30」；② **"你不要硬编码啊，我的 web 页面后面怎么搞 i18n"**。

①我第一版把中文**硬编码进了服务端 Java**（`ItemColumnSemantics` 里写"火抗性"等），②这条否决了那个做法。

**改法：服务端只发 key，文案在页面侧翻译**（与 `Result.msg` 的既有约定一致）

| 层 | 内容 |
|---|---|
| 服务端 | `ItemColumnSemantics` 重写为**纯 key**：`rowLabelKey` / `Option.labelKey` / `Bit.labelKey`；`ItemColumnRegistry.SECTION_LABELS`（中文）→ `SECTION_LABEL_KEYS`；DTO 字段随之改名（`sectionLabelKey`/`rowLabelKey`/`options[].labelKey`/`bits[]`） |
| 页面文案表 | 新增 `static/i18n/{zh,en}.json`。**属性名 key 与客户端 `jpstale-client/src/locales/zh.json` 的 `itemtip.*` 同名同义**（将来可合表）；另含 `job.*`、`enum.*`、`admin.section.*`、`admin.item.*`、`admin.common.*` |
| i18n 装配 | 新增 `static/js/i18n.js`，刻意与客户端 `src/i18n/index.ts` **同一套约定**：locale 取 `localStorage['locale']` ?? `navigator.language`；`t(key, params)` 走点分路径 + `{name}` 替换；**缺 key 先回退 zh、再回退为 key 本身并 `console.warn`**（不静默）；`data-i18n`/`data-i18n-placeholder`/`data-i18n-title` 装配静态文案 |
| 成对区间 | 用 `rowLabelKey` + `rowPart` 表达，**不假设配法**：普通配对是同一列的 min/max，而**攻击力是交叉配对**（小攻 = `atkpow1min`~`atkpow2min`、大攻 = `atkpow1max`~`atkpow2max`，与客户端 tooltip 同源）。客户端按 label 分组、按 part 排序、渲染成 `值1 - 值2`；行名 `title` 给出涉及的列名（数据口径可追溯） |
| classitem 组合值 | 服务端给**位表**（`bits[]`），**不在服务端拼串**；客户端遇到不在候选里的组合值时按位表拼（拼法与文案都在客户端） |
| 需求列修正 | 行名沿用 `itemtip.*`；职业需求修正（`reqAdjust`）仍在 `REQ_MOD` 上做，**只改显示、不写库** |

**实测**

- **中/英切换**（`localStorage.locale`）：`物品管理`→`Items`、`查询/重置条件/列显示/刷新`→`Search/Reset/Columns/Refresh`、
  `身份/数值门槛/装备语义`→`Identity/Thresholds/Equip semantics`、
  `MELEE 近战`→`MELEE`、`单手武器（右手）`→`one-hand weapon (right)` ✓
- **Short Bow 详情**（zh）：`生物抗性 = 0 - 0`、`火抗性 = 0 - 0`、`冰抗性/雷抗性/毒抗性 = 0 - 0`、
  `攻击力(小) = 1 - 1 ⟨atkpow1min / atkpow2min⟩`、`攻击力(大) = 3 - 4 ⟨atkpow1max / atkpow2max⟩`、
  `耐久度 = 30 - 40`、`命中 = 15 - 21`、`等级要求/力量要求/精神要求/才能要求/敏捷要求/体力要求`；
  段标题为「基础属性（**51 列，29 行**）」——合并后行数变少一眼可见
- 服务端 `ItemColumnSemantics`/`ItemColumnRegistry`/`AdminItemColumn` 里**已无中文字面量**（grep 验证为空）

**仍未做**：其它静态页（`login/register/me/admin-maps`）与 `www`→jar 之前那一代共享的
`js/api.js` 错误码中文小表**仍是硬编码**。机制已就位（`PTi18n` + `data-i18n`），清扫可另做一批。

**属性名用词：改用「日服汉化文本」（用户 2026-09-21 提供）**

用户明确：截图里的 tooltip 是**我们自己的客户端**（不是他在玩的版本），他给日服汉化文本正是**因为对现有汉化不满意**。
⇒ 所以用词**以日服表为准**（`sinAbilityName` / `sinSpecialName` 逐条对照），不再"保留我们客户端的措辞"：

| key | 我们客户端原措辞 | **改为（日服汉化）** |
|---|---|---|
| `itemtip.atk` | 攻击力 | **攻击** |
| `itemtip.range` | 攻击距离 | **射程** |
| `itemtip.crit` | 必杀 | **必杀率** |
| `itemtip.hit` | 命中 | **命中率** |
| `itemtip.def` | 躲闪 | **躲避** |
| `itemtip.block` | 格挡 | **抵挡率** |
| `itemtip.speed` | 移动速度 | **速度** |
| `itemtip.recMp` / `recStm` | 恢复魔法 / 恢复体力 | **恢复灵力 / 恢复耐力** |
| `itemtip.resBionic` | 生物抗性 | **魔防御** |
| `itemtip.resEarth` | 自然抗性 | **自然属性** |
| `itemtip.resFire/Ice/Lightning/Poison/Water/Wind` | 火/冰/雷/毒/水/风抗性 | **火/冰/雷/毒/水/风防御** |
| `itemtip.incLife/Mana/Stm` | 生命增加 / 魔力增加 / 体力增加 | **生命提高 / 灵力提高 / 耐力提高** |
| `itemtip.reqHealth` | 体力要求 | **体质要求** |

（未变的：`attackSpeed` 攻击速度、`absorb` 防御、`durability` 耐久度、`recHp` 恢复生命、`regen*` 再生、`reqLv/Str/Spirit/Talent/Agility` 五项要求）

**补上原先缺名的四项**（用户报"这些字段缺少展示文本"）：
`weight` → **重量**、`price` → **价格**、`potioncount` → **药水数量**（用户给出用词）、
`potionspace` → **药水存放数量**（日服表 `sinAbilityName[27]`）。
实测 Short Bow：`重量 = 7`、`价格 = 60`、`药水存放数量 = 0`、`药水数量 = 0`；
`魔防御 = 0 - 0`、`火防御 = 0 - 0`、`躲避 = 0 - 0`、`防御 = 0 - 0`、`抵挡率 = 0 - 0`、
`必杀率 = 2`、`射程 = 190`、`体质要求 = 0`。

**✅ 已统一到客户端（用户 2026-09-21：「语言文件同样改到客户端去」）**

`jpstale-client/src/locales/zh.json` 改了 **26 处**（`git diff` 为 27 增 27 删，只有值行，无格式 churn）：

| 段 | 键 | 改动 |
|---|---|---|
| `itemtip.*` | 21 个 | 与上表逐条相同（攻击/射程/必杀率/命中率/躲避/抵挡率/速度/恢复灵力/恢复耐力/魔防御/自然属性/火&冰&雷&毒&水&风防御/生命提高/灵力提高/耐力提高/体质要求） |
| `panel.*` | `defense` / `crit` / `block` | 躲闪→**躲避**、必杀→**必杀率**、格挡→**抵挡率**（角色面板与 tooltip 必须同词） |
| `stats.*` | `hit` / `range` | 命中→**命中率**、攻击距离→**射程** |

⚠ **改法是按键路径改，不是字符串替换** —— 表里有两个**同形异义词**必须保持原样：
`gui.load.cacheHit` = "缓存命中"、`chat.log.playerMiss/monsterMiss` = "…未命中"（那是"命中"的另一个意思）。
另 `panel.group.resist` = "抗性" 未动（它是**分组名**，日服表里没有对应词）。

**校验**（脚本比对）：
- 客户端 `zh.json` 的 `itemtip` 段与管理端 `i18n/zh.json` 的**同名键逐条一致** ✓
- 客户端 `zh`/`en` 的**键集合仍完全对齐**（`i18n/index.ts` 把两者声明为同型，值改动不影响）✓
- 客户端多出的 4 个 `itemtip` 键（`specHeader` / `job` / `jobTier` / `wtype`）管理端没有 —— 有意为之：
  管理端的职业名走自己的 `job.N` 键，不用客户端的位掩码→名字映射表

**✅ 英文表同批换成「英文原表」的用词（用户 2026-09-21 又给了 `sinAbilityName` 的英文版）**

两张 en 表都改：管理端 `i18n/en.json` **30 处**、客户端 `src/locales/en.json` **26 处**。
对照（原用词 → 英文原表）：
`Attack`→**Attack Power**、`Atk. Speed`→**Weapon Speed**、`Defense`→**Defense Rating**、`Hit`→**Attack Rating**、
`Absorb`→**Absorb Rating**、`Block`→**Block Rating**、`Move Speed`→**Speed**、**`Durability`→`Integrity`**、
`Life/Mana/Stamina Recovery`→**HP/MP/STM Recovery**、`Bionic/Nature/Fire/Ice/Lightning/Poison/Water/Wind Res.`→
**Organic/Nature/Flame/Frost/Lightning/Poison/Water/Wind Type**、`Life/Mana/Stamina Regen.`→**HP/MP/STM Regen**、
`Life/Mana/Stamina +`→**Add. HP/MP/STM**、`Req. Str`→**Req. Strength**；
管理端自有键：`Potion Storage`、`Potion Count`、`Attack Power (min)/(max)`。

> **术语收获（值得记）**：英文原表把"抗性"一类叫 **Type**（Organic/Nature/Flame/Frost/Lightning/Poison/Water/Wind），
> 与 AGENTS 里那 8 条抗性轴的原名（BIONIC/EARTH/FIRE/ICE/LIGHTNING/POISON/WATER/WIND）**一致**；
> 而耐久度它叫 **Integrity** —— 正好对上我们的列名 `integritymin/max` ✓。这两条互相印证了这套用词的来源。

⚠ **只改 `itemtip.*`，`panel.*` / `stats.*` 的英文没动**：英文面板是**刻意缩写**（`Def` / `Crit` / `Block` / `Atk Spd`），
换成 "Defense Rating" 这类长词会撑坏面板排版。⇒ 产生一处**中英面板措辞不平行**：
中文侧我先前把 `panel`/`stats` 对齐到了 tooltip 用词（躲避/必杀率/抵挡率/命中率/射程），英文侧保持缩写。
要严格平行的话，二选一：把中文面板退回缩写（躲闪/必杀/格挡），或把英文面板展开 —— **这是排版取舍，需你定**。

**一致性校验（脚本，zh 与 en 各跑一次）**：
- 管理端与客户端的 `itemtip` 段**逐条一致**（zh ✓ / en ✓）
- 客户端 `zh`/`en` 键集合一致（值改动不影响其 TS 同型声明）✓
- 差异仅剩客户端那 4 个自有键（`specHeader`/`job`/`jobTier`/`wtype`），管理端有意不搬

**仍未命名的列**：职业特效那 31 列（`addspec*`）。日服表的 `sinSpecialName` 覆盖得很好
（攻击速度/必杀率/躲闪/防御/抵挡率/魔法熟练度/速度/魔防御/自然属性/…/生命最大值增加/灵力最大值增加/生命再生/…），
可选下一批补上；难点是 `addspecabsorb` 与 `addspecdefense`/`addspecatkpower` 的语义归属要先定
（两份来源的"防御/躲闪/吸收"用词不一致）。

**✅ 去掉页面上我加的括号说明（用户 2026-09-21：「很烦你在页面上加了很多括号说明」）**

这条**本项目早有明文**（工作区 `AGENTS.md` §21：「UI 文案只写'操作 + 必要信息'，不要替玩家解释」，
判据是"**需要 X 级 / 冷却 X 秒 / 代价…** → 留；'这是什么、会把你送到哪、我们还没做完'式的括号补充 → 删"）。
我违反的是同一个毛病，这次逐条清掉：

| 位置 | 删掉的 | 现在的样子 |
|---|---|---|
| 详情行 | **「（库中 12）」原值对照 —— 整块设计删掉**（含 `admin.common.rawHint` 键与 `.item-raw` 样式） | 只显示名字与值 |
| 列表单元格 | `title="原值 4"` | 只显示名字 |
| 段标题 | 「基础属性（51 列，29 行）」 | 段名「基础属性」；列/行数移到 **悬停**（`sectionCountHint`） |
| 勾选块 | 「…（勾选 = 掉落命中时可能随机到该职业）」 | 「掉落特效候选」 |
| 自身职业 | 「自身职业（primaryspec）：弓箭手 —— 也在掉落池里」 | 「自身职业：弓箭手」 |
| 枚举值 | 「副手（左手：盾/法球）」「无（不挂）」「背包盒（原版保留位）」「戒指（左\|右）」「未定义（代码只定义了…）」 | 「副手」「无」「背包盒」「戒指」「未定义」 |
| 筛选组 | 「攻防数值（区间相交）」 | 「攻防数值」 |
| 分页 | 「…共 33 条（每页 50）」 | 「…共 33 条」 |
| 其它 | 「（403）」「（其余功能不受影响）」「（当前值，不在候选表里）」 | 「当前账号不是管理员」「Mix 配方加载失败，Mix 下拉不可用」「当前值」 |
| 脚本 | 顺带删掉随之无用的 `stripTags()`、`raws` 变量、`emptyValue`/`poolTitleEditing` 两个键 | — |

**有意保留的两处括号**：`攻击力(小)` / `攻击力(大)`（**你给的用词**，且它是"两行同名需要一个区分"的必要信息）、
`（管理员）`（账号标签的角色标记，其它静态页既有约定，改它会让本页与它们不一致）。

实测（Short Bow）：段标题只有「身份/需求/基础属性/职业特效/其他」；`classitem = 双手武器`、
`modelposition = 左手`、`火防御 = 0 - 0`、`重量 = 7`、`自身职业：弓箭手`、`掉落特效候选`、
`第 1 / 1 页，共 33 条`；**全页可见文本里只剩 `攻击力(小)/(大)` 两处括号**。

**✅ 职业特效那 31 列也命名 + 合区间了，并且 `Spec.` 前缀**还回去了**（用户 2026-09-21 两连反馈）**

起因：用户指出 31 个 `addspec*` 列还是裸数字（`addspecrunspeedmin 0 / addspecrunspeedmax 0 …`），
并追问"**谁让你省掉 Spec. 的？**" —— 我先前为了"去重"让特效行**复用了基础属性的 key**，
于是英文侧把原表里的 `Spec. ATK SPD` 显示成了 `Weapon Speed`。那是我自作主张，已改回。

**做法**：特效行用**独立 key**（`itemtip.spec*`），zh / en 各自照自己那张表逐条抄：
- **en 取自英文表 `sinSpecialName`**（**含 `Spec.` 前缀**）：
  `Spec. ATK SPD` / `Spec. CRIT` / `Spec. DEF RTG` / `Spec. ABS RTG` / `Spec. BLK RTG` / `Spec. SPD` /
  `Spec. ATK POW` / `Spec. ATK RTG` / `Spec. RNG` / `HP Recovery` / `MP Recovery` / `STM Recovery` /
  `Spec. Organic…Wind`（8 元素）/ `Max HP Boost` / `Max MP Boost` / `Magic APT`
- **zh 取自日服表 `sinSpecialName`**（该表**本身不带前缀**）：攻击速度/必杀率/躲闪/防御/抵挡率/速度/
  攻击力/命中/射程/生命再生/灵力再生/耐力再生 + 8 元素 + 生命最大值增加/灵力最大值增加/魔法熟练度
- 成对区间同样按 `rowLabelKey`+`rowPart` 合并；`addspecatkpowermin/max` 与 `addspecatkratingmin/max`
  是 **Lv 除数** ⇒ 整行用 `lvDiv(min,max)` 显示（`Lv/1 - 3`；此前我只按单列调 `lvDiv(v,v)`，白丢了它一半设计）
- **客户端一并改**：`ItemInfo.tsx` 的 24 处特效行 label 从基础 key 换成 spec key（按**字段名**逐行定位，
  绝不误伤基础属性那些同名 label）；客户端 `zh/en` 各加 23 个 `itemtip.spec*` 键。
  改前确认过：仓库里没有脚本/测试断言这些文案（`grep` 过 `scripts/`、`src/`，命中全在 `ItemInfo.tsx` 内）

⚠ **两处"来源本身就不一致"，我照抄未统一**（要统一需你定）：
1. **日服表内部**：`sinAbilityName` 写 `命中率`/`躲避`，而 `sinSpecialName` 写 `命中`/`躲闪` ⇒
   页面上基础属性行显示 `命中率`/`躲避`、特效行显示 `命中`/`躲闪`（各自忠实于自己那张表）。
2. **中英前缀不对称**：英文表给特效行加了 `Spec.`，日服表没加 ⇒ zh 的特效行看起来与基础属性同名同形。

实测（Great Bow）：zh `速度 = 0 - 0` / `防御 = 0 - 0` / `躲闪 = 0 - 0` / `攻击速度 = 2` / `必杀率 = 5` /
`攻击力 = Lv/5` / `命中 = 0` / `生命再生` / `灵力再生 = 0 - 0` / `耐力再生` / `抵挡率 = 0` / `射程 = 20`；
en 对应 `Spec. SPD` / `Spec. ABS RTG` / `Spec. DEF RTG` / `Spec. ATK SPD` / `Spec. CRIT` / `Spec. ATK POW = Lv/5` /
`Spec. ATK RTG` / `HP|MP|STM Recovery` / `Spec. BLK RTG` / `Spec. RNG = 20` ✓。段内行数 31 → **13**。

**仍未做的**：`必杀率 = 5` 没有 `%`（原版 tooltip 显示 `5%`）—— 那是我没加的单位，要加说一声。

**✅ 必杀加上了 `%`（用户 2026-09-21：「要加啊」）**

- **单位做成服务端字段**：`ItemColumnSemantics.Semantics` 加 `unit` → DTO `unit` → 页面只读行末尾接一次
  （区间行不写成 `6% - 10%`）。单位是**数据**不是文案，故**不参与 i18n**。
- 只给**必杀两列**（`critical` / `addspeccritical`）加 `%`，依据：客户端 `ItemInfo.tsx` 就是
  `` `${it.critical}%` `` / `` `${it.specCritical}%` ``，**无缩放**；实测库里的 `critical=6`
  与用户截图里那件 Great Bow 的「必杀 6%」**逐字对得上**。
- 实测：zh `必杀率 = 6%` / `必杀率 = 5%`（特效那行）；en `Critical = 6%` / `Spec. CRIT = 5%` ✓

**✅ 顺带核实了 `block` / `absorb` 的量纲 —— 我先前"客户端有 bug"的结论是错的（已撤回）**

我一度据"客户端显示时除以 10"推断**客户端有显示 bug**。**错**。查序列化后的事实链：

| 环节 | 事实 | 出处 |
|---|---|---|
| 模板/实例 | **朴素值**（盾 `block` 6–18、`absorb` 0.8–3.3） | 库 + `ItemRollService`（原样存模板值） |
| **上线** | **×10**（`setBlockRating((int) Math.round(it.getBlockRating() * 10))`） | `ItemNetworkHandler:379-381`（同一套 ×10 还用于 `manaRegen`/`lifeRegen`/`staminaRegen`/`specAbsorb`/`specSpeed`/`specBlockRating`/`specMagicMastery`；`specPer*Regen` 是 **×100**） |
| 客户端 | `bridge.ts` **不再缩放**，`ItemInfo` 的 `/10` 把 ×10 **还原** | `bridge.ts:116`、`ItemInfo.tsx:117` |

⇒ **协议用整数传浮点**，客户端除以 10 是**正确**的；**DB 值就是显示值**（`block=16` 上线为 160 → 客户端 160/10 = **16** → 游戏显示 `16%`）。
我错在算术：把线上的值当成了 DB 值（16 而不是 160），于是算出"显示成 2%"。**没有客户端 bug。**

⇒ 由此只得到一条**正确**的推论并已实施：**`block` 与 `addspecblock` 该带 `%`**（游戏显示 `` `${Math.round(blockRating/10)}%` ``，值是 DB 值）。
实测 Tower Shield：zh `抵挡率 = 10 - 15%`、`抵挡率 = 4%`（特效行）；en `Block Rating = 10 - 15%`、`Spec. BLK RTG = 4%`；
`absorb` 仍无单位（游戏显示 `1.7`，非百分比）✓。

**✅ 那 12 个候选位在两种模式下都不再单列（用户 2026-09-21：「在物品详情继续显示这些字段很蠢」）**

我先前只在**编辑态**跳过 `addspecclass1..12` 的行，只读态却把它们列成 12 行「是/否」——
而勾选组件（只读态是**禁用的勾选框**）展示的正是同样的 12 个位 ⇒ **同一件事显示两遍**。
现在**两种模式都跳过**，勾选组件是它们的唯一展示/编辑入口。

实测（Great Bow）：
- 只读态：12 个禁用勾选框（`2=勾选`、`5=勾选`…）+ 13 行（自身职业/速度/防御/躲闪/攻击速度/必杀率/攻击力/命中/生命再生/灵力再生/耐力再生/抵挡率/射程）
- 编辑态：同一批 12 个勾选框（转为可点）+ 同样 13 行 —— **两种模式看到的集合一致**
- 段标题悬停：`职业特效 [31 列，13 行]` ⇒ 31 = 13 行 + 12 勾选框 ✓ 数对得上

---

## 八、明确不做

- **不做审计**（决策 #7）；**不考虑用 `null` 清空某列**（决策 #13）。
- 不改任何表结构。
- 不做掉落配置（本次范围是 `gamedb.itemlist` 这一张表）。
- 不做玩家物品（`userdb.item`）的查询或发放。
- **不吸收** `spawn-debug` 连游戏服（10008）的实时 WebSocket（决策 #16）——
  新系统只做刷怪**配置**编辑；实时观察不再是管理端能力。
- 本次不动 `maps` 与 `spawn-debug` 两个页面（路径也不改），留到收纳它们时一起做（§6.1 末）。
- 不删那 4 个数据文件（§4.3）；`www/ClanImage/` 是公会图标资产，本次**原样搬进 jar**，
  路径跟随新约定（§4.4，已裁决）。

---

## 九、一页速览

```
GET  /api/admin/items/columns      列清单（列名/类型/所属段/可改/可筛），反射生成，不查库
GET  /api/admin/items              列表：四组筛选 + 白名单排序 + 分页；返回全部列
GET  /api/admin/items/{id}         单行全部列
POST /api/admin/items/{id}         改物品定义：部分更新，全部列可改（减 id 与时间戳类列）
GET  /api/admin/items/{id}/mixes   Mix 下拉用的配方（服务端内部求 wartale 分类）

详情 = 弹窗浮层，五段承载 108 列：身份13 / 需求6 / 基础51 / 职业特效31 / 其他7
      只读；点「编辑」才可改，编辑界面同构分段
筛选 = 身份 / 数值门槛 / 装备语义 三类精确或单列区间；攻防数值 = 列对上的「区间相交」
能力 = Spec / Mix / Age + 职业需求修正 从已删页面原样搬到 static/js/item-effects.js（列名口径）
落点 = 全部搬进 jar（resources/static/），页内路径相对，api.js 读 window.PT_API_BASE（默认 './'）
命名 = 数据库列名；不动 schema；不做审计

清理 = pviewer 与模拟器页面已删；后端侧（SimulatorController/Service/ItemSummary/ItemDetail/
       SkillController/SkillService/skills.json）等物品管理做完再删（ItemCategory 与 mix 查询先搬）
       4 个孤儿数据文件保留待定
ClanImage = 公会图标资产（文件名 = CL.MIconCnt 编号，服务端只发 CIMG 编号不发 URL）
            已裁决路径跟随新约定 → 搬进 static/ClanImage/，不再阻碍 nginx 退休（§4.4）
收纳 = maps（地图查看）+ spawn-debug（只做配置编辑，不连游戏服 WS）+ 物品管理；pviewer 丢弃

已完成 = ① columns 比对（108=108，零差异，1036 行）② www 迁进 jar + 路径相对化（两套 context-path 都实测通过）
         ③ 权限门（GM 判据 + SaInterceptor，含管理员正向路径实测）④ 动态菜单 ⑤ **物品管理后端 6 个接口 + 页面**（§7.3）
本地跑 = cd apps/web-server && mvn spring-boot:run（默认 Maven profile dev；不需要 nginx，不需要 --spring.profiles.active）
         打开 http://localhost:8080/pt/admin-items.html（需 GM 账号；菜单里「管理功能 → 物品管理」）
下一步 = ① 页面点「保存」的真写一次（界面路径，见 §7.3 未验证清单）② §4.2 那批后端的删除
```
