package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.gamedb.entity.TeleportDestination;
import org.jpstale.dao.gamedb.mapper.TeleportDestinationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送目的地目录：**物品 idcode → 去哪张图 + 怎么选落点**。
 *
 * 数据在 `gamedb.teleportdestination`（与 maplist/itemlist 同一套 DB 口径，便于以后在后台改），
 * 启动时一次性载入内存（表极小）。idcode 的拆分口径 = **原版 `sinITEM_MASK2`/`MASK3`**：
 * `family = idcode >>> 16`、`code = idcode & 0xFFFF`。
 *
 * 本类只回答"这个物品要去哪"，**不做搬运**（搬运是 `TeleportService.teleport`）、
 * 也**不管代价**（扣道具/冷却由调用方——目前是 `ItemService.useItem`——负责）。
 * 新增一种传送卷轴 = 往表里加一行（见 `docs/传送系统.md`），不用改代码。
 */
@Slf4j
@Service
public class TeleportDestinationCatalog {

    @Autowired
    private TeleportDestinationMapper mapper;

    /** key = family<<16 | code（精确匹配）；familyGeneral = family<<16（该族通用） */
    private final Map<Integer, TeleportDestination> exact = new ConcurrentHashMap<>();
    private final Map<Integer, TeleportDestination> familyGeneral = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (TeleportDestination d : mapper.selectList(null)) {
            if (d.getItemFamily() == null) {
                continue;
            }
            int family = d.getItemFamily();
            if (d.getItemCode() == null) {
                familyGeneral.put(family, d);
            } else {
                exact.put((family << 16) | (d.getItemCode() & 0xFFFF), d);
            }
        }
        log.info("TeleportDestinationCatalog 载入 {} 条精确 + {} 条族通用",
                exact.size(), familyGeneral.size());
    }

    /**
     * 解析物品 idcode 对应的目的地。
     * 先精确匹配（family+code），再退到族通用（family，code=NULL）。
     *
     * @return null = 这个物品不是"固定目的地"类（例如以太核心之外的消耗品、或 Union/Teleport Core 这类
     *         需要玩家选目标/选图的）——调用方据此走各自的分支，**不要**默认送到某个图。
     */
    public TeleportDestination resolve(int idCode) {
        int family = (idCode >>> 16) & 0xFFFF;
        int code = idCode & 0xFFFF;
        TeleportDestination d = exact.get((family << 16) | code);
        return d != null ? d : familyGeneral.get(family);
    }

    /** 供后台/调试：全部条目 */
    public List<TeleportDestination> all() {
        Map<Integer, TeleportDestination> merged = new LinkedHashMap<>(exact);
        merged.putAll(familyGeneral);
        return List.copyOf(merged.values());
    }
}
