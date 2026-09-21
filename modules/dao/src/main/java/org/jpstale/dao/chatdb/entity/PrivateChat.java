package org.jpstale.dao.chatdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName(schema = "chatdb", value = "privatechat")
public class PrivateChat {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("sendername")
    private String senderName;
    @TableField("receivername")
    private String receiverName;
    @TableField("message")
    private String message;
    @TableField("unixtime")
    private Integer unixTime;
}
