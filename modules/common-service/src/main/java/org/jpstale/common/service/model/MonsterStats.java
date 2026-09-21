package org.jpstale.common.service.model;

/**
 * 怪物参与战斗计算所需的属性**快照**（7 个 int）。
 *
 * 共享层（`DamageCalculator`）只认这个记录，不认 game-server 的 `Monster` —— 那是运行时实体
 * （extends BaseEntity，带坐标/AI/刷怪状态），属于 app 层，不能进共享模块。
 * 调用点在 game 侧用 `Monster.combatStats()` 取一次快照传进来。
 *
 * 为什么不写成接口让 Monster 实现：接口会被"顺手加一个只有某个实体有的取值"腐蚀，
 * 而快照是**显式的、有边界的** —— 加字段意味着所有调用点都要重新取快照，改不动就是不该加。
 */
public record MonsterStats(int level, int defense, int absorption,
                           int maxHp, int attackRating, int atkMin, int atkMax) {
}
