# 管理端：怪物管理（`gamedb.monsterlist`）设计与证据

> 2026-09-22 · 承接 [物品管理设计](./2026-09-21-pt-web-admin-items-design.md) 的同一套框架
> 页面：`/pt/admin-monsters.html` · 接口：`/pt/api/admin/monsters/**`

---

## 〇 决策表（用户 2026-09-22 定）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 掉落关联键 | **按 `monsterid` 显示 + 修代码**（见 §二，这是一处真实 bug） |
| 2 | 可编辑性 | **可编辑**，与物品管理同构（主键/时间戳列除外） |
| 3 | 固定筛选 | 名字（模糊）· 等级区间 · 属性+本性 · 所在地图 —— 四组全做 |
| 4 | 刷怪部分深度 | **只给地图名**（不带数量与 per-map 配置） |

列表默认列（用户点名"名字、等级、攻防属性、经验值"+ 能认出这只怪的两类语义列）：
`monsterid` `name` `level` `hp` `exp` `atkpowmin` `atkpowmax` `defense` `monstertype` `propertymon`

---

## 一 数据来源：四张表，全部在 `gamedb`

| 表 | 行数（2026-09-22 实测） | 用途 |
|---|---|---|
| `monsterlist` | 456 | 怪物定义，**53 列**（等级/生命/经验/攻防/抗性/AI/特殊攻击…） |
| `mapmonster` | 48（= 有刷怪配置的图数） | 每图一行：`stage` + `monster1..12`/`count1..12` + `bossmonster1..3`/`submonster1..3` |
| `dropitem` | 2268（304 个 dropid） | 掉落行：`items`（空格分隔的物品码 / `Gold` / `Air`）+ `chance`（**权重**） |
| `maplist` | 63 | 把 `stage` 翻成地图名（`name` / `shortname`） |

**刷新侧的链路**（`MapManager.loadMapsFromDatabase` 只读这几列）：
`mapmonster.stage` = **字符串形式**的 `maplist.id`；`monster1..12` 存的是怪物**名字**，
刷怪时按名字查模板（`MonsterSpawnService.monsterTemplatesByName`）。实测 280 条非空引用
**精确匹配 280 条**（大小写不敏感也是 280，无差异）→ 界面按**精确匹配**判图，
与游戏同一口径；用大小写不敏感会给出游戏里**不会发生**的刷怪，等于谎报。

实测：456 只怪里**193 只**真的会出现在 `monster1..12` 里。

---

## 二 ⚠ 掉落键：代码取错了字段（本次修掉的一处真实 bug）

**症状**：`LootService` 的类注释写着 `dropid == monsterlist.id`，`MonsterSpawnService.createMonster`
也照它写 `monster.setTemplateId(template.getId())`，`CombatService` 再用 `monster.getTemplateId()`
去掷掉落。**但数据是按 `monsterid` 写的。**

**实测证据**（2026-09-22，只读查询）：

| 判据 | 按 `monsterlist.id` | 按 `monsterlist.monsterid` |
|---|---|---|
| `dropitem` 行命中 | 347 / 2268 | **2203 / 2268** |
| 唯一 dropid 命中 | 52 / 304 | **300 / 304** |
| **有掉落表的怪** | 52 只 | **300 只** |

- 两个 id 的值域：`id` 1..858、`monsterid` 1..1469，而 `dropitem.dropid` 最大 **1469**；
  **没有任何一只怪的两个 id 相等**，所以 347 与 2203 是两个互不重叠的集合 —— 不是巧合，是系统性错位。
- **语义旁证**（决定性）：Mushroom Ghost = `id=10` / `monsterid=1010`。
  `dropid=1010` 名下是 `wa101 wh101 wp101 wd101 ws101 …`（**一级**武器，配 5 级怪完全合理）；
  而 `dropid=10` 名下是 `da112 wa110 wc110 …`（**110 段高阶装备**）。
  代码取 `id=10` ⇒ 5 级怪按高阶装备的表掷点。
- **修复风险极低**：`templateId` 唯一的下游用途就是掷掉落；它会随 `S2C_MonsterAppear` 下发给客户端，
  但客户端**忽略**它（`jpstale-client/src/ui/WorldView.ts` 的回调签名里是 `_templateId`）。

