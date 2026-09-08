package org.jpstale.server.game.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.dao.userdb.entity.CharacterExpDef;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import org.jpstale.dao.userdb.mapper.CharacterExpDefMapper;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.jpstale.server.game.entity.PlayerEntity;
import org.jpstale.server.game.entity.EntityIdSource;
import org.jpstale.server.game.model.Player;
import org.jpstale.server.game.network.GamePacketHandler;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据服务 — 权威加载角色属性与装备（对齐 ex-machina smCHAR_INFO）
 * <p>
 * 服务端为权威：属性/装备以 DB 为准，客户端上报仅作参考。
 */
@Slf4j
@Service
public class PlayerService {

    @Autowired
    private CharacterInfoMapper characterInfoMapper;

    @Autowired
    private PlayerStatCalculator statCalculator;

    @Autowired
    private CharacterExpDefMapper charExpDefMapper;

    @Autowired
    private org.jpstale.server.game.item.ItemStorageService itemStorage;

    @Autowired
    private org.jpstale.server.game.item.ItemRollService itemRoll;

    /** 等级 → 升到该级所需总经验（characterexpdef / ExpLevelTable） */
    private final Map<Integer, Long> expTable = new ConcurrentHashMap<>();

    @PostConstruct
    void loadExpTable() {
        for (CharacterExpDef e : charExpDefMapper.selectList(null)) {
            expTable.put(e.getLevel(), e.getExpRequired());
        }
        log.info("Loaded exp table: {} levels", expTable.size());
    }

    /** 达到 level 所需累计经验；无记录返回 0 */
    public long getExpForLevel(int level) {
        Long v = expTable.get(level);
        return v != null ? v : 0;
    }

    /**
     * 从累计经验反算等级（对齐原版 GetLevelFromExp：经验落在 [expTable[L], expTable[L+1]) → L）
     */
    public int getLevelFromExp(long exp) {
        int level = 1;
        for (int l = 2; l <= 127; l++) {
            Long req = expTable.get(l);
            if (req != null && exp >= req) {
                level = l;
            } else {
                break;
            }
        }
        return level;
    }

    /**
     * 重算面板（升级/分配属性后调用）：最大 HP/MP/SP，并收敛当前值
     */
    public void recalcPanel(Player p) {
        // 失效派生属性缓存（升级/属性分配/装备变化后重建；stats 惰性一次全量计算）
        statCalculator.invalidate(p);
        p.setMaxHp(statCalculator.maxHp(p));
        p.setMaxMp(statCalculator.maxMp(p));
        p.setMaxSp(statCalculator.maxSp(p));
        if (p.getHp() > p.getMaxHp()) p.setHp(p.getMaxHp());
        if (p.getMp() > p.getMaxMp()) p.setMp(p.getMaxMp());
        if (p.getSp() > p.getMaxSp()) p.setSp(p.getMaxSp());
    }

    /** characterId -> Player（在线玩家） */
    private final Map<Long, Player> players = new ConcurrentHashMap<>();

    /**
     * 获取玩家；不存在则从 DB 权威加载（属性 + 装备）
     */
    public Player getOrCreate(PlayerSession session) {
        return players.computeIfAbsent(session.getCharacterId(), id -> load(session));
    }

    public Player getPlayer(PlayerSession session) {
        return players.get(session.getCharacterId());
    }

    // ======== 在线 PlayerEntity(D11/实体化) ========

    /** charId → 在线玩家实体 */
    private final Map<Long, PlayerEntity> entities = new ConcurrentHashMap<>();

    /**
     * 确保该会话存在对应 PlayerEntity(进图后调用);不存在则创建并挂到 session.entity。
     */
    public PlayerEntity ensureEntity(PlayerSession session) {
        if (session == null || session.getCharacterId() == null) {
            return null;
        }
        Long cid = session.getCharacterId();
        PlayerEntity entity = entities.get(cid);
        if (entity == null) {
            Player p = getOrCreate(session);
            entity = new PlayerEntity(EntityIdSource.nextId(), cid, p, session);
            entities.put(cid, entity);
            session.setEntity(entity);
            log.info("PlayerEntity created: char={} runtimeId={}", cid, entity.getId());
        }
        return entity;
    }

    /** 会话对应实体;未 ensure 过则 null */
    public PlayerEntity getEntity(PlayerSession session) {
        return session != null && session.getCharacterId() != null
            ? entities.get(session.getCharacterId())
            : null;
    }

