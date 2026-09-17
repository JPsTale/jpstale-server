package org.jpstale.server.game.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 组队数据
 */
@Data
public class Party {

    private long id;
    private long leaderId;
    private List<Long> memberIds;

    public Party(long leaderId) {
        this.leaderId = leaderId;
        this.memberIds = new ArrayList<>();
        this.memberIds.add(leaderId);
    }

    public boolean isFull() {
        return memberIds.size() >= 5;
    }

    public boolean isMember(long playerId) {
        return memberIds.contains(playerId);
    }

    public void addMember(long playerId) {
        if (!memberIds.contains(playerId)) {
            memberIds.add(playerId);
        }
    }

    public void removeMember(long playerId) {
        memberIds.remove(Long.valueOf(playerId));
    }

    public boolean isEmpty() {
        return memberIds.isEmpty();
    }
}
