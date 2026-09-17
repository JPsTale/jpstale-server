package org.jpstale.dao.logdb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 *
 * @author pt-dao
 * @since 2026-03-15
 */
@Data
@TableName(schema = "logdb", value = "disconnects")
public class Disconnects {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("ip")
    private String ip;
    @TableField("accountname")
    private String accountName;
    @TableField("netserver")
    private Integer netServer;
    @TableField("keepalive")
    private Integer keepAlive;
    @TableField("servertype")
    private String serverType;
    @TableField("location")
    private String location;
    @TableField("returnaddress")
    private String returnAddress;
    @TableField("returnaddresscall")
    private String returnAddressCall;
    @TableField("date")
    private LocalDateTime date;
}
