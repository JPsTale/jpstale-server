package org.jpstale.server.game.model;

import lombok.Data;
import org.jpstale.server.game.model.Equipment;
import org.jpstale.server.game.model.Inventory;
import org.jpstale.server.game.network.PlayerSession;

/**
 * 玩家游戏数据
 * 绑定到 PlayerSession，存储游戏状态
 */
@Data
public class Player {

    private final PlayerSession session;
    private final int slotIndex; // 角色槽位 (1-3)

    // 角色基础数据
    private String name;
    private int job;            // 职业
    private int level;
    private int exp;
    private int gold;

    // 属性
    private int strength;
    private int spirit;
    private int talent;
    private int agility;
    private int health;
    private int statePoint; // 未分配的属性点（每级 +5，对齐 exm ReformCharStatePoint）

    // 状态
    private int hp;
    private int maxHp;
    private int mp;
    private int maxMp;
    private int sp;
    private int maxSp;

    // 位置
    private int currentMapId;
    private float x;
    private float y;
    private float z;

    // 物品
    private Inventory inventory;
    private Equipment equipment;

    /** 元素抗性 [8]：0生物 1大地 2火 3冰 4雷 5毒 6水 7风（来自装备实例） */
    private int[] resistances = new int[8];

    public Player(PlayerSession session, int slotIndex) {
        this.session = session;
        this.slotIndex = slotIndex;
        this.inventory = new Inventory();
        this.equipment = new Equipment();
    }

    public long getId() {
        return session.getCharacterId();
    }

    public String getName() {
        return name;
    }

    public int getCurrentMapId() {
        return currentMapId;
    }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }

    public void setX(float x) { this.x = x; }
    public void setY(float y) { this.y = y; }
    public void setZ(float z) { this.z = z; }
}
