# pt-web-server 管理端：地图 / NPC / 怪物配置原型设计

**日期**: 2026-03-16  
**状态**: 原型设计（仅文档，不包含实现）  
**相关文档**:  
- `2026-03-16-merge-web-and-role-design.md`（Web 合并与角色设计，已选方案 B：`user_info.web_admin`）  
- `2026-03-16-pt-web-satoken-redis-design.md`（Sa-Token + Redis 设计，`/api/admin/**` 由 `@SaCheckRole("admin")` 保护）  
- `2026-03-16-server-architecture.md`（整体架构与模块划分）

---

## 一、目标与范围

### 1.1 目标

在 **pt-web-server** 中基于现有的登录与角色设计，为 `/api/admin/**` 下新增一套「**地图 / NPC / 怪物配置管理**」能力，提供：

- 面向运维/策划的 **Web 管理后台 UI 原型**（信息架构、页面布局与关键交互）；  
- 与之对应的 **后端 API 原型**（路径结构、资源划分与请求/响应形态），满足「只用 GET/POST」及「仅 admin 角色可访问」的安全约束。

本设计仅描述 **原型与接口规划**，不涉及具体代码实现与 DB 变更。

### 1.2 范围

- 管理端仅对 `user_info.web_admin = true` 的账号开放（已由 Sa-Token 统一判断并授予 `"admin"` 角色）。  
- 设计覆盖以下领域对象：
  - 地图（Map）基础信息与启用/禁用；
  - 全局 NPC 模板（NpcTemplate）；
  - 全局怪物模板（MonsterTemplate）；
  - 地图内 NPC 实例（MapNpc，地图上具体摆放的 NPC）；
  - 地图内刷怪点 / 刷怪组（MapSpawn）。
- 安全约束：
  - 所有接口路径均在 `/api/admin/**` 下；  
  - 仅使用 HTTP **GET / POST**，不暴露 PUT/DELETE；  
  - 删除与状态变更优先采用「软删除 / 启用禁用」策略。

不在本次范围内的内容（可作为后续扩展）：

- 道具与掉落配置、活动配置、公会运维等；  
- 高阶能力如批量导入导出、版本对比/回滚、在线热更新等。

---

## 二、资源模型设计

以下模型为逻辑层设计，字段名称与类型可在实现时按实际 DB schema 微调。

### 2.1 地图 Map

- `id` / `mapId`: 唯一标识，对应游戏中的地图 ID；  
- `name`: 地图名称（中文或英文）；  
- `code`: 地图内部代码（可选，英文标识符，便于搜索与脚本引用）；  
- `recommendedLevelMin` / `recommendedLevelMax`: 推荐等级范围；  
- `enabled`: 是否对玩家开放（true/false）；  
- `description`: 文本描述；  
- `createdAt` / `updatedAt`: 审计字段（可选）。

### 2.2 NPC 模板 NpcTemplate（全局字典）

- `id`: 唯一标识；  
- `name`: NPC 名称；  
- `type`: NPC 类型（如 `SHOP`, `QUEST`, `TELEPORT`, `SERVICE` 等枚举）；  
- `modelId` / `appearance`: 外观/模型引用（按实际 C++ 配置对齐）；  
- `scriptId` / `behaviorType`: 行为或脚本标识（驱动其交互逻辑）；  
- `enabled`: 模板启用状态；  
- `description`: 备注说明。

### 2.3 怪物模板 MonsterTemplate（全局字典）

- `id`: 唯一标识；  
- `name`: 怪物名称；  
- `level`: 等级；  
- `hp` / `attack` / `defense`: 基础属性（可简化为只读或部分可编辑）；  
- `aiType`: AI 行为类型标识；  
- `enabled`: 模板启用状态；  
- `description`: 备注说明。

### 2.4 地图内 NPC 实例 MapNpc

- `id`: 唯一标识；  
- `mapId`: 所属地图 ID；  
- `npcTemplateId`: 引用的 NPC 模板 ID；  
- `x` / `y` / `z`: 坐标；  
- `rotation`: 朝向（可选）；  
- `enabled`: 是否启用该 NPC 实例。

