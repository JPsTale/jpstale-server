# 管理端：NPC 与地图（只读"汇聚点"版）设计与证据

> 2026-09-22 · 承接 [怪物册](./2026-09-21-pt-web-admin-monsters-design.md) 的同一套框架与约定
> 页面：`/admin/npcs` · `/admin/npc/{npclist.id}` · `/admin/maps` · `/admin/map/{maplist.id}`

---

## 〇 决策（用户 2026-09-22 定）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 推进顺序 | 怪物收尾 → **NPC** → **地图** |
| 2 | 地图深度 | **只读"汇聚点"版**（maplist 全列 + 刷怪配置 + NPC + 怪物刷新点），可编辑留到以后 |

路由与主键约定沿用怪物册 §十三：**URL 传数据库主键**（`npclist.id` / `maplist.id`），
页面路由由 `AdminPageController` 映射到静态页并注入 `<base>`。

---

## 一 数据来源（实测）

| 表 | 行数 | 说明 |
|---|---|---|
| `npclist` | 204（**17 列**） | 身份 4（id/name/gamefile/teleportid）· 对话 4（message1..4）· 事件与任务 6（eventtype/eventparam/skillquests/questid/questtypeid/questtypesubid）· **商店 3**（weaponshop/defenseshop/miscshop） |
| `mapnpc` | 219 | `idnpc` → `npclist.id`（**219/219 全部对得上**）· `stage` → `maplist.id` · x/y/z/angle · `enabled`（0×29 / 1×190）· `onlygm`（4 个非零） |
| `maplist` | 63 | 7 列；`typemap` 是**文本**（`Underworld 20 / Deserts 8 / Grasslands 6 / …`，13 种）；`pvp` 仅 1 张非零；`stagefile` 63 张全非空 |
| `mapspawnpoint` | 4059 | 每图 1~149 个 `{stage, x, z, description}` |
| `mapmonster` | 48 | 每图一行（**63 - 48 = 15 张图没有刷怪配置**，城镇图即如此 → 页面上明说） |

**商店清单的判据**（与运行时同源）：三个商店列任一非空即商家 ——
`NpcShopService.isMerchant(NpcList)`（Java 侧，运行时）与 `AdminNpcService.MERCHANT_SQL`（SQL 侧，
必须下推才能分页）是**同一条判据的两个表达**，两边注释互相指名。实测 `merchant=true 73` +
`false 131` = **204 的完整划分** ✓；有清单的：武器店 13 / 防具店 13 / 杂货店 60。

`eventtype` 有 28 个码（0×151 起）——**原版源码里没有对照表**（2026-09-22 在 `NewSourcePT-2023` 里找过），
所以这一批（`eventtype`/`eventparam`/`teleportid`/`skillquests`/`quest*`）**不起语义名、不给候选**：
按"列名含义"起行名（事件类型 / 事件参数 / 传送 ID …），值原样显示与编辑。

---

## 二 接口

