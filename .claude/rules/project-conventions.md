# colorful-geo-mgmt 架构与代码约定

本文件记录与用户确认的长期工程要求。修改本项目代码前必须阅读；与通用模板规则冲突时，以本文件及用户最新明确要求为准。临时进度和验证结果写入 `.doc/HANDOFF.md`。

## 先判断设计是否必要

- 用户提出方案时，先检查已有实现、上下游调用和数据来源，给出独立判断。重复配置、职责冲突或不必要的复杂度应主动指出，不默认顺着提议增加代码。
- 每个类、方法、字段必须承担具体职责。不能只因“DDD 应该有这一层”而新增类或转换。
- 同一个事实只维护一处；能从已有类型推导的信息，不再增加数据库字段重复维护。
- 空占位类和只有调用转发的中间层应评估删除或合并；规划留在文档，实际开发时再建立实现。
- 类小不等于无价值：请求与响应边界、协议策略、真实业务编排应根据职责判断，不能机械合并。

## 分层与数据流

本项目采用现有目录分层：

```text
presentation/controller：接口接收、响应及 HTTP 错误映射
application/*Business：应用编排、分页输入和接口模型转换
domain/service：业务规则、校验及现有持久化服务
infrastructure/repository：DAO 与数据库实体
infrastructure/client：外部协议、平台及浏览器适配
```

- 简单配置和基础档案 CRUD，Service 直接使用数据库实体，不额外建立只有同名字段的领域模型。
- 数据流采用 `Request → Entity → Response`：应用层接收请求并构造实体，Service 校验和保存实体，应用层构造响应。
- 只有存在独立领域行为、不变量或生命周期时，才引入领域模型；不要提前为未来可能的需求建立抽象。
- 输入限制、密钥隔离、协议适配有实际价值，不能在简化时删掉。
- 不使用 `BeanUtils.copyProperties` 等按属性名隐式复制；字段显式赋值，不为转换新增无必要的框架或中间对象。
- 不引入 MapStruct 等映射框架（2026-10-17 用户确认）：同名自动映射会让实体上的 `apiKey` 等敏感字段在 Response 加同名字段后无声输出；现有转换多含计算字段（如 `keyConfigured`、`protocolType`），框架省不了多少代码。转换方法留在各模块 Business / Service 内，不放 infrastructure。

## 命名与依赖注入

- 表名采用模块前缀，实体、DAO、持久化 Service 与完整表名一致，例如：

| 表名 | 实体 | DAO | Service |
|---|---|---|---|
| `geo_llm_channel` | `GeoLlmChannelEntity` | `GeoLlmChannelDao` | `GeoLlmChannelService` |
| `geo_project_info` | `GeoProjectInfoEntity` | `GeoProjectInfoDao` | `GeoProjectInfoService` |

- 接口输入命名 `XxxRequest`，接口输出命名 `XxxResponse`。只有独立于接口的业务命令才使用 `XxxCommand`，不为命名增加 `Request → Command` 转换。
- 合并或修改职责后同步类名、方法名和注释，不保留已经失真的 `LoginService` 等名称。
- Spring 依赖始终使用 `jakarta.annotation.Resource` 字段注入；不使用构造器注入或 `@RequiredArgsConstructor` 注入依赖。注入后的初始化使用 `@PostConstruct`。
- Lombok 可用于数据模型及日志；枚举构造参数使用 `@RequiredArgsConstructor` 不属于 Spring 依赖注入。
- 使用 `channel`、`entity`、`response` 等能表达职责的变量名，避免 `c`、`p` 等含糊缩写。
- 方法名按 [naming.md](naming.md) §3.2 写成“动词 + 业务对象”，类名已经带上下文也不省略业务对象：用 `listTasks`、`getTask`、`listTaskResults`、`createReport`、`savePlan`，不用 `list`、`detail`、`results`、`create`。控制器处理方法与所调用的 Business 方法同名。
- 动词按语义选：可能查不到用 `find`，必须存在用 `get`，纯校验抛异常用 `validate`，校验后返回确定值用 `confirm`；状态写操作写明结果，如 `updateResultSucceeded`、`updateResultFailed`，不用 `succeed`、`fail`、`save` 这类只有动作的名字。布尔方法用 `is`、`has`、`contains` 开头；名词不作方法名（`taskSnapshot` 改为 `getTaskSnapshot`）。
- 业务对象在项目内可能同名时带上所属对象：报告分析表也带 result，所以单条诊断结果写 `TaskResult`（`listTaskResults`、`getTaskResult`），与已有的 `DiagnosisTaskResultStatus` 一致。
- 改名按“类内声明 + 通过注入字段发起的调用”定位，再靠编译找出遗漏（包括 Mockito `when(service).x(...)` 写法）；`create`、`read`、`fail` 这类通用名不做全局文本替换。接口路径和页面文案是对外契约，内部改名不随之改。