    /** 移除在线实体(下线/踢号) */
    public void removeEntity(long characterId) {
        PlayerEntity entity = entities.remove(characterId);
        if (entity != null && entity.getSession() != null) {
            entity.getSession().setEntity(null);
        }
    }

    public void removePlayer(long characterId) {
        players.remove(characterId);
    }

    /** 下线存档：先持久化玩家数据，再移出缓存 */
    public void persistAndRemove(long characterId) {
        Player p = players.get(characterId);
        if (p != null) {
            persistStats(p);
            log.info("Player {} saved on exit", p.getName());
        }
        players.remove(characterId);
        removeEntity(characterId);
    }

    /**
     * 角色信息面板（对齐 exm sinCharStatus 完整字段）
     */
    public java.util.Map<String, Object> characterPanel(Player p) {
        int[] base = statCalculator.baseAttack(p);
        // 下一级所需经验（升到 level+1 的阈值），1 级显示 1000 而非 0
        long nowExp = getExpForLevel(p.getLevel() + 1);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("job", p.getJob());
        m.put("level", p.getLevel());
        m.put("exp", p.getExp());
        m.put("nextExp", nowExp);
        m.put("gold", p.getGold());
        m.put("strength", p.getStrength());
        m.put("spirit", p.getSpirit());
        m.put("talent", p.getTalent());
        m.put("agility", p.getAgility());
        m.put("health", p.getHealth());
        m.put("statePoint", p.getStatePoint());
        m.put("hp", p.getHp());
        m.put("maxHp", p.getMaxHp());
        m.put("mp", p.getMp());
        m.put("maxMp", p.getMaxMp());
        m.put("sp", p.getSp());
        m.put("maxSp", p.getMaxSp());
        m.put("attackMin", base[0]);
        m.put("attackMax", base[1]);
        m.put("attackRating", statCalculator.attackRating(p));
        m.put("defense", statCalculator.defense(p));
        m.put("absorption", statCalculator.absorption(p));
        m.put("moveSpeed", statCalculator.moveSpeedStat(p));
        m.put("walkSpeed", statCalculator.walkSpeed(p));
        m.put("runSpeed", statCalculator.runSpeed(p));
        m.put("attackSpeed", statCalculator.attackSpeed(p));
        m.put("critical", statCalculator.criticalHit(p));
        m.put("block", statCalculator.blockChance(p));
        m.put("shootingRange", statCalculator.shootingRange(p));
        m.put("maxWeight", statCalculator.maxWeight(p));
        m.put("totalStatPoints", PlayerStatCalculator.totalStatPoints(p.getLevel()));
        // 元素抗性（面板显示 5 个：生物/毒/火/雷/冰，exm 显示顺序 [0][5][2][4][3]）
        int[] res = p.getResistances() != null ? p.getResistances() : new int[8];
        m.put("resBionic", res[0]);
        m.put("resPoison", res[5]);
        m.put("resFire", res[2]);
        m.put("resLightning", res[4]);
        m.put("resIce", res[3]);
        return m;
    }

    /**
     * 构建 S2C_PlayerState（HUD 低帧状态通道）：
     * hp/mp/sp/level/gold/exp + 位置 + 名字，进图/血量变化时推送。
     */
    public MessageProto.S2C_PlayerState.Builder buildPlayerState(Player p) {
        PlayerEntity entity = p.getSession() != null ? p.getSession().getEntity() : null;
        float x = 0, y = 0, z = 0;
        int mapId = 0;
        if (entity != null) {
            x = (float) entity.getX();
            y = (float) entity.getY();
            z = (float) entity.getZ();
            mapId = entity.getMapId();
        }
        return MessageProto.S2C_PlayerState.newBuilder()
            .setPlayerId(p.getId())
            .setMapId(mapId)
            .setPosition(CommonProto.Position.newBuilder().setX(x).setY(y).setZ(z))
            .setHp(p.getHp()).setMp(p.getMp())
            .setMaxHp(p.getMaxHp()).setMaxMp(p.getMaxMp())
            .setSp(p.getSp()).setMaxSp(p.getMaxSp())
            .setLevel(p.getLevel())
            .setGold(p.getGold())
            .setExp(p.getExp())
            .setNextExp(getExpForLevel(p.getLevel() + 1))
            .setMoveSpeed(statCalculator.moveSpeedStat(p))
            .setWalkSpeed((int) statCalculator.walkSpeed(p))
            .setRunSpeed((int) statCalculator.runSpeed(p))
            .setPlayerName(p.getName() != null ? p.getName() : "");
    }

