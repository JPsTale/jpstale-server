package org.jpstale.server.game.service;

import lombok.extern.slf4j.Slf4j;
import org.jpstale.server.game.model.Trade;
import org.jpstale.server.game.network.GameMessageSender;
import org.jpstale.server.game.network.SessionManager;
import org.jpstale.server.game.network.PlayerSession;
import org.jpstale.server.proto.base.MessageProto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 交易服务
 */
@Slf4j
@Service
public class TradeService {

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private GameMessageSender messageSender;

    private final Map<Long, Trade> trades = new ConcurrentHashMap<>();
    private final Map<Long, Long> playerTradeMap = new ConcurrentHashMap<>(); // playerId -> tradeId
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);

    /**
     * 发起交易请求
     */
    public void requestTrade(long requesterId, String targetName) {
        PlayerSession requester = sessionManager.getSessionByCharacterId(requesterId);
        if (requester == null) return;

        PlayerSession target = sessionManager.getSessionByCharacterName(targetName);
        if (target == null) {
            sendError(requesterId, "目标玩家不在线");
            return;
        }

        if (requesterId == target.getCharacterId()) {
            sendError(requesterId, "不能和自己交易");
            return;
        }

        if (playerTradeMap.containsKey(requesterId)) {
            sendError(requesterId, "你已在交易中");
            return;
        }

        long tradeId = tradeIdGenerator.getAndIncrement();
        Trade trade = new Trade(tradeId, requesterId, target.getCharacterId());
        trades.put(tradeId, trade);

        // 发送交易请求
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setTradeRequest(MessageProto.S2C_TradeRequest.newBuilder()
                .setTradeId(tradeId)
                .setRequesterId(requesterId)
                .setRequesterName(requester.getCharacterName())
                .build())
            .build();
        messageSender.sendToPlayer(target.getCharacterId(), msg);

        log.info("Trade request: {} -> {}", requester.getCharacterName(), targetName);
    }

    /**
     * 接受交易
     */
    public void acceptTrade(long playerId, long tradeId) {
        Trade trade = trades.get(tradeId);
        if (trade == null) {
            sendError(playerId, "交易不存在");
            return;
        }

        if (playerTradeMap.containsKey(playerId)) {
            sendError(playerId, "你已在交易中");
            return;
        }

        playerTradeMap.put(trade.getRequesterId(), tradeId);
        playerTradeMap.put(trade.getAccepterId(), tradeId);

        // 发送交易窗口打开
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setTradeOpen(MessageProto.S2C_TradeOpen.newBuilder()
                .setTradeId(tradeId)
                .build())
            .build();
        messageSender.sendToPlayer(trade.getRequesterId(), msg);
        messageSender.sendToPlayer(trade.getAccepterId(), msg);

        log.info("Trade accepted: tradeId={}", tradeId);
    }

    /**
     * 添加交易物品
     */
    public void addItem(long playerId, long tradeId, int itemId, int quantity) {
        Trade trade = trades.get(tradeId);
        if (trade == null) return;

        Trade.TradeItem item = new Trade.TradeItem(itemId, quantity);

        if (playerId == trade.getRequesterId()) {
            trade.getRequesterItems().add(item);
            trade.setRequesterConfirmed(false);
            trade.setAccepterConfirmed(false);
        } else if (playerId == trade.getAccepterId()) {
            trade.getAccepterItems().add(item);
            trade.setRequesterConfirmed(false);
            trade.setAccepterConfirmed(false);
        }

        // 通知双方更新
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setTradeUpdate(MessageProto.S2C_TradeUpdate.newBuilder()
                .setTradeId(tradeId)
                .build())
            .build();
        messageSender.sendToPlayer(trade.getRequesterId(), msg);
        messageSender.sendToPlayer(trade.getAccepterId(), msg);
    }

    /**
     * 确认交易
     */
    public void confirm(long playerId, long tradeId) {
        Trade trade = trades.get(tradeId);
        if (trade == null) return;

        if (playerId == trade.getRequesterId()) {
            trade.setRequesterConfirmed(true);
        } else if (playerId == trade.getAccepterId()) {
            trade.setAccepterConfirmed(true);
        }

        // 检查双方是否都确认
        if (trade.isComplete()) {
            executeTrade(trade);
        }
    }

    /**
     * 执行交易
     */
    private void executeTrade(Trade trade) {
        // TODO: 实际物品和金币交换

        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setTradeComplete(MessageProto.S2C_TradeComplete.newBuilder()
                .setSuccess(true)
                .setMessage("交易完成")
                .build())
            .build();
        messageSender.sendToPlayer(trade.getRequesterId(), msg);
        messageSender.sendToPlayer(trade.getAccepterId(), msg);

        // 清理
        playerTradeMap.remove(trade.getRequesterId());
        playerTradeMap.remove(trade.getAccepterId());
        trades.remove(trade.getId());

        log.info("Trade completed: tradeId={}", trade.getId());
    }

    private void sendError(long playerId, String error) {
        MessageProto.ServerMessage msg = MessageProto.ServerMessage.newBuilder()
            .setError(MessageProto.S2C_Error.newBuilder()
                .setErrorMessage(error)
                .build())
            .build();
        messageSender.sendToPlayer(playerId, msg);
    }
}
