package org.jpstale.common.service.shop;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.entity.NpcList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.jpstale.dao.gamedb.mapper.NpcListMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 商店：商品清单与价格（**只读数据，不碰 schema**）。
 *
 * 数据来源（全是现成的 `gamedb` 列）：
 * - 商品清单 = `npclist.weaponshop / defenseshop / miscshop` 三个**空格分隔的文本列**，
 *   里面是物品的 `codeimg1`（如 `ws108`、`PL101`）—— 原版 `*Shop` 标记的等价物在这里是
 *   "三个列非空即商家"（204 个 NPC 里 72 个是商家）。
 * - 价格 = `itemlist.price`（买入价）。EU 的服务端买入价还有"带特化 ×1.2、按稀有度
 *   `(price/2)*rarity`"，我们没有稀有度数据 → 买入价就是 `price`（差异记在注释里）。
 * - **卖出价照抄 EU `ItemServer::GetItemSellPrice`（`itemserver.cpp:4066-4095`）**：
 *   <pre>
 *     pure = round(price*(dur/maxDur) + (price - price*(dur/maxDur))*0.25f)
 *     sell = min(price/5, pure/5)        // 上限为原价的 20%
 *   </pre>
 *   耐久为 0 时按 1 计（原版：`if(Dur[0]==0)Dur[0]=1`）。原版日服变体用 /4，我们用 EU 的 /5。
 *
 * ⚠ **同一个 `codeimg1` 可能对应两行物品**：其中一件是 **40 级转职任务的武器**（外观与 37 级那把
 * 相同、用来刷怪）。判据 = **取 `questid` 为空的那件**（用户 2026-09-14 说明）；若同码仍有多个
 * 非任务行 → `log.error` 报出并取最小 `id`（确定性，不静默、不随机）；若该码**只有任务行** →
 * 报错并**不上架**（宁可少一件，也不把任务武器卖给玩家）。
 */
@Slf4j
@Lazy
@Service
public class NpcShopService {

    /** 商品类别（对应 npclist 的三列；一个 NPC 可以同时是武器店 + 防具店）。 */
    public static final int KIND_WEAPON = 0;
    public static final int KIND_DEFENSE = 1;
    public static final int KIND_MISC = 2;

    /** 卖出价上限比例的分母（EU：5 ⇒ 最多 20%；原版日服变体是 4 ⇒ 25%）。 */
    private static final int SELL_PRICE_DIV = 5;
    /** 耐久不满时的折算权重（原版 EU：未损部分按 100%、损耗部分按 25%）。 */
    private static final float WORN_WEIGHT = 0.25f;

    /** 一行商品（下发用）。 */
    public record Offer(int itemlistId, String code, String name, long price, int kind) {
    }

    private final NpcListMapper npcListMapper;
    private final ItemListMapper itemListMapper;

    /** npclist.id → 定义 */
    private final Map<Long, NpcList> npcDefs = new ConcurrentHashMap<>();
    /** lower(codeimg1) → 该码的所有候选行（同码多行靠 `questid` 判据选） */
    private final Map<String, List<ItemList>> itemsByCode = new ConcurrentHashMap<>();

    @Autowired
    public NpcShopService(NpcListMapper npcListMapper, ItemListMapper itemListMapper) {
        this.npcListMapper = npcListMapper;
        this.itemListMapper = itemListMapper;
    }

