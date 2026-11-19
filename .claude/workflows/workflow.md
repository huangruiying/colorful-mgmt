# Agent 清单与加载时机

任务路由以 [CLAUDE.md](../CLAUDE.md) §3 为准，本文件只维护 Agent 清单、懒加载原则和输出要求。

## Agent 清单

文件位于 `agents/<分组>/<编号>-<名称>.md`。

| 分组 | 编号 | Agent | 用途 |
|---|---|---|---|
| planners 需求与设计 | 01 | requirements-breakdown | 需求拆解与澄清 |
| | 02 | architecture-review | 架构评审 |
| | 03 | api-contract-design | 接口契约与接口文档 |
| | 24 | system-design-tradeoff | 多方案取舍 |
| builders 研发实现 | 04 | code-generation | 代码实现 |
| | 05 | refactor | 代码重构 |
| | 06 | unit-test-gen | 单元测试 |
| | 07 | doc-gen | 开发文档与详细设计 |
| | 27 | db-schema-review | 表结构评审 |
| | 31 | runtime-api-test | 真实接口回归 |
| guardians 质量与安全 | 08 | vuln-scan | 通用漏洞扫描 |
| | 09 | code-review | 代码复核 |
| | 10 | injection-xss | 注入与 XSS 专项 |
| | 11 | dependency-audit | 依赖审计 |
| | 12 | perf-hotspot | 性能热点分析 |
| | 26 | threat-modeling | 威胁建模 |
| responders 故障排查 | 14 | log-rca | 日志根因分析 |
| | 16 | slow-sql | 慢 SQL 分析 |
| stewards 治理与知识 | 20 | tech-debt | 技术债识别 |
| | 21 | pr-summary | PR 描述 |
| | 22 | knowledge-qa | 知识问答 |

2026-11-19 按“本项目没有对应的基础设施或流程”删除了 15 告警分诊、17 配置漂移、23 值班沉淀、25 ADR、28 分布式排障、29 成本优化；30 路由并入 [routing-policy.md](routing-policy.md)。编号不复用，避免与旧记录混淆。

## 专题角色懒加载

- 普通知识问题只读相关代码，必要时读 22；不展开实现流程。
- 架构设计读 02，取舍读 24，表设计读 27，文档生成读 07。
- 其他 Agent 仅在当前流程实际进入对应职责时读取。
- 工程规则集中在 rules；角色文件只写职责、使用时机、要读的规则和交付证据，不复制规则正文。
- 编码和评审使用同一份 [注释与架构验收步骤](code-change-flow.md)，不能各自维护不同标准。
- 多候选主流程选择和风险升级见 [routing-policy.md](routing-policy.md)，不强制用户选择内部 Agent 编号。
- 用户未要求创建任务或并行代理时，以当前任务内顺序切换职责完成工作。

## 输出要求

按用户任务报告结果、证据和限制；代码变更须给出公共流程要求的验收记录。
不强制每次输出五个英文栏目或 Next Agent，不在回复中重复路由和规则正文。安全、故障、技债类结论需要分级时用 CRITICAL / HIGH / MEDIUM / LOW。
