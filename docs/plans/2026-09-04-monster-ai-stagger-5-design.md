# 怪物 AI/移动 i%5 错峰 设计

日期：2026-09-04
状态：已实现

## 背景

pt-game-server 以 **20FPS**（50ms/tick）运行。`GameServer.tick` 每 tick 调 `MonsterSpawnService.tick` → `updateAndCleanup`，后者**每 tick 遍历全部已刷出怪物**，每怪执行 AI 决策 + 移动碰撞（`movementService.updateMonster`）。

碰撞压力测试（44 图全激活 × 200 怪 = 8800 怪，不分片）显示：walk 约 94ms/tick、run 约 142ms/tick，**远超 50ms 预算**。单线程扛不住"全量每 tick 全动"的最坏负载。

## 原版 C++ 依据

原版怪物的 AI/移动**并不每 tick 全量执行**，采用错峰：

**EU（unitserver.cpp:3143）**：
```cpp
if ((i % 4) == (iUnitWheel % 4))   // 只更新 id%4==轮次 的 1/4 单位
    UpdateUnit(pcUnit);
iUnitWheel++;                        // 每 tick 轮转
```
每单位 4 tick 更新一次。

**ex-machina（OnSever.cpp:8179）**：
```cpp
if ((srAutoPlayCount & 3) == 0) {   // 每 4 tick 一整轮
    for (每个怪) if (NearPlayCount>0) srAutoCharMain(怪);  // 玩家附近才 AI
}
srAutoPlayCount++;
```
每 4 tick 一轮 + 视野内才活跃。

**共同思想**：怪物 AI/碰撞是 4 tick 错峰负载，且远离玩家的怪基本不跑。

## 方案：i%5 分片

EU 16FPS 每 4 tick 一轮 = **每秒 4 轮**怪物更新。我们 20FPS 要保持**每秒 4 轮**，即 **每 5 tick 一轮**（20/4=5）。

实现：
```java
// updateAndCleanup 内，怪物 AI + 移动 分片执行：
if ((monster.getId() % 5) == (tickCounter % 5)) {
    aiEngine.update(monster);                  // AI 决策
    movementService.updateMonster(monster);    // 移动 + 碰撞
}
// 死亡清理(removeIf)不分片，每 tick 执行
```

- `monster.getId() % 5` 把怪物分成 5 组
- `tickCounter % 5` 每 tick 轮转到下一组
- 每 tick 只处理 1/5 怪物，5 tick 内每个怪物轮到一次
- **等效每怪 5 tick 决策/移动一次**（250ms/次 @20fps），AI 节奏与原版 4/16fps 一致

### 与现有机制的关系
- 怪物数量受刷怪激活（玩家 proximity）限制，实际活跃远小于"每图 200"——错峰进一步降低到 1/5
- 死亡清理、刷怪检查不分片（低频副作用需及时）

## 压力测试数据（i%5 vs 不分片，单线程）

测试：每图 200 怪随机游走，walk=3.2（无 CCD）/ run=6.4（触发 CCD），200 tick。

| 图数 | 实体 | 调度 | 模式 | avg(ms) | p95(ms) | max(ms) | max/50ms |
|---|---|---|---|---|---|---|---|
| 22 | 4400 | 不分片 | walk | 39.0 | 44.6 | 48.8 | 98% |
| 22 | 4400 | **i%5 分片** | walk | **7.6** | 8.4 | 8.8 | **18%** |
| 22 | 4400 | 不分片 | run | 49.8 | 51.5 | 52.5 | 105% |
| 22 | 4400 | **i%5 分片** | run | **10.5** | 11.2 | 11.6 | **23%** |
| 44 | 8800 | 不分片 | run | 142 | 145 | 173 | 347% |
| 44 | 8800 | **i%5 分片** | run | **30.9** | 33.2 | 36.7 | **73%** |

**结论**：
- i%5 分片将每 tick 碰撞耗时降至约 1/4~1/5
- 44 图全活跃（8800 怪）run 最坏 37ms avg，**单线程 20FPS 达标**
- 真实负载（玩家活跃的 5-10 图 + 激活裁剪）下余量充足

## 落点

`MonsterSpawnService.updateAndCleanup` 的怪物循环：对 `aiEngine.update` + `movementService.updateMonster` 加 `id%5 == tickCounter%5` 条件；`removeIf` 死亡清理保持每 tick。

## 验证

- 碰撞回归：BridgeTest / CollisionMeshTest / CoordinateParityTest
- 压力测试：`CollisionStressTest`（含 i%5 分片对比，位于 `src/test/java`）

压力测试运行（工具在 test 源码树，需 test classpath）：
```bash
mvn -o -pl pt-game-server exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=org.jpstale.server.game.tool.CollisionStressTest \
    -Dexec.args="200 200" -Dpt.smd.root=E:\JPsTale\client
```
