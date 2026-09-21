package org.jpstale.dao.logdb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jpstale.dao.logdb.entity.EventKillLog;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author pt-dao
 * @since 2026-03-15
 */
public interface EventKillLogMapper extends BaseMapper<EventKillLog> {

    int insertEventKillLog(EventKillLog entity);

}
