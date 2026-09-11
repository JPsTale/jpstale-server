# 怪物掉落 + 掉落物名牌 设计

日期：2026-09-11
状态：待实现
范围：jpstale-server（掉落系统）+ jpstale-client（名牌 / 落点）

## 背景

四项诉求：

1. 鼠标指向掉落物时显示名牌（白字），样式对齐怪物名牌。
2. 按 A 键切换显示附近所有掉落物名牌。
3. 服务端实现怪物死亡掉落（当前完全缺失）。
4. `/@get` 刷出的掉落物常"沉入地下"、位置/朝向随机性差。

现状盘点：

- **客户端**：掉落物名牌已存在但是 3D `THREE.Sprite`（淡黄），hover 显示；怪物名牌是 Canvas overlay `drawPill`。`KeyBinding` 已定义 `showGroundItems:'KeyA'`，但 `main.ts` 的 `onKeyDown` 无对应分支 → A 键未实现。
- **服务端**：`GroundItemManager` / `GroundItemAOI`（每 tick 自动 AOI 广播）/ `ItemRollService.rollByIdCode` / `MapRegionService.getHeight` 均已具备；`CombatService.handleMonsterDeath` 只给经验与占位金币（`lvl*rnd(5,15)`），无掉落。`Monster.templateId` 从未被赋值。
- **数据库**：`gamedb.dropitem` 2268 行 / 305 个 dropid；`dropid == monsterlist.id`（已验证 dropid=1 即 Hopy）。每行 `items` 为候选 itemlist idCode 的空格分隔列表，或 `Gold` / `Air`；`chance` 为权重；`goldmin..goldmax` 为金币范围。
- **限时道具**：jpstale-server 仅有从 C++ 迁移的 `ItemPremium` 结构体与 `ItemTimerType` 枚举，**无 premium 业务逻辑**。

## 原版依据（PristonTale-EU lootserver.cpp / unitserver.cpp）

**加载**（lootserver.cpp:15）：
`SELECT * FROM DropItem WHERE DropID > 0 ORDER BY DropID ASC, Chance DESC`，按 dropid 分组；每行 `iTotalDropChance += chance`；`items` 按空格切分为候选 idCode。

**单次掷点**（lootserver.cpp:717）：
```cpp
int iRand = RandomI(0, totalChance);
for (def : defs) { iTotal += def.chance; if (iRand <= iTotal) return def; }
```
命中 Air → 无掉落；Gold → `RandomI(goldMin, goldMax)`；Items → 候选里随机取一个 idCode 生成物品。

**掉落次数**（unitserver.cpp:746 `OnSetDrop` + 1019 `HandleKill`）：
基础 `iNumDrops = monsterlist.dropquantity`，逐项加成：
ThirdEye 限时道具 +1；SixthSense 25% +1；ServerWideDropBuff 15% +1；组队 Hunt 模式 +1；任务降低 -N；全局事件 `EVENT_EXTRADROPS` +N。
随后 `for (i = 0; i < iNumDrops; i++)` 各掷一次，投放到怪物死亡坐标。

**公共掉落**（unitserver.cpp:1127）：`dropispublic` true → 附近所有人可见 + 组队掷骰；false → 仅击杀者可见。

## 设计

### 1. 客户端掉落物名牌（overlay 白字 + A 键切换）

- 新增状态 `groundItemLabelsOn`；`WorldView` 暴露 `toggleGroundItemLabels()`。
- `main.ts`：`keyBinding.onKeyDown` 增加 `case 'showGroundItems'` → `worldView.toggleGroundItemLabels()`。
- `drawNameplateOverlay` 增加掉落物循环：显示条件 `g.root === hoverTarget?.root || (groundItemLabelsOn && 在 NAME_TAG_RANGE 内)`；用 `anchorToScreen(g.root, g.topY)` + `drawPill(..., { nameColor:'#ffffff', showHp:false })`。
- 移除 3D Sprite 名牌（`makeItemLabel` 及 `GroundItemActor.label`），统一走 overlay。