### 2.5 地图内刷怪点 MapSpawn

- `id`: 唯一标识；  
- `mapId`: 所属地图 ID；  
- `monsterTemplateId`: 引用的怪物模板 ID；  
- `x` / `y` / `z`: 坐标；  
- `radius`: 刷怪范围半径；  
- `maxCount`: 最大怪物数量；  
- `respawnIntervalSec`: 刷新间隔（秒）；  
- `enabled`: 刷怪点启用状态；  
- `description`: 备注说明（例如「新手村外围小怪」等）。

---

## 三、API 原型设计（仅 GET & POST）

### 3.1 通用约定

- 所有接口路径均以 `/api/admin/` 开头，并由 `@SaCheckRole("admin")` 保护；  
- 鉴权失败：
  - 未登录：返回 HTTP 401 + 统一 JSON 体；  
  - 已登录但无 `"admin"` 角色：返回 HTTP 403 + 统一 JSON 体。  
- 成功返回建议统一封装为：

```json
{
  "code": 0,
  "message": "OK",
  "data": { ... }
}
```

错误场景返回：

- `code != 0`，`message` 提示错误原因；  
- HTTP 状态码按语义设置（400/401/403/404/500）。

删除/状态变更不使用 DELETE/PUT，而采用 POST 动作式路径：

- 新建：`POST .../create`  
- 更新：`POST .../{id}/update`  
- 启用/禁用：`POST .../{id}/enable` / `.../{id}/disable`  
- 删除：`POST .../{id}/delete`（实现上可以是软删）。

### 3.2 地图管理 `/api/admin/maps/**`

#### 3.2.1 列表与详情

- **GET `/api/admin/maps`**  
  - 查询参数：
    - `page`, `size`（分页）；  
    - `keyword`（按名称/代码模糊）；  
    - `enabled`（可选过滤 true/false）。  
  - 返回：分页 Map 列表（仅 summary 字段）。

- **GET `/api/admin/maps/{mapId}`**  
  - 功能：获取单张地图详情（基础信息 + 可选统计字段）。

#### 3.2.2 新建与编辑

- **POST `/api/admin/maps/create`**  
  - 请求体（示例）：
    - `name`, `code`, `recommendedLevelMin`, `recommendedLevelMax`, `enabled`, `description`。

- **POST `/api/admin/maps/{mapId}/update`**  
  - 请求体：与 create 相同的子集，至少包括：
    - `recommendedLevelMin`, `recommendedLevelMax`, `description`；  
    - 若允许改名/改代码，则一并包含。

#### 3.2.3 启用/禁用

- **POST `/api/admin/maps/{mapId}/enable`**  
- **POST `/api/admin/maps/{mapId}/disable`**  

可选约定：优先通过 enabled 表示是否开放，真正删除地图时再单独暴露 delete。

---

### 3.3 NPC 模板 `/api/admin/npc-templates/**`

#### 3.3.1 列表与详情

- **GET `/api/admin/npc-templates`**  
  - 支持分页、按名称/类型搜索；  
  - 可选 `enabled` 过滤。

- **GET `/api/admin/npc-templates/{id}`**  
  - 读取单个模板详情。

- **GET `/api/admin/npc-templates/simple-list`**（可选优化）  
  - 返回 `id + name` 的简短列表，供前端下拉选择使用。

#### 3.3.2 新建、编辑、禁用/删除

- **POST `/api/admin/npc-templates/create`**  
- **POST `/api/admin/npc-templates/{id}/update`**  
- **POST `/api/admin/npc-templates/{id}/disable`** / `enable`  
- **POST `/api/admin/npc-templates/{id}/delete`**（可选，逻辑或物理删除）。

---

### 3.4 怪物模板 `/api/admin/monster-templates/**`

结构与 NPC 模板类似：