**NPC（`/api/admin/npcs`）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/columns` | 列清单（17 列 4 段；段名 key 前缀 `admin.npc.section.`） |
| GET | `/facets` | 有 NPC 的地图（38 张，带计数）+ 事件类型（29 种，原值+计数） |
| GET | `` | 列表：`name_like` / `map`（经 mapnpc 反查）/ `merchant`（true/false）/ `eventtype` + 分页排序 |
| GET | `/{id}` | 单行全部 17 列 |
| POST | `/{id}` | 部分更新（与物品/怪物同一套校验） |
| GET | `/{id}/shops` | `{isMerchant, weapon[], defense[], misc[]}`，每条 = `{code, itemId(主键), idcode, name}` |
| POST | `/{id}/shops` | **保存商店清单**（见 §三） |
| GET | `/{id}/places` | `{count, mapCount, placements[]}`（mapnpc，只读） |

**地图（`/api/admin/maps`）**：`/columns`（7 列）· `/{id}`（**改成列名键的行**，与其它模块同构）·
`/{id}/spawn`（普通怪槽位 + Boss/副怪槽位 + maxmonsters/interval）· `/{id}/npcs` · `/{id}/points`。
⚠ 列表接口仍是旧的 `AdminMapSummary` 形状（那版页面在前端过滤）；**没有写接口**（本期只读）。

---

## 三 商店编辑的口径

- 键 = **数据库列名**（`weaponshop`/`defenseshop`/`miscshop`），值 = 物品码数组；**没出现的键不动**，
  给出的键整体替换，且**顺序即游戏内商店顺序**（原样写回客户端给的顺序）。
- 校验（不过一律 400，且**一行都不写**）：空物品码、同一列里码重复、值不是数组、请求体里没有可保存的列。
  物品码在 `itemlist` 里找不到**不拒绝**（既有数据的常态）→ 进 `warnings` 回传，界面红字列出。
- **语义未变的列不写库**（逐列比较，**大小写不敏感** —— 游戏匹配码就不分大小写）。
  三列都没变时返回 `unchanged: true` 且**一行都不写**，不是错误。
  ⚠ **这是踩过坑才改的**：第一版无条件写回，于是"一次没改任何东西的保存"也动了数据 ——
  把 `NULL` 变成空串、把多余空白规范化（实测语料里两种都有：`weaponshop` 1 行首尾空白 / 3 行双空格，
  `miscshop` 13 行有空白问题；空值惯例是 **NULL** 而不是空串）。修好后：
  **无改动保存 → `touched: []` + 库中逐字节未变**；只改大小写 → 同样不算改动；
  真改动 → 才写，且还原后**原文逐字相同**。
- 物品码 → 物品的解析走共用的 `ItemCodeLookup`（与怪物掉落**同一实现**）。

---

## 四 地图"汇聚点"（只读）

四段：**maplist 全列**（走 /columns 的段序）· **刷怪配置** · **NPC** · **怪物刷新点**。

- 名字一律**可点**：刷怪槽位里的怪物名 → `/admin/monster/{monsterlist.id}`；
  NPC → `/admin/npc/{npclist.id}`（都是主键）。
- 刷怪槽位里名字在 `monsterlist` 里找不到的，**标红并说明**"刷怪代码按名字查模板，会跳过它"
  （`MapManager` 确实按名字查 —— 这不是装饰，是真实会发生的跳过）。
- `bossmonster*` / `submonster*` 单独一段并标明**当前刷怪代码不读这几列**（与怪物详情同口径）。
- 页面**没有编辑按钮**（`PTDetail` 已容忍只读页缺按钮）；锚点/上限/间隔只读展示。

---

## 五 共用层新增

| 件 | 作用 |
|---|---|
| `ItemCodeLookup`（web-server/@Service） | **物品码 → 物品**的唯一解析（大小写不敏感；同名多个 code 后者覆盖前者，与 `LootService.reload()` 一致）。怪物掉落与 NPC 商店共用，怪物侧原先那份私有实现已删除 |
| `admin-common.renderItemChips(container, entries, onRemove, emptyText, T)` | 物品 chip 列表；条目带 `href` 时渲染成**可点引用**，`onRemove` 省略时**不显示 ✕**（只读态） |
| `admin-common.itemPicker(inputId, placeholder, onPick, T)` | 物品选择器（防抖 220ms → `/api/admin/items/search` → 点结果回调）。掉落行与商店列共用 |

---

## 六 验证记录（2026-09-22 第四轮）

**NPC 接口**：17 列 4 段（身份4/对话4/事件与任务6/商店3）；facets 地图 38 张、事件类型 29 种；
`name_like=blacksmith → 13`；`merchant=true → 73` / `false → 131`（合计 204 ✓）；`map=3 → 40`；
`eventtype=14 → 9`（与库里 `14×9` 计数一致 ✓）；未知键/`merchant=maybe` → 400；
不存在的 id → `10408`。

**商店保存往返**（NPC 64 blacksmith_drol，25/16/0 件）：
① 无改动保存 → `touched=[] unchanged=true` 且**库中未变** ✓；
② 只把码改小写 → 同样 `unchanged` ✓；
③ 真改末位（`WD113 → WS101`）→ 写入 ✓、回传的清单同步 ✓；
④ 还原 → `touched=['weaponshop']`，**原文逐字相同** ✓。
拒绝路径 5 例（空码/重复码/非数组/空体/未知列）全 400。

**地图接口**：`/columns → 7 列`；`/maps/3` 返回 7 个字段
（`Ricarten Town / ric / Cities / 0 / 0 / Data\Maps\town1.dat`）；`/maps/3/spawn → configured=false`；
`/maps/3/npcs → 42`；`/maps/3/points → 2`。

**浏览器**：NPC 列表 204 条/5 页、4 组筛选（地图 39 / 是否商人 3 / 事件类型 30 个候选）、菜单出现「NPC 管理」并点亮 ✓；
NPC 详情 6 段 + 三组商店 chip（武器 25 / 防具 16 / 杂货 0，物品名可点 → `./admin/item/91` 主键 ✓）
+ 摆放（`Ricarten Town = (-994, 17348) angle=5193`）；商店编辑：搜 `dagger` → 点选 `ws201`
→ 武器店 **14 → 15** ✓、「取消」丢弃 ✓；点击已在清单里的 `WA101` **不新增**（去重大小写不敏感 ✓）。
地图页 `Acacia Groove #0`：7 段、`Grasslands · 等级要求 1 · 刷怪槽位 6 · NPC 4 · 怪物刷新点 102`、
怪物名可点 → `./admin/monster/10`、Boss 段带"代码不读"说明、**无编辑按钮**、零 JS 报错；
`Ricarten Town #3`：`NPC 42 · 怪物刷新点 2` + 「这张图没有刷怪配置」✓。