    /**
     * 构建 S2C_CharacterStatus（角色信息面板完整数据），字段与 characterPanel 一致。
     */
    public MessageProto.S2C_CharacterStatus.Builder buildCharacterStatus(Player p) {
        int[] base = statCalculator.baseAttack(p);
        int[] res = p.getResistances() != null ? p.getResistances() : new int[8];
        return MessageProto.S2C_CharacterStatus.newBuilder()
            .setPlayerId(p.getId())
            .setName(p.getName() != null ? p.getName() : "")
            .setJob(p.getJob())
            .setLevel(p.getLevel())
            .setExp(p.getExp())
            .setNextExp(getExpForLevel(p.getLevel() + 1))
            .setGold(p.getGold())
            .setStrength(p.getStrength()).setSpirit(p.getSpirit())
            .setTalent(p.getTalent()).setAgility(p.getAgility()).setHealth(p.getHealth())
            .setStatePoint(p.getStatePoint())
            .setTotalStatPoints(PlayerStatCalculator.totalStatPoints(p.getLevel()))
            .setHp(p.getHp()).setMaxHp(p.getMaxHp())
            .setMp(p.getMp()).setMaxMp(p.getMaxMp())
            .setSp(p.getSp()).setMaxSp(p.getMaxSp())
            .setAttackMin(base[0]).setAttackMax(base[1])
            .setAttackRating(statCalculator.attackRating(p))
            .setDefense(statCalculator.defense(p))
            .setAbsorption(statCalculator.absorption(p))
            .setMoveSpeed(statCalculator.moveSpeedStat(p))
            .setWalkSpeed((int) statCalculator.walkSpeed(p))
            .setRunSpeed((int) statCalculator.runSpeed(p))
            .setAttackSpeed(statCalculator.attackSpeed(p))
            .setCritical(statCalculator.criticalHit(p))
            .setBlock(statCalculator.blockChance(p))
            .setAvoid(statCalculator.avoidChance(p))
            .setShootingRange(statCalculator.shootingRange(p))
            .setMaxWeight(statCalculator.maxWeight(p))
            .setResBionic(res[0]).setResPoison(res[5])
            .setResFire(res[2]).setResLightning(res[4]).setResIce(res[3])
            .setHpRegen((float) statCalculator.regenHp(p))
            .setMpRegen((float) statCalculator.regenMp(p))
            .setStmRegen((float) statCalculator.stmRegenTotal(p));
    }

    /**
     * 推送玩家完整状态：S2C_PlayerState（HUD）+ S2C_CharacterStatus（角色面板）。
     * 进图 / 属性分配 / 战斗血量变化后调用。
     */
    public void sendPlayerStatus(PlayerSession session, Player p) {
        if (session == null || !session.isLoggedIn()) {
            return;
        }
        session.send(MessageProto.ServerMessage.newBuilder()
            .setPlayerState(buildPlayerState(p))
            .build());
        session.send(MessageProto.ServerMessage.newBuilder()
            .setCharacterStatus(buildCharacterStatus(p))
            .build());
    }

    /**
     * 报文入口：属性分配（服务端权威）。
     * C2S_AllocateStat{stat, points}: stat 为 strength/spirit/talent/agility/health，
     * 或撤销标记 "undo"（回退最近一次分配）。points>0 分配、points<0 从指定属性撤回
     * |points| 点（供面板快速 −1/−10/−100）。成功后回推 PlayerState+CharacterStatus。
     */
    @GamePacketHandler(MessageProto.ClientMessage.ALLOCATE_STAT_FIELD_NUMBER)
    public void handleAllocateStat(PlayerSession session, MessageProto.ClientMessage message) {
        if (session == null || !session.isPlaying() || session.getCharacterId() == null) {
            return;
        }
        Player p = getPlayer(session);
        if (p == null) {
            return;
        }
        MessageProto.C2S_AllocateStat req = message.getAllocateStat();
        int pts = req.getPoints();
        boolean ok = "undo".equals(req.getStat())
            ? undoStat(p)
            : pts < 0 ? deductStat(p, req.getStat(), -pts)
                      : allocateStat(p, req.getStat(), pts > 0 ? pts : 1);

        if (ok) {
            recalcPanel(p);
            sendPlayerStatus(session, p);
        } else {
            session.send(MessageProto.ServerMessage.newBuilder()
                .setError(MessageProto.S2C_Error.newBuilder()
                    .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                    .setErrorMessage("属性分配失败：属性点不足或可撤销历史为空")
                    .build())
                .build());
        }
    }