- **GET `/api/admin/monster-templates`**  
- **GET `/api/admin/monster-templates/{id}`**  
- **GET `/api/admin/monster-templates/simple-list`**（可选）  
- **POST `/api/admin/monster-templates/create`**  
- **POST `/api/admin/monster-templates/{id}/update`**  
- **POST `/api/admin/monster-templates/{id}/disable` / `enable`**  
- **POST `/api/admin/monster-templates/{id}/delete`**（可选）。

---

### 3.5 地图内 NPC 实例 `/api/admin/maps/{mapId}/npcs/**`

#### 3.5.1 列表

- **GET `/api/admin/maps/{mapId}/npcs`**  
  - 返回该地图下所有 NPC 实例列表。

#### 3.5.2 新增、编辑、删除

- **POST `/api/admin/maps/{mapId}/npcs/create`**  
  - 请求体：
    - `npcTemplateId`  
    - `x`, `y`, `z`  
    - `rotation`（可选）  
    - `enabled`（可选，默认 true）。

- **POST `/api/admin/maps/{mapId}/npcs/{mapNpcId}/update`**  
  - 请求体：允许更新 `npcTemplateId` 与坐标等字段。

- **POST `/api/admin/maps/{mapId}/npcs/{mapNpcId}/delete`**  
  - 删除该 NPC 实例（软删或真删，由实现决定）。

---

### 3.6 地图内刷怪点 `/api/admin/maps/{mapId}/spawns/**`

#### 3.6.1 列表

- **GET `/api/admin/maps/{mapId}/spawns`**  
  - 返回该地图的所有刷怪配置；
  - 可支持按 `monsterTemplateId` 过滤。

#### 3.6.2 新增、编辑、删除

- **POST `/api/admin/maps/{mapId}/spawns/create`**  
  - 请求体：
    - `monsterTemplateId`  
    - `x`, `y`, `z`  
    - `radius`  
    - `maxCount`  
    - `respawnIntervalSec`  
    - `enabled`。

- **POST `/api/admin/maps/{mapId}/spawns/{spawnId}/update`**  
  - 请求体：允许更新上述参数的任意子集。

- **POST `/api/admin/maps/{mapId}/spawns/{spawnId}/delete`**  
  - 删除该刷怪点（软删或真删）。

#### 3.6.3 批量复制（可选）

- **POST `/api/admin/maps/{mapId}/spawns/batch-copy`**  
  - 请求体示例：
    - `fromMapId`: 源地图 ID；  
    - `strategy`: 复制策略（覆盖已有/仅新增等）。

---

## 四、管理后台 UI 原型

本节描述管理后台的页面结构与关键交互，对应上述 API 原型。前端可使用任意技术栈（如 Vue/React/纯 JSP+JS），本设计偏重信息架构与表单/表格布局。

### 4.1 总体信息架构

- 管理后台入口：登录后若 `web_admin = true`，在站点中显示「管理后台」入口（如 `/admin/`）。  
- 左侧导航（示例）：
  - `运维总览`  
  - `地图管理`  
  - `NPC 模板`  
  - `怪物模板`
- 所有页面中的接口调用均走 `/api/admin/**` 并在前端统一处理 401/403。

---

### 4.2 运维总览页 `/admin/overview`

**目的**：提供简单的首页，让运维快速跳转到常用的地图与配置页。

- 顶部标题：`运维总览`  
- 卡片区（只读统计，可后续扩展）：
  - 「地图总数 / 已启用地图数」  
  - 「NPC 模板总数」  
  - 「怪物模板总数」
- 快捷入口按钮：
  - 「查看全部地图」→ 跳转 `/admin/maps`  
  - 「NPC 模板管理」→ 跳转 `/admin/npc-templates`  
  - 「怪物模板管理」→ 跳转 `/admin/monster-templates`

后续可在此页新增「当前在线人数」「服务器状态」等信息。

---

### 4.3 地图管理列表页 `/admin/maps`

**目标**：浏览所有地图的基本信息与状态，一键进入详情或进行启用/禁用。

