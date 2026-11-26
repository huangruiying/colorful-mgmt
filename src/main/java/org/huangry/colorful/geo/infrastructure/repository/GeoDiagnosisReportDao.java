package org.huangry.colorful.geo.infrastructure.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;

/** 诊断报告表 geo_diagnosis_report 的读写入口：提供 BaseMapper 增删改查；报告状态流转由领域服务负责，事务由调用方负责。 */
@Mapper
public interface GeoDiagnosisReportDao extends BaseMapper<GeoDiagnosisReportEntity> {
}
