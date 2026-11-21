# 大模型渠道与调用设计约定

大模型渠道配置、策略路由、联网搜索与引用解析的设计决策（与用户确认，原写在项目约定中，2026-11-19 原文移入）。
改动 `geo_llm_channel`、`infrastructure/client/llm/`、模型配置页，或新增模型调用时读取；跨模块的工程约定见 [项目约定](../project-conventions.md)。

## 已确认的大模型设计

- 配置唯一来源为数据库 `geo_llm_channel`，不恢复 YAML 模型配置、自动导入或默认回退。
- 默认筛选已启用渠道，按 `score` 降序、ID 升序选择；指定渠道或模型时增加对应约束。无匹配或调用失败明确返回，不自动切换模型。
- 渠道直接保存 strategy_bean_name，用 Spring 注入的 Map<String, LLMStrategy> 按 Bean 名获取实例。不再维护额外策略编码、自建注册表或 strategyType/strategyCode/strategyName 包装方法；缺失 Bean 保存与调用时明确拒绝，不反射、不降级。
- 页面使用数据库渠道名称和模型名称，策略选项展示“策略中文名称 · 协议名称”（2026-10-29 用户确认）：中文名称由各策略类实现 `displayName()` 声明，新增策略漏写编译不通过；不另建 Bean 名到中文名的对照表或枚举，保存和路由仍用 Bean 名。厂商策略通过 `apiKeyConsoleUrl()` 声明 API Key 获取页，经 /llm-channels/strategies 返回给配置页展示；通用策略返回 null，前端不另维护地址表。协议由协议父类声明，不存入数据库或重复放入策略目录；枚举值、字段和业务方法须有简洁注释。重命名 Bean 时同步迁移数据库配置。
- Chat Completions、Responses 抽象父类复用协议交互与引用解析，子策略处理厂商参数。protocol_type 不独立持久化，响应从实例推导；provider_code 只作供应商资料。页面选项来自 /llm-channels/strategies 的实际 Bean Map，不开放未实现策略。
- 路由一次查询选取 GeoLlmChannelEntity 后直接交给策略，不建立同字段的连接配置副本或重复传递密钥。未知 Bean 和远程空密钥在调用前拒绝；实体的密钥不得参与日志、toString 或接口响应。只有协议调用和异常边界承担实际职责的层保留，纯转发和已停用 SDK 代码及时删除。
- LLMProtocolType 只表示 OPENAI_CHAT_COMPLETIONS、OPENAI_RESPONSES 交互格式；本地模拟策略已删除（2026-10-29），测试用 Mockito 策略替身或 `.test` 脚本的本地 HTTP 模型替身。引用始终按响应的结构化字段解析，不根据搜索标志过滤，也不从正文 URL 或搜索召回来源猜测。搜索参数按供应商处理，不把普通兼容供应商自动标为支持联网。
- 不返回 url_citation 的厂商由子策略重写 `collectStrategyCitations`，把接口结构化返回的来源网址补为引用：DeepSeek 取 `web_search_call` 的 `open_page`（去掉 `ws_call_id` 标识），千问、智谱取 `web_search_call.action.sources` 中 `type=url` 的网址（共用 `LLMWebSearchResultParser.collectWebSearchSources`）；文心（千帆智能搜索 Chat）取响应顶层 `references` 中 `type=web` 的网址与标题，正文 `^[n]^` 角标不解析。Chat 与 Responses 父类各提供同名默认空实现的补充入口。标题、摘要、位置留空且不抓取补全；检索词不作为引用；文本注解已有同一网址时保留注解资料。标准 url_citation（豆包、OpenAI）留在父类统一解析，不下沉到子策略。
- `LLMQueryRequest` 仅含 `prompt`、`channelCode`、`modelName`、`enableWebSearch`，每次独立调用，无角色枚举、消息列表或历史上下文。重复执行通过多次独立调用完成。
- 是否联网由调用方声明，不按渠道或提示词识别（2026-11-16 用户确认）：`enableWebSearch` 默认 false，只有诊断执行（`DiagnosisTaskWorker`）和测试接口用 `LLMQueryRequest.withWebSearch` 打开；制题、改写、报告判定、维度归一、优化建议、地域策略、内容优化等内部任务一律不联网，新增模型调用不写就不搜。Responses 父类判断开关，打开才调用子类的 `addWebSearchParams` 挂载厂商搜索参数；子类只把“要联网”翻译成自己的参数，不判断用途。千帆智能搜索（接口本身即搜索）、`gpt-5-search-api` 等搜索专用模型不受开关控制，内部任务不要选用。
- 密钥仅供服务端使用，响应只返回 `keyConfigured`；编辑空密钥保留原值，输入新值才替换。