- 顶部操作区：
  - 搜索框：关键字（按名称/代码模糊）；  
  - 状态筛选：全部 / 启用 / 禁用；  
  - （可选）「新建地图」按钮 → 打开新建表单（POST `/api/admin/maps/create`）。

- 列表表格字段（示例）：
  - `ID`  
  - `名称`  
  - `代码`  
  - `推荐等级`（如 `10 - 20`）  
  - `状态`（启用/禁用标签）  
  - 操作列：
    - 「详情」→ 跳到 `/admin/maps/{mapId}`；  
    - 「启用/禁用」按钮：
      - 当前为启用 → 显示「禁用」→ 调 `POST /api/admin/maps/{mapId}/disable`；  
      - 当前为禁用 → 显示「启用」→ 调 `POST /api/admin/maps/{mapId}/enable`。

---

### 4.4 地图详情页 `/admin/maps/{mapId}`

整体布局：顶部基础信息卡片 + 下方 Tab。

#### 4.4.1 顶部基础信息卡片

- 显示字段：
  - 地图名称、ID、代码；  
  - 推荐等级范围；  
  - 当前状态（启用/禁用）。
- 操作：
  - 「编辑基础信息」按钮：弹出对话框表单，字段包括名称、代码、推荐等级、描述 → `POST /api/admin/maps/{mapId}/update`；  
  - 「启用/禁用」按钮：调用 `POST /api/admin/maps/{mapId}/enable/disable`。

#### 4.4.2 Tab1：基础信息

内容以表单形式展示基础字段：

- 名称、代码、推荐等级 min/max；  
- 描述（多行文本）；  
- 状态仅展示，不在该处修改（避免与顶部开关重复）。

底部统一「保存」按钮，调用 `POST /api/admin/maps/{mapId}/update`。

#### 4.4.3 Tab2：NPC 管理

**顶部操作**：

- 「新增 NPC」按钮 → 打开右侧抽屉或弹窗表单；
- 简单统计：本地图 NPC 数量。

**NPC 列表表格字段**：

- `ID`  
- `NPC 模板` 名称（可点击打开模板详情）  
- `坐标 X / Y / Z`  
- `朝向`（可选）  
- `状态`（启用/禁用）  
- 操作：
  - 「编辑」→ 弹窗表单，可调整 `npcTemplateId` 与坐标/朝向 → `POST /api/admin/maps/{mapId}/npcs/{mapNpcId}/update`；  
  - 「删除」→ 确认后调用 `POST /api/admin/maps/{mapId}/npcs/{mapNpcId}/delete`。

**新增/编辑 NPC 表单字段**：

- NPC 模板：下拉选择，数据来自 `GET /api/admin/npc-templates/simple-list` 或分页接口；  
- 坐标：X、Y、Z（可做基础数值验证）；  
- 朝向：可选；  
- 启用开关：默认为 true。

提交时区分：

- 新增：`POST /api/admin/maps/{mapId}/npcs/create`；  
- 编辑：`POST /api/admin/maps/{mapId}/npcs/{mapNpcId}/update`。

#### 4.4.4 Tab3：刷怪管理

**顶部操作**：

- 「新增刷怪点」按钮；  
- 可选筛选：按怪物模板筛选当前列表。

**刷怪点表格字段**：

- `ID`  
- `怪物模板` 名称（可点击打开详情）  
- `坐标 X / Y / Z`  
- `半径`  
- `最大数量 maxCount`  
- `刷新间隔 respawnIntervalSec`  
- `状态`（启用/禁用）  
- 操作：
  - 「编辑」→ 表单修改参数 → `POST /api/admin/maps/{mapId}/spawns/{spawnId}/update`；  
  - 「启用/禁用」→ 修改 `enabled` 字段同样走 update；  
  - 「删除」→ `POST /api/admin/maps/{mapId}/spawns/{spawnId}/delete`。

**新增/编辑刷怪点表单字段**：

- 怪物模板：下拉选择，自 `GET /api/admin/monster-templates/simple-list`；  
- 坐标：X、Y、Z；  
- 半径；  
- 最大数量；  
- 刷新间隔（秒）；  
- 启用开关。

