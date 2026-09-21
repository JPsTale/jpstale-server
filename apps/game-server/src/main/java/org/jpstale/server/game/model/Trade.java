package org.jpstale.server.game.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易数据
 */
@Data
public class Trade {

    private long id;
    private long requesterId;
    private long accepterId;
    private List<TradeItem> requesterItems;
    private List<TradeItem> accepterItems;
    private boolean requesterConfirmed;
    private boolean accepterConfirmed;

    public Trade(long id, long requesterId, long accepterId) {
        this.id = id;
        this.requesterId = requesterId;
        this.accepterId = accepterId;
        this.requesterItems = new ArrayList<>();
        this.accepterItems = new ArrayList<>();
        this.requesterConfirmed = false;
        this.accepterConfirmed = false;
    }

    public boolean isComplete() {
        return requesterConfirmed && accepterConfirmed;
    }

    @Data
    public static class TradeItem {
        private int itemId;
        private int quantity;

        public TradeItem(int itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }
}
