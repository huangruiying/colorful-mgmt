package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一段提示词某一版的数据库记录：哪个提示词、第几版、模板内容。
 * <p>对应表 geo_prompt_template，每次保存新增一行、旧版本保留；同一提示词版本号最大的是当前版本。通过 DAO 持久化，不负责填变量。</p>
 */
@Data
@TableName("geo_prompt_template")
public class GeoPromptTemplateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 提示词标识，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.PromptTemplateKey} 的枚举名称。 */
    private String promptKey;
    /** 同一提示词的版本号，每次保存加 1；版本号最大的是当前版本。 */
    private Integer version;
    /** 提示词模板，{{变量名}} 处由程序填入数据。 */
    private String content;
    /** 由数据库生成的保存时间，插入后重新查询回读。 */
    private LocalDateTime createdAt;
}