---

## 七 待做 / 已知差异

1. ⚠ **一处等你确认的还原**：测试 NPC 64 时我把 `miscshop` 从 `NULL` 写成了 `''`
   （语义完全相同：`notBlank('')` 与 `null` 同判、拆码都得到空列表；但语料惯例是 NULL）。
   按项目规矩（对运行库的写操作先给语句等确认）**我没有自行执行**，语句如下：
   ```sql
   UPDATE gamedb.npclist SET miscshop = NULL WHERE id = 64;   -- 影响 1 行 1 列；回滚 SET miscshop = ''
   ```
   原始转储在本机/服务器都没找到（`/data` 全盘 grep 超时），所以这一处只能按语义还原。
2. **摆放（mapnpc）编辑未做**：坐标/角度/`enabled`/`onlygm` 的增删改属下一步。
3. **商店清单的顺序调整（↑/↓）未做**：要调整目前只能删掉重加（顺序对游戏内陈列有意义）。
4. **地图的 NPC 摆放（`mapnpc`）编辑与怪物刷新点编辑未做**（见 §九）；地图的列与刷怪配置这一轮已可编辑。
5. `eventtype` 等编号列没有取值表 → 不起名、不给候选（原版源码里也没有对照表）。
6. 列表筛选状态仍未进 URL（怪物册 §十三 记过）。


---

## 八 NPC 名的本地化（2026-09-22 用户指出）

**事实**：`npclist.name` 是**内部键**（`blacksmith_drol`、`miscellaneous_store_lynn` 这种），
客户端把显示名放在 `jpstale-client/src/locales/{zh,en}.json` 的 **`npc.*` 段**，
**以内名称为键**：`"blacksmith_drol": { "name": "铁匠 Drol" }`。

**实测覆盖**：库里 204 行 / **191 个不同名称**（13 个名字被多行复用），
客户端 zh 与 en **各 191 条 ⇒ 覆盖 100%**（唯一多出的键是 `gmOnly`，那是 UI 文案不是名字）。

**做法**（三条：不另起一套名字 / 显示 + 追溯 / 按本地化名能搜到）

1. **镜像到管理端 i18n**：把这份表汇入 `static/i18n/{zh,en}.json` 的 **`npcName.*`**（键 = 内名称），
   `_source` 里写明出处与"改名时两边一起改"。**管理端不自己造名字**。
2. **显示**：NPC 列表的 `name` 列、NPC 详情标题与 `name` 行、地图详情页的 NPC 段 —— 一律显示**本地化名**，
   **内名称放进 `title`** 便于追溯（`decorate` 现在支持返回 `title`）。切 en 时同一批键显示英文名。
3. **按本地化名检索**：`PTi18n.keys('npcName')` 反查"本地化名 → 内名称"，
   命中就把这批内名称用**新筛选 `names=`**（逗号分隔、精确匹配、上限 200，超了 400）交给服务端；
   没命中则回退原来的 `name_like`（内名称子串）。
   ⚠ 这样**服务端不需要知道任何显示名** —— 语言表是页面的，不是数据。

**验证**：列表页首行 `蘑菇洞穴看守`（title = `mushroom_cave_keeper`）；搜「铁匠」→ 提示
`按本地化名匹配到 7 个`，7 个铁匠全中（`铁匠弗兰 / 铁匠巴特兹 / 铁匠 Umph / 铁匠 Drol / …`，
title 是各自内名称）；切 en → `Mushroom Cave Keeper` / `Miscellaneous Store, Lynn` / `Blacksmith Frain`；
详情标题 `铁匠 Drol  #64`（name 行 title = `blacksmith_drol`）；
地图页 `指挥官德里克 → /admin/npc/94`、`杂货店, Namy → /admin/npc/112`。
接口：`?names=blacksmith_drol,blacksmith_gus` → 2 条；不存在的名单 → 0 条；201 个 → 400。