### 诊断模块术语与命名线

| 业务概念 | 页面与注释用语 | 类名前缀 | 表 |
|---|---|---|---|
| 诊断方案 | 诊断方案 | `DiagnosisPlan` | `geo_diagnosis_plan` 及问题、渠道子表 |
| 一轮执行 | 诊断任务 | `DiagnosisTask` | `geo_diagnosis_task` |
| 一个问题在一个渠道上的一次调用记录 | 诊断结果（单次诊断） | `DiagnosisTaskResult` | `geo_diagnosis_task_result` |
| 一次任务的分析报告 | 诊断报告 | `DiagnosisReport` | `geo_diagnosis_report` |
| 报告中对单条诊断结果的分析 | 回答分析 | `DiagnosisReportResultAnalysis` | `geo_diagnosis_report_result_analysis` |

- 表名（2026-11-18 用户确认）：页面上独立管理的对象（方案、任务、报告）用短名，从属的表带所属对象前缀：方案的问题与渠道、任务的诊断结果、报告的回答分析。报告仍通过 `task_id` 关联所属任务，只是表名不再体现。
- 同一业务对象的表、实体、DAO、领域服务、Business、Response、Controller、后台任务、枚举及测试类沿同一前缀命名；改名时整组一起改，控制器测试保留 `Mgmt` 前缀（如 `MgmtDiagnosisReportControllerTest`）。
- “诊断结果”只指单条调用记录，“诊断报告”只指整份报告，两者不互相借用；不再使用“采样”，重复调用称“重复执行次数”。

## 方法与可读性

- 已有 MyBatis-Plus 能力直接使用，例如 `getById`；不再增加仅查询后返回的包装方法。记录缺失判断放在需要该业务语义的位置。
- 避免重复查询和实体二次复制。保存后为取得数据库生成的 ID、时间而回读，有明确用途，可保留。
- 状态更新只修改目标字段，避免把旧实体全量回写覆盖其他配置。
- 避免无意义嵌套、取反的复杂三元表达式和挤在一行的查询／转换／计数；按业务步骤拆成清晰语句，不为减少行数牺牲可读性。
- 日志记录实际选中渠道及必要结果，避免入口和下层重复记录同一调用，不打印问题原文、回答原文或凭据。
- 注释解释职责、规则及必要边界，与最终实现一致；修改后删除过时的能力描述和兼容说明。
- 方法注释写实际效果，不写模糊的动作描述：写清前置条件（如“仅失败或部分失败的任务”）、作用在哪一层对象（任务还是诊断结果）、保留什么、清除什么、后续由谁执行。例如重置任务写“非成功的诊断结果改回待执行并清空旧数据，成功结果保留，任务回到待执行由后台重新领取”，不写“重新排队”。
- 会写多张表或改状态的方法，在方法注释里写清：写入哪些表、各写几条、写成什么状态、是否同一事务及失败如何回滚、数据来源的筛选口径，以及不负责的后续步骤由谁完成（示例：`GeoDiagnosisReportService.createReport`）。步骤编号注释只写该步的目的和规则，不重复表名；代码里的实体与 DAO 已能看出读写哪张表。
- `@param` 用业务名称（“诊断任务 ID”），不用“当前任务 ID”这类依赖上下文的说法。同一行为在 Controller 与 Business 的注释口径一致，改一处同步另一处；改名后同步注释和文档中的类名、表名。

## 列表与分页