    /**
     * 载入 NPC 定义与物品码索引（两次全表扫描）。
     *
     * ⚠ 两处刻意的设计：
     *  - `@Lazy`：本模块的 bean 会在**两个进程**里被实例化（两个 app 都 scanBasePackages="org.jpstale"），
     *    而 web-server 目前没有商店界面 —— 不该为它付出启动期两次扫表。惰性化后只在首次使用时初始化。
     *  - `try/catch`：扫表失败（库不可达/表缺失）**只记 error 并留空目录**，不让异常冒到容器把启动打断 ——
     *    商店打不开是"功能不可用"，服务起不来是"整个游戏不可用"，两者严重度差一个量级。
     */
    @PostConstruct
    public void init() {
        try {
            for (NpcList n : npcListMapper.selectList(null)) {
                npcDefs.put(n.getId().longValue(), n);
            }
            for (ItemList it : itemListMapper.selectList(null)) {
                if (it.getCodeImg1() == null || it.getCodeImg1().isBlank()) {
                    continue;
                }
                itemsByCode.computeIfAbsent(it.getCodeImg1().trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(it);
            }
            long merchants = npcDefs.values().stream().filter(this::isMerchant).count();
            log.info("[Shop] 载入 {} 个 NPC（其中商家 {}）、{} 个物品码",
                    npcDefs.size(), merchants, itemsByCode.size());
        } catch (Exception e) {
            log.error("[Shop] 载入 NPC/物品码失败 → 商店目录留空（服务本身照常启动）：{}", e.toString(), e);
        }
    }

    /** 三个商店列任一非空即商家（原版没有独立的"是否商人"列）。 */
    public boolean isMerchant(NpcList n) {
        return n != null && (notBlank(n.getWeaponShop()) || notBlank(n.getDefenseShop()) || notBlank(n.getMiscShop()));
    }

    public boolean isMerchant(long npcId) {
        return isMerchant(npcDefs.get(npcId));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 该 NPC 的商品清单（**所有类别合并**，每行的 `kind` 标明类别；空列表表示不是商家或清单解析不出）。
     * 空清单**不是**静默 —— 解析失败的码会 `log.error`。
     */
    public List<Offer> offers(long npcId) {
        NpcList n = npcDefs.get(npcId);
        if (n == null) {
            return List.of();
        }
        List<Offer> out = new ArrayList<>();
        addColumn(out, n.getWeaponShop(), KIND_WEAPON, npcId);
        addColumn(out, n.getDefenseShop(), KIND_DEFENSE, npcId);
        addColumn(out, n.getMiscShop(), KIND_MISC, npcId);
        return out;
    }

    private void addColumn(List<Offer> out, String column, int kind, long npcId) {
        if (!notBlank(column)) {
            return;
        }
        for (String raw : column.trim().split("\\s+")) {
            if (raw.isBlank()) {
                continue;
            }
            ItemList it = resolveCode(raw);
            if (it == null) {
                continue;   // 具体原因已在 resolveCode 里报出
            }
            out.add(new Offer(it.getId().intValue(), it.getCodeImg1(),
                    it.getName() == null ? "" : it.getName(),
                    it.getPrice() == null ? 0L : it.getPrice(), kind));
        }
        log.debug("[Shop] npc={} kind={} 上架 {} 行", npcId, kind, out.size());
    }

    /**
     * 把商品码解析成**该上架的那件**物品；解析不出返回 null（并已 `log.error`）。
     * 判据见类注释：优先 `questid` 为空的行；仍多解取最小 id；只有任务行则不上架。
     */
    public ItemList resolveCode(String code) {
        List<ItemList> rows = itemsByCode.get(code.trim().toLowerCase(Locale.ROOT));
        if (rows == null || rows.isEmpty()) {
            log.error("[Shop] 商品码 '{}' 在 itemlist.codeimg1 里找不到 —— 这一行不会上架", code);
            return null;
        }
        List<ItemList> normal = rows.stream().filter(r -> r.getQuestId() == null || r.getQuestId() == 0).toList();
        if (normal.isEmpty()) {
            // 同码全是任务物品：**不上架**（宁可少一件，也不能把任务武器卖给玩家）
            log.error("[Shop] 商品码 '{}' 只匹配到任务物品（questid 非空，如 id={}）—— 不上架",
                    code, rows.getFirst().getId());
            return null;
        }
        if (normal.size() > 1) {
            ItemList pick = normal.stream().min(Comparator.comparing(ItemList::getId)).orElseThrow();
            log.error("[Shop] 商品码 '{}' 有 {} 个**非任务**候选（ids={}）—— 取最小 id={}；请核对数据",
                    code, normal.size(), normal.stream().map(ItemList::getId).toList(), pick.getId());
            return pick;
        }
        return normal.getFirst();
    }

    /** 买入价（EU：无稀有度数据 ⇒ 就是 `price`；特化 ×1.2 待有该数据再补）。 */
    public static long buyPrice(ItemList it) {
        return it == null || it.getPrice() == null ? 0L : it.getPrice();
    }

    /**
     * 卖出价（EU `GetItemSellPrice`，见类注释）。`durability`/`durabilityMax` 为 0 时按 1 计
     * （原版 `if(Dur[0]==0)Dur[0]=1`）。
     */
    public static long sellPrice(long price, int durability, int durabilityMax) {
        if (price <= 0) {
            return 0;
        }
        int cur = durability > 0 ? durability : 1;
        int max = durabilityMax > 0 ? durabilityMax : 1;
        if (cur > max) {
            cur = max;   // 数据异常时不让卖价超过原价（原版也只做除法，这里显式钳一下）
        }
        float ratio = (float) cur / max;
        long pure = Math.round(price * ratio + (price - price * ratio) * WORN_WEIGHT);
        return Math.min(price / SELL_PRICE_DIV, pure / SELL_PRICE_DIV);
    }

    /** 便于测试：直接注入定义与索引（不碰 DB）。 */
    void primeForTest(Map<Long, NpcList> defs, Map<String, List<ItemList>> byCode) {
        npcDefs.clear();
        npcDefs.putAll(defs);
        itemsByCode.clear();
        itemsByCode.putAll(new HashMap<>(byCode));
    }
}
