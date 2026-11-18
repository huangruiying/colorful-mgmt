-- 诊断报告两张表改名，保留已有数据（2026-11-18 用户确认）：页面上独立管理的对象用短名，从属的表带所属对象前缀。
--   geo_diagnosis_task_report          → geo_diagnosis_report                  （针对一次诊断任务生成的报告）
--   geo_diagnosis_task_result_analysis → geo_diagnosis_report_result_analysis  （报告中对每条诊断结果的分析）
-- 列不变；索引与约束名称同步改名，执行后与 001_init.sql 新建的表结构一致。新库直接使用 001_init.sql，无需执行本脚本。
-- 可直接对未部署本分支的库执行：第 0 步自动核对，不满足时报错停止且未做任何修改；第 1 步按需补上本分支更早的两次加列。
-- 执行后重启为新代码。执行到重启之间，仍在运行的旧服务读写报告会报错；后台正在生成的报告保持生成中，新代码启动后续跑。
SET NAMES utf8mb4;

-- 0. 自动核对：两张旧表、6 个旧约束、4 个旧索引都在，新表名还不存在，才继续。
--    结构语句不能回滚，不满足时准备一条查询“提示名”表的语句，让 mysql 客户端报错停止，避免改到一半；表名即提示
SET @precheck_ok = (
        (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME IN ('geo_diagnosis_task_report','geo_diagnosis_task_result_analysis')) = 2
    AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME IN ('geo_diagnosis_report','geo_diagnosis_report_result_analysis')) = 0
    AND (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME IN
           ('fk_diagnosis_task_result_analysis_report','fk_diagnosis_task_result_analysis_result','chk_diagnosis_task_result_analysis_status',
            'chk_diagnosis_task_result_analysis_sentiment','fk_diagnosis_task_report_task','chk_diagnosis_task_report_status')) = 6
    AND (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME IN
           ('idx_diagnosis_task_report_task','uk_diagnosis_task_result_analysis','idx_diagnosis_task_result_analysis_status',
            'idx_diagnosis_task_result_analysis_result')) = 4);
SET @precheck_sql = IF(@precheck_ok, 'DO 0', 'SELECT 1 FROM `前提不满足_可能已改过名或约束名不符_未做任何修改`');
PREPARE precheck_stmt FROM @precheck_sql;
EXECUTE precheck_stmt;
DEALLOCATE PREPARE precheck_stmt;

-- 1. 补上本分支更早的两次加列（2026-11-15 问题地域、2026-11-16 报告语言），已有则跳过；报告语言须在改名前加在旧表上
SET @region_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'geo_diagnosis_plan_question' AND COLUMN_NAME = 'region'),
    'DO 0',
    'ALTER TABLE geo_diagnosis_plan_question ADD COLUMN region VARCHAR(50) NULL COMMENT ''问题面向的地域或国家；为空表示不分地域'' AFTER language');
PREPARE region_stmt FROM @region_sql;
EXECUTE region_stmt;
DEALLOCATE PREPARE region_stmt;
SET @language_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'geo_diagnosis_task_report' AND COLUMN_NAME = 'report_language'),
    'DO 0',
    'ALTER TABLE geo_diagnosis_task_report ADD COLUMN report_language VARCHAR(50) NOT NULL DEFAULT ''中文'' COMMENT ''报告解析内容的语言；问题、回答、引用等原文不翻译'' AFTER prompt_version');
PREPARE language_stmt FROM @language_sql;
EXECUTE language_stmt;
DEALLOCATE PREPARE language_stmt;

-- 2. 删除两张表的外键和检查约束：约束名带旧表名，需删后按新名重建
ALTER TABLE geo_diagnosis_task_result_analysis
    DROP FOREIGN KEY fk_diagnosis_task_result_analysis_report,
    DROP FOREIGN KEY fk_diagnosis_task_result_analysis_result,
    DROP CHECK chk_diagnosis_task_result_analysis_status,
    DROP CHECK chk_diagnosis_task_result_analysis_sentiment;
ALTER TABLE geo_diagnosis_task_report
    DROP FOREIGN KEY fk_diagnosis_task_report_task,
    DROP CHECK chk_diagnosis_task_report_status;

-- 3. 两张表改名
RENAME TABLE geo_diagnosis_task_report TO geo_diagnosis_report,
             geo_diagnosis_task_result_analysis TO geo_diagnosis_report_result_analysis;

-- 4. 诊断报告：索引改名并重建约束
ALTER TABLE geo_diagnosis_report
    RENAME INDEX idx_diagnosis_task_report_task TO idx_diagnosis_report_task;
-- 按状态领取报告的索引 2026-10-29 才加进 001_init.sql，未手工补加的库没有这条索引（本机库即如此）：
-- 有就改名，没有就按新名补建；直接 RENAME 会在表已改名后报错中断，留下改了一半的库
SET @status_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'geo_diagnosis_report'
             AND INDEX_NAME = 'idx_diagnosis_task_report_status'),
    'ALTER TABLE geo_diagnosis_report RENAME INDEX idx_diagnosis_task_report_status TO idx_diagnosis_report_status',
    'ALTER TABLE geo_diagnosis_report ADD KEY idx_diagnosis_report_status (status)');
PREPARE status_index_stmt FROM @status_index_sql;
EXECUTE status_index_stmt;
DEALLOCATE PREPARE status_index_stmt;
ALTER TABLE geo_diagnosis_report
    ADD CONSTRAINT fk_diagnosis_report_task FOREIGN KEY (task_id) REFERENCES geo_diagnosis_task(id),
    ADD CONSTRAINT chk_diagnosis_report_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'));

-- 5. 回答分析：索引改名，report_id 列注释里的表名同步，重建约束
ALTER TABLE geo_diagnosis_report_result_analysis
    RENAME INDEX uk_diagnosis_task_result_analysis TO uk_diagnosis_report_result_analysis,
    RENAME INDEX idx_diagnosis_task_result_analysis_status TO idx_diagnosis_report_result_analysis_status,
    RENAME INDEX idx_diagnosis_task_result_analysis_result TO idx_diagnosis_report_result_analysis_result;
ALTER TABLE geo_diagnosis_report_result_analysis
    MODIFY report_id BIGINT UNSIGNED NOT NULL COMMENT '所属诊断报告ID（geo_diagnosis_report）',
    ADD CONSTRAINT fk_diagnosis_report_result_analysis_report FOREIGN KEY (report_id) REFERENCES geo_diagnosis_report(id),
    ADD CONSTRAINT fk_diagnosis_report_result_analysis_result FOREIGN KEY (task_result_id) REFERENCES geo_diagnosis_task_result(id),
    ADD CONSTRAINT chk_diagnosis_report_result_analysis_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT chk_diagnosis_report_result_analysis_sentiment CHECK (brand_sentiment IS NULL OR brand_sentiment IN ('POSITIVE','NEUTRAL','NEGATIVE'));
