package org.jpstale.server.game.collision;

import org.jpstale.server.game.model.MapMesh;
import org.jpstale.server.game.service.MapRegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 碰撞系统：碰撞帧 F = 世界坐标（worldX, worldY, worldZ），无需边界转换。
 */
@Component
public class CollisionSystem {

    @Autowired
    private MapRegionService mapRegionService;

    private final Map<Integer, CollisionMesh> meshes = new ConcurrentHashMap<>();

    /** 懒加载地图碰撞网格（无网格返回 null）。 */
    public CollisionMesh mesh(int mapId) {
        return meshes.computeIfAbsent(mapId, id -> {
            MapMesh m = mapRegionService.getMesh(id);
            return m == null ? null : CollisionMesh.fromMapMesh(m);
        });
    }

    /**
     * 权威移动：world 坐标输入/输出。
     * @param angle 弧度，0=+Z（与怪物 GetSin/GetCos 一致）
     * @param step  世界单位步长
     */
    public CollisionMesh.MoveResult move(int mapId, float x, float y, float z, double angle, double step, int bodyWidth) {
        CollisionMesh cm = mesh(mapId);
        if (cm == null) {
            // 无碰撞网格：退化——直线移动 + getHeight 定 Y（保持原行为）
            CollisionMesh.MoveResult r = new CollisionMesh.MoveResult();
            r.x = (float) (x + Math.sin(angle) * step);
            r.z = (float) (z + Math.cos(angle) * step);
            r.y = mapRegionService.getHeight(mapId, r.x, r.z);
            r.collision = false;
            return r;
        }
        // 碰撞帧 F = 世界坐标，直接调用，无需转换
        return cm.checkNextMoveCcd(x, y, z, angle, step, bodyWidth);
    }
}
