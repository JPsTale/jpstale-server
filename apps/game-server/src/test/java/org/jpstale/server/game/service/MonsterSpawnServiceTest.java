package org.jpstale.server.game.service;

import org.jpstale.server.game.model.Monster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MonsterSpawnService#broodOf} 的特征测试 —— 怪物种族映射
 * （monsterlist.**propertymon** 列，用户 2026-09-24 指认 = 原版怪物脚本
 * {@code *몬스터종족} 的数据源，{@code fileread.cpp:4130-4175}）。
 *
 * <p>消费点：Jumping Crash 对 DEMON +30%（{@code Svr_Damge.cpp:2834}）等"对某族加成"系。
 * 库内分布（2026-09-24 实测）：Normal 128 / Demon 117 / Mutant 103 / Undead 92 / Machine 16。
 */
class MonsterSpawnServiceTest {

    @Test
    void 五族映射_大小写不敏感() {
        assertEquals(Monster.Brood.NORMAL, MonsterSpawnService.broodOf("Normal"));
        assertEquals(Monster.Brood.DEMON, MonsterSpawnService.broodOf("Demon"));
        assertEquals(Monster.Brood.DEMON, MonsterSpawnService.broodOf("demon"), "大小写不敏感");
        assertEquals(Monster.Brood.UNDEAD, MonsterSpawnService.broodOf("Undead"));
        assertEquals(Monster.Brood.MUTANT, MonsterSpawnService.broodOf("Mutant"));
        assertEquals(Monster.Brood.MECHANIC, MonsterSpawnService.broodOf("Machine"));
    }

    @Test
    void 缺失与未知值按NORMAL() {
        // 原版脚本缺省（没写 *몬스터종족）就是 NORMAL —— fileread.cpp:4147
        assertEquals(Monster.Brood.NORMAL, MonsterSpawnService.broodOf(null));
        assertEquals(Monster.Brood.NORMAL, MonsterSpawnService.broodOf(""));
        assertEquals(Monster.Brood.NORMAL, MonsterSpawnService.broodOf("  "));
        assertEquals(Monster.Brood.NORMAL, MonsterSpawnService.broodOf("??unknown??"),
                "未知值按缺省 NORMAL（与原版一致），不猜测");
    }
}