**改动**（一行 + 注释）：`MonsterSpawnService.createMonster`
`template.getId()` → `template.getMonsterId()`；`LootService` 与 `Monster.templateId` 的注释同步纠正。
⚠ 该修复的**游戏内效果要等 game-server 下次启动**才生效（本次只重启了 web-server）。

**页面的口径**：详情的掉落段用 `dropid = monsterlist.monsterid`，并且
**总权重与"哪几行会被跳过"一律调用游戏同一份实现**（`LootService.buildTables`，纯静态，不触发它的扫表 bean），
于是页面上的百分比与游戏里的实际概率是同一个分母 —— 否则那张表就是最容易骗人的东西。

`chance` 是**权重**不是百分比：某行几率 = 该行权重 / 该 dropid 的权重总和。
实测 Mushroom Ghost：总权重 10,000,000 → Air 3,100,000 = **31%**、Gold 4,800,000 = **48%**、
两种小药水 1,400,000 = **14%**、一级武器串 500,000 = **5%**、二级武器串 200,000 = **2%**。

---

## 三 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/monsters/columns` | 列清单（段序 / 可改 / 可筛 / 取值语义） |
| GET | `/api/admin/monsters/facets` | 筛选候选：本性·属性（**原值+计数**）+ 有刷怪配置的 48 张图 |
| GET | `/api/admin/monsters` | 列表：`name_like` `level_min/max` `monstertype` `propertymon` `map` + `sort/order/page/size` |
| GET | `/api/admin/monsters/{id}` | 单行**全部 53 列** |
| POST | `/api/admin/monsters/{id}` | **部分更新**（留空 = 不改；未知列/主键/类型不符 → 400 并留日志） |
| GET | `/api/admin/monsters/{id}/spawn` | `{maps[], bossMaps[]}` |
| GET | `/api/admin/monsters/{id}/drops` | `{dropId, totalChance, rows[]}`（每行含 `id`、`percent`、`skipped`、`skipReason`） |
| POST | `/api/admin/monsters/{id}/drops` | **保存掉落表**（整表替换；见 §九）+ `added/updated/removed/warnings` |
| GET | `/api/admin/monsters/bosses` | 在 `mapmonster` 的 `boss/submonster` 列里被声明的怪名（Boss 徽章用，见 §十） |
| GET | `/api/admin/items/search` | **物品选择器**用：按 `codeimg1` 或名字搜索（掉落编辑，NPC 商店编辑将复用） |

口径与物品一致：对外一律**数据库列名**；行含全部列；筛选是固定几组（**未知键一律 400**，
不静默忽略）；分页用 `selectCount` + 夹紧 `LIMIT/OFFSET`（本仓没有 `PaginationInnerInterceptor`，
`selectPage` 会**不报错地**返回全表）。鉴权 `@SaCheckRole("admin")` + 方法体内显式再校验一次。

---

## 四 共用层（本次把物品侧的实现抽出来两边共用）

物品与怪物的页面/接口形状相同，**不能抄第二份**（AGENTS #15）。抽出：

| 共用件 | 职责 |
|---|---|
| `web/admin/ColumnRegistry` | 反射生成列名⇄属性映射 + 段序 + 可改/可筛 + **自检**（漏归类的列落 `Unassigned` 并报 error） |
| `web/admin/ColumnSemantics` | 语义**类型**：`Kind`/`Option`/`TextOption`/`Bit`/`Semantics` |
| `web/admin/AdminQueryParams` | `sort/order/page/size`、空串=没填、分页夹紧、未知键 400 |
| `web/admin/AdminEntityService` | 列清单 / 计数分页列表 / 详情 / 部分更新（白名单+类型转换+LIKE 转义+区间相交） |
| `web/dto/AdminColumn` | 列元信息 DTO（原 `AdminItemColumn`，改名共用） |
| `static/js/admin-common.js` | 页面的取值语义显示、区间行配对、脏值转换、筛选面板、列选择器 |

各表只留自己的东西：`ItemColumnRegistry`/`ItemColumnSemantics`/`ItemQueryParams` 与
`MonsterColumnRegistry`/`MonsterColumnSemantics`/`MonsterQueryParams`。