提交区分：

- 新增：`POST /api/admin/maps/{mapId}/spawns/create`；  
- 编辑：`POST /api/admin/maps/{mapId}/spawns/{spawnId}/update`。

---

### 4.5 NPC 模板管理页 `/admin/npc-templates`

**列表页**：

- 顶部：
  - 关键字搜索（名称）；  
  - 类型筛选（SHOP/QUEST/...）；  
  - 状态筛选（启用/禁用）；  
  - 「新建模板」按钮。

- 表格字段：
  - `ID`  
  - `名称`  
  - `类型`  
  - `状态`（启用/禁用）  
  - 操作：
    - 「编辑」→ 打开表单 → `POST /api/admin/npc-templates/{id}/update`；  
    - 「启用/禁用」→ `POST /api/admin/npc-templates/{id}/enable/disable`；  
    - 「删除」→ `POST /api/admin/npc-templates/{id}/delete`（若需要硬删）。

**新建/编辑模板表单**：

- 名称；  
- 类型（下拉）；  
- 外观/模型标识；  
- 脚本/行为标识；  
- 描述；  
- 启用开关。

---

### 4.6 怪物模板管理页 `/admin/monster-templates`

整体与 NPC 模板管理类似：

- 列表字段：
  - `ID`  
  - `名称`  
  - `等级`  
  - （可选）`类型` 或 `AI 类型`  
  - `状态`

- 表单字段（可精简）：
  - 名称；  
  - 等级；  
  - HP / 攻击 / 防御（可选）；  
  - AI 类型；  
  - 描述；  
  - 启用开关。

- 操作均通过前文定义的 `/api/admin/monster-templates/**` 接口完成。

---

## 五、关键交互流程示例

### 5.1 调整某地图刷怪配置

1. 管理员登录 Web 后端，点击「管理后台」进入 `/admin/overview`；  
2. 在左侧导航选择「地图管理」，在列表中搜索并找到目标地图；  
3. 点击「详情」进入 `/admin/maps/{mapId}`；  
4. 切换到「刷怪」Tab，查看已有刷怪点列表；  
5. 点击某个刷怪点的「编辑」，调整 `monsterTemplateId`、数量或刷新间隔，点击「保存」，前端调用 `POST /api/admin/maps/{mapId}/spawns/{spawnId}/update`；  
6. 若需暂时关闭某个刷怪点，点击「禁用」按钮，前端仅改 `enabled=false` 并调用相同的 update 接口；  
7. 成功后在页面顶部展示统一的成功提示，并在表格中实时更新状态。

### 5.2 为地图新增一个 NPC

1. 在地图详情页切到「NPC」Tab；  
2. 点击「新增 NPC」，弹出表单；  
3. 从下拉框选择一个 NPC 模板，填写坐标与朝向；  
4. 点击保存，前端调用 `POST /api/admin/maps/{mapId}/npcs/create`；  
5. 刷新当前表格行数据，显示新 NPC 实例。

---

## 六、与现有设计的关系与后续步骤

- 管理端所有接口均基于现有的 Sa-Token + Redis 登录体系，复用 `web_admin` 角色判断；  
- API 原型遵守 `/api/admin/**` 路径与仅 GET/POST 的安全约束；  
- UI 原型采用「地图为中心 + 字典型模板管理」的结构，兼顾实现复杂度与运维可用性。

后续实现建议（由 implementation plan 单独详细展开）：

1. 在 pt-web-server 中为上述资源按模块拆分 Controller（如 `AdminMapController`、`AdminNpcTemplateController` 等），路径与本设计保持一致；  
2. 结合现有 pt-dao 中与地图/NPC/怪物相关的表与实体，补全或新增所需 Mapper 与 Service；  
3. 按本设计构建前端管理页面，可先以最简单的表格 + 弹窗方式落地；  
4. 针对关键修改操作增加操作日志审计与最小权限校验（长期目标）。

**本设计到此为止，不涉及具体实现代码。**

