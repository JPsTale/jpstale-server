package org.jpstale.dao.chatdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName(schema = "chatdb", value = "tradechat")
public class TradeChat {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("charactername")
    private String characterName;
    @TableField("message")
    private String message;
    @TableField("unixtime")
    private Integer unixTime;
}
