package org.jpstale.server.core.impl;

import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.common.struct.packets.PacketVersion;
import org.jpstale.server.core.LogServer;
import org.springframework.stereotype.Service;

/**
 * LogServer 默认实现（登录服侧）。
 */
@Slf4j
@Service
public class LogServerImpl implements LogServer {

    @Override
    public void onLogEx(ChannelHandlerContext ctx, Object user, PacketVersion packet) {
        String charName = ctx.channel().remoteAddress().toString();
        log.info("[{}][{}] PKTHDR_Version. Server full: {}, Version: {}", "LS", charName, packet.isServerFull(), packet.getVersion());
        /**
         * 			std::string charName = CHARACTERSERVER->GetCharacterName( pcUserData );
         *
         * 			if ( charName.empty() )
         * 				charName = pcUserData->pcSocketData->szIP;
         *
         * 			//INFO( "PKTHDR_Version: %s. Server full: %d, Version: %d", charName.c_str(), ( (PacketVersion *)psPacket )->bServerFull, ( (PacketVersion *)psPacket )->iVersion );
         * 			LOGSERVER->OnLogEx( USERDATATOUSER( pcUserData ), ACCLOGID_CharacterLoad, "[%s][%s] PKTHDR_Version. Server full: %d, Version: %d", (GAME_SERVER ? "GS" : "LS"), charName.c_str(), ( (PacketVersion *)psPacket )->bServerFull, ( (PacketVersion *)psPacket )->iVersion );
         */
    }

    @Override
    public void onLogCheat(Object userSession, Object packet) {
        // TODO: 使用具体的 UserSession / PacketLogCheat 类型，对齐 C++ 写入数据库/文件的逻辑。
        log.trace("onLogCheat (TODO) userSession={} packet={}", userSession, packet);
    }
}

