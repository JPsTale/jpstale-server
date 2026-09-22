package org.jpstale.common.service.item;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * **派生属性重算器** —— 把"锻造等级 N + 合成配方 id"变成属性加成（`ItemInstance.statMod`）。
 *
 * <p><b>设计（用户 2026-09-22 定）</b>：数据库只存**基准属性** + N + 配方 id；
 * 装备的最终属性在**读取时**算出来（{@code ItemInstance.effective(...)} 会按需触发本类）。
 * 这样：
 * <ul>
 *   <li>改一次锻造/合成规则 ⇒ **所有已存在的装备跟着变对**（不用刷库）；</li>
 *   <li>锻造失败掉级 ⇒ 只要用 N−1 重算一次就**精确**回到原值 —— 不需要原版那套反向减法
 *       （EU `OnDownAge` 的 `DownDamage/DownCritical/DownDefense` 必须把原等级传进去、还要处理取整，见 `ItemStat`）；</li>
 *   <li>写库/读库的映射**完全不用改**（基准字段就是 DB 列，原 getter 没被动过）。</li>
 * </ul>
 *
 * <p><b>重算时机</b>：`ItemInstance` 里一个 dirty 标记 —— 刚加载、改过 N、改过配方时为脏；
 * 下次读有效值时重算一次，之后一直有效（用户要的"防止总是重算"）。全量重算是 O(N) 次加法，很便宜。
 *
 * <p>⚠ 重算**从零开始**（先清空加成再逐级加），所以它是**幂等**的：不存在"减回去"这一步，
 * 也就不会积累误差。
 */
@Slf4j
@Service
public class ItemDerivedStats implements ItemInstance.DerivedStatsResolver {

    private final MixRecipeService recipes;

    public ItemDerivedStats(MixRecipeService recipes) {
        this.recipes = recipes;
    }

    /**
     * 装配到 `ItemInstance`（进程内单例）。**测试也要显式调它**（否则读有效值只会拿到基准 + 一条 error）。
     * 没装配时读有效值会打一条 error（不静默降级，见 `ItemInstance#ensureDerived`）。
     */
    public static void installGlobal(MixRecipeService recipes) {
        ItemInstance.setDerivedResolver(new ItemDerivedStats(recipes));
    }

    @PostConstruct
    void install() {
        ItemInstance.setDerivedResolver(this);
        // ⚠ 这行日志是"合成属性没生效"的第一现场：它必须 > 0，且要能看到下面的单条命中日志。
        //   若这里是 0，说明配方表没载进来（mapper/库连错），而不是"算法不对"。
        int n = recipes.recipes().size();
        // ⚠ 空表 = 合成完全不生效（`byUniqueId` 恒 null）。**必须 ERROR**：这次排查就是因为它是
        //   一行 INFO 里的 0，翻了几轮日志才看见（根因是 Spring 回退到了无参构造，见 MixRecipeService 的注释）。
        if (n == 0) {
            log.error("[ItemStat] 配方表是**空的** —— 合成属性永远不会生效！"
                    + "（多半是 MixRecipeService 的构造函数没标 @Autowired，Spring 回退到了无参构造）");
        } else {
            log.info("[ItemStat] 派生属性重算器已装配：配方表 {} 条（锻造/合成加成读时计算，DB 只存基准值）", n);
        }
    }

    @Override
    public void recompute(ItemInstance it) {
        it.clearModifiers();
        it.setDerivedCraftMask(0);
        it.setDerivedMixEffects(java.util.List.of());

        // ① 锻造：逐级累加（"防御 +5%"这类是**按当前值**涨的，所以必须一级一级来，不能乘 N）
        int levels = Math.max(0, it.getAgingNum());
        for (int levelBefore = 0; levelBefore < levels; levelBefore++) {
            AgeGrowth.apply(it, levelBefore);
        }

        // ② 合成：按配方 id 查配方，把每一组效果作用上去（同一条 `MixEffect.apply`，与预览/真合成共用）
        int recipeId = it.getAgingNum2();
        if (recipeId > 0) {
            MixRecipe recipe = recipes.byUniqueId(recipeId);
            if (recipe == null) {
                // 不静默：配方没了（数据被删/改了 id）会让这件装备少一块属性，必须能看见
                log.warn("[ItemStat] 装备 uid={}（{}）的合成配方 id={} 查不到 —— 只算锻造、不算合成。"
                                + "（配方表共 {} 条；样例 id={}）",
                        it.getId(), it.name(), recipeId, recipes.recipes().size(), recipes.sampleUniqueIds(5));
            } else {
                int mask = 0;
                java.util.List<MixEffect.Applied> applied = new java.util.ArrayList<>();
                for (MixRecipe.Slot slot : recipe.slots()) {
                    if (slot.used()) {
                        applied.add(MixEffect.apply(it, slot, true));   // 返回值就是"这一条效果"（位/key/值/直加）
                        mask |= slot.bit();
                    }
                }
                it.setDerivedMixEffects(java.util.List.copyOf(applied));
                // 掩码同样是**派生**的（客户端靠它标记"哪几行是合成加上去的"，不落库）
                it.setDerivedCraftMask(mask);
                log.info("[ItemStat] uid={}（{}）按配方 {}「{}」重算合成：mask=0x{}",
                        it.getId(), it.name(), recipeId, recipe.description(), Integer.toHexString(mask));
            }
        }
    }
}
