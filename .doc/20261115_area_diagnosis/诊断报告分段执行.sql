-- 诊断报告分两段执行（2026-11-19 用户确认）：报告表加 stage、content_progress 两列，状态加 PAUSED（已暂停）。
--   stage：JUDGE 判定每条回答 / CONTENT 生成报告内容，默认 JUDGE；执行前已完成的报告记为 CONTENT。
--   content_progress：第 2 段四个子步骤的状态与说明，只用于页面显示，已有报告为空。
--   status：在 PENDING / RUNNING / SUCCEEDED / FAILED 之外加 PAUSED。
-- 每一步先检查再执行，重复执行不报错、不重复修改；执行后与 001_init.sql 新建的表结构一致。新库直接使用 001_init.sql，无需执行本脚本。
-- 先执行本脚本再发新代码：新列有默认值，状态约束只是放宽，旧代码在新表上照常运行。
SET NAMES utf8mb4;

-- 1. 加 stage 列；已有时跳过
SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                           AND TABLE_NAME = 'geo_diagnosis_report' AND COLUMN_NAME = 'stage') = 0,
    'ALTER TABLE geo_diagnosis_report ADD COLUMN stage VARCHAR(20) NOT NULL DEFAULT ''JUDGE'' COMMENT ''执行到或停在哪一段：JUDGE 判定每条回答 / CONTENT 生成报告内容'' AFTER status',
    'DO 0');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 2. 加 content_progress 列；已有时跳过
SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                           AND TABLE_NAME = 'geo_diagnosis_report' AND COLUMN_NAME = 'content_progress') = 0,
    'ALTER TABLE geo_diagnosis_report ADD COLUMN content_progress JSON NULL COMMENT ''第 2 段四个子步骤的状态与说明，执行中逐步更新，只用于页面显示'' AFTER summary',
    'DO 0');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 3. 已完成的报告两段都已做完，记为生成报告内容段
UPDATE geo_diagnosis_report SET stage = 'CONTENT' WHERE status = 'SUCCEEDED' AND stage <> 'CONTENT';

-- 4. 状态约束加入 PAUSED：旧约束还不含 PAUSED 时先删除，再按新取值重建；已是新约束时两句都跳过
SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
                           AND CONSTRAINT_NAME = 'chk_diagnosis_report_status' AND CHECK_CLAUSE NOT LIKE '%PAUSED%') = 1,
    'ALTER TABLE geo_diagnosis_report DROP CHECK chk_diagnosis_report_status',
    'DO 0');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
                           AND CONSTRAINT_NAME = 'chk_diagnosis_report_status') = 0,
    'ALTER TABLE geo_diagnosis_report ADD CONSTRAINT chk_diagnosis_report_status CHECK (status IN (''PENDING'',''RUNNING'',''PAUSED'',''SUCCEEDED'',''FAILED''))',
    'DO 0');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 5. stage 只允许两个取值；已有约束时跳过
SET @migration_sql = IF((SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE()
                           AND CONSTRAINT_NAME = 'chk_diagnosis_report_stage') = 0,
    'ALTER TABLE geo_diagnosis_report ADD CONSTRAINT chk_diagnosis_report_stage CHECK (stage IN (''JUDGE'',''CONTENT''))',
    'DO 0');
PREPARE migration_stmt FROM @migration_sql;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 6. 同步三列的注释，与 001_init.sql 一致；只改注释，类型、默认值与数据不变，重复执行结果相同
ALTER TABLE geo_diagnosis_report
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / PAUSED / SUCCEEDED / FAILED',
    MODIFY COLUMN summary JSON NULL COMMENT '报告内容（汇总指标、主要发现、优化建议与地域策略），第 2 段结束时写入，完成后冻结',
    MODIFY COLUMN error_message VARCHAR(1000) NULL COMMENT '脱敏后的暂停或失败原因';

-- 7. 核对：应看到 stage、content_progress 两列、两个约束（状态约束含 PAUSED），以及各状态、各段的报告数（已完成的都在 CONTENT）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'geo_diagnosis_report' AND COLUMN_NAME IN ('status', 'stage', 'content_progress');
SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME IN ('chk_diagnosis_report_status', 'chk_diagnosis_report_stage');
SELECT status, stage, COUNT(*) AS reports FROM geo_diagnosis_report GROUP BY status, stage ORDER BY status, stage;