    /**
     * 权威落库：经验/金币/属性/属性点写回 characterinfo
     */
    public void persistStats(Player player) {
        CharacterInfo info = characterInfoMapper.selectById(player.getId());
        if (info == null) {
            return;
        }
        info.setExperience((long) player.getExp());
        info.setGold(player.getGold());
        info.setLevel(player.getLevel());
        info.setStrength(player.getStrength());
        info.setSpirit(player.getSpirit());
        info.setTalent(player.getTalent());
        info.setAgility(player.getAgility());
        info.setHealth(player.getHealth());
        info.setStatePoint(player.getStatePoint());
        characterInfoMapper.updateById(info);
    }

    /**
     * 属性分配（对齐原版：属性 += N, StatePoint -= N；面板按职业公式重算）
     *
     * @return 成功与否
     */
    public boolean allocateStat(Player player, String stat, int points) {
        if (points <= 0 || player.getStatePoint() < points) {
            return false;
        }
        switch (stat) {
            case "strength" -> player.setStrength(player.getStrength() + points);
            case "spirit" -> player.setSpirit(player.getSpirit() + points);
            case "talent" -> player.setTalent(player.getTalent() + points);
            case "agility" -> player.setAgility(player.getAgility() + points);
            case "health" -> player.setHealth(player.getHealth() + points);
            default -> {
                return false;
            }
        }
        player.setStatePoint(player.getStatePoint() - points);

        // 记录分配历史（撤销用，最多 5 次）
        player.pushStatAlloc(stat);

        // 重算面板（原版 ReformCharForm）
        player.setMaxHp(statCalculator.maxHp(player));
        player.setMaxMp(statCalculator.maxMp(player));
        player.setMaxSp(statCalculator.maxSp(player));

        persistStats(player);
        log.info("Player {} allocated {} to {}, statePoint left {}", player.getName(), points, stat, player.getStatePoint());
        return true;
    }

    /**
     * 撤销最近一次属性分配（对齐原版面板第 6 个箭头：恢复上次加点）。
     * 历史为空（面板会话外）返回 false。
     */
    public boolean undoStat(Player player) {
        String stat = player.pollLastStatAlloc();
        if (stat == null) {
            return false;
        }
        switch (stat) {
            case "strength" -> player.setStrength(Math.max(1, player.getStrength() - 1));
            case "spirit" -> player.setSpirit(Math.max(1, player.getSpirit() - 1));
            case "talent" -> player.setTalent(Math.max(1, player.getTalent() - 1));
            case "agility" -> player.setAgility(Math.max(1, player.getAgility() - 1));
            case "health" -> player.setHealth(Math.max(1, player.getHealth() - 1));
            default -> {
                log.warn("Player {} undo with invalid history stat: {}", player.getName(), stat);
                return false;
            }
        }
        player.setStatePoint(player.getStatePoint() + 1);

        // 重算面板（原版 ReformCharForm）
        player.setMaxHp(statCalculator.maxHp(player));
        player.setMaxMp(statCalculator.maxMp(player));
        player.setMaxSp(statCalculator.maxSp(player));

        persistStats(player);
        log.info("Player {} undo {}+1, statePoint back to {}", player.getName(), stat, player.getStatePoint());
        return true;
    }

    /**
     * 从指定属性撤回 points 点（面板快速 −1/−10/−100）。下限保护：不扣到低于 1。
     * 撤回点数全部回到 StatePoint，同样重算面板并落库。
     */
    public boolean deductStat(Player player, String stat, int points) {
        if (points <= 0) {
            return false;
        }
        int cur = switch (stat) {
            case "strength" -> player.getStrength();
            case "spirit" -> player.getSpirit();
            case "talent" -> player.getTalent();
            case "agility" -> player.getAgility();
            case "health" -> player.getHealth();
            default -> -1;
        };
        if (cur < 0) {
            return false;
        }
        int delta = Math.min(points, cur - 1);
        if (delta <= 0) {
            return false;
        }
        switch (stat) {
            case "strength" -> player.setStrength(cur - delta);
            case "spirit" -> player.setSpirit(cur - delta);
            case "talent" -> player.setTalent(cur - delta);
            case "agility" -> player.setAgility(cur - delta);
            case "health" -> player.setHealth(cur - delta);
            default -> { return false; }
        }
        player.setStatePoint(player.getStatePoint() + delta);

        player.setMaxHp(statCalculator.maxHp(player));
        player.setMaxMp(statCalculator.maxMp(player));
        player.setMaxSp(statCalculator.maxSp(player));

        persistStats(player);
        log.info("Player {} deducted {}-{}, statePoint back to {}", player.getName(), stat, delta, player.getStatePoint());
        return true;
    }