⚠ **顺带发现**：客户端 zh 表里有个别条目仍是英文（如 `cursed_temple_guardian` → `Cursed Temple Guardian`）
—— 那是**客户端自己的翻译表**，管理端只是镜像它；要改文案应当改客户端。


---

## 九 地图管理：列表统一 + 可编辑（2026-09-22 第五轮，用户"继续做地图管理"）

上一轮地图只有"只读汇聚点"；这一轮把它做成**能用**的模块。

### 9.1 列表换成同一套框架

旧列表是"前端过滤 5 列"的老页面。现在与物品/怪物/NPC 同构：**五组固定筛选 + 全部 7 列 + 列选择器 + 分页 + 点行进详情**。
`AdminMapSummary` 与 `AdminMapService.listAll/findById` **整体删除**（只被自己用，无其它引用）。

| 筛选 | 口径 |
|---|---|
| 名字 | 一个输入框同时搜 `name` 与 `shortname`（ILike + 通配符转义） |
| 地形类型 | `typemap` 原文精确（13 种，候选取自 `/facets`，带计数） |
| 等级要求 | `levelreq` 区间 |
| PvP | `pvp` 0/1 |
| **是否有刷怪配置** | **不是本表的列**：判据 = `mapmonster` 里有没有这张图的行（48/63 张有），与详情页 `spawn.configured` **同源** |

实测：`has_spawn=true → 48` / `false → 15`（正好是 48/63 的划分 ✓）；`typemap=Cities → 5`；`pvp=1 → 1`；`name_like=ric → 1`；未知键/非法布尔/区间倒置 → 400。

### 9.2 maplist 列可编辑

`POST /api/admin/maps/{id}`（复用基类的部分更新：存在 / 可改 / 类型相容；
`levelreq`/`pvp`/`typemap`/`name`/`shortname`/`stagefile` 都在可改列内）。

### 9.3 刷怪配置可编辑（`POST /{id}/spawn`，整行替换）

编辑 12 个普通怪槽位（**怪物名 + 数量**）+ `maxmonsters`/`interval` + 3 个 Boss + 3 个副怪槽位；
没配置过的 15 张图（多为城镇）保存即**新建一行**（upsert）。

三条口径都是实测定的：

1. **空槽位写 NULL，不是空串** —— 语料 48 行全部如此（`monster6` null=16、`monster12` null=48、空串 **0** 行）。
   ⚠ 因此**不能用 `updateById`**（MyBatis-Plus 忽略 null 字段，清空槽位根本写不进去），
   这里用 `UpdateWrapper.set(列, null)` 显式写 NULL —— 这是这一轮最容易写错的一处。
2. **不碰 `hoursbossmonster*` / `countsub*` / `maxenemyflag`**：`hoursbossmonster1` 存的是
   "出现小时列表"（实测 `0 1 2 … 23`），当前刷怪代码**不读**这几列，我们也没有编辑它的界面
   —— **不动没搞懂的列**。
3. 怪物名在 `monsterlist` 里找不到、或数量为 0：**不拒绝但告警**（`MapManager` 按名字查模板，确实会跳过它们），
   告警随响应回传、界面红字列出。

校验（不过一律 400 且**一行都不写**）：`slot` 越界或重复、数量为负、`waves`/`bossWaves` 不是数组或含非对象元素、`kind` 不是 `boss|sub`。

**怪物选择器**复用这一轮抽出的 `PTAdmin.searchPicker`（与物品选择器**同一套交互**，只是换了个 `searchFn`
打 `/api/admin/monsters?name_like=`）；结果显示 `名字 Lv等级 #主键`。

⚠ 刷怪配置的改动**要等 game-server 重启才生效**（`MapManager` 只在启动时读一次 `mapmonster`）——
与掉落修复同一条规矩，界面上也写着这句。

### 9.4 验证

**接口往返**（Acacia Groove，stage 0，6 个槽位 + boss1/sub1）：

