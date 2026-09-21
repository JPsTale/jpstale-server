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
}