- 所有分页列表接口统一返回 `infrastructure/common/model/PageResponse<T>`（`items`、`total`、`page`、`size`），不再为每个模块新建 `XxxPage`。
- 分页参数统一用 `infrastructure/common/model/PageQuery`：Business 用接口入参 `new PageQuery(page, size[, keyword])` 构造，构造时校验页码从 0 开始、`size` 为 1～100、关键词去空白且最多 200 字，空白视为未填写；不再各自写校验。Service 接收 `PageQuery`，用 `.last(pageQuery.limitClause())` 或 `offset()` 分页，用 `pageQuery.toResponse(items, total)` 组装结果；列表和总数使用同一筛选条件。
- 列表行只给列表用时建行类，命名 `XxxPageItem`（如 `ContentOptimizationRecordPageItem`）；与创建、详情共用的 Response 或领域对象直接作为 `T`，不为列表再复制一份。
- 列表方法注释直白写“XX列表（分页）查询，可按 X 筛选”；不用“摘要”指代列表行，不在不负责的层写“避免加载正文”等说明。

## 接口异常响应

- 所有接口出错统一返回 Spring `ProblemDetail`，前端读取 `detail` 字段展示原因，不再返回 `{"message": ...}` 等自定义结构。
- `IllegalArgumentException → 400`、`NoSuchElementException → 404` 由 `presentation/controller/GlobalExceptionHandler` 统一处理并记 warn 日志，各 Controller 不再重复编写。
- 含模块语义的映射（如发布记录 `IllegalStateException → 409`、诊断模块存储失败 500、`LLMClientException` 的提示文案、渠道编码重复 409）留在对应 Controller，Controller 内处理优先于全局处理。
- Controller 单测使用 `standaloneSetup` 时需 `.setControllerAdvice(new GlobalExceptionHandler())`，否则 400/404 断言不生效。

## 登录与访问控制

- 管理后台只有一个登录账号，不提供注册；账号来自环境变量 `GEO_ADMIN_USERNAME`、`GEO_ADMIN_PASSWORD_HASH`（BCrypt 哈希），不建用户表，未配置哈希时服务拒绝启动。
- 访问控制集中在 `infrastructure/config/security/AdminSecurityConfig`：除登录页及其样式、脚本和 `robots.txt` 外全部要求登录；新增公开资源只改这里的放行清单。未登录访问页面跳转登录页，访问接口返回 401 ProblemDetail；同一 IP 15 分钟内连续失败 5 次锁定 15 分钟（`LoginAttemptLimiter`）。
- 写请求必须带跨站令牌：页面接口请求统一走 `shared/http.js` 的 `AdminHttp.requestJson`（自动附带 `X-XSRF-TOKEN`，401 跳转登录页），不直接使用 `fetch`；表单提交用 `AdminHttp.csrfToken()` 填 `_csrf`。新页面必须引入 `shared/http.js`、`shared/navigation.js`（含退出入口）与 `shared/browser-takeover.js`（人工接管弹窗）。
- 所有响应带 `X-Robots-Tag: noindex, nofollow`，并提供禁止抓取的 `robots.txt`；这只约束遵守规则的爬虫，真正的访问控制仍是登录。

## 浏览器人工接管

- 术语（2026-10-31 用户确认）：「浏览器人工接管」指框架：服务器上的 Playwright 页面需要人时，把实时画面推到管理页，用户在画面上点击、拖动、输入，操作转发回页面，直到调用方给出的完成条件满足。「人工验证」只是其中一个场景，不再用来指整个框架。
- 框架与业务隔离：框架在 `infrastructure/client/playwright/takeover/`，类名前缀 `BrowserTakeover`，入口 `BrowserTakeoverRegistry.requestTakeover(页面, 标题, 原因, 完成条件)`，接口 `/browser-takeovers`；只认页面、标题、原因、完成条件和超时，不引用平台、业务枚举。场景放在 `takeover/` 下的子包（人工验证为 `takeover/verification/`），由场景决定何时请求接管、怎样算完成；平台客户端只调用场景入口。
- 何时用接管：代码能自动处理的（弹窗、协议勾选）写在平台适配里；要人做但能用管理页表单转交的（选验证方式、填短信码）用表单；只有必须直接在平台页面上操作的（拖滑块、点选图片）才用接管。不提前做没有真实需求的场景。
- 人工验证的识别规则集中在 `HumanVerificationRule`，只收录实际遇到并核对过页面特征的验证；不识别验证码内容、不模拟拖动、不代替用户完成验证。