- 无改动保存 → `warnings 0`、`created false`、值不变；
- 槽位 1 数量 12 → **99**（**直读库确认落库**）→ 还原 → 12；
- 往空槽位填一只怪（slot 7 = Zombie×3）→ 7 个槽位 → 清空 → 回到 NULL；
- 5 条拒绝路径（slot 越界 / slot 重复 / 数量为负 / `kind` 非法 / `waves` 非数组）→ 全 400；
- 列表：63 条、`has_spawn` 48/15、`typemap=Cities` 5、`pvp=1` 1、facets 13 种。

⚠ **期间一次假警报**：我那个"直读库比对"的探针（把 SQL 塞进 ssh + podman 的嵌套引号里）报"③ 改数量后库中未变"，
用单条 SQL 复核后确认**写库是正常的**（12→99→12）—— 探针的问题，不是代码的问题。记在这里免得下次再被自己骗。

**浏览器**：列表 63 条 / 2 页、6 个筛选控件（地形类型 14 选项、PvP 与有刷怪配置各 3）、按 `has_spawn=true` 筛出 **48** 条；
详情 7 段 + 两个编辑入口（列编辑 / 编辑刷怪配置）；刷怪编辑器 12 个槽位（6 个已填 + 6 个空槽选择器）、
上限 250 / 间隔 1、Boss+副怪 6 个槽位（共 10 个选择器）；选择器搜 `zombie` → `Zombie Lv19 #3` / `Zombie Hulk Lv115 #348`；
「取消」丢弃改动；零 JS 报错。

### 9.5 还没做

- **NPC 摆放（`mapnpc`）编辑**：加/删/移 NPC、`enabled`/`onlygm` 开关（下一步）。
- **怪物刷新点（`mapspawnpoint`）编辑**。
- Boss/副怪的 `hoursbossmonster*`（出现小时）与 `countsub*` 的编辑 —— 没有消费方，先不碰。
- 列表筛选状态进 URL（怪物册 §十三 记过）。


---

## 十 地图可视化编辑（2026-09-22 第六轮，用户："NPC、刷怪点需要可视化编辑"）

**用户定的三条口径**：① **可以不复用地图组件源码，但要复现其地图可视化逻辑**；
② **详情页做可视化**、点"编辑"进**独立页面**；③ **攒着点保存**。

### 10.1 复现的可视化算法（照 `jpstale-client/src/ui/WorldMap.ts` 的算法重写，见 `js/map-canvas.js`）

| 项 | 做法（与客户端逐条对应） |
|---|---|
| 视图模型 | 中心 `(cx, cz)` + `unit`（**每个屏幕像素多少世界单位**），夹在 `[8, 128]`（同它的 `ZOOM_MIN/ZOOM_MAX`） |
| 坐标变换 | `toScreen(x,z) = [w/2 + (x-cx)/unit, h/2 + (z-cz)/unit]`；`toWorld` 是其逆 |
| 取景 | `fitTo(aabb, pad=0.06)`：`unit = max(bw,bh) / (min(cw,ch) * (1-2*pad))` |
| 滚轮缩放 | **以指针为锚**：先记指针下的世界点、缩放后再 `cx += before - after` 平移回来（它 `zoom()` 的补偿法） |
| 平面图 | `/res/image/planemap/index.json` 给每图 AABB，`/res/image/planemap/<id>.webp` 是图；绘制 = `drawImage` 到 **AABB 的屏幕矩形**（按 AABB 拉伸 ⇒ 用不到 index 里的 `scale`） |
| 标记 | **NPC = 绿色圆点 + 朝向线、怪物刷新点 = 红色圆点**（用户 2026-09-22 三轮定调，见 §10.7）。颜色只是**我们挑的对比色**（绿 `#8bf08b` / 红 `#ff5252`，取自客户端地图标记色调），配深色描边 ——**与"出生点"无关**（见 §10.9 的术语纠正） |
| 缺图 | **涂底 + 明说**（它 `drawMap` 的规矩：不静默留白） |

**不做 TGA 图标解码**：用户的口径是"复现**逻辑**"、不是复现资产 —— 客户端那三个标记图标是 PT 加密 TGA
（要移植 `ui-texture.ts` 的解码器），这里用自绘形状替代（信息一样：位置 + 朝向）。

### 10.2 资产怎么给（`/res/**` → 可配资产根）

