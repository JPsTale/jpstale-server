package org.jpstale.server.web.service;

import org.jpstale.dao.gamedb.entity.MapList;
import org.jpstale.dao.gamedb.mapper.MapListMapper;
import org.jpstale.server.web.dto.AdminMapSummary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端：地图相关查询能力。
 *
 * 当前仅提供只读列表 / 单条详情，后续可在此基础上扩展编辑、启用/禁用等操作。
 */
@Service
public class AdminMapService {

    private final MapListMapper mapListMapper;

    public AdminMapService(MapListMapper mapListMapper) {
        this.mapListMapper = mapListMapper;
    }

    /**
     * 查询所有地图的概要信息。
     */
    public List<AdminMapSummary> listAll() {
        List<MapList> entities = mapListMapper.selectList(null);
        return entities.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    /**
     * 根据 ID 查询单张地图，若不存在返回 null。
     */
    public AdminMapSummary findById(Integer id) {
        if (id == null) {
            return null;
        }
        MapList entity = mapListMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        return toSummary(entity);
    }

    private AdminMapSummary toSummary(MapList entity) {
        Objects.requireNonNull(entity, "entity");
        AdminMapSummary dto = new AdminMapSummary();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setShortName(entity.getShortName());
        dto.setTypeMap(entity.getTypeMap());
        dto.setLevelReq(entity.getLevelReq());
        return dto;
    }
}

