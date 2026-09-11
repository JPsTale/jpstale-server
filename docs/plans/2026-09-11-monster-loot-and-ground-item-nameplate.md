# 怪物掉落 + 掉落物名牌 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现服务端怪物死亡掉落（对齐 EU lootserver）、`/@get` 贴地随机化，以及客户端掉落物白字名牌 + A 键全显。

**Architecture:** 服务端新增 `LootService`（启动时把 `gamedb.dropitem` 加载成 `dropId → 加权掉落表`，提供 `roll` 与热重载 `reload`），`CombatService.handleMonsterDeath` 按 `monsterlist.dropquantity + premium 加成` 循环掷点，物品经 `ItemRollService` 生成后落入 `GroundItemManager`；`GroundItemAOI` 增加 owner 可见性过滤。客户端把掉落物名牌从 3D Sprite 改为 Canvas overlay（白字），并用 `getHeight`/随机散布修复 `/@get`。

**Tech Stack:** 服务端 Java 21 / Spring Boot / MyBatis-Plus / JUnit4；客户端 TypeScript / Vite / three 0.160。

## Global Constraints

- 服务端测试框架为 **JUnit4**（`org.junit.Test` / `org.junit.Assert`），参照 `GroundItemManagerSqueezeTest`。
- `dropitem.items` 的 token 对应 `itemlist.codeimg1`（**大小写不敏感**，如 `se101` → `SE101`）；token 转 `itemlist.idcode` 后交给 `ItemRollService.rollByIdCode`。
- `dropitem.dropid == monsterlist.id`（已由数据库验证）。
- 掉落物 Y 一律用 `MapRegionService.getHeight(mapId, x, z)`。
- premium 只做 `PremiumService.getTimeLeft` **stub**（返回 0），完整 premium 另立 spec；不实时写库。
- 客户端所有资产经 `/res/*`；改动后必须 `npx tsc --noEmit` 通过。

---

### Task S1: DropItemMapper 增加 `selectAllByDropIdGt0`

**Files:**
- Modify: `pt-dao/src/main/java/org/jpstale/dao/gamedb/mapper/DropItemMapper.java`
- Modify: `pt-dao/src/main/resources/org/jpstale/dao/gamedb/mapper/DropItemMapper.xml`

**Interfaces:**
- Produces: `List<DropItem> selectAllByDropIdGt0()`（XML 已存在，但列名写错，需一并修正）。

- [ ] **Step 1: 增加接口方法**

```java
package org.jpstale.dao.gamedb.mapper;

import org.jpstale.dao.gamedb.entity.DropItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface DropItemMapper extends BaseMapper<DropItem> {

    /** 全部 dropid>0 的掉落行（gamedb.dropitem），按 dropid/chance 排序（XML 实现）。 */
    List<DropItem> selectAllByDropIdGt0();
}
```

- [ ] **Step 1b: 修正 XML 列名（`drop_id` → `dropid`）**

`DropItemMapper.xml` 现用 `drop_id`，但数据库列名是 `dropid`（已验证），会报列不存在。整文件替换为：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="org.jpstale.dao.gamedb.mapper.DropItemMapper">

    <!-- C++ lootserver.cpp:30 -->
    <select id="selectAllByDropIdGt0" resultType="org.jpstale.dao.gamedb.entity.DropItem">
        SELECT * FROM gamedb.dropitem WHERE dropid &gt; 0 ORDER BY dropid ASC, chance DESC
    </select>

</mapper>
```

- [ ] **Step 2: 编译**

Run: `mvn -o -q -pl pt-dao -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add pt-dao/src/main/java/org/jpstale/dao/gamedb/mapper/DropItemMapper.java \
        pt-dao/src/main/resources/org/jpstale/dao/gamedb/mapper/DropItemMapper.xml
