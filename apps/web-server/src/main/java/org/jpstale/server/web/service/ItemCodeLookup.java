package org.jpstale.server.web.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.jpstale.dao.gamedb.entity.ItemList;
import org.jpstale.dao.gamedb.mapper.ItemListMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **物品码 → 物品** 的解析（管理端唯一实现）。
 *
 * <p>
 * 谁需要它：怪物掉落（`dropitem.items`）与 NPC 商店（`npclist.weaponshop/defenseshop/miscshop`）
 * 是**同一种东西** —— 空格分隔的 `itemlist.codeimg1` 码串。两处各写一份解析，迟早会漂
 * （AGENTS #15）；而且启动期的 game-server 侧也有一份（`LootService.reload()`），
 * 口径必须一致：**大小写不敏感**（实测库里 `WS112` 与 `ws202` 混用）。
 *
 * <p>
 * ⚠ 同名多个 code 时**后者覆盖前者** —— 与 `LootService.reload()` 建 `codeToIdCode` 的覆盖顺序一致，
 * 免得"这一码到底指哪件"两边给出不同答案。
 */
@Service
public class ItemCodeLookup {

    private final ItemListMapper itemListMapper;

    public ItemCodeLookup(ItemListMapper itemListMapper) {
        this.itemListMapper = itemListMapper;
    }

    /** 从若干"空格分隔的码串"里收集码并解析。`Gold` / `Air` 之类非码串由调用方自行处理。 */
    public Map<String, ItemList> byItemsStrings(Collection<String> itemStrings) {
        Set<String> codes = new LinkedHashSet<>();
        if (itemStrings != null) {
            for (String s : itemStrings) {
                if (s == null) continue;
                for (String tok : s.trim().split("\\s+")) {
                    if (!tok.isBlank()) {
                        codes.add(tok.toLowerCase());
                    }
                }
            }
        }
        return byCode(codes);
    }

    /** 码（大小写不敏感）→ 物品；查不到的不在返回里（由调用方决定"认不出"怎么处理）。 */
    public Map<String, ItemList> byCode(Collection<String> codes) {
        Map<String, ItemList> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        // 一次查完：`lower(codeimg1) IN (?,?,…)`（参数化，不走字符串拼接）
        StringBuilder ph = new StringBuilder();
        List<Object> args = new ArrayList<>(codes.size());
        for (String c : codes) {
            if (args.size() > 0) {
                ph.append(",");
            }
            ph.append("{").append(args.size()).append("}");
            args.add(c.toLowerCase());
        }
        QueryWrapper<ItemList> w = new QueryWrapper<>();
        w.apply("lower(codeimg1) IN (" + ph + ")", args.toArray());
        for (ItemList it : itemListMapper.selectList(w)) {
            if (it.getCodeImg1() != null) {
                out.put(it.getCodeImg1().trim().toLowerCase(), it);
            }
        }
        return out;
    }
}
