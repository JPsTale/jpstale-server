package org.jpstale.server.web.clan.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.mq.ClanMessageData;
import org.jpstale.common.mq.CommonMsg;
import org.jpstale.common.mq.RedisMsgListener;
import org.jpstale.common.service.clan.ClanManager;

/**
 * 公会 MQ 消息的公共壳：订阅（{@link RedisMsgListener}）与 **JSON 载荷解析**留在这里，
 * 表逻辑一律调 {@link ClanManager}（common-service，公会表的唯一实现）。
 *
 * <h3>这条链是干什么的</h3>
 * 它**不是**游戏内玩家的入口 —— 玩家的建会/退会走 game-server 的 `C2S_Clan*`
 * （因为要玩家内存态，见 game-server `ClanHandler` 类注释）。这里是 **web 侧（GM/后台）**
 * 触发公会变更的入口。
 *
 * <h3>⚠ 载荷契约（`ClanMessageData`）—— 字段含义按操作类型而变，别按名字猜</h3>
 * <table>
 *   <tr><th>type</th><th>clanName</th><th>charName</th><th>targetName</th><th>其余</th></tr>
 *   <tr><td>CREATE</td><td>公会名</td><td>会长角色</td><td>—</td><td>userId / charType / level = 会长</td></tr>
 *   <tr><td>DISSOLVE</td><td>公会名</td><td>**操作者**角色</td><td>—</td><td>—</td></tr>
 *   <tr><td>INVITE</td><td>公会名</td><td>**操作者**角色</td><td>被邀角色</td><td>targetUserId / targetType / targetLevel = 被邀者</td></tr>
 *   <tr><td>KICK</td><td>公会名</td><td>**操作者**角色</td><td>被踢角色</td><td>—</td></tr>
 *   <tr><td>LEAVE</td><td>公会名</td><td>退会者本人</td><td>—</td><td>—</td></tr>
 *   <tr><td>TRANSFER_LEADER</td><td>公会名</td><td>**操作者**（现任会长）</td><td>新会长</td><td>—</td></tr>
 *   <tr><td>SET_SUB_LEADER / RELEASE_SUB_LEADER</td><td>公会名</td><td>**操作者**</td><td>目标成员</td><td>—</td></tr>
 * </table>
 * ⚠ "charName = 操作者" 是 2026-09-25 定的。上一版把 `charName` 当成**目标**
 * （`subLeaderUpdate(d.getCharName())`），而服务端又不校验操作者 ⇒ 语义无从确认。
 * 现在服务端要判权限，两个角色都必须显式给全。**这条链目前没有任何生产方**；
 * 谁先接（web-admin）就按上表填。
 */
@Slf4j
public abstract class ClanMessageListener implements RedisMsgListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 公会表的唯一实现（common-service）。 */
    protected final ClanManager clanManager;

    protected ClanMessageListener(ClanManager clanManager) {
        this.clanManager = clanManager;
    }

    @Override
    public void onMessage(CommonMsg message) {
        log.info("Clan message received: type={}", message.getType());
        try {
            handle(message.getData());
        } catch (Exception e) {
            log.error("Error handling clan message: type={}", message.getType(), e);
        }
    }

    protected abstract void handle(String data);

    /**
     * JSON 载荷 → 类型化。**解析失败返回 null 并记 error**（调用方直接 return，
     * 不猜、不拿默认值继续做下去）。
     */
    protected static ClanMessageData parse(String data) {
        try {
            return MAPPER.readValue(data, ClanMessageData.class);
        } catch (Exception e) {
            log.error("公会 MQ 载荷解析失败，已丢弃: {}", data, e);
            return null;
        }
    }

    /** 统一记录写操作的结果：**失败必须留痕**（成功那条由 `ClanManager` 自己打日志）。 */
    protected void report(String op, ClanManager.Result result) {
        if (result != ClanManager.Result.OK) {
            log.warn("[Clan/MQ] {} 被拒：{}", op, result);
        }
    }
}
