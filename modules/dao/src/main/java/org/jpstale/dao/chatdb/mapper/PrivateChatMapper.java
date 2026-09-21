package org.jpstale.dao.chatdb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jpstale.dao.chatdb.entity.PrivateChat;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author pt-dao
 * @since 2026-03-15
 */
public interface PrivateChatMapper extends BaseMapper<PrivateChat> {

    int insertPrivateChat(PrivateChat entity);

}
