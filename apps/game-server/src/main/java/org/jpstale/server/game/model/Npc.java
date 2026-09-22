package org.jpstale.server.game.model;

import lombok.Getter;
import lombok.Setter;
import org.jpstale.server.game.entity.BaseEntity;
import org.jpstale.server.game.entity.EntityIdSource;

/**
 * NPC 实体（静态站桩）。
 *
 * 由 gamedb.mapnpc（分布）+ gamedb.npclist（定义）加载而来；纯展示，无移动/AI。
 * 坐标域与服务端实体一致（raw 整数）：x=x, y=y, z=-z；angle 弧度（0~4095 → 0~2π）。
 *
 * **身份分两个**（同 Monster 的 monster_id / template_id）：
 *  - {@code npcId}（定义 id = npclist.id）：交互/商店用；同图可有多个同 npcId 的摆放。
 *  - {@code id}（运行时 id = EntityIdSource）：每次摆放唯一，AOI/协议按它区分实例。
 */
@Getter
@Setter
public class Npc extends BaseEntity {

    private int npcId;        // npclist.id（定义 id）
    /**
     * `npclist.eventtype` —— **NPC 提供哪种服务的判据**（用户 2026-09-22 确认）：
     * `4` 力量大师 / `6` 锻造大师 / `11` 合成大师 / `9` 传送（不是打造）。
     * ⚠ 我先前按 NPC **id** 硬编码服务 ✗（那是我用另一条血统的脚本名/模型猜出来的），已改为按本字段判。
     */
    private int eventType;
    private String nameKey;   // 本地化 slug（npclist.name）
    private String modelFile; // 规范化模型路径（char/npc/xxx/xxx.inx）
    /**
     * 来自 `mapnpc.onlygm`：**只有 GM 能交互**（原版 `bGMOnly`；NPC 照常可见、非 GM 点击被拒，
     * 见 `unitinfo.cpp:1731` + `unitserver.cpp:339-345`）。不是"对普通玩家隐藏"。
     */
    private boolean gmOnly;

    public Npc() {
        super(EntityIdSource.nextId());
    }

    public int getEventType() {
        return eventType;
    }

    public void setEventType(int eventType) {
        this.eventType = eventType;
    }
}