`pt.assets.root`（环境变量 `PT_ASSET_ROOT`，默认 `/data/PristonTale/apps/client/`），
`/res/**` 与 `/exm-run/**` **共用**这一个根（客户端里 `/res/**` 是 Vite devAssets 虚拟出来的，这里给它同一个前缀，
于是页面侧的 URL 写法与组件侧完全一致）。**启动时把解析结果打进日志**（根不存在就 WARN）——
不这么做，指错地方的表现就是"图全 404"，而成因从现象上看不出来。

⚠ **两个踩坑（都真踩了）**：
1. **`file:` + `E:/x/` 不是合法的资源位置**：手拼 `"file:" + root` 得到 `file:E:/x/`，Spring 会当**相对位置**解析 ⇒ 静默 404。
   老的写死路径是 `file:/data/...`（有前导斜杠）所以一直能用，改成可配后把斜杠弄丢了。修法：一律走 **`File.toURI()`**。
2. **`/res/...` 是根绝对路径**：应用挂在 `/pt` 下，写 `/res/...` 会落到 `http://host/res/...` ⇒ 404。
   页面里必须走 **`PT.apiUrl()`**（→ `./res/...`，再由服务端注入的 `<base href="/pt/">` 解析）。
   ⚠ 我当时的 curl 也漏了 context-path，**两边一起 404**，看起来像"服务端没配对"。

### 10.3 编辑器（独立整页 `/admin/map/{id}/edit`）

- 工具栏：**选择/拖动 · 放置 NPC · 放置怪物刷新点 · 删除**；拖空白平移、滚轮缩放、拖标记移动。
- 右栏属性：NPC = `x/y/z/angle/enabled/onlyGm`（+ 名字只读）、怪物刷新点 = `x/z/description`，各带"删除这个标记"。
- **新放 NPC 的高度 `y` 取"最近的既有 NPC"**：地图有起伏，给 0 会把 NPC 埋进地里（语料里的 y 都是几百量级）。
- 放 NPC 前要**先在左栏选一只**（`PTAdmin.searchPicker` + `resolveNpcNames`：按**本地化名**反查内名称 ✓）。
  ⚠ **选择器"打开就有东西"是用户报过才改的**（2026-09-22："编辑地图放置 NPC，没法从全部 NPC 中选择"）：
  原先它必须先输入关键词才显示结果，空着就是一片空白 —— 像坏了；而且只给 10 条。
  现在 `searchPicker` 支持 `showInitial`（建好即搜一次），且**空查询列出的正是"本图已有的"**：
  NPC 选择器 → 本图 `mapnpc` 里已有的那些 NPC（摆放时最可能选的就是它们）；怪物选择器（刷怪槽位）→
  本图刷怪配置里已用到的怪。搜索上限同时从 10 提到 20。物品选择器也一并加了 `showInitial`。
  ⚠ 配套的**顺序坑**：NPC 选择器必须在 `load()` **之后**建 —— 它的"空查询列本图已有"要读 `state.npcs`，
  建早了那一刻还是空的 ⇒ 列表空着，而 `showInitial` 只跑那一次（踩过：改完第一版仍看不到列表）。
- 新怪物刷新点的 `description` 自动编号（语料就是 `'1'/'2'` 这种）。
- 详情页只做**只读可视化**（同一个画布，NPC + 怪物刷新点标记）+「可视化编辑」按钮。

### 10.4 保存（攒着点保存）

两个"整表替换"接口：`POST /{id}/npcs`、`POST /{id}/points`，**只提交脏了的那一边**；
保存成功后**重新拉取**（新增行拿到了数据库主键，标记的 key 要跟着换，否则第二次保存会重复插）。
未保存提醒：`beforeunload` + 头部「● 未保存」徽章 + 「放弃改动」。

### 10.5 ⚠ `mapspawnpoint.id` 在本库**没有序列**（待你确认的 DDL）

实测六张表里，`dropitem`/`mapmonster`/`mapnpc`/`monsterlist`/`npclist` 的 `id` 都有
`nextval('gamedb.*_id_seq')`，**只有 `mapspawnpoint.id` 是裸 `integer NOT NULL`**（导入时留下的缺口）
⇒ 新增怪物刷新点会 `null value in column "id" violates not-null constraint`。

应用侧已**自适应**（不静默）：启动/首次用到时探一次 `information_schema.columns.column_default` ——
有默认值就交给库（将来补了序列会自动走序列），没有才 `max(id)+1`，并打一条 WARN 说明。
按项目规矩**我没有改库**；要补序列的话语句如下（影响：只加默认值，不动任何行）：