**新增字段**：`AdminColumn.textOptions`（`{value,labelKey}[]`）—— 怪物有两列是**文本枚举**
（`monstertype` = `Good/Normal/Neutral/Evil`、`propertymon` = `Demon/Normal/Machine/Mutant/Undead`），
数字 `options` 装不下。没有它，编辑只能手打字符串，一个拼写错误就是一条**看不出来**的脏数据。

---

## 五 命名依据（**不猜**）

- **与物品同名的量复用客户端串表的 key**，避免同一概念两套词：
  `defense`→`itemtip.def`（躲避）、`absorb`→`itemtip.absorb`（防御）、`attackrating`→`itemtip.hit`、
  `block`→`itemtip.block`、`attackspeed`→`itemtip.attackSpeed`、`attackrange`→`itemtip.range`、
  `movespeed`→`itemtip.speed`、五种抗性 → `itemtip.resBionic/resFire/resIce/resLightning/resPoison`。
  ⚠ 物品页那两个**容易搞反**的对应关系（`def`=躲避 / `absorb`=防御）在这里沿用同一套。
- **起名但含义存疑的不编**：`healthpoint`、`magic`、`stage`、`spawntime`、`questitemdrop`
  一律**不起名**，界面回退显示**列名**（已实测：详情里就显示 `healthpoint = 40`、`magic = 0`、
  `stage = 0:2:`）。想要名字，请给措辞 —— 与 `classitem`/`modelposition`/`weaponclass` 那三行同一个待办。
- 单位只给有依据的：`potionpercent`（名字里就写着 percent）。
- 成对区间：`atkpowmin/max`→攻击力、`spawnmin/max`→刷新数量（`MonsterSpawnService` 用它决定**每点刷几只**）、
  `specialhitpowermin/max`→特殊攻击力。

---

## 六 验证记录（2026-09-22）

**物品侧回归**（重构了它的实现）：`/items/columns`、`/facets`、列表、详情四份 JSON 与重构前基线比对 ——
`facets` / 列表 / 详情**逐字节一致**；`columns` 的差异**只有新增的 `textOptions` 字段**
（108 列的取值逐项相等，剔除该字段后与基线完全一致）。物品页在浏览器里复验：1036 条 / 21 页、
表头 10 列正确、24 个筛选控件、分类 datalist 46 项、详情 5 段 + 特效池 + Mix 下拉 36 项、（无报错）。

**怪物接口**：列 53 列 7 段；筛选五例（`name_like=mushroom`→1 条；`level 100..135`→187 条；
`Evil+Undead`→90 条；**小写 `good` 精确命中 1 条**（库里 `Good` 与 `good` 并存，不归并）；
`map=0`→6 条；`map=62`→2 条，经查 `mapmonster` 确有 stage=62 配置，**返回正确**）；
拒绝路径 4 例（未知键/区间倒置/非法排序列/非整数）全 400 + 服务端 WARN 日志；
不存在的 id → 404 `error.web.monsterNotFound`。

**写库路径**：POST 写回**相同值** → 200、库值前后一致、日志一行
`[MonsterAdmin] 修改怪物定义 id=10 列=[level, has_run]`；拒绝 5 例（主键/未知列/非数字/null/空体）全 400。
**界面上**把 `level` 填成 `05`（等价 5，脏值判为改、写库值不变）→ 点「保存」→ 界面"已保存"、
服务端日志出现同一行、数据库仍为 `level=5` ✓（完整 UI 链路打通，且**没有改动任何数据**）。

**浏览器**：怪物页列表 456 条 / 10 页、语义列显示"善良/邪恶""普通/变异"、
详情 7 段（身份/战斗/抗性/AI/特殊攻击/刷怪与掉落/任务）、编辑态 52 个可改列
（文本枚举→下拉且当前值=`Evil`/`Mutant`，`has_run`→勾选框，数字→数字框）、
刷怪地图 = `Acacia Groove` + `Garden of Freedom`、掉落段含总权重与各行几率。

**独立交叉验证**：`monsterlist.stage` 这一列的值是 `0:2:`（冒号分隔的地图 id），
与从 `mapmonster` 反查出的 `[0, 2]` **完全一致** —— 两条独立路径给出同一个答案。

