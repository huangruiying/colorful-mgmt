-- 制题提示词写明品牌范围（2026-11-25 用户确认）：两个制题提示词各新增一版。
--   生成母题：写明 NON_BRAND、BRAND、COMPARISON 各指什么；BRAND 是直接询问本项目，COMPARISON 是本项目与竞品比较，
--             两者的问题里都必须出现项目名称或别名；不设计只问竞品、或只比较竞品的问题。
--   按地域改写：品牌名不翻译、不音译、不换成代称；BRAND、COMPARISON 母题改写后必须仍出现项目名称或别名。
--   起因：旧说法只写“品牌认知与竞品比较单独标记”，模型把只问竞品的题标成了 BRAND、COMPARISON，报告里没有可统计的品牌评价，对比胜负还会算错。
-- 做法：取每个提示词的最新一版，把其中的旧句子换成新句子后存为下一版；页面上改过的其他内容原样保留，旧版本不动。
-- 先检查再执行，可重复执行：最新一版里已经没有旧句子时（已执行过，或这几句已在页面上改写过）不新增版本，末尾的核对查询会指出需要人工处理的提示词。
-- 新库直接使用 001_init.sql，无需执行本脚本。只新增行，不改表结构，不动已有的行。
SET NAMES utf8mb4;

-- 1. 生成母题提示词：最新一版同时含有两处旧句子时才新增一版
INSERT INTO geo_prompt_template (prompt_key, version, content)
SELECT latest.prompt_key, latest.version + 1,
       REPLACE(REPLACE(latest.content,
           '品牌认知与竞品比较单独标记。',
           '直接询问本项目、或把本项目与竞品比较的问题单独标记。'),
           '品牌范围仅用 NON_BRAND, BRAND, COMPARISON。竞品比较问题标记 COMPARISON。',
           '品牌范围仅用 NON_BRAND, BRAND, COMPARISON，按问题有没有问到本项目来标记：
- NON_BRAND：不出现项目名称及任何别名的品类或场景问题；不出现品牌名的品类之间的比较也标记 NON_BRAND，意图用 COMPARISON。
- BRAND：直接询问本项目，问题里必须按项目资料里的写法出现项目名称或任一别名。
- COMPARISON：把本项目与竞品放在一起比较，问题里必须按项目资料里的写法出现项目名称或任一别名。
不设计只询问竞品、或只比较竞品而不涉及本项目的问题。')
FROM (SELECT prompt_key, version, content FROM geo_prompt_template
      WHERE prompt_key = 'DIAGNOSIS_PLAN_QUESTION_GENERATE' ORDER BY version DESC LIMIT 1) latest
WHERE INSTR(latest.content, '品牌认知与竞品比较单独标记。') > 0
  AND INSTR(latest.content, '品牌范围仅用 NON_BRAND, BRAND, COMPARISON。竞品比较问题标记 COMPARISON。') > 0;

-- 2. 按地域改写问题提示词：最新一版含有旧的第 4 条时才新增一版
INSERT INTO geo_prompt_template (prompt_key, version, content)
SELECT latest.prompt_key, latest.version + 1,
       REPLACE(latest.content,
           '4. 母题里出现的品牌名保持原样；NON_BRAND 母题改写后不得出现项目名称或任何别名；不添加母题没有的品牌。',
           '4. 母题里出现的品牌名保持原样，不翻译、不音译，也不换成“该品牌”这类代称；BRAND、COMPARISON 母题改写后必须仍然出现母题里的项目名称或别名；NON_BRAND 母题改写后不得出现项目名称或任何别名；不添加母题没有的品牌。')
FROM (SELECT prompt_key, version, content FROM geo_prompt_template
      WHERE prompt_key = 'DIAGNOSIS_PLAN_QUESTION_LOCALIZE' ORDER BY version DESC LIMIT 1) latest
WHERE INSTR(latest.content, '4. 母题里出现的品牌名保持原样；NON_BRAND 母题改写后不得出现项目名称或任何别名；不添加母题没有的品牌。') > 0;

-- 3. 核对：两个提示词的最新一版都应显示“已是新说法”；显示“需人工核对”的，到「基础配置 → 提示词管理」对照 001_init.sql 手工改写后保存
SELECT t.prompt_key AS 提示词, t.version AS 最新版本,
       IF(INSTR(t.content, IF(t.prompt_key = 'DIAGNOSIS_PLAN_QUESTION_GENERATE',
               '不设计只询问竞品、或只比较竞品而不涉及本项目的问题。',
               'BRAND、COMPARISON 母题改写后必须仍然出现母题里的项目名称或别名')) > 0,
          '已是新说法', '需人工核对') AS 状态
FROM geo_prompt_template t
JOIN (SELECT prompt_key, MAX(version) AS version FROM geo_prompt_template
      WHERE prompt_key IN ('DIAGNOSIS_PLAN_QUESTION_GENERATE', 'DIAGNOSIS_PLAN_QUESTION_LOCALIZE') GROUP BY prompt_key) latest
  ON latest.prompt_key = t.prompt_key AND latest.version = t.version
ORDER BY t.prompt_key;
