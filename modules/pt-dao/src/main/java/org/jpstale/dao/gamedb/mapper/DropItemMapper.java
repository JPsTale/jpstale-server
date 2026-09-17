package org.jpstale.dao.gamedb.mapper;

import org.jpstale.dao.gamedb.entity.DropItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface DropItemMapper extends BaseMapper<DropItem> {

    /** 全部 dropid>0 的掉落行（gamedb.dropitem），按 dropid/chance 排序（XML 实现）。 */
    List<DropItem> selectAllByDropIdGt0();
}