---

## 七 数据里的怪现象（如实展示，不粉饰）

- `monstertype` 有大小写重复：`Good` 1 条 + `good` 1 条。筛选是**精确匹配**、下拉带计数，
  两条都列出来（归并就与数据库不一致了）。
- `spawntime` 456 行**全是 `'0'`**（死列）；`stage`/`dropispublic`/`dropquantity`/`spawnmin/max`
  大量为 NULL；`questmap` 有 `-1`。
- `dropitem` 里 **dropid ≤ 0 有 53 行**、`chance ≤ 0 有 69 行` —— 游戏会跳过这两类；
  落到某只怪的表里时，页面照样列出这一行并标"游戏会跳过 + 原因"（不静默）。
- `mapmonster` 的 `bossmonster1..3` / `submonster1..3`（各 30 个非空值，如 `Platin Mav@0`、`Eed@1`）
  **当前刷怪代码完全不读**（`MapManager` 只建 `monster1..12` 这 12 个 wave）。
  界面把它们单独放在 `bossMaps` 一段并标明"刷怪代码只读 monster1..12" —— 既不全盘忽略（那是藏数据），
  也不假装它们真的会刷（那是谎报）。

---

## 八 未做 / 待定

- **掉落的编辑**不在本期：详情里的掉落段是**只读**（数据在 `dropitem`，编辑它属于另一个功能）。
- 未起名的列（`healthpoint`、`magic`、`stage`、`spawntime`、`questitemdrop`，以及物品侧遗留的
  `classitem`/`modelposition`/`weaponclass` 行名）需要**你给措辞**才会出现在界面上，现在显示列名。
- `nav.js` 的菜单文案与 `api.js` 的错误码表**仍是硬编码中文**（历史遗留，物品页也一样）；
  要 i18n 需一并改，`nav.js` 还得考虑没有 i18n.js 的页面（`me.html`/`admin-maps.html`）。
- 模拟器后端的删除仍在物品设计文档 §4.2 的待办里（`SimulatorService.mixes` 仍被物品详情复用）。


---

## 九 掉落编辑（2026-09-22 第二轮；用户选"完整版"）

**语义 = 整表替换**：`POST /{id}/drops` 的 `body.rows` 就是保存后的**全部行**
（带 `id` = 改这一行、不带 `id` = 新增、现有行未出现在 rows 里 = 删除），**一个事务**里 diff 出
INSERT/UPDATE/DELETE。为什么不拆成"增/删/改"三个接口：编辑一张 ≤15 行的表时界面上就是三种操作混着做的，
拆开会让"保存"变成非原子的多步，中途失败就留下半张表。

**两条让实现变简单的结构事实**（都实测过）：

- `dropitem` **只有主键约束**（无 `(dropid, items)` 唯一键）⇒ 不需要 `userdb.item` 那套停车/哨兵槽逻辑（AGENTS #26）。
- `dropid` == `monsterlist.monsterid` 且 `monsterid` 唯一，**304 个 dropid 没有一个被两只怪共用**
  ⇒ 保存只会动到**这一只怪**的行，不可能波及别的怪。

  ⚠ 正因为这条，我们**不需要**像某些管理系统那样"检测到共享 DropID 就自动新建一个专属 DropID"
  —— 我们的数据模型本身就是"一怪一表"，那套保护在这里是纯复杂度（见 §十"不要抄的"）。

**校验**（不过一律 400，且**整表不落地**）：空 `items`、负权重、引用了别只怪的行 `id`、
同一 `id` 重复出现、金币区间倒置、负数金币、缺 `chance`。
**不拒绝但告警**：物品码在 `itemlist` 里找不到（既有数据的常态）→ 进 `warnings` 回传，界面红字列出。

金币语义：只对 `Gold` 行有意义，其余行按语料既有约定写 **0**（不是 NULL —— 实测 2268 行里非 Gold 行都是 0）。

**编辑期预检 vs 服务端权威判定**：界面上只预判"权重 ≤ 0 / 物品行没有物品"（这两条当场能看出来），
而"物品码认不出"由**服务端保存时**判定（界面不查 itemlist，避免第二份判定）。