## 诊断方案制题

- 地域必选（2026-11-16 用户确认）：生成候选时先按「母题语言」生成一套母题，再按每个地域行（地域 + 可空语言）改写；生成母题时把本次地域告诉模型，母题要在这些地域都成立。母题只用于改写和审核对照，不保存。手动添加的题和旧方案的题仍可不分地域。
- 问题面向哪些地域、用什么语言只看本次输入：项目档案的目标市场、目标语言只在页面上展示作参考，不发给制题模型，也不参与统计。

## 诊断报告分段执行

- 两段（2026-11-19 用户确认）：报告分「判定每条回答」与「生成报告内容」两段，`geo_diagnosis_report.stage` 记录执行到或停在哪一段（`JUDGE` / `CONTENT`），与 `status` 一起表示进度。只在两段之间设断点：判定每条回答都要调用模型、结果逐条落库，重跑只判失败的；第 2 段（维度归一、统计、优化建议、地域策略）只有几次调用，中间结果不另存，失败或中断时整段重做，不在第 2 段内部另加断点。
- 失败就暂停，不自动重试：判定段全部判完、有失败才暂停，不因连续失败提前停；第 2 段四步都跑完再看结果，有失败先保存本次内容再暂停。暂停后由用户选「重试」（只重做失败的部分）或「忽略失败继续」（按降级口径往下走：失败的回答不计入判定指标，失败的建议、策略显示生成失败）；已完成的报告也可重判失败的回答或重新生成报告内容。只有冻结的判定渠道被删除时报告失败（重跑不换渠道），早期失败的报告不能重跑。
- 分工：执行器 `DiagnosisReportWorker` 只负责按段执行、判断结果；段与状态的每次变化、重跑与忽略的前置条件都在 `GeoDiagnosisReportService`，写库带当前状态条件；Controller 和执行器不另写状态规则。重新排队锁所属任务行，与生成新报告串行，同一任务同时最多一份报告在生成（暂停的不算）。
- 第 2 段子步骤进度（2026-11-19 用户确认）：只拆显示、不拆重试。执行器每步开始、结束时把四个子步骤（合并同义维度、统计、写优化建议、写地域策略）的状态与说明写入 `content_progress`，写地域策略逐个地域报进度；各步结果的说明集中在 `DiagnosisReportContentProgress`。进度写库失败只记警告、不中断生成，重新排队时清空；不要据此做重试或状态判断。
- 「补全报告」已删除（2026-11-19 用户确认）：早期报告缺内容时用「重新生成报告内容」整段重做，不再维护缺失部分清单。

## 诊断报告页面与导出网页

- 文件分工（2026-11-04 用户确认）：`static/admin/diagnosis-report-view.js`（`DiagnosisReportView`）渲染各页签及证据、回答片段，页面与导出网页共用；`diagnosis-report-export.js`（`DiagnosisReportExport`）组装并下载导出网页；`diagnosis-reports.js` 是页面本身（列表、生成、详情、两段进度、重试与重跑、弹窗）。页签的标识、标题、渲染函数与缺失提示只在 `DiagnosisReportView.tabs` 中定义；渲染函数不读全局状态，模型筛选与查看入口经第一个参数 `context` 传入。样式在导出时从当前页面内联。改报告页面时按下面两条写，导出自动跟上，不另改导出代码：
  - 页签内容只用已读取的报告数据同步渲染。点开后才另调接口取得的内容不会进入导出网页，需要时在 `DiagnosisReportExport.downloadReport` 中单独读取（如回答原文）。
  - 「看回答」「看证据」这类查看入口一律经 `context.entries` 生成（或用已有的 `quoteLine`、`answerLine`、`adviceItem`）：页面上是按钮，导出时是打开随网页导出的弹窗的链接，对应回答与证据一并导出。不在渲染函数里直接创建按钮并绑定点击，导出时按钮会被去掉。
