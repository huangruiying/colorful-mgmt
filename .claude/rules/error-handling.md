# 异常处理规范

本项目接口出错统一返回 Spring `ProblemDetail`，异常到 HTTP 状态的映射及各 Controller 的模块特有处理见[项目约定「接口异常响应」](project-conventions.md)。本文件只写通用的处理原则。

---

## 1. 异常怎么选

| 场景 | 抛什么 | 接口结果 | 日志 |
|---|---|---|---|
| 入参不合法、业务前置条件不满足 | `IllegalArgumentException`，消息写页面可以直接展示的原因 | 400，全局处理 | warn |
| 记录不存在、必需配置缺失（如提示词未配置） | `NoSuchElementException` | 404，全局处理 | warn |
| 模块有特定语义的失败（如发布记录状态冲突、模型调用失败、渠道编码重复） | 模块已有的异常（`IllegalStateException`、`LLMClientException`、`DuplicateKeyException` 等） | 在对应 Controller 映射 | 由处理处决定 |
| 数据库、外部服务等系统故障 | 原始异常向上抛，不改写成业务异常 | 500，响应不透出内部细节 | error + 堆栈 |

- 不引入 `BizException`、错误码枚举或 `{code, message}` 响应结构；需要新的状态映射时，按项目约定加在对应 Controller 或全局处理里。
- 异常消息写业务原因，不拼接下层异常的 message；原始异常作为 cause 保留。

---

## 2. 核心规则

### 2.1 禁止吞异常

```java
// ❌
try {
    run();
} catch (Exception exception) {
}

// ✅ 能恢复才 catch，否则向上抛
try {
    run();
} catch (SpecificException exception) {
    fallbackOrThrow(exception);
}
```

### 2.2 两种抛出写法

- 校验拦截直接抛，不需要 cause：`throw new IllegalArgumentException("每次生成 1 到 100 个问题");`
- catch 中转换已有异常，必须保留 cause，message 自己写干净的业务描述：

```java
} catch (JSONException exception) {
    throw new IllegalArgumentException(outputName + "不是有效 JSON", exception);      // ✅
    // throw new IllegalArgumentException("解析失败：" + exception.getMessage());     // ❌ 丢了 cause，还混入技术细节
}
```

### 2.3 捕获具体类型

先捕获可识别的失败，再按需兜底；兜底的 `catch (Exception)` 只放在真正的边界（后台执行器的单轮处理、接口的全局处理），并记录 error 日志。

### 2.4 非 HTTP 场景在边界处理

后台执行器等非 HTTP 场景在边界 catch：记录原因后转成业务状态（如诊断结果失败、报告暂停），不让异常中断轮询线程；中间层不层层 catch。

### 2.5 同一异常不重复打印

谁真正处理，谁记录；禁止每层 catch 都打印一次。

### 2.6 非核心流程异常隔离

写代码或抽取方法时，先按 `refactor.md` 的“核心流程与非核心流程识别”判断职责归属。

非核心流程不得阻断主流程；非核心流程抽成方法时，方法内部自行 `try-catch`，失败后记录 `warn` 日志并吞掉异常（如报告子步骤进度写库失败）。

---

## 3. 重试 / 降级规则

**可重试**：瞬时网络抖动、第三方超时、临时资源竞争
**不可重试**：参数错误、数据格式错误、明确业务拒绝

降级必须显式：说明为什么降级、降到什么行为、对结果的影响。
禁止悄悄失败后继续返回“看似正常”的数据。

---

## 4. 幂等写入规则

适用于：批量落库、重复提交、并发领取任务。业务决定与 DAO 的分工见 [architecture.md](architecture.md) §3。

- 返回必须区分新增与跳过，不能让调用方误以为全部新建成功。
- 只忽略“已存在”冲突（如唯一键冲突），其他数据库异常必须继续抛出。
- 禁止在 Controller / Business 层散落重复查重逻辑。

---

## 5. 分层职责

| 层 | 负责 | 禁止 |
|---|---|---|
| Controller | 接口映射，以及项目约定里属于本模块的异常映射 | 写业务异常分支、暴露底层细节 |
| Business / Service | 定义业务失败语义，决定重试、降级、回滚 | 返回 null 代替明确失败 |
| DAO / 外部客户端 | 保留数据访问或外部调用失败的事实，向上抛可识别异常 | 吞异常、把错误伪装成空数据 |

---

## 6. Review 检查项

- [ ] 是否存在吞异常 / 无意义 catch
- [ ] 是否丢失原始 cause
- [ ] 是否把失败伪装成成功（返回空集合 / false）
- [ ] 异常类型与接口状态是否符合第 1 节和项目约定
- [ ] 重试是否只用于可恢复异常
- [ ] 同一异常是否被重复打印