**百分比一律按实际总权重算**，不假定基数：实测每只怪的总权重在 **30,000 ~ 20,000,000** 之间，
其中 **202/304 只恰好 10,000,000**（连一个 10,000 都没有）。所以"1000 = 10%"这类提示对我们**是错的**，
界面用的是"权重 / 本怪权重总和"，输入时实时重算。

写库前后各留一行 INFO：`[MonsterAdmin] 保存掉落 怪物id=… （dropid=…）新增 a 改 u 删 r；告警 n 条：[…]`。

## 十 展示改进（借鉴 RAGEZONE 的 `game-server-manager` 截图）

来源：某位巴西作者发在 RAGEZONE 的 PT 私服 Web 后台，**界面是葡萄牙语**，13 张截图覆盖
怪物掉落 / 掉落模拟 / 战斗模拟器 / 物品 / NPC 商店 / 经验表编辑器六个模块。我们借鉴 4 项、明确不抄 2 项。

**借鉴（已落地）**

| 借的东西 | 落成什么 | 依据 / 差异 |
|---|---|---|
| 详情头部放关键数值 | 详情标题下一行 `等级 5 · 生命 40 · 经验 106 · 攻击力 2 - 3 · 本性 邪恶` | 他们的头部是 `Level 4 · HP 15 · EXP 215`；这里直接用**行名 key** 拼，不新增文案键 |
| 卡片视图 + 按等级分段 + BOSS 徽章 | 怪物列表可切"表格 / 卡片"；卡片按 `等级 1-10 / 11-20 / …` 升序分段，每卡 名字 + Lvl/HP/EXP + 语义列 | ⚠ **徽章依据**：`mapmonster` 的 `bossmonster1..3`/`submonster1..3` 里有 **52 个**不同的怪名，且**全部**能对上 `monsterlist.name`；但这几列**当前刷怪代码不读**（`MapManager` 只建 `monster1..12` 的 wave）⇒ 徽章 `title` 如实写明"仅表里声明" |
| 掉落分布图 | 详情掉落段：**甜甜圈（手写 SVG，无第三方库）+ 图例**，每段 = 一行的 `percent` | 他们是饼图 + `2.000 / 10,000 → 20.0%` 列表；我们两样都有（分布图 + 逐行权重/几率） |
| 物品页网格视图 | 物品列表可切"表格 / 网格"；卡片 = 名字 + 码徽章 + 分类 + 价格 | 他们的物品页是按分类的卡片墙（带 Ativo 徽章、金币价签） |

**不抄（各有明确理由）**

1. **"检测到共享 DropID"警告 + 保存时自动新建专属 DropID**：我们数据里 304 个 dropid **没有一个**被两只怪共用
   （关联键 `monsterid` 本身唯一）⇒ 这个问题不存在，抄过来只是无用复杂度。反过来它给了我们一条**安全性质**（见 §九）。
2. **`Base 10000（1000 = 10%）` 的权重记法**：我们每只怪的总权重从 30,000 到 20,000,000 不等，
   照搬那行提示会给出**错的百分比**。
3. （他们的）**战斗模拟器**：用户此前明确否掉模拟器（物品侧已删），且其 DPS/冷却模型是按他们改过的服务端写的。
4. （他们的）**一键应用平衡建议**：自动改数值会把"建议"变成"静默改数据"；要做也只做只读建议。

他们的另外两个模块：**NPC 商店编辑** 纳入下一步（见 §十一，与掉落编辑共用同一个物品选择器）；
**经验表编辑器**（编辑 + 导出 `LevelTable.h` + 增长率曲线 + 难度墙分析）暂时不碰 —— 我们的 XP 表是**代码里的表**
而不是 DB，要编辑得先决定数据源怎么放，不是照抄 UI 就能落地的。

## 十一 item / monster / npc / map 四者联动（2026-09-22 用户指出）

**键全都在库里，没有数据障碍**，缺的是"管理端模块"与"可点的引用"。实测：