- 筛选、排序、展开收起等其他交互不随导出带过去；需要在导出网页中可用时，在导出模块的 `exportPageScript` 中另加。
- 页签内的子页签（如 ⑦ 的地域，2026-11-15）用带 `data-subtab-group`、`data-subtab`、`data-subtab-panel` 的链接，渲染函数不绑定事件：页面由 `diagnosis-reports.js` 委托点击后调用 `DiagnosisReportView.selectSubtab`，导出网页由 `exportPageScript` 切换。
- 报告语言（2026-11-16 用户确认）：生成报告时选择，默认中文，存 `geo_diagnosis_report.report_language`。由模型写出的解析内容按报告语言书写：逐条判定的观点概括与维度名、维度归一、优化建议、⑦ 地域策略；新增需要模型书写的报告内容也把报告语言传进提示词。问题、回答、引用片段、来源和品牌名保持原文，不翻译。代码拼出的问题标题、指标名与页面固定文字保持中文，不做页面多语言。

## 提示词管理

- 提示词放在库表 `geo_prompt_template`，在「基础配置 → 提示词管理」维护（2026-11-19 用户确认）。可维护的提示词由枚举 `PromptTemplateKey` 声明：枚举名是库里的 key，带页面显示的描述和可用变量（英文变量名、中文说明、是否必填）。库是唯一来源，代码里不留默认文字，缺了就明确报错，不回退。
- 现有七个，枚举声明顺序即页面下拉顺序，按业务流程排：诊断方案制题两个（生成母题、按地域改写）、诊断报告四个（逐条判定、合并同义维度、优化建议、地域策略）、内容优化一个（正文优化）。
- 模板里写 `{{变量名}}`，代码按名字算好取值后用 `PromptTemplateRenderer.render` 一次扫描填充，不连续调用 `replace`，免得填进去的回答原文被再次替换；取不到值直接报错。保存时拒绝未声明的变量（多半是拼错）和缺少的必填变量。
- 保存总是新增一版、不覆盖，旧版本可载入后再保存回滚。制题、内容优化每次调用读当前版本；判定提示词由报告冻结版本（`prompt_version` 记作 v + 版本号），续跑沿用，手动重判换成最新版；第 2 段每次开始时读一次当前版本，同一次执行不重复查库。
- 分工：诊断报告的 `*Prompt` 类只负责“模板 + 数据 → 提示词”和输出解析，不访问数据库；模板由执行器取出后传入。制题、内容优化没有单独的提示词类，由 `QuestionGenerateService`、`ContentOptimizationService` 在调用模型前取当前版本、在本类填变量；缺提示词时抛 `NoSuchElementException`，经全局处理返回 404，页面显示“提示词未配置：…”，不调用模型。模板里写的模型输出格式与解析绑定，改模板时要保持一致：改坏了制题直接报错，报告会在那一步暂停。
- 新增提示词：在 `PromptTemplateKey` 加枚举值并声明变量 → 在 `001_init.sql` 与迁移脚本写入初始模板（初始模板应由原代码用占位符拼出，并核对填入数据后与原提示词一致）→ 对应的类改为取模板填变量。测试会检查每个枚举都有初始模板且变量合规。

## 已确认的大模型设计

