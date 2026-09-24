package org.jpstale.dao.clandb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jpstale.dao.clandb.entity.Cl;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author pt-dao
 * @since 2026-03-15
 */
public interface ClMapper extends BaseMapper<Cl> {

    Cl selectIdClanZangMemCntByClanName(@Param("clanName") String clanName);
    String selectClanZangByClanName(@Param("clanName") String clanName);
    Integer selectIdByClanName(@Param("clanName") String clanName);
    /** ClanSystem ASP CheckClanLeader.asp: 按族长账号查公会名 */
    String selectClanNameByUserId(@Param("userId") String userId);
    Cl selectClanNameNoteByMIconCnt(@Param("mIconCnt") Integer mIconCnt);
    String selectClanZangByClanNameForCheck(@Param("clanName") String clanName);

    /**
     * 排行榜用：**全表**按公会积分倒序。
     * ⚠ 旧名 `selectByClanNameOrderByCpointDesc` 是错的 —— 这条 SQL **没有任何 clanName 过滤**。
     */
    List<Cl> selectAllOrderByCpointDesc();

    /**
     * 公会详情：按公会名取**整行**。
     * ⚠ 旧名把选出的列一个个念进方法名（`...MemCntNoteMIconCntRegiDate...`），正是它与列名一起过期的
     * 原因 —— 当时漏了 `idx` 与 `cpoint`，导致 clanId/cPoint 恒为 null、rank 恒为 0。
     */
    Cl selectByClanName(@Param("clanName") String clanName);

    int insertCl(Cl entity);
    int updateMemCntByClanName(@Param("memCnt") Integer memCnt, @Param("clanName") String clanName);
    int updateClanZangAndUserIdByClanName(@Param("clanZang") String clanZang, @Param("userId") String userId, @Param("clanName") String clanName);
    int deleteByClanName(@Param("clanName") String clanName);

}
