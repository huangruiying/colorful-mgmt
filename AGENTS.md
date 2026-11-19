# AGENTS.md — colorful-geo-mgmt 协作入口（Codex 等工具）

> 本文件供 Codex/GPT、WorkBuddy 等开发工具使用，只做入口约束，不复制工程规则。
> 完整规则、Agent 路由、Workflow、注释、命名、重构、日志、异常处理，统一以 [.claude/CLAUDE.md](.claude/CLAUDE.md) 为唯一规则源。

## 语言要求

始终使用中文与用户交流，包括解释、说明、步骤、总结和提问；除非用户明确要求使用其他语言。

## 本地文件读取

当用户提供本地绝对路径时，优先直接读取本地文件。
除非读取工具实际报错，否则不要先假设无法访问本地文件，也不要要求用户手动粘贴文件内容。

## 接手顺序

1. 阅读本文件与 [当前交接](.doc/HANDOFF.md)，再检查 `git status`、最近提交和相关 `git diff`。交接是快照；代码、数据库实际状态和可重复测试优先。
2. 读取 [.claude/CLAUDE.md](.claude/CLAUDE.md)、[项目架构与代码约定](.claude/rules/project-conventions.md) 与 [.claude/memory/MEMORY.md](.claude/memory/MEMORY.md)，再按 CLAUDE.md 的路由结果加载对应 workflows / agents / rules。不要把旧仓库路径当成当前路径。

## 强制约束

- 不允许绕过 `.claude/CLAUDE.md` 直接执行复杂开发任务；除非是 10 行以内的简单修改，否则先给出规划并与用户确认，再进入开发。
- 生成或修改代码时，按 CLAUDE.md 指向的代码变更流程执行注释、重构、注释同步和逐方法审计要求。
- 永远不要自动提交 git commit，除非用户明确要求。

## 项目记忆维护

`.claude/memory/` 只记录长期有效、跨会话有复用价值的内容，不记录临时实现细节。

当发现用户反馈、工程决策或协作偏好可能值得长期沉淀时，先判断归属：

- 强执行规范：建议写入 `.claude/rules/`
- 项目背景 / 设计决策 / 协作偏好：建议写入 `.claude/memory/`
- 当前任务细节：不写入 memory

除非用户明确要求“写入 memory / 记录下来 / 更新规则文件”，否则不要直接修改 `.claude/memory/`；先说明建议沉淀位置并等待确认。

## graphify

- **graphify** (`~/.Codex/skills/graphify/SKILL.md`) - any input to knowledge graph. Trigger: `/graphify`
- 当用户输入 `/graphify` 时，先调用 graphify skill，再处理其他任务。
