package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "gamedb", value = "action_field_settings")
public class ActionFieldSettings {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
}