```sql
CREATE SEQUENCE gamedb.mapspawnpoint_id_seq OWNED BY gamedb.mapspawnpoint.id;
ALTER TABLE gamedb.mapspawnpoint ALTER COLUMN id SET DEFAULT nextval('gamedb.mapspawnpoint_id_seq');
SELECT setval('gamedb.mapspawnpoint_id_seq', (SELECT max(id) FROM gamedb.mapspawnpoint));
-- 回滚：ALTER TABLE gamedb.mapspawnpoint ALTER COLUMN id DROP DEFAULT;
--       DROP SEQUENCE gamedb.mapspawnpoint_id_seq;
```

### 10.6 验证

**接口往返**（Ricarten：42 个 NPC 摆放 + 2 个怪物刷新点）：
无改动保存后内容一致 ✓；移动一个 NPC（`x+1`、`angle=12345`）→ 落库 ✓ → 挪回后与快照一致 ✓；
新增一个摆放 → 43 ✓ → 删回 → **与快照一致** ✓；怪物刷新点新增 → 3 ✓ → 删回 → **与快照一致** ✓；
6 条拒绝路径（npcId 不存在 / 缺 x / `enabled=2` / placeId 不属于本图 / points 非数组 / 缺 z）→ 全 400 且**未动数据** ✓。

**浏览器**：详情页可视化 —— 里查顿平面图上 **42 个琥珀三角（带朝向）+ 2 个绿点**全部落在可行走区域
（坐标变换若错会整体偏出图外，这是最直观的校验）；编辑器 —— 工具/图例/三栏布局正常，
在画布 45%/55% 处放一个怪物刷新点 → 标记出现在点击处 + 「● 未保存」亮起 → 点保存 → `已保存：新增 1 改 2 删 0`
→ 库里出现 `6713 (515, 18465)` ✓ → 用接口还原成原来的 2 个 ✓（**没有留下数据改动**）。

**修掉的 bug**：编辑器建好画布后漏了 `syncCanvas()` ⇒ 地图在、**标记全无**，直到第一次编辑才出现（已补，并留注释）。


### 10.7 标记形状的定式（2026-09-22 用户两轮定调）

用户先要"**刷怪点是红色的圆点，而非三角形**"，再补"**NPC 用绿色方形点**" ⇒ 定式为：

| 标记 | 形状 | 颜色 | 说明 |
|---|---|---|---|
| NPC 摆放（`mapnpc`） | **方形点** | 绿 `#8bf08b` | 原来是"琥珀三角 + 朝向"；改成方形后**朝向不再画在图上**（面板里仍有 `angle` 数字可编辑） |
| 怪物刷新点（`mapspawnpoint`） | **圆点** | 红 `#ff5252` | 原来是绿点（与 NPC 撞色），现在与绿点靠颜色区分 |

编辑器左栏的图例同步成"NPC（绿色方块）/ 怪物刷新点（红色圆点）"，英文 `NPC (green square) / Spawn point (red dot)`。
验证：画布像素统计 —— 绿方块 3206 px、红点 113 px、**琥珀三角 0 px**（旧画法已彻底不在）。

⚠ **顺带修掉一类"改了没生效"的坑**：静态页的 JS/CSS 原先会被浏览器缓存 ⇒ 改了脚本但页面还跑旧版，
症状与"服务端没重新编译"一模一样（这一轮真的被它骗过一次：截图里怪物刷新点还是绿的）。
现在 `spring.web.resources.cache.cachecontrol.no-cache: true` —— 每次让浏览器回来校验（304 很便宜）。

⚠ 另外记一笔**不是 bug 的假警报**：编辑页在某次保存时报"请求参数无效"，
原因是**我在服务端用接口把那个新放的怪物刷新点（6713）删掉了**，而页面本地还留着它 ⇒ 服务端按"placeId 不属于这张图"正确拒绝
（日志：`保存怪物刷新点被拒 id=3 原因=第 3 条：pointId=6713 不属于这张图`）。
即：**页面脏状态 + 外部改库**的必然结果，不是校验写错。


### 10.8 "NPC 有面向吗？这个角度怎么体现？"（2026-09-22 用户三问）

**① 有面向，而且是真数据**：`mapnpc.angle` 219 行里只有 **5 行是 0**、**172 个不同取值** ✓；
服务端 `NpcSpawnService` 真的在用它 —— `angleDx = angle / ANGLE_CIRCLE * 2π`，再按原版客户端渲染做 yaw 镜像
`angleGl = π - angleDx` 下发给客户端（源码里连着注释一起抄的），所以游戏里 NPC 确实会转身。

