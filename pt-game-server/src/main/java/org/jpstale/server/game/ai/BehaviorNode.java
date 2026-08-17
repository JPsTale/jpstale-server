package org.jpstale.server.game.ai;

import org.jpstale.server.game.monster.Monster;

/**
 * 行为树节点抽象类
 */
public abstract class BehaviorNode {

    /**
     * 执行节点逻辑
     *
     * @param monster 怪物
     * @param context AI 上下文
     * @return 节点状态
     */
    public abstract NodeStatus tick(Monster monster, AiContext context);
}
