package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoPromptTemplateEntity;

/**
 * 提示词模板表 geo_prompt_template 的读写入口：只用 BaseMapper 的增删改查。
 * <p>同一提示词的版本号由数据库唯一键保证不重复；当前版本、校验与事务由领域服务负责。</p>
 */
@Mapper
public interface GeoPromptTemplateDao extends BaseMapper<GeoPromptTemplateEntity> {
}
