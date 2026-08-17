package org.jpstale.server.game.monster;

import lombok.Data;

/**
 * 怪物实体
 */
@Data
public class Monster {

    private long id;
    private int templateId;
    private String name;
    private int level;
    private int hp;
    private int maxHp;
    private int mp;
    private int maxMp;
    private int attack;
    private int defense;
    private float speed;
    private float attackRange;
    private float attackSpeed; // 攻击间隔（毫秒）
    private int mapId;
    private float x;
    private float y;
    private float z;
    private MonsterState state;
    private Long targetPlayerId; // 当前仇恨目标
    private long lastMoveTime;
    private long lastAttackTime;
    private long deathTime;
    private int respawnTime; // 刷新时间（毫秒）
    private int exp; // 击杀经验
    private int gold; // 掉落金币

    public Monster() {
        this.state = MonsterState.IDLE;
        this.attackRange = 2.0f;
        this.attackSpeed = 1000.0f; // 1秒
        this.respawnTime = 30000; // 30秒
    }

    public boolean isAlive() {
        return hp > 0;
    }

    public void takeDamage(int damage) {
        int actualDamage = Math.max(1, damage - defense / 2);
        hp = Math.max(0, hp - actualDamage);
        if (hp == 0) {
            state = MonsterState.DEAD;
            deathTime = System.currentTimeMillis();
        }
    }

    public void heal(int amount) {
        hp = Math.min(maxHp, hp + amount);
    }

    public float distanceTo(float targetX, float targetY, float targetZ) {
        float dx = x - targetX;
        float dy = y - targetY;
        float dz = z - targetZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public void moveTo(float targetX, float targetY, float targetZ, float maxDistance) {
        float dx = targetX - x;
        float dy = targetY - y;
        float dz = targetZ - z;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance <= maxDistance) {
            x = targetX;
            y = targetY;
            z = targetZ;
        } else {
            float ratio = maxDistance / distance;
            x += dx * ratio;
            y += dy * ratio;
            z += dz * ratio;
        }
    }
}
