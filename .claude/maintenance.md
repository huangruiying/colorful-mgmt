# 协作规则维护与 hook

## 单一归属

工程要求写 rules，执行顺序写 workflows，环境位置写 memory。入口只负责授权、证据与路由。新增条款先查现有归属，使用 Markdown 链接引用，不复制正文。已确认的注释硬要求不可因精简弱化。

删除或搬移前检查引用者；当前入口、规则与流程全部更新，历史交接保留其时间语境。规则整理记录放当前 `.doc` 验收文档，不重新塞回规则形成第二套正文。

Claude Code 会自动加载 `.claude/rules/` 下的规则。本项目给专题文件加 `paths` frontmatter 限定触发路径，项目约定保留全局加载；Codex 等工具仍按入口显式读取。frontmatter 和链接都不能证明完整正文已读。

## 运行检查

在仓库任意位置执行以下命令时，脚本路径应以仓库根目录为基准：

```sh
python3 .claude/scripts/check_context.py
python3 -B -m unittest discover -s .claude/scripts -p 'test_*.py'
```

[检查器](scripts/check_context.py) 只读 `.claude` 文档、根 AGENTS 和两个客户端的 JSON 配置，检查：

- Markdown 本地路径与章节存在，规则能从入口沿链接到达；忽略代码块示例，不请求远程链接。
- 必需入口/配置存在、JSON 有效且无重复键，hook 使用限时共用脚本；专题 paths 使用带引号的字符串列表，防止格式损坏退化为全局加载。
- 长行重复正文候选只提醒，明确结构错误返回非零；不把近义、冲突、注释准确性或业务正确性声称为自动验证。

旧文件引用通过失效链接检查发现，裸文本文件名及近义重复仍需人工检索。检查器不是通用 Markdown 解析器，路径引用应使用普通内联链接；不支持的复杂语法不要假定已覆盖。

修改检查器时运行 [回归用例](scripts/test_check_context.py)，涵盖坏链接、章节、漏入口、无效配置、重复候选与 hook 防循环。人工继续核对删改去向、模块边界、保留要求及每条任务路由。

## 两种客户端接入

- Claude Code：项目配置 [settings.json](settings.json)。
- Codex：项目配置 [hooks.json](../.codex/hooks.json)。
- 两者共用 `SessionStart` 与 `Stop`：开始时短提醒读取入口，结束时运行结构检查；明确错误请求修正一次。收到 `stop_hook_active` 后只报告残余错误，避免无限续跑；重复候选不阻止结束。
- hook 不执行构建、网络、写库、重启或 Git 变更，不读取会话记录，不维护“已经读过规则”的虚假账本。解析或环境故障只告警，不能当成检查通过。
- Python 3.9+ 与 Git 在 PATH 可用；Codex 命令通过 Git 确定当前 worktree 根，Claude 命令使用其项目目录变量，均对路径加引号。

配置写入不代表客户端已启用。Codex 要求审阅并信任当前 hook 定义；项目层还需被信任，组织策略也可能禁用本地 hook。Claude 修改 hook 后应在 `/hooks` 检查加载状态，必要时重开会话。不能通过改信任文件或绕过参数伪造启用。

核对来源：[Codex 官方 hook 文档](https://learn.chatgpt.com/docs/hooks)、[Claude Code 官方 hook 文档](https://code.claude.com/docs/en/hooks)、[Claude 规则加载说明](https://code.claude.com/docs/en/memory#path-specific-rules)。本机可直接测试命令及事件协议；真实生命周期触发须由客户端日志或界面确认，二者分开报告。

本地权限文件只保留必要读取、编辑及只读 Git 白名单，不把历史的一次性 curl、宽泛 shell 执行或个人路径当成共享能力；权限不是任务授权，用户指令与运行环境限制始终优先。
