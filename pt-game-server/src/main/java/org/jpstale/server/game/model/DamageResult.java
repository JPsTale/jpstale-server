package org.jpstale.server.game.model;

import lombok.Data;

/**
 * 伤害计算结果
 */
@Data
public class DamageResult {

    private int rawDamage;       // 基础伤害
    private int finalDamage;     // 最终伤害
    private boolean critical;    // 是否暴击
    private boolean missed;      // 是否未命中
    private boolean blocked;     // 是否格挡
    private int healAmount;      // 治疗量

    public static DamageResult miss() {
        DamageResult r = new DamageResult();
        r.missed = true;
        r.finalDamage = 0;
        return r;
    }

    public static DamageResult blocked(int rawDamage) {
        DamageResult r = new DamageResult();
        r.rawDamage = rawDamage;
        r.blocked = true;
        r.finalDamage = 0;
        return r;
    }
}
