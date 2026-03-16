package org.jpstale.dao.userdb.mapper;

import org.apache.ibatis.annotations.Param;
import org.jpstale.dao.userdb.entity.CharacterInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author pt-dao
 * @since 2026-03-15
 */
public interface CharacterInfoMapper extends BaseMapper<CharacterInfo> {

    /** ClanSystem NewClan/DeleteClan: 按角色名更新 ClanId */
    int updateClanIdByName(@Param("name") String name, @Param("clanId") Integer clanId);
}
