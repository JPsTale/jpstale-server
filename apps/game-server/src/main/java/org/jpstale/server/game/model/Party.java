package org.jpstale.server.game.model;

import lombok.Data;
import org.jpstale.server.common.enums.party.PartyMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 组队数据（内存态，不落库——EU PartyInfo 同构：堆上对象、断线即退、无全局持久化）。
 *
 * <p>成员表**队长恒在首位**：客户端队伍窗的队长徽章、协议 {@code S2C_PartyUpdate} 的成员序
 * 都依赖这个约定（EU 用数组顺序表达队长；我们同时带显式 {@code leader} 字段，顺序是冗余保险）。
 */
@Data
public class Party {

    /** 人数上限——EU {@code MAX_PARTY_MEMBERS 6} / NSPT {@code PARTY_PLAYER_MAX 6}，三源一致 */
    public static final int MAX_MEMBERS = 6;

    private final long id;
    private long leaderId;
    /** 队长在首位（见类注释）；增删由 PartyService 维护顺序 */
    private final List<Long> memberIds;
    /** Normal/Hunt 双模式（D4）：影响经验总量% 与（服务端 OnSetDrop 处）Hunt 每杀 +1 掉落 */
    private PartyMode mode = PartyMode.NORMAL;

    public Party(long id, long leaderId) {
        this.id = id;
        this.leaderId = leaderId;
        this.memberIds = new ArrayList<>();
        this.memberIds.add(leaderId);
    }

    public boolean isFull() {
        return memberIds.size() >= MAX_MEMBERS;
    }

    public boolean isMember(long playerId) {
        return memberIds.contains(playerId);
    }
}
