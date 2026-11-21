# colorful-mgmt 架构与代码约定

本文件记录当前 colorful-mgmt 项目与用户确认的长期架构选择和实现约定，属于项目记忆。修改本项目代码前必须阅读；与通用模板规则冲突时，以本文件及用户最新明确要求为准。临时进度和验证结果写入 `.doc/HANDOFF.md`。

本项目是独立的 Java 17 / Spring Boot 3 服务（MyBatis-Plus、MySQL、Playwright、静态管理页面），POM、目录和运行配置以当前仓库为准。本文件只放跨模块的工程约定；单个模块的设计决策见文末「模块设计决策」。

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
- 控制器处理方法与所调用的 Business 方法同名；动词与变量语义见 [命名规范](../rules/naming.md)。
- 业务对象在项目内可能同名时带上所属对象：报告分析表也带 result，所以单条诊断结果写 `TaskResult`（`listTaskResults`、`getTaskResult`），与已有的 `DiagnosisTaskResultStatus` 一致。

## 方法与可读性

- 已有 MyBatis-Plus 能力直接使用，例如 `getById`；不再增加仅查询后返回的包装方法。记录缺失判断放在需要该业务语义的位置。
- 避免重复查询和实体二次复制。保存后为取得数据库生成的 ID、时间而回读，有明确用途，可保留。
- 状态更新只修改目标字段，避免把旧实体全量回写覆盖其他配置。
- 主流程、复用与抽象判断见 [开发规范](../rules/engineering.md)，日志和失败边界见 [可靠性规范](../rules/reliability.md)，职责与状态说明见 [Java 注释](../rules/comments/comment-java.md)。
- 不打印问题原文、回答原文或凭据；模型密钥的具体隔离契约见大模型模块决策。

## 列表与分页

- 所有分页列表接口统一返回 `infrastructure/common/model/PageResponse<T>`（`items`、`total`、`page`、`size`），不再为每个模块新建 `XxxPage`。
- 分页参数统一用 `infrastructure/common/model/PageQuery`：Business 用接口入参 `new PageQuery(page, size[, keyword])` 构造，构造时校验页码从 0 开始、`size` 为 1～100、关键词去空白且最多 200 字，空白视为未填写；不再各自写校验。Service 接收 `PageQuery`，用 `.last(pageQuery.limitClause())` 或 `offset()` 分页，用 `pageQuery.toResponse(items, total)` 组装结果；列表和总数使用同一筛选条件。
- 列表行只给列表用时建行类，命名 `XxxPageItem`（如 `ContentOptimizationRecordPageItem`）；与创建、详情共用的 Response 或领域对象直接作为 `T`，不为列表再复制一份。
- 列表方法注释直白写“XX列表（分页）查询，可按 X 筛选”；不用“摘要”指代列表行，不在不负责的层写“避免加载正文”等说明。

## 接口异常响应

- 不引入 BizException 或新的业务错误码响应信封。所有接口出错统一返回 Spring `ProblemDetail`，前端读取 `detail` 字段展示原因，不再返回 `{"message": ...}` 等自定义结构。
- `IllegalArgumentException → 400`、`NoSuchElementException → 404` 由 `presentation/controller/GlobalExceptionHandler` 统一处理并记 warn 日志，各 Controller 不再重复编写。
- 含模块语义的映射（如发布记录 `IllegalStateException → 409`、诊断模块存储失败 500、`LLMClientException` 的提示文案、渠道编码重复 409）留在对应 Controller，Controller 内处理优先于全局处理。
- Controller 单测使用 `standaloneSetup` 时需 `.setControllerAdvice(new GlobalExceptionHandler())`，否则 400/404 断言不生效。

## 登录与访问控制

- 管理后台只有一个登录账号，不提供注册；账号来自环境变量 `GEO_ADMIN_USERNAME`、`GEO_ADMIN_PASSWORD_HASH`（BCrypt 哈希），不建用户表，工程要求是未配置哈希时服务拒绝启动；当前配置是否仍有默认值须现场核对，不能把要求当作已实现。
- 访问控制集中在 `infrastructure/config/security/AdminSecurityConfig`：除登录页及其样式、脚本和 `robots.txt` 外全部要求登录；新增公开资源只改这里的放行清单。未登录访问页面跳转登录页，访问接口返回 401 ProblemDetail；同一 IP 15 分钟内连续失败 5 次锁定 15 分钟（`LoginAttemptLimiter`）。
- 写请求必须带跨站令牌：页面接口请求统一走 `shared/http.js` 的 `AdminHttp.requestJson`（自动附带 `X-XSRF-TOKEN`，401 跳转登录页），不直接使用 `fetch`；表单提交用 `AdminHttp.csrfToken()` 填 `_csrf`。新页面必须引入 `shared/http.js`、`shared/navigation.js`（含退出入口）与 `shared/browser-takeover.js`（人工接管弹窗）。
- 所有响应带 `X-Robots-Tag: noindex, nofollow`，并提供禁止抓取的 `robots.txt`；这只约束遵守规则的爬虫，真正的访问控制仍是登录。

## 模块设计决策

单个模块的设计决策按模块分文件维护，改到对应模块时读取（按本表及各文件首段触发条件读取）。下表的原章节以前写在本文件，2026-11-19 原文移入对应文件，旧文档里“项目约定「…」”的引用按此表查找：

| 模块 | 文件 | 原章节 |
|---|---|---|
| 诊断方案、任务、结果与报告 | [modules/diagnosis.md](modules/diagnosis.md) | 诊断模块术语与命名线、诊断方案制题、诊断报告分段执行、诊断报告页面与导出网页 |
| 提示词管理 | [modules/prompt-template.md](modules/prompt-template.md) | 提示词管理 |
| 大模型渠道与调用 | [modules/llm.md](modules/llm.md) | 已确认的大模型设计 |
| 浏览器人工接管 | [modules/browser-takeover.md](modules/browser-takeover.md) | 浏览器人工接管 |

新增模块决策写入 memory 中对应模块文件；新建主题时同步项目记忆索引，本表仅保留旧章节查找关系。

## 接口输出与决策边界

- 成功响应直接返回业务对象或列表，不套统一业务码信封；接口 Response 位于 application 模型，领域服务不依赖接口 Response。
- 本文件记录跨模块项目约定，模块决策放 memory/modules。通用工程要求放 rules；同一要求只维护一处，不在入口、流程与记忆重复抄写。
