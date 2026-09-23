package org.jpstale.server.game.service;

import org.jpstale.server.game.model.Monster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CombatService#playerAttackAllowed} 的特征测试 —— 「玩家不能攻击召唤物」这条规则。
 *
 * <p>
 * 为什么值得一个测试：这条规则有**三条**攻击入口（起手 / 命中帧 / 技能），而且
 * **写错过一次** —— 我最初只挡了"自己的召唤物"（原版那句 `attack = 0` 其实**没有 owner 判断**，
 * 任何人的召唤物都打不了），于是别人的召唤兽点上去就能打（用户 2026-09-23 报的现象）。
 *
 * <p>
 * 反例（若出现则本实现是错的）：把 `playerAttackAllowed` 改回只判 `ownerCharId == 自己`
 * （那需要传入攻击者），或者有人在某一条入口把守卫删掉 —— 后者本测试抓不到（它只测判据本身），
 * 所以三条入口共用这一个判据是有意的：删守卫时改的是调用方，而判据本身仍是唯一实现。
 */
class CombatServiceTest {

    private static Monster monster(boolean summon) {
        Monster m = new Monster(1L);
        m.setHp(100);
        m.setMaxHp(100);
        if (summon) {
            m.setOwnerCharId(42L);      // 有主人 = 召唤物（`Monster.isSummon()` 的唯一判据）
            m.setOwnerEntityId(7L);
            m.setOwnerName("Someone");
        }
        return m;
    }

    @Test
    void 召唤物一律不可被玩家攻击_不管是誰的() {
        assertFalse(CombatService.playerAttackAllowed(monster(true)),
            "召唤物不可攻击：原版 `attack = 0` 没有 owner 判断，非 PkMode 下任何人的召唤物都打不了");
    }

    @Test
    void 普通怪照旧可攻击() {
        assertTrue(CombatService.playerAttackAllowed(monster(false)));
    }

    @Test
    void 空目标不在这里判_由调用方各自处理() {
        // 三条入口对"目标不存在"的**反应各不相同**（起手：广播动作不出计划；命中帧：回 MISS；
        // 技能：直接返回），所以这个判据不对 null 下结论 —— 它只管"召唤物"这一件事。
        assertTrue(CombatService.playerAttackAllowed(null));
    }
}