### 2. /@get 贴地 + 随机

- `ChatService.treatGet`：`ny = mapRegionService.getHeight(mapId, nx, nz)`（不再用 `ent.getY()`）；`dist = 0.5 + rnd*29.5`（约 0.5~30 世界单位，散布更广）。
- 客户端 `spawnGroundItem`：`pivot.rotation.y = Math.random() * 2π`（不再由位置派生）。

### 3. 服务端怪物掉落

- `Monster` 增加字段 `dropId`、`dropIsPublic`、`dropQuantity`（或在 `createMonster` 直接写入 templateId 复用）。
- `MonsterSpawnService.createMonster`：补 `setTemplateId(template.getId())`；写入 `dropIsPublic` / `dropQuantity`；**移除** `setGold(lvl*rnd)`。
- 新增 `LootService`：
  - 启动时 `DropItemMapper.selectAllByDropIdGt0()` → `Map<Integer, DropTable>`（`totalChance` + `defs`），带缓存。
  - `roll(dropId)` 复刻 EU `GetRandomDropDefinition`：返回 `Air | Gold(min,max) | ItemCode`。
- `CombatService.handleMonsterDeath`：
  - `numDrops = max(0, dropQuantity) + lootService.extraDrops()`。
  - 循环 `numDrops` 次 `roll(dropId)`：Gold 累加；ItemCode → `itemRoll.rollByIdCode(code, null)` → 怪物死亡点附近随机偏移落 `GroundItemManager`，`y = mapRegionService.getHeight(...)`，`ownerId = dropIsPublic ? 0 : killerId`。
  - 金币一次性给击杀者并 `persistStats`。
  - Appear 由 `GroundItemAOI` 下一 tick 自动广播，不手动发。
- **非公共可见性**（`GroundItemAOI`）：`gi.ownerId != 0 && gi.ownerId != 当前玩家` 的掉落不发送 Appear（对齐 EU `SendItemStageUser`）。同时影响玩家主动丢弃（ownerId=丢弃者）。

### 4. 掉落数量加成

- 本轮：基础 `dropQuantity` + 全局事件额外掉落 `extraDrops`（可配 0/1/2/3）。
- 留 TODO：ThirdEye / SixthSense / DropBuff / 组队 Hunt 加成，待 premium 业务系统实现后接入 `LootService.extraDrops(...)`。

## 非目标

- premium / 限时道具系统本身。
- 组队掷骰分配（`RollDiceDropItem`）。
- 任务掉落（`iQuestItemID` / `SendQuestDropItemToUser`）。
- 掉落物堆叠合并。

## 落点

服务端：

- `pt-game-server/.../game/item/LootService.java`（新增）
- `pt-game-server/.../game/model/Monster.java`
- `pt-game-server/.../game/service/MonsterSpawnService.java`
- `pt-game-server/.../game/service/CombatService.java`
- `pt-game-server/.../game/service/GroundItemAOI.java`
- `pt-game-server/.../game/service/ChatService.java`

客户端：

- `src/ui/WorldView.ts`（名牌、落点随机）
- `src/main.ts`（A 键分支）

## 验证

- 服务端单测：`LootServiceTest`——构造 dropid 表，验证加权掷点落在正确行、Air/Gold/Items 分支、总数边界。
- 手工：击杀 Hopy/Zombie 观察掉落；`/@get` 物品贴地且位置分散；A 键切换名牌；hover 白字名牌。
- 非公共掉落：击杀者可见、旁人不可见。

## 风险

- `dropitem.items` 中未知 idCode（EU 代码会 WARN 跳过）：`LootService` 需容错。
- 掉落物数量可能瞬时冲高 `GroundItemManager.STG_ITEM_MAX`：沿用现有挤压/丢弃策略。
- 非公共可见性改动会改变玩家丢弃物的可见范围，需确认符合预期。
