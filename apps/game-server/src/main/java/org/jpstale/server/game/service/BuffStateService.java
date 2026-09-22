package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.common.service.item.ForceOrb;
import org.jpstale.common.service.item.ForceOrbService;
import org.jpstale.common.service.model.Player;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.BuffStateProto;
import org.jpstale.server.proto.base.S2C_BuffState;
import org.jpstale.server.proto.base.ServerMessage;
import org.springframework.stereotype.Service;

/**
 * "生效中的 buff"的唯一生产者（客户端左上角那排圆环图标的数据来源）。
 *
 * <p><b>为什么单独一个类</b>：这段内容有**两个**发送时机 —— ①buff 生效/刷新时（物品使用处）、
 * ②任何一次状态推送时（进图 / 重连 / 升级 / 回血都会走 `PlayerService.sendPlayerStatus`）。
 * 若两边各拼一份 proto，迟早会漂移（AGENTS #15 的"一个判定只能有一份实现"）。
 *
 * <p><b>服务端权威</b>：客户端只按 {@code remaining_ms} 做倒计时绘制，不自行判断 buff 是否生效
 * （效果、面板数值、伤害全部由服务端算）。{@code total_ms} 也由服务端给 —— 客户端<b>不</b>查
 * 时长表去反推比例，免得两端版本不一致时圆环画错却无人发现。
 *
 * <p>目前只有一种 buff：力量石（`Player.forceOrb*`，EU 三张表：伤害/时长/百分比）。
 * 将来加技能 buff 时，**在这里加一支**即可，调用方不用动。
 */
@Slf4j
@Service
public class BuffStateService {

    /** 拼当前生效的 buff 列表（服务端权威，无副作用）。 */
    public S2C_BuffState build(Player p) {
        S2C_BuffState.Builder b = S2C_BuffState.newBuilder();
        if (p == null) {
            return b.build();
        }
        long now = System.currentTimeMillis();
        if (ForceOrbService.active(p)) {
            int code = p.getForceOrbCode();
            int tier = ForceOrb.tierIndexOf(code);
            b.addBuffs(BuffStateProto.newBuilder()
                    .setItemCode(code)
                    // 客户端 `itemDefByCode()` 拿图标。**不新增图标资产** —— 原版没有专门的 buff
                    // 图标资产（`UpKeepItemName[]` 只有文字），所以我们用物品自己的图标。
                    .setItemlistId(0)
                    .setRemainingMs(Math.max(0, p.getForceOrbUntil() - now))
                    .setTotalMs(tier >= 0 ? ForceOrb.durationMs(tier) : 0)
                    .setStack(1));
        }
        return b.build();
    }

    /** 推给某个会话（无会话/未登录时静默跳过，与 `PlayerService.sendPlayerStatus` 同约定）。 */
    public void push(PlayerSession session, Player p) {
        if (session == null || !session.isLoggedIn()) {
            return;
        }
        session.send(ServerMessage.newBuilder().setBuffState(build(p)).build());
    }
}