**② 原版地图上并不画朝向**：原版客户端的**地图**只有 `MatNpcPos` 一个 8×8 的点（`DrawMapNPC` 遍历 `smCHAR_STATE_NPC`），
朝向只体现在 **3D 模型**上。所以"地图上画朝向"是**我们有意多给的**（摆放 NPC 时"朝哪边"是要紧信息），不是复现。
⇒ 定式为 **绿点（位置）+ 朝向线（方向）**，怪物刷新点仍是红点。

**③ ⚠ 顺带修掉一个我的真错误：角度单位是 0..4095，不是 65536。**
`NpcSpawnService` 自己写着 `ANGLE_CIRCLE = 4096.0`（一整圈）⇒ 我先前画三角时用 `angle / 65536 * 2π`，
**转出来的朝向小了 16 倍，几乎都指北**（已随这次改动修掉，并把这个依据写进了 `map-canvas.js` 的文件头注释）。

**落线用的式子**（与两处运行时同一套，避免"我们自己的第三套约定"）：
`angleDx = angle / 4096 * 2π` → `angleGl = π - angleDx` → 画布上 `rotate(-angleGl)`（客户端 `drawMonsterMark` 的记法），
线自圆心指向画布上方（= 北；地图 y 向下而北 = −z）。
⚠ **基准方向不能想当然**（用户 2026-09-22 实测："这个表示朝向的线方向反了？" —— 确实反了，差 180°）：
我一开始把线画向 `-y`（= 北），而客户端 `drawMonsterMark` 的三角**尖在 `(0, +5.5)`** ⇒ 它们的基准朝向是
**+y（画布向下 = +z = 南）**。现改为 `(0, +len)`：基准南 + `rotate(-angleGl)` ⇒ 与客户端标记的朝向逐个吻合。
教训与 AGENTS 里那条一致：**"看着像北"不是依据**，依据是客户端自己那几行 `moveTo/lineTo` 的坐标。


---

## 十一 术语纠正：**玩家出生点 ≠ 怪物刷新点**（2026-09-22 用户指出）

我把两者混过（文档/图例里笼统写"刷新点"，还拿客户端的"出生点圆点"当形状先例）。它们是完全不同的数据：

| 概念 | 数据在哪 | 条数 | 谁用 |
|---|---|---|---|
| **玩家出生点** | `fields.json` 的 `startPoints`（EU `MapGame.cpp` 生成；客户端 `src/maps/fields.json`，服务端同源） | 每图 **≤10 个**（里查顿 2 个） | 角色创建 / 传送 / 复活选点 |
| **怪物刷新点** | `gamedb.mapspawnpoint` | **4059 行，每图 1~149 个** | 服务端刷怪机制（本管理端画**红点**的就是它） |

AGENTS 纠错 #20 早就写过这一条（"`.spp` 是刷怪点不是出生点"），我这次又在**另一个维度**上犯了同类错：
把 `mapspawnpoint` 说成"刷新点"（歧义），并把它与客户端的 `startPoints` 摆在一起当先例。
**改法**：界面文案、代码注释、本文档统一改成"**怪物刷新点**"，并在 `map-canvas.js` 文件头写明两者的区别
（红/绿只是我们挑的对比色，**不是因为概念相同**）。`spawnTitle`/`hasSpawn`/`刷新上限` 这些说的是
`mapmonster`（刷怪**配置**）与数量上限，不涉及这次的术语，保持原样。

⚠ 顺带记一个**没做但值得做**的事：玩家出生点（`startPoints`）目前**没有**在管理端可视化（数据在 `fields.json`，
不在 DB）。地图编辑页里把它一起画出来会更完整 —— 但那要先决定"管理端要不要读 `fields.json`"（它是生成文件），
故没有顺手加。

---

## 十二 详情页布局：可视化放最前（2026-09-22 用户："翻到下面很麻烦"）

原顺序是 **列段（7 段 121 行）→ 刷怪配置 → NPC（里查顿 42 个）→ 怪物刷新点 → 可视化**，
要看图或点"可视化编辑"得先翻很久。现改为：

**可视化（地图）→ 刷怪配置 → NPC → 怪物刷新点 → 全部列**

理由：地图是这一页的"总览"，先看到它才知道下面那些列表在说什么；而列段是最长、最不需要第一眼的。