| 关系 | 数据键 | 数据齐 | 管理端现状 |
|---|---|---|---|
| 怪物 → 物品 | `dropitem.items`（空格分隔码）= `itemlist.codeimg1` + `chance` 权重 | 2268 行 | ✅ 已联动（物品名可点） |
| 怪物 → 地图 | `mapmonster.stage` = `maplist.id`，槽位存怪名（280/280 精确匹配） | ✅ | ✅ 已联动（地图名可点） |
| 地图 → 怪物 | 同上反向 | ✅ | ⬜ 地图模块待做 |
| 地图 → NPC | `mapnpc.stage` = `maplist.id`、`idnpc` = `npclist.id`（219/219 全对上） | ✅ | ⬜ |
| NPC → 物品 | `npclist.weaponshop / defenseshop / miscshop`（空格分隔码；13/13/60 个 NPC 有清单） | ✅ **运行时已在用**（`NpcShopService`） | ⬜ |
| 地图 → 刷新点 | `mapspawnpoint.stage` | 4059 行 | ⬜ |
| 物品 → 掉落来源（怪/图） | 反查 `dropitem` → `monsterlist` → `mapmonster` | ✅ | ⬜（下一步） |
| 物品 → 哪个 NPC 卖 | 反查 `npclist` 三个 shop 列 | ✅ | ⬜ |

**三条纪律**（已写进实现）：

1. **跨表引用一律可点**，目标页支持**深链**：`admin-items.html?idcode=<idcode>`、
   `admin-monsters.html?id=<monsterlist.id>`、`admin-maps.html?id=<maplist.id>`。
   （物品页深链命中唯一一条会**直接开详情**，一条都没有则明说；怪物页深链直接开详情；
   地图页暂时是"把 id 填进搜索框"，做到"汇聚点"版时换成开详情。）
2. **双向**：能点进去就要有反查段（物品详情的"掉落来源 / 哪些 NPC 在卖"排在下一次）。
3. **一条实现**：`npclist` 的商店清单与 `dropitem` 的掉落行是**同一种东西**（空格分隔的物品码、大小写不敏感），
   管理端不重复解析 —— 物品选择器只做一份（`/api/admin/items/search`）。

**推进顺序（用户 2026-09-22 定）**：怪物收尾 → **NPC 管理** → **地图管理**；
地图模块的深度选**"汇聚点"只读版**（maplist 全部列 + 刷怪配置 + NPC + 刷新点），可编辑留到以后。

## 十二 本轮验证记录（2026-09-22 第二轮）

**后端**

- **掉落保存往返**（Mushroom Ghost id=10 / dropid=1010 / 5 行）：
  ① 快照 → ② 改一行权重（+1）→ 服务端回 `新增0 改5 删0`、库中该行 4800001 ✓ →
  ③ 改回 → **库中与快照逐字段一致** ✓ → ④ 新增一行 `Air`（回 `新增1`，该行 `percent=0.01%` = 1000/10001000 ✓）→
  ⑤ 删回（回 `删1`）→ ⑥ **最终与快照一致（含每行的 `id`）**，总权重回到 10,000,000 ✓。
  **全程没有留下数据改动。**
- **拒绝路径 6 例**（负权重 / 空 items / 别只怪的 id / 同 id 重复 / 金币区间倒置 / 缺 chance）→ 全 400，
  且拒绝后库**未动**；服务端逐条 WARN 日志（含"第 N 条"定位）。
- `/api/admin/items/search`：按名字 `stone axe` → `WA101 Stone Axe 60`；按码 `pl101` → 命中（大小写不敏感）✓
- `/api/admin/monsters/bosses` → 52 个名字 ✓
- **`coerce` 改接共用 `JsonValues` 后的回归**：物品 `String`/`Integer`/`Double` 写回同值 → 200 且值未变；
  未知列 / 主键 / 类型不符 → 400；怪物写回（含 `Boolean` + `String`）→ 200 且值未变 ✓

**浏览器**（真实登录会话 `test_fs_20`）

- 怪物页：卡片视图 200 张/3 页、**13 段按等级升序**、44 张带 BOSS 徽章、卡片可点进详情 ✓；
  深链 `?id=10` 直接开详情 ✓；头部数值一行 ✓；掉落段甜甜圈 **5 段 + 图例**（31/48/14/5/2%）✓；
  **可点引用**：`Acacia Groove → admin-maps.html?id=0`、`Mini Life Potion → admin-items.html?idcode=67240192` ✓