    private Player load(PlayerSession session) {
        CharacterInfo info = characterInfoMapper.selectById(session.getCharacterId());
        if (info == null) {
            log.warn("Player load failed: character {} not found", session.getCharacterId());
            return null;
        }

        Player p = new Player(session, 0);
        p.setCharacterId(info.getId().longValue());
        p.setName(info.getName());
        p.setJob(info.getJobCode() != null ? info.getJobCode() : 0);
        p.setHead(info.getHead() != null ? info.getHead() : 0);
        p.setRank(info.getRank() != null ? info.getRank() : 0);
        p.setLevel(info.getLevel() != null ? info.getLevel() : 1);
        p.setExp(info.getExperience() != null ? info.getExperience() : 0L);
        p.setGold(info.getGold() != null ? info.getGold() : 0);

        // 属性（权威，DB 为准）：1级职业固定分配 + 玩家分配点；StatePoint 为未分配点
        p.setStrength(info.getStrength() != null ? info.getStrength() : 10);
        p.setSpirit(info.getSpirit() != null ? info.getSpirit() : 10);
        p.setTalent(info.getTalent() != null ? info.getTalent() : 10);
        p.setAgility(info.getAgility() != null ? info.getAgility() : 10);
        p.setHealth(info.getHealth() != null ? info.getHealth() : 10);
        p.setStatePoint(info.getStatePoint() != null ? info.getStatePoint() : 0);

        // 面板（原版公式，服务端权威）：HP/MP/SP 由职业系数 + 属性实时计算
        recalcPanel(p);
        p.setHp(p.getMaxHp());
        p.setMp(p.getMaxMp());
        p.setSp(p.getMaxSp());

        // 物品权威装载：背包/仓库/装备/备用武器 全部活行 → items + 重建画布位图 + 抗性
        loadItems(p);

        log.info("Player {} (lv{}) loaded: str={} spi={} tal={} agi={} hea={} stateP={} hp={} items={}",
            p.getName(), p.getLevel(), p.getStrength(), p.getSpirit(), p.getTalent(),
            p.getAgility(), p.getHealth(), p.getStatePoint(), p.getMaxHp(), p.getItems().byUidCount());
        return p;
    }

    /**
     * 装载玩家全部活物品（delete_time IS NULL）：背包/仓库/装备/备用武器，
     * 转换 ItemInstance（补模板）→ index 进 items → 重建位图 → 汇总元素抗性。
     */
    private void loadItems(Player player) {
        int cid = Math.toIntExact(player.getId());
        java.util.List<org.jpstale.dao.userdb.entity.Item> rows = itemStorage.loadActiveRows(cid);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        org.jpstale.server.game.item.PlayerItems items = player.getItems();
        int[] res = new int[8];
        for (org.jpstale.dao.userdb.entity.Item row : rows) {
            org.jpstale.server.game.item.ItemInstance it = itemStorage.fromRow(row);
            Integer itemListId = row.getItemListId() != null ? row.getItemListId() : row.getItemCode();
            if (itemListId != null) {
                org.jpstale.dao.gamedb.entity.ItemList def = itemRoll.itemListById(itemListId);
                it.setTemplate(def);
            }
            items.index(it);
            // 元素抗性（EElementID: 0生物 1大地 2火 3冰 4雷 5毒 6水 7风）
            if (it.getLocation() == org.jpstale.server.game.item.ItemLocations.EQUIP) {
                res[0] += it.getResBionic();
                res[1] += it.getResEarth();
                res[2] += it.getResFire();
                res[3] += it.getResIce();
                res[4] += it.getResLighting();
                res[5] += it.getResPoison();
                res[6] += it.getResWater();
                res[7] += it.getResWind();
            }
        }
        items.rebuildBitmaps();
        player.setResistances(res);
        items.markClean();
        log.info("Player {} items loaded: {} rows", player.getName(), rows.size());
    }
}
