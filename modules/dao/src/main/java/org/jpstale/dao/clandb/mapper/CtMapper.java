package org.jpstale.dao.clandb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jpstale.dao.clandb.entity.Ct;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author pt-dao
 * @since 2026-03-15
 */
public interface CtMapper extends BaseMapper<Ct> {

    /** ClanSystem ASP: ticket 校验 */
    Integer selectSNoByUserIdAndServerName(@Param("userId") String userId, @Param("serverName") String serverName);

}
