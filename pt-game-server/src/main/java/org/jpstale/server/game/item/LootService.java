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
