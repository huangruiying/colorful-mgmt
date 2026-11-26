package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 诊断方案里的一道问题：正文、来源、意图、品牌范围、语言、地域和顺序。
 * <p>对应表 geo_diagnosis_plan_question；问题属于单个方案，顺序由 sortOrder 固定。</p>
 */
@Data
@TableName("geo_diagnosis_plan_question")
public class GeoDiagnosisPlanQuestionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private String questionText;
    private String source;
    private String intent;
    private String brandScope;
    private String language;
    /** 问题面向的地域或国家（如美国、东南亚），与语言相互独立；为空表示不分地域。 */
    private String region;
    private Integer sortOrder;
}
