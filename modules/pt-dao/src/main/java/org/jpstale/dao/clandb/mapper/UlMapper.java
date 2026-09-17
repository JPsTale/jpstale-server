package org.jpstale.dao.clandb.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jpstale.dao.clandb.entity.Ul;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author pt-dao
 * @since 2026-03-15
 */
public interface UlMapper extends BaseMapper<Ul> {

    String selectClanNameByChName(@Param("chName") String chName);
    Ul selectClanNameAndPermiByChName(@Param("chName") String chName);
    String selectChNameByPermi2AndClanName(@Param("clanName") String clanName);
    List<String> selectChNameListByClanName(@Param("clanName") String clanName);
    String selectUserIdByChNameAndClanName(@Param("chName") String chName, @Param("clanName") String clanName);
    Ul selectByChName(@Param("chName") String chName);
    int insertUl(Ul entity);
    int updatePermi0ByChName(@Param("chName") String chName);
    int updatePermi2ByChName(@Param("chName") String chName);
    int updatePermi0ByClanNameInChName(@Param("chName") String chName);
    int updateMIconCntByChName(@Param("chName") String chName, @Param("mIconCnt") Integer mIconCnt);
    int deleteByChName(@Param("chName") String chName);
    int deleteByClanName(@Param("clanName") String clanName);

    /**
     * 按角色名查公会（名牌显示用）：返回 {clan_name, icon_id} 或 null（无公会）。
     * 注：clandb.ul 真实列名为 clanname/chname/delactive（无下划线），
     * clanlist 图标 join 用 clanname（ul 无 clan_id 列）。
     */
    @Select("""
        SELECT u.clanname AS clan_name, c.iconid AS icon_id
          FROM clandb.ul u
          LEFT JOIN clandb.clanlist c ON c.clanname = u.clanname AND c.deleteactive = 0
         WHERE u.chname = #{chName} AND u.delactive = '0'
         LIMIT 1
        """)
    Map<String, Object> selectClanByChName(@Param("chName") String chName);

}
