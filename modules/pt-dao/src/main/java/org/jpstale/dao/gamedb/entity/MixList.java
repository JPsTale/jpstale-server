package org.jpstale.dao.gamedb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@TableName(schema = "gamedb", value = "mixlist")
public class MixList {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    @TableField("mixuniqueid")
    private Integer mixUniqueId;
    @TableField("groupmixid")
    private Integer groupMixId;
    @TableField("typemix")
    private Integer typeMix;
    @TableField("typemixname")
    private String typeMixName;
    @TableField("description")
    private String description;
    @TableField("lucidy")
    private Integer lucidy;
    @TableField("sereneo")
    private Integer sereneo;
    @TableField("fadeo")
    private Integer fadeo;
    @TableField("sparky")
    private Integer sparky;
    @TableField("raident")
    private Integer raident;
    @TableField("transparo")
    private Integer transparo;
    @TableField("murky")
    private Integer murky;
    @TableField("devine")
    private Integer devine;
    @TableField("celesto")
    private Integer celesto;
    @TableField("mirage")
    private Integer mirage;
    @TableField("inferna")
    private Integer inferna;
    @TableField("enigma")
    private Integer enigma;
    @TableField("bellum")
    private Integer bellum;
    @TableField("oredo")
    private Integer oredo;
    @TableField("newsheltom14")
    private Integer newSheltom14;
    @TableField("newsheltom15")
    private Integer newSheltom15;
    @TableField("typeatributte")
    private Integer typeAtributte;
    @TableField("atributte")
    private Double atributte;
    @TableField("peratributte")
    private Integer perAtributte;
    @TableField("typeatributte2")
    private Integer typeAtributte2;
    @TableField("atributte2")
    private Double atributte2;
    @TableField("peratributte2")
    private Integer perAtributte2;
    @TableField("typeatributte3")
    private Integer typeAtributte3;
    @TableField("atributte3")
    private Double atributte3;
    @TableField("peratributte3")
    private Integer perAtributte3;
    @TableField("typeatributte4")
    private Integer typeAtributte4;
    @TableField("atributte4")
    private Double atributte4;
    @TableField("peratributte4")
    private Integer perAtributte4;
    @TableField("typeatributte5")
    private Integer typeAtributte5;
    @TableField("atributte5")
    private Double atributte5;
    @TableField("peratributte5")
    private Integer perAtributte5;
    @TableField("typeatributte6")
    private Integer typeAtributte6;
    @TableField("atributte6")
    private Double atributte6;
    @TableField("peratributte6")
    private Integer perAtributte6;
    @TableField("typeatributte7")
    private Integer typeAtributte7;
    @TableField("atributte7")
    private Double atributte7;
    @TableField("peratributte7")
    private Integer perAtributte7;
    @TableField("typeatributte8")
    private Integer typeAtributte8;
    @TableField("atributte8")
    private Double atributte8;
    @TableField("peratributte8")
    private Integer perAtributte8;
}
