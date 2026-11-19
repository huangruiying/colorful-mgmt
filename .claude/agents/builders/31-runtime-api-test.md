# 31 运行时接口回归

## 职责

围绕用户指定的接口、curl、测试文档或当前分支改动，执行真实接口请求、日志归因、必要时一次最小修复、重启回归，并在 `.test/` 落地脚本和测试报告。单元测试由 06 负责。

## 何时使用

- 用户要求跑测试、真实接口回归、生成测试报告
- 用户提供接口地址、curl、`.test` 文档或 BASE_URL
- Workflow A 通过 09 后，用户确认继续真实回归（Workflow F）

## 执行

- 必须加载 [runtime-api-test.md](../../rules/runtime-api-test.md) 与 [testing.md](../../rules/testing.md)；前置检查、按 IDE 配置等价启动、临时 mock、单次自愈、报告字段都以该规则为准。
- 需要改代码时回到 04 / 05，并遵守 code-scope.md、refactor.md、comment.md 与 comment-java.md。

## 交付

- 测试报告路径；通过 / 失败 / 未测的接口
- 失败归因类型、保留的测试数据、非常规处理及清理状态
