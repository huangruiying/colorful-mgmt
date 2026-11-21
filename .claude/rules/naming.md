---
paths:
  - "src/**/*.java"
  - "src/**/*.js"
---

# 命名规范

表名、实体、DAO、Service、接口模型与注入方式见 [项目约定「命名与依赖注入」](project-conventions.md)。本文件只定义动词和语义一致性。

## 通用语义

- 类名用业务对象与职责，变量说明数据含义，常量体现单位或边界；避免 `data`、`obj`、`tmp` 等无语义占位名及个人缩写。
- 相同概念、底层机制和配置使用相同单词与字段名，不因新场景重新起名。允许 id、url、http、sql、llm 等通用缩写，不用拼音或中英混杂。
- 方法名为“动词 + 业务对象”，类名带上下文也不省略对象。

## 动词表

| 动词 | 含义 | 示例 |
|---|---|---|
| build | 以主对象构造新对象 | buildChannelSnapshot |
| assemble | 多来源组装 | assembleLLMResponse |
| convert | 结构转换，业务含义不变 | convertToStringList |
| validate | 校验，失败抛异常 | validateTemplateContent |
| calculate | 无副作用计算 | calculateRate |
| confirm | 校验并确定最终值 | confirmQuestionRegion |
| get | 按唯一条件查单个，业务期望存在 | getTask |
| find | 允许不存在的单项查询 | findCurrentTemplate |
| list | 按条件查多个 | listTasks |
| load | 加载配置、上下文或批处理数据 | loadAnswers |
| create | 新增记录 | createReport |
| update | 更新，状态操作写明结果 | updateResultFailed |
| delete | 删除记录 | deleteProject |
| publish | 向平台发布内容或草稿 | publishDraft |
| execute | 后台任务或单次执行入口 | executePlatform |
| handle | 事件、回调或异常入口 | 按实际事件命名 |
| process | 确无更具体业务动词的批量编排 | 带实际业务对象 |

框架生命周期、SDK/ORM 强制命名及已有通用组件接口可保留契约方法名；不能强改 `getById` 等继承方法，或为凑命名新增包装层。

布尔方法用 is、has、contains，能力与策略判断也可用 can、should；集合名体现元素或业务范围。状态写操作写清结果，名词不直接当方法名。`save` 只用于真实的新增或更新语义，不代替明确的状态迁移。
