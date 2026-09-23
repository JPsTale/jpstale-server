package org.jpstale.server.common.model;

import lombok.Data;

/**
 * 角色外观（装备 → 3D 模型挂载描述）。
 *
 * **纯数据**：只依赖 JDK 与 Lombok，不碰 protobuf、Netty、Spring —— 这样它才能被
 * common-service（以及将来的 web-server，例如网页端渲染角色预览）使用。
 * 与线上类型的互转在 game-server 的 `AppearanceCodec`（那一层才允许碰 protobuf）。
 *
 * 字段语义与原版 `sinSetCharItem` / `S2C_PlayerAppear.appearance` 一一对应：
 * <ul>
 *   <li>主手(槽1)武器 → weaponDorp / weaponIdcode / weaponPos</li>
 *   <li>副手(槽2)盾/匕首 → offHandDorp / offHandIdcode / offHandKind / offHandPos=2</li>
 *   <li>躯干甲（classItem=ARMOR）→ bodyModel / bodyModelIdcode</li>
 * </ul>
 *
 * ⚠ 字符串字段用 **空串**（不是 null）表示"没有"：原 proto3 的 getter 对未设置的 string 返回 ""，
 * 而 {@code ItemNetworkHandler} 靠"外观是否真的变了"来决定要不要广播（`!app.equals(before)`）——
 * 若这里用 null 而旧值用 ""，任何一次重算都会被判成"变了"，
 * 症状是"随便整理一下背包，角色动画就重播一次"（用户 2026-09-16 实测过同类问题）。
 *
 * 协议里还有一个 `size_level`（体型缩放）字段，但**没有任何代码推导它**（恒为默认值），
 * 故此处不设该字段；将来真要按体型缩放时再加。
 */
@Data
public class CharacterAppearance {

    /** 职业(1-10)，决定骨骼/身体前缀/头前缀/种族 */
    private int classId;
    /** 头型/头模型编号(0-2)，配合 rank 决定头模型 */
    private int head;
    /** 转职阶级(0-7)，决定头模型后缀(a/b/c) */
    private int rank;
    /** 身体模型 dorpItem（如 "da115"）；无则空串 */
    private String bodyModel = "";
    /** 身体模型 idcode（如 33623808） */
    private int bodyModelIdcode;
    /** 武器 dorpItem（如 "WA102"）；无则空串 */
    private String weaponDorp = "";
    /** 武器 idcode，动画匹配按武器类型 */
    private int weaponIdcode;
    /** 武器挂点 modelPosition（2左/4右/0无） */
    private int weaponPos;
    /** 副手 dorpItem（盾 ds* / 匕首 WD* / ws*）；念珠 om* 不挂载故为空串 */
    private String offHandDorp = "";
    /** 副手 idcode */
    private int offHandIdcode;
    /** 副手类型：0=无 1=盾 2=匕首/武器 */
    private int offHandKind;
    /** 副手挂点（2=左） */
    private int offHandPos;

    /**
     * **锻造/合成呼吸发光的输入** —— 原版 `sinSetCharItem`（`playsub.cpp:834-866`）用的那两列，
     * 客户端据此查色表（`agingBlink.ts`：等级 → 行 → 呼吸光颜色 + 第二通道叠加贴图）。
     *
     * <p>为什么放进外观：原版这段就在 `sinSetCharItem`（与"挂哪个模型"同一个函数）里算，
     * 且**只有外观这条链路会把别人的装备信息发给旁观者** —— 客户端没有别人的物品表。
     *
     * <p>服务端**只发原始事实、不持有色表**：色表只在客户端一份（`src/game/data/aging-blink.generated.json`），
     * 免得同一张表两边各存一份、改一边就漂移。取值语义：
     * <ul>
     *   <li>{@code weaponKindCode} = 原版 `ItemKindCode`：1=合成物、2=锻造物（0=普通 ⇒ 不发光）</li>
     *   <li>{@code weaponAgingLevel} = 原版 `ItemAgingNum[0]`：锻造等级；**合成物则是"材料槽+1"**
     *       （原版如此，见 `sinTrade.cpp:5008`）—— 故合成物通常为 0，对应色表行 0</li>
     * </ul>
     * 副手同理（盾/匕首；没有副手件时保持 0）。
     */
    private int weaponKindCode;
    private int weaponAgingLevel;
    private int offHandKindCode;
    private int offHandAgingLevel;
}
