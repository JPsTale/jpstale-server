package org.jpstale.server.game.entity;

/**
 * 实体基类(运行时最小通用核)。
 *
 * 对应原版 smCHAR 的"serial + 位置/朝向"子集:
 * - id:全局唯一运行时 ID(EntityIdSource 发号,0 为空)
 * - mapId:归属区域/地图(-1=无)
 * - x/y/z/angle:世界坐标(double,即原版定点 >>FLOATNS 后的域)
 *
 * 派生类负责各自游戏数据(怪模板/玩家属性/地面物),实体不反向依赖驱动者(D12)。
 * 位置/可见性由统一 AOI 按坐标索引,广播按运行时 id。
 */
public abstract class BaseEntity {

    private final long id;
    private volatile int mapId = -1;
    private volatile boolean removed = false;

    /** 世界坐标(与服务端既有 world 域一致);protected:子类(如 Monster.moveTo)可直接读写 */
    protected volatile double x;
    protected volatile double y;
    protected volatile double z;
    /** 朝向(弧度,0=+Z,与服务端玩家/怪一致) */
    protected volatile double angle;

    protected BaseEntity(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }

    public int getMapId() {
        return mapId;
    }

    public void setMapId(int mapId) {
        this.mapId = mapId;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public double getAngle() {
        return angle;
    }

    public void setAngle(double angle) {
        this.angle = angle;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }
}