- 掉落编辑器：5 行；类型下拉（物品/金币/空）、权重框、物品 chip（带名字与 ✕）、金币两个框、增删行 ✓；
  选择器输入 `axe` → 10 条结果（`WA101 Stone Axe 60 G`）→ 点选加入 chip 并清空输入 ✓；
  把第一行权重改成 1,000,000 → 合计变 7,900,000、各行实时重算为 12.66/60.76/17.72/6.33/2.53% ✓（可复算）；
  「取消」丢弃改动 ✓；**无改动点「保存掉落」→ `已保存：新增 0 改 5 删 0`，库中仍与快照一致** ✓
- 物品页：网格视图 50 张/页（`Stone Axe` + `WA101` 徽章 + `Axes` + `价格 60`）✓；
  深链 `?idcode=67240192` → 筛到 1 条并**直接打开** `Mini Life Potion #427` ✓

**期间修掉的两个前端 bug（都是浏览器里才暴露的）**

1. **在游离节点上刷新百分比**：`dropBlock()` 是先把整块建好、再 `appendChild` 进浮层的，
   而 `buildDropEditor()` 末尾就调了刷新 → 那一刻 `document.getElementById('dropPct_0')` 还是 `null`
   ⇒ **元素都在、文本全空**。修法：挪到挂载之后刷新（`renderModal` 里 `appendChild(dropBlock())` 之后），
   并在原处留注释写明这条约束。
2. **网格视图用了已删除的本地 `fmt`**：物品页在上一轮重构时把 `fmt` 换成了 `PTAdmin.fmt`，
   新写的 `renderGrid` 又用了 `fmt(` → `ReferenceError: fmt is not defined`，**网格全空**。
   ⇒ 教训与 AGENTS #15 同源：**共用件抽取之后，旧名字的残留引用必须全局搜一遍**。


---

## 十三 详情独立成页 + 路由约定（2026-09-22 第三轮，用户定）

**决策**：物品/怪物详情从"列表页上的浮层"改为**独立页面**（用户提议、我认同）。理由不是审美，是实测出来的：

| 页面 | 浮层卡片高度 | 视口 | 结论 |
|---|---|---|---|
| 物品详情（108 列 × 5 段） | **2102px** | 720px | 约 2.9 屏 |
| 怪物详情（53 列 × 7 段 + 刷怪地图 + 掉落） | **2557px** | 720px | 约 3.6 屏 |

而且 `modalBody` 没有内部滚动（`clientHeight == scrollHeight`）⇒ 是整页在滚：**顶部的「编辑/保存」会滚出屏幕**；
掉落编辑器（含物品选择器下拉）还要跟页面滚动抢位置。另外四条结构性理由：
两种保存语义（列的部分更新 / 掉落的整表替换）挤在一个容器里不得不互斥；后退/刷新/分享在浮层里都要手工补
（深链就是补出来的）；浮层需要焦点陷阱而我们没做；跨表引用在页面之间是普通导航，在浮层里是"关一个开一个"。

**路由约定（用户指定）**

```
/admin/{list}            → 列表页    /admin/items   /admin/monsters   /admin/maps
/admin/{entity}/{主键id}  → 详情页    /admin/item/123   /admin/monster/10   /admin/map/0
```

- **URL 里传数据库主键**，不传 `idcode` 这类语义字段 —— 一个语义码可能对应多行（本库实测有同名同码的重复行），
  只有主键能唯一定位一行。掉落行里的物品引用因此走 `itemlist.id`（`AdminMonsterService.drops()` 的
  entries 现在同时给 `itemId`（主键）与 `idcode`（展示/追溯用））。
- **服务端必须有一个页面控制器**（`AdminPageController`）：路径式路由下页内的 `./js/…`、`./admin/items`
  这类相对路径会按**当前目录**解析（`/pt/admin/item/js/…`）⇒ 全 404。控制器把静态页读出来、把
  `<!--PT_BASE-->` 换成 `<base href="{contextPath}/">` 再返回，相对路径就统一回到 context 根
  —— **页面里已有的相对链接一个都不用改**。
  ⚠ `context-path` 可配（`PT_WEB_CONTEXT_PATH`，默认 `/pt`，docker 那套是空）⇒ base **不能写死**，必须由请求现算。
  页面本身不鉴权（与静态页一致）：有数据的是 `/api/admin/**`，那些才校验角色。