- 配置唯一来源为数据库 `geo_llm_channel`，不恢复 YAML 模型配置、自动导入或默认回退。
- 默认筛选已启用渠道，按 `score` 降序、ID 升序选择；指定渠道或模型时增加对应约束。无匹配或调用失败明确返回，不自动切换模型。
- 渠道直接保存 strategy_bean_name，用 Spring 注入的 Map<String, LLMStrategy> 按 Bean 名获取实例。不再维护额外策略编码、自建注册表或 strategyType/strategyCode/strategyName 包装方法；缺失 Bean 保存与调用时明确拒绝，不反射、不降级。
- 页面使用数据库渠道名称和模型名称，策略选项展示“策略中文名称 · 协议名称”（2026-10-29 用户确认）：中文名称由各策略类实现 `displayName()` 声明，新增策略漏写编译不通过；不另建 Bean 名到中文名的对照表或枚举，保存和路由仍用 Bean 名。厂商策略通过 `apiKeyConsoleUrl()` 声明 API Key 获取页，经 /llm-channels/strategies 返回给配置页展示；通用策略返回 null，前端不另维护地址表。协议由协议父类声明，不存入数据库或重复放入策略目录；枚举值、字段和业务方法须有简洁注释。重命名 Bean 时同步迁移数据库配置。
- Chat Completions、Responses 抽象父类复用协议交互与引用解析，子策略处理厂商参数。protocol_type 不独立持久化，响应从实例推导；provider_code 只作供应商资料。页面选项来自 /llm-channels/strategies 的实际 Bean Map，不开放未实现策略。
- 路由一次查询选取 GeoLlmChannelEntity 后直接交给策略，不建立同字段的连接配置副本或重复传递密钥。未知 Bean 和远程空密钥在调用前拒绝；实体的密钥不得参与日志、toString 或接口响应。只有协议调用和异常边界承担实际职责的层保留，纯转发和已停用 SDK 代码及时删除。
- LLMProtocolType 只表示 OPENAI_CHAT_COMPLETIONS、OPENAI_RESPONSES 交互格式；本地模拟策略已删除（2026-10-29），测试用 Mockito 策略替身或 `.test` 脚本的本地 HTTP 模型替身。引用始终按响应的结构化字段解析，不根据搜索标志过滤，也不从正文 URL 或搜索召回来源猜测。搜索参数按供应商处理，不把普通兼容供应商自动标为支持联网。
- 引用只来自接口结构化数据，不把正文链接当成实际引用，不虚构引用。
- 不返回 url_citation 的厂商由子策略重写 `collectStrategyCitations`，把接口结构化返回的来源网址补为引用：DeepSeek 取 `web_search_call` 的 `open_page`（去掉 `ws_call_id` 标识），千问、智谱取 `web_search_call.action.sources` 中 `type=url` 的网址（共用 `LLMWebSearchResultParser.collectWebSearchSources`）；文心（千帆智能搜索 Chat）取响应顶层 `references` 中 `type=web` 的网址与标题，正文 `^[n]^` 角标不解析。Chat 与 Responses 父类各提供同名默认空实现的补充入口。标题、摘要、位置留空且不抓取补全；检索词不作为引用；文本注解已有同一网址时保留注解资料。标准 url_citation（豆包、OpenAI）留在父类统一解析，不下沉到子策略。
- `LLMQueryRequest` 仅含 `prompt`、`channelCode`、`modelName`、`enableWebSearch`，每次独立调用，无角色枚举、消息列表或历史上下文。重复执行通过多次独立调用完成。
- 是否联网由调用方声明，不按渠道或提示词识别（2026-11-16 用户确认）：`enableWebSearch` 默认 false，只有诊断执行（`DiagnosisTaskWorker`）和测试接口用 `LLMQueryRequest.withWebSearch` 打开；制题、改写、报告判定、维度归一、优化建议、地域策略、内容优化等内部任务一律不联网，新增模型调用不写就不搜。Responses 父类判断开关，打开才调用子类的 `addWebSearchParams` 挂载厂商搜索参数；子类只把“要联网”翻译成自己的参数，不判断用途。千帆智能搜索（接口本身即搜索）、`gpt-5-search-api` 等搜索专用模型不受开关控制，内部任务不要选用。
- 密钥仅供服务端使用，响应只返回 `keyConfigured`；编辑空密钥保留原值，输入新值才替换。

## 修改后的检查

- 检查完整生产者和消费者，包括源码、测试、前端、SQL、资源版本及有效文档引用；不只修改用户指出的一行。
- 重命名表或类时同步关联引用，已有表迁移保留数据，避免创建一个同名空表替代原数据。
- 保留用户未提交改动，不自动提交、重置、清理或批量格式化。
- 测试覆盖实际业务边界，不为重复字段赋值写机械测试；按改动运行相关验证，区分代码实现、测试通过和真实平台联调。
- 长期规则写入本文件，当前完成情况、风险和下一步写入 `.doc/HANDOFF.md`；明确服务是否重启，不把源码修改当成运行实例已更新。
