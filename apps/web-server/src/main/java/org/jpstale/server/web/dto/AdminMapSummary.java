package org.jpstale.server.web.dto;

/**
 * 管理端地图列表/详情返回 DTO。
 * 对应 gamedb.map_list 的部分字段。
 */
public class AdminMapSummary {

    private Integer id;
    private String name;
    private String shortName;
    private String typeMap;
    private Integer levelReq;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getTypeMap() {
        return typeMap;
    }

    public void setTypeMap(String typeMap) {
        this.typeMap = typeMap;
    }

    public Integer getLevelReq() {
        return levelReq;
    }

    public void setLevelReq(Integer levelReq) {
        this.levelReq = levelReq;
    }
}