- `id` 非数字、实体名不在白名单 ⇒ **404**（不让 `/admin/item/foo` 落到页面上）。

**结构**

- 列表页变**纯列表**（筛选 / 表格 / 卡片·网格 / 列显示 / 分页），行与卡片点击 = **跳转**（浮层已彻底删除 ——
  按 AGENTS #15，替换而不是并存，同一份渲染逻辑不能有两个宿主）。
- 详情页共用骨架 `js/admin-detail.js`：路径取主键 → `/columns` + `/{id}` → 标题/关键数值/工具条（编辑·保存·取消·返回列表）
  → 分段渲染（`renderSections`，带 `decorate` 与 `skipColumn`/`beforeSection` 两个钩子）。
  渲染零件（区间行配对、取值语义、编辑控件、脏值转换）仍全在 `admin-common.js`，三页共用。
- `admin-item.html`：Spec / Age / Mix 三个**预览**选择器 + 「掉落特效候选」勾选块（那 12 列的编辑入口，列本身不再重复成行）。
- `admin-monster.html`：刷怪地图（地图名 → `/admin/map/{id}`）+ 掉落（物品名 → `/admin/item/{id}`，含只读视图、分布图、整表替换的编辑器）。
- `admin-map.html`：**最小版**（`maplist` 五个字段）。"汇聚点"四段（刷怪配置 / NPC / 刷新点）留到地图模块那一步 ——
  先给怪物详情里的地图引用一个**真实落点**，而不是让它 404。
- `nav.js`：菜单改干净路由；**详情页要把它对应的列表项点亮**（`/admin/item/123` → `/admin/items`）。

**本轮验证（2026-09-22 第三轮）**

- 路由：`/admin/{items,monsters,item/1,monster/10,map/3}` 全 200 且**注入的 base 都是 `<base href="/pt/">`**；
  `/admin/item/abc`、`/admin/nope` → 404。
- 物品详情 `/admin/item/1`：`document.baseURI=/pt/`（所以 `./css/style.css` 生效、背景色已应用）、标题/关键数值、
  5 段 68 行、候选勾选块、Mix 下拉 36 项、零 JS 报错；导航点亮「物品管理」。
- 怪物详情 `/admin/monster/10`：9 段、分布图 5 段、「编辑掉落」在；**跨页引用解析到主键路由** ——
  `./admin/item/427 → /pt/admin/item/427`（Mini Life Potion）、`./admin/map/0 → /pt/admin/map/0`；导航点亮「怪物管理」。
- 从掉落行点过去落地正确：`/admin/item/427` = `Mini Life Potion #427`（就是那一行里的物品）。
- 地图页 `/admin/map/0` = `Acacia Groove #0`（id/name/shortname/typemap/levelreq）。
- 列表：怪物列表 50 行、**没有 modal 元素**、点第一行 → `/admin/monster/1`（Hopy）；物品列表 1036 条/21 页、
  分类候选 46、网格 50 卡、点卡片 → `/admin/item/1`（Stone Axe）。
- 掉落编辑器在**新页面上**照常：5 行、类型/权重/物品 chip/选择器、实时百分比 31/48/14/5/2%、合计 10,000,000；
  无改动保存 → `已保存：新增 0 改 5 删 0`，**库中与快照仍逐字段一致**（id 6677-6681 未变）。

**修掉的一个 bug**：`nav.js` 的 `isActive` 最初用 `new URL(href, window.location.href)` 解析菜单的相对路径 ⇒
在 `/admin/item/123` 下算成 `/pt/admin/item/admin/items`，**详情页点不亮父项**。
应该用 **`document.baseURI`**（就是服务端注入的 `<base>`）—— 页面里 `<a href>` 的解析规则与它一致，两边才不打架。

**待做**：列表状态（筛选/分页）还没进 URL ⇒ 从详情页返回列表会丢筛选（浏览器 bfcache 不可依赖）；
详情页的「上一件/下一件」也还没做（用来补回浮层"连着改几十条"的便利）。两者都记在下一步。