git commit -m "feat(dao): DropItemMapper.selectAllByDropIdGt0 + 修正 dropid 列名"
```

---

### Task S2: `LootService`（加权掉落表 + 热重载）

**Files:**
- Create: `pt-game-server/src/main/java/org/jpstale/server/game/item/LootService.java`
- Test: `pt-game-server/src/test/java/org/jpstale/server/game/item/LootServiceTest.java`

**Interfaces:**
- Consumes: `DropItemMapper.selectAllByDropIdGt0()`; `ItemListMapper.selectList(null)`（`itemlist.codeimg1`/`idcode`）。
- Produces:
  - `enum DropType { AIR, GOLD, ITEMS }`
  - `static final class DropResult { DropType type; int gold; int itemCode; }`
  - `static Map<Integer, DropTable> buildTables(List<DropItem> rows, Map<String,Integer> codeToIdCode)`
  - `DropResult roll(int dropId)`
  - `void reload()`
  - `int extraDrops(Player player)`
  - `static final class DropTable` / `static final class DropDef`

- [ ] **Step 1: 写失败测试**

```java
package org.jpstale.server.game.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.jpstale.dao.gamedb.entity.DropItem;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class LootServiceTest {

    private static DropItem row(int dropId, String items, int chance, int gmin, int gmax) {
        DropItem d = new DropItem();
        d.setDropId(dropId);
        d.setItems(items);
        d.setChance(chance);
        d.setGoldMin(gmin);
        d.setGoldMax(gmax);
        return d;
    }

    @Test
    public void weightedPickHitsOnlyRowInRange() {
        // 权重 [Air 100, Gold 300, ITEMS 600]；rand 落在不同区间
        List<DropItem> rows = List.of(
                row(1, "Air", 100, 0, 0),
                row(1, "Gold", 300, 5, 5),
                row(1, "wa105", 600, 0, 0));
        Map<Integer, LootService.DropTable> tables =
                LootService.buildTables(rows, Map.of("wa105", 16844032));

        assertEquals(LootService.DropType.AIR, LootService.rollStatic(tables.get(1), 0.05).type);
        assertEquals(LootService.DropType.GOLD, LootService.rollStatic(tables.get(1), 0.30).type);
        assertEquals(5, LootService.rollStatic(tables.get(1), 0.30).gold);
        LootService.DropResult it = LootService.rollStatic(tables.get(1), 0.90);
        assertEquals(LootService.DropType.ITEMS, it.type);
        assertEquals(16844032, it.itemCode);
    }

    @Test
    public void unknownTokenSkippedAndAirOnUnknownDropId() {
        Map<Integer, LootService.DropTable> tables =
                LootService.buildTables(List.of(row(2, "nosuchcode", 100, 0, 0)), Map.of());
        // 候选全部无法解析 → 该行 defs 为空
        assertEquals(0, tables.get(2).defs.size());
        assertNull(LootService.rollStatic(tables.get(2), 0.5));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -o -q -pl pt-game-server -am -Dtest=LootServiceTest test`
Expected: FAIL（`LootService` 不存在）

- [ ] **Step 3: 实现 LootService**

```java
package org.jpstale.server.game.item;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.DropItem;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.DropItemMapper;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.server.common.enums.item.ItemTimerType;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.service.PremiumService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 怪物掉落表（对齐 PristonTale-EU lootserver.cpp）。
 * dropid == monsterlist.id；dropitem.items 为候选 itemlist.codeimg1 列表（空格分隔）或 Gold/Air。
 */
@Slf4j
@Service
public class LootService {

    public enum DropType { AIR, GOLD, ITEMS }

    public static final class DropDef {
        public final DropType type;
        public final int chance;
        public final int goldMin, goldMax;
        public final List<Integer> itemCodes;

        DropDef(DropType type, int chance, int goldMin, int goldMax, List<Integer> itemCodes) {
            this.type = type;
            this.chance = chance;
            this.goldMin = goldMin;
            this.goldMax = goldMax;
            this.itemCodes = itemCodes;
        }
    }

    public static final class DropTable {
        public final int totalChance;
        public final List<DropDef> defs;

        DropTable(int totalChance, List<DropDef> defs) {
            this.totalChance = totalChance;
            this.defs = defs;
        }
    }

    public static final class DropResult {
        public final DropType type;
        public final int gold;
        public final int itemCode;

        DropResult(DropType type, int gold, int itemCode) {
            this.type = type;
            this.gold = gold;
            this.itemCode = itemCode;
        }
    }

    private final DropItemMapper dropItemMapper;
    private final ItemListMapper itemListMapper;
    private final PremiumService premiumService;

    /** 全局事件额外掉落（可配 0/1/2/3，对应 EU EVENT_EXTRADROPS） */
    @Value("${pt.loot.extra-drops:0}")
    private int eventExtraDrops = 0;

    private volatile Map<Integer, DropTable> tables = Map.of();

    public LootService(DropItemMapper dropItemMapper, ItemListMapper itemListMapper,
                       PremiumService premiumService) {
        this.dropItemMapper = dropItemMapper;
        this.itemListMapper = itemListMapper;
        this.premiumService = premiumService;
    }

    @PostConstruct
    public void reload() {
        Map<String, Integer> codeToIdCode = new HashMap<>();
        for (ItemList it : itemListMapper.selectList(null)) {
            if (it.getCodeImg1() != null && !it.getCodeImg1().isBlank() && it.getIdCode() != null) {
                codeToIdCode.put(it.getCodeImg1().trim().toLowerCase(), it.getIdCode());
            }
        }
        Map<Integer, DropTable> built = buildTables(dropItemMapper.selectAllByDropIdGt0(), codeToIdCode);
        tables = built;
        log.info("[Loot] 掉落表已加载: {} 个 dropid", built.size());
    }

    /** 纯函数：按 dropid 分组构建加权表（可单测）。 */
    public static Map<Integer, DropTable> buildTables(List<DropItem> rows, Map<String, Integer> codeToIdCode) {
        Map<Integer, List<DropDef>> byId = new HashMap<>();
        for (DropItem d : rows) {
            if (d.getDropId() == null || d.getDropId() <= 0) continue;
            String items = d.getItems() == null ? "" : d.getItems().trim();
            int chance = d.getChance() == null ? 0 : d.getChance();
            if (chance <= 0) continue;
            DropDef def;
            if (items.equalsIgnoreCase("Gold")) {
                def = new DropDef(DropType.GOLD, chance,
                        d.getGoldMin() == null ? 0 : d.getGoldMin(),
                        d.getGoldMax() == null ? 0 : d.getGoldMax(), List.of());
            } else if (items.equalsIgnoreCase("Air")) {
                def = new DropDef(DropType.AIR, chance, 0, 0, List.of());
            } else {
                List<Integer> codes = new ArrayList<>();
                for (String tok : items.split("\\s+")) {
                    if (tok.isBlank()) continue;
                    Integer code = codeToIdCode.get(tok.toLowerCase());
                    if (code != null) codes.add(code);
                    else log.warn("[Loot] dropid={} 未知 item code: {}", d.getDropId(), tok);
                }
                if (codes.isEmpty()) continue; // 全部无法解析 → 跳过该行
                def = new DropDef(DropType.ITEMS, chance, 0, 0, codes);
            }
            byId.computeIfAbsent(d.getDropId(), k -> new ArrayList<>()).add(def);
        }
        Map<Integer, DropTable> out = new HashMap<>();
        for (Map.Entry<Integer, List<DropDef>> e : byId.entrySet()) {
            int total = 0;
            for (DropDef def : e.getValue()) total += def.chance;
            out.put(e.getKey(), new DropTable(total, e.getValue()));
        }
        return out;
    }

    /** 掷点（供测试注入固定随机数）。 */
    static DropResult rollStatic(DropTable table, double rand01) {
        if (table == null || table.defs.isEmpty() || table.totalChance <= 0) return null;
        double target = rand01 * table.totalChance;
        int acc = 0;
        for (DropDef def : table.defs) {
            acc += def.chance;
            if (target < acc) {
                switch (def.type) {
                    case AIR:
                        return new DropResult(DropType.AIR, 0, 0);
                    case GOLD: {
                        int g = def.goldMax > def.goldMin
                                ? ThreadLocalRandom.current().nextInt(def.goldMin, def.goldMax + 1)
                                : def.goldMin;
                        return new DropResult(DropType.GOLD, g, 0);
                    }
                    default: {
                        int idx = ThreadLocalRandom.current().nextInt(def.itemCodes.size());
                        return new DropResult(DropType.ITEMS, 0, def.itemCodes.get(idx));
                    }
                }
            }
        }
        return null;
    }

    public DropResult roll(int dropId) {
        return rollStatic(tables.get(dropId), ThreadLocalRandom.current().nextDouble());
    }

    /** 掉落次数加成：基础由调用方给 dropQuantity，这里只算 premium + 全局事件。 */
    public int extraDrops(Player player) {
        int extra = 0;
        if (player != null) {
            if (premiumService.getTimeLeft(player.getId(), ItemTimerType.THIRD_EYE) > 0) extra++;
            if (premiumService.getTimeLeft(player.getId(), ItemTimerType.SIXTH_SENSE) > 0
                    && ThreadLocalRandom.current().nextInt(100) < 25) extra++;
            if (premiumService.getTimeLeft(player.getId(), ItemTimerType.S_DROP_BUFF) > 0
                    && ThreadLocalRandom.current().nextInt(100) < 15) extra++;
        }
        return extra + eventExtraDrops;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -o -q -pl pt-game-server -am -Dtest=LootServiceTest test`
Expected: PASS（2 tests）

- [ ] **Step 5: 提交**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/item/LootService.java \
        pt-game-server/src/test/java/org/jpstale/server/game/item/LootServiceTest.java
git commit -m "feat(loot): LootService 加权掉落表 + 热重载"
```

---

### Task S3: `PremiumService` stub

**Files:**
- Create: `pt-game-server/src/main/java/org/jpstale/server/game/service/PremiumService.java`

**Interfaces:**
- Produces: `int getTimeLeft(long playerId, ItemTimerType type)`（本轮恒 0）。

- [ ] **Step 1: 实现 stub**

```java
package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.enums.item.ItemTimerType;
import org.springframework.stereotype.Service;

/**
 * 限时道具（premium）查询接口 —— 本轮 stub。
 * 完整 premium 系统（物品使用 / characteritemtimer 读写 / 登录同步 / 每秒递减 / 落库）另立 spec。
 */
@Slf4j
@Service
public class PremiumService {

    /** 剩余有效秒数；无该 premium 返回 0。 */
    public int getTimeLeft(long playerId, ItemTimerType type) {
        return 0; // TODO(premium-spec): 读 userdb.characteritemtimer
    }
}
```

- [ ] **Step 2: 编译**

Run: `mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/service/PremiumService.java
git commit -m "feat(premium): PremiumService.getTimeLeft stub"
```

---

### Task S4: Monster 掉落字段 + createMonster 写入

**Files:**
- Modify: `pt-game-server/src/main/java/org/jpstale/server/game/model/Monster.java`
- Modify: `pt-game-server/src/main/java/org/jpstale/server/game/service/MonsterSpawnService.java`（`createMonster`，约 279-328 行）

**Interfaces:**
- Produces: `Monster.getDropQuantity()/getDropIsPublic()/getTemplateId()`。
- Consumes: `MonsterList.getDropQuantity()/getDropIsPublic()/getId()`。

- [ ] **Step 1: Monster 增加字段**

在 `Monster.java` 的 `private String modelFile;` 附近加入：

```java
    // 掉落（对齐 monsterlist.dropquantity / dropispublic）
    private int dropQuantity = 1;      // 掉落掷点次数
    private boolean dropIsPublic;      // true=公共可见，false=仅击杀者可见
```

（`templateId` 已由 `BaseEntity`/`Monster` 提供 `setTemplateId`。）

- [ ] **Step 2: createMonster 写入并移除占位金币**

在 `MonsterSpawnService.createMonster` 中，将：

```java
        // 金币：按等级简单推导（后续接 dropitem 精确掉落）
        int lvl = template.getLevel() != null ? template.getLevel() : 1;
        monster.setGold(lvl * ThreadLocalRandom.current().nextInt(5, 15));
```

替换为：

```java
        // 掉落：dropid == monsterlist.id；金币改由 dropitem 的 Gold 行决定（见 CombatService）
        monster.setTemplateId(template.getId());
        monster.setDropQuantity(template.getDropQuantity() != null && template.getDropQuantity() > 0
                ? template.getDropQuantity() : 1);
        monster.setDropIsPublic(template.getDropIsPublic() != null && template.getDropIsPublic() != 0);
```

- [ ] **Step 3: 编译**

Run: `mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/model/Monster.java \
        pt-game-server/src/main/java/org/jpstale/server/game/service/MonsterSpawnService.java
git commit -m "feat(loot): Monster 携带 dropid/dropQuantity/dropIsPublic, 移除占位金币"
```

---

### Task S5: CombatService 接入怪物掉落

**Files:**
- Modify: `pt-game-server/src/main/java/org/jpstale/server/game/service/CombatService.java`

**Interfaces:**
- Consumes: `LootService.roll(int)` / `LootService.extraDrops(Player)`；`ItemRollService.rollByIdCode(int, Integer)`；`GroundItemManager.add(...)`；`MapRegionService.getHeight(...)`。
- Produces: 无（副作用：掉落物入 `GroundItemManager`，金币给击杀者）。

- [ ] **Step 1: 增加注入**

在 `CombatService` 字段区追加：

```java
    @Autowired
    private org.jpstale.server.game.item.LootService lootService;

    @Autowired
    private org.jpstale.server.game.item.ItemRollService itemRollService;

    @Autowired
    private org.jpstale.server.game.item.GroundItemManager groundItems;
```

- [ ] **Step 2: 改造 handleMonsterDeath**

将 `handleMonsterDeath` 中金币段（`int gold = monster.getGold(); killer.setGold(...)`）与末尾 `monsterAOI.onMonsterDeath(..., gold)` 替换为掉落循环：

```java
        // 掉落（对齐 EU OnSetDrop + HandleKill）：dropQuantity + premium/事件加成，逐次掷点
        int numDrops = Math.max(0, monster.getDropQuantity()) + lootService.extraDrops(killer);
        int gold = 0;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < numDrops; i++) {
            org.jpstale.server.game.item.LootService.DropResult dr = lootService.roll(monster.getTemplateId());
            if (dr == null || dr.type == org.jpstale.server.game.item.LootService.DropType.AIR) {
                continue;
            }
            if (dr.type == org.jpstale.server.game.item.LootService.DropType.GOLD) {
                gold += dr.gold;
                continue;
            }
            org.jpstale.server.game.item.ItemInstance item = itemRollService.rollByIdCode(dr.itemCode, null);
            if (item == null) {
                continue;
            }
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 0.3 + rnd.nextDouble() * 1.2;
            double gx = monster.getX() + Math.cos(ang) * dist;
            double gz = monster.getZ() + Math.sin(ang) * dist;
            double gy = mapRegionService.getHeight(monster.getMapId(), gx, gz);
            long ownerId = monster.isDropIsPublic() ? 0L : killer.getId();
            groundItems.add(item, monster.getMapId(), gx, gy, gz, ownerId, 0);
        }
        if (gold > 0) {
            killer.setGold(killer.getGold() + gold);
        }
```

并在文件顶部 `import java.util.concurrent.ThreadLocalRandom;`。原 `log.info("Monster {} killed ... gold={}", ..., gold)` 与 `battleLogService.monsterKilled(..., gold)` 沿用新 `gold` 变量。

- [ ] **Step 3: 编译**

Run: `mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/service/CombatService.java
git commit -m "feat(loot): 怪物死亡按 dropitem 掉落, 金币走 Gold 行"
```

---

### Task S6: GroundItemAOI owner 可见性过滤

**Files:**
- Modify: `pt-game-server/src/main/java/org/jpstale/server/game/service/GroundItemAOI.java`（`reconcile`，约 84-98 行）

**Interfaces:**
- Consumes: `GroundItem.ownerId`。

- [ ] **Step 1: reconcile 增加 owner 过滤**

在 `for (GroundItemManager.GroundItem gi : items)` 循环体开头（`long id = gi.id;` 之后）加入：

```java
            // 非公共掉落（ownerId != 0）仅 owner 可见（对齐 EU SendItemStageUser）
            if (gi.ownerId != 0 && gi.ownerId != pid) {
                if (visible.remove(id)) {
                    session.send(buildDisappear(id));
                }
                continue;
            }
```

（`pid` 为 `session.getCharacterId()` 的 `Long`，已在方法内取得。）

- [ ] **Step 2: 编译**

Run: `mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/service/GroundItemAOI.java
git commit -m "feat(loot): 非公共掉落仅击杀者可见"
```

---

### Task S7: ChatService `/@get` 贴地随机 + `/@reloadloot`

**Files:**
- Modify: `pt-game-server/src/main/java/org/jpstale/server/game/service/ChatService.java`

**Interfaces:**
- Consumes: `MapRegionService.getHeight(int,double,double)`；`LootService.reload()`。

- [ ] **Step 1: 增加注入**

在 `ChatService` 字段区追加：

```java
    @Autowired
    private org.jpstale.server.game.service.MapRegionService mapRegionService;

    @Autowired
    private org.jpstale.server.game.item.LootService lootService;
```

- [ ] **Step 2: `/@reloadloot` 分支**

在 `treatCommand` 的 `if (cmd.startsWith("/@"))` 块内、`@get` 判断之后加入：

```java
            if (name.equals("@reloadloot")) {
                lootService.reload();
                systemMessage(session, "loot table reloaded");
                return;
            }
```

- [ ] **Step 3: treatGet 贴地 + 扩大随机**

将 `treatGet` 中：

```java
        double dist = 0.75 + rnd.nextDouble() * 1.75; // 世界单位（entity 坐标域，≈0.75~2.5 米）
        double nx = ent.getX() + Math.cos(ang) * dist;
        double nz = ent.getZ() + Math.sin(ang) * dist;
        double ny = ent.getY();
```

替换为：

```java
        double dist = 0.5 + rnd.nextDouble() * 29.5; // 世界单位，散布 0.5~30
        double nx = ent.getX() + Math.cos(ang) * dist;
        double nz = ent.getZ() + Math.sin(ang) * dist;
        double ny = mapRegionService.getHeight(ent.getMapId(), nx, nz); // 落点地形高度，避免沉入地下
```

- [ ] **Step 4: 编译**

Run: `mvn -o -q -pl pt-game-server -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add pt-game-server/src/main/java/org/jpstale/server/game/service/ChatService.java
git commit -m "feat(loot): /@get 贴地+散布30, 新增 /@reloadloot"
```

---

### Task C1: 客户端掉落物 overlay 白字名牌 + A 键状态

**Files:**
- Modify: `src/ui/WorldView.ts`

**Interfaces:**
- Produces: `WorldView.toggleGroundItemLabels(): void`。
- Consumes: `drawPill` / `anchorToScreen` / `hoverTarget` / `NAME_TAG_RANGE`（均已存在）。

- [ ] **Step 1: 接口加方法**

在 `export interface WorldView {` 中 `toggleMinimap(): void;` 之后加入：

```ts
  /** 切换"显示附近所有掉落物名牌"（A 键） */
  toggleGroundItemLabels(): void;
```

- [ ] **Step 2: 状态与 toggle 实现**

在 `const groundItems = new Map<number, GroundItemActor>();` 附近加入状态：

```ts
  let groundItemLabelsOn = false;
  function toggleGroundItemLabels(): void { groundItemLabelsOn = !groundItemLabelsOn; }
```

- [ ] **Step 3: GroundItemActor 去掉 Sprite，改存名牌锚高**

将 `GroundItemActor` 的 `label: THREE.Sprite;` 改为 `topY: number;`（名牌锚点相对高度）。

- [ ] **Step 4: spawnGroundItem 移除 Sprite**

把：

```ts
        const label = makeItemLabel(name);
        label.visible = false; // 名牌悬停可见（design-nameplate-hpbar.md）
        label.position.y = model.position.y + modelTopY(model) + 0.55;
        root.add(label);
```

替换为：

```ts
        const topY = model.position.y + modelTopY(model) + 0.55; // overlay 名牌锚点
```

并把 `groundItems.set(...)` 的 `label` 字段改为 `topY`：

```ts
        groundItems.set(groundItemId, { groundItemId, name, root, topY, model, blinkOn: false, mats });
```

- [ ] **Step 5: 删除 makeItemLabel 与 hover 联动**

删除 `makeItemLabel` 函数整体，以及 `syncItemHoverLabels` 函数及其两处调用（`probeCursorAt` 内 `syncItemHoverLabels();`）。hover 显隐改由 Step 6 的 overlay 每帧判断。

- [ ] **Step 6: drawNameplateOverlay 增加掉落物循环**

在 `drawNameplateOverlay` 的 NPC 循环之前加入：

```ts
    // 掉落物：hover 命中 或 A 键开启且在附近范围内 → 白字名牌
    for (const g of groundItems.values()) {
      if (!g.root.visible) continue;
      const hovered = hoverTarget?.root === g.root;
      if (!hovered) {
        if (!groundItemLabelsOn) continue;
        const dx = g.root.position.x - selfPos.x, dz = g.root.position.z - selfPos.z;
        if (dx * dx + dz * dz > NAME_TAG_RANGE * NAME_TAG_RANGE) continue;
      }
      const pt = anchorToScreen(g.root, g.topY);
      if (!pt) continue;
      drawPill(ctx, pt.x, pt.y, g.name || '', {
        nameColor: '#ffffff', showHp: false, ratio: 1, selected: hovered,
      });
    }
```

- [ ] **Step 7: 导出 toggle**

在返回对象中（`toggleMinimap,` 之后）加入 `toggleGroundItemLabels,`。

- [ ] **Step 8: 类型检查**

Run: `npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 9: 手工验证**

`npm run dev` → 进图，鼠标指向掉落物应显示白字名牌；按 A 附近掉落物名牌常显，再按 A 隐藏。

- [ ] **Step 10: 提交**

```bash
git add src/ui/WorldView.ts
git commit -m "feat(ui): 掉落物白字 overlay 名牌 + A 键全显"
```

---

### Task C2: main.ts A 键分支

**Files:**
- Modify: `src/main.ts`（`keyBinding.onKeyDown`，约 201-242 行）

- [ ] **Step 1: 增加 case**

在 `switch (action)` 中加入：

```ts
    case 'showGroundItems':
      worldView.toggleGroundItemLabels();
      break;
```

- [ ] **Step 2: 类型检查**

Run: `npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 手工验证**

进图按 A：掉落物名牌切换显示。

- [ ] **Step 4: 提交**

```bash
git add src/main.ts
git commit -m "feat(ui): A 键切换掉落物名牌"
```

---

### Task C3: 掉落物随机朝向

**Files:**
- Modify: `src/ui/WorldView.ts`（`spawnGroundItem`，约 2408-2410 行）

- [ ] **Step 1: 改为随机朝向**

把：

```ts
        pivot.rotation.y = (Math.floor(x * 256) + Math.floor(z * 256)) >> 2 & 0xFFF;
        pivot.rotation.y = pivot.rotation.y / 0xFFF * Math.PI * 2;
```

替换为：

```ts
        pivot.rotation.y = Math.random() * Math.PI * 2; // 随机水平朝向
```

- [ ] **Step 2: 类型检查**

Run: `npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 3: 手工验证**

`/@get` 多个物品：位置分散（0.5~30）、朝向各异、贴地不沉。

- [ ] **Step 4: 提交**

```bash
git add src/ui/WorldView.ts
git commit -m "feat(ui): 掉落物随机朝向"
```

---

## Self-Review

- **Spec 覆盖**：① 掉落物名牌（C1/C2）②`/@get` 贴地+随机（S7/C3）③ 怪物掉落（S1/S2/S4/S5）④ 掉落数量加成（S2 `extraDrops` + S4 `dropQuantity`）⑤ 非公共可见性（S6）⑥ 热重载（S2 `reload` + S7 `/@reloadloot`）⑦ premium stub（S3）。均有对应任务。
- **类型一致性**：`LootService.DropType/DropResult/roll/extraDrops/reload` 在 S2 定义，S5/S7 按同签名调用；`Monster.getDropQuantity()/isDropIsPublic()` 在 S4 定义，S5 使用；客户端 `toggleGroundItemLabels` 在 C1 定义、C2 调用。
- **无占位符**：所有代码步骤给出完整代码。
