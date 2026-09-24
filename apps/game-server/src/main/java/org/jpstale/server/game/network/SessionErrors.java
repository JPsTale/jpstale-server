package org.jpstale.server.game.network;

import org.jpstale.server.proto.base.CommonProto;
import org.jpstale.server.proto.base.S2C_Error;
import org.jpstale.server.proto.base.ServerMessage;

/**
 * 给客户端推一条"业务失败"提示。
 *
 * <p>约定：**服务端只发 i18n key，不发文案**（客户端 `tOr` 翻译；见 `AGENTS.md` 与
 * `jpstale-client/src/i18n`）。这里统一用 {@code UNKNOWN_ERROR} 作为协议层的 ErrorCode ——
 * 现有的三处（`ItemNetworkHandler` / `NpcShopHandler` / `SkillNetworkHandler`）用的都是它，
 * 语义上"具体原因看 key"。
 *
 * <p>为什么抽出来：这段 builder 在三个 handler 里被**逐字复制了三遍**（只差缩进）。
 * 同一个判定/片段出现第二份就是漂移的种子（`AGENTS.md` 纠错 #15），所以公会这批不再添第四份。
 */
public final class SessionErrors {

    private SessionErrors() {
    }

    /** 推一条 key 型错误（不带参数）。 */
    public static void send(PlayerSession session, String key) {
        session.send(ServerMessage.newBuilder()
                .setError(S2C_Error.newBuilder()
                        .setErrorCode(CommonProto.ErrorCode.UNKNOWN_ERROR)
                        .setKey(key)
                        .build())
                .build());
    }
}
