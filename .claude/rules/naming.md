# 命名规范

本文件是通用命名规则；本项目的类名前缀、表名与实体对应、控制器与 Business 同名等约定见[项目约定「命名与依赖注入」](project-conventions.md)。

# 1. 命名目标

- 见名知意
- 降低理解、沟通和误用成本
- 保持团队风格统一

禁止为了简短而牺牲语义。

---

# 2. 核心原则

## 2.1 优先表达业务语义

命名应优先说明业务对象、业务动作、数据含义、状态语义。

推荐：`createReport()`、`succeededResults`、`reportStatus`
禁止：`data`、`info`、`obj`

## 2.2 统一风格

同一项目中，相同概念使用同一单词，相同层级保持同一命名模式，禁止同一概念多种叫法混用。

例如：

- 不要同时使用 `userId` / `user_id` / `uid` 表达同一含义
- 不要同时使用 create / save / add 表达同一业务动作

### 2.2.1 相同机制、相同字段名

多个组件共用同一底层机制时，触发该机制的配置字段名必须完全一致，禁止为同一机制另起新名。

**反例**：组件 A 用 `earlyExitResponse` 触发提前终止；新写的组件 B 也触发同一套提前终止，却叫 `targetNotFoundResponse`。两个名字指向同一行为。

**正例**：组件 B 沿用 `earlyExitResponse`。

**判断方法**：新参数最终驱动的是同一段处理逻辑或写入同一个字段？是 → 必须用已有的名字。

## 2.3 长度适中

足够清楚即可，不必刻意最短，也不要无意义过长。

推荐：`judgeRetryCount`
不推荐：`cnt`、`numberOfRetryOperationsForCurrentReportJudge`

## 2.4 禁止无依据缩写

允许行业通用缩写：id、url、http、sql、dto、vo、llm。
禁止个人习惯缩写：usr、cfgInfo、tmpData、objVal。

---

# 3. 不同对象命名规范

## 3.1 类名

使用名词或名词短语，明确业务对象，按职责决定后缀。

推荐：`GeoDiagnosisReportService`、`GeoPromptTemplateDao`、`SavePromptTemplateRequest`、`PromptTemplateRenderer`
禁止：`DataManager`、`CommonProcessor`、`BaseHandler`、`TmpService`

---

## 3.2 方法名

方法名 = **动词 + 业务对象**，动词表达动作，业务对象说明操作目标。

### 固定动词表

遇到以下场景必须使用对应动词，保证全工程一致。示例均为本项目现有方法。

| 分类 | 动词 | 语义 | 示例 |
|---|---|---|---|
| 构造 | `build` | 以主对象为核心构造对象（来源单一） | `buildChannelSnapshot`、`buildQuestionSnapshot` |
| 构造 | `assemble` | 多来源拼装外部请求或响应（来源复杂） | `assembleLLMResponse` |
| 构造 | `convert` | A 对象 → B 对象，结构变语义不变 | `convertToStringList` |
| 校验/计算 | `validate` | 纯校验，失败抛 `IllegalArgumentException` 等明确异常，无返回值 | `validateQuestions`、`validateTemplateContent` |
| 校验/计算 | `calculate` | 纯计算，无副作用，返回计算结果 | `calculateRate` |
| 校验/计算 | `confirm` | 校验 + 计算混合，返回最终确定值 | `confirmQuestionRegion` |
| 查询 | `get` | 按主键 / 唯一键查单个，期望存在（不存在抛异常） | `getTask`、`getCurrentTemplate` |
| 查询 | `find` | 按条件查单个，允许不存在（返回 null / Optional） | `findCurrentTemplate`、`findByPlatform` |
| 查询 | `list` | 按条件查多个 | `listTasks`、`listTemplateVersions` |
| 查询 | `load` | 加载配置 / 上下文 / 批处理数据 | `loadJudgePromptTemplate`、`loadAnswers` |
| 持久化 | `create` | 新增记录 | `createReport`、`createTemplateVersion` |
| 持久化 | `update` | 更新已有记录；状态写操作写明结果 | `updateResultSucceeded`、`updateReportPaused` |
| 持久化 | `delete` | 删除记录 | `deleteProject`、`deleteChannel` |
| 外部发布 | `publish` | 向内容平台发布内容或草稿 | `publishDraft` |
| 编排 | `process` | 批量 / 流水线编排，无更具体业务动词可用 | — |
| 编排 | `handle` | 外部事件、回调或异常处理入口 | — |
| 编排 | `execute` | 后台任务或单次执行入口 | `executePlatform` |

表中未覆盖的场景，使用最贴近业务语义的动词，遵循"动词 + 业务对象"原则即可。

### 编排类动词使用条件

`process` / `handle` / `execute` **有条件允许**：**有更具体的业务动词时，禁止使用**。

| 动词 | 允许 | 禁止 |
|---|---|---|
| `process` | 批量/流水线编排，确实无业务动词可替代 | 替代具体业务动词，如用 `processReport` 代替 `createReport` |
| `handle` | 外部事件、回调、异常处理入口 | Service 内部方法的懒命名 |
| `execute` | 后台任务、单次执行入口 | 同上 |

### 永远禁止

```java
doData()    // 无任何语义
exec()      // 过于模糊
process()   // 无业务对象时禁止，如 process()、doProcess()
handle()    // 无业务对象时禁止，如 handle()、doHandle()
```

**例外：以下两种情况不受此限制**

1. **框架生命周期 / SDK / ORM 约定方法**（Spring、MyBatis 等框架强制命名，如执行器的 `start()`）
2. **自定义框架 / 抽象类 / 通用组件接口**，且类型名本身已提供足够语义

```java
// ✅ 类型名 DataProcessor 是语义锚点，process() 是刻意通用的扩展点
interface DataProcessor {
    void process(DataContext context);
}

// ❌ 业务 Service 里类型名不是语义锚点，必须用具体业务动词
class GeoDiagnosisReportService {
    void process(CreateReportRequest request) { ... }  // 应改为 createReport()
}
```

**判断标准**：类型名本身是否已提供足够语义？是 → 通用动词可接受；否 → 必须用具体业务动词。

---

## 3.3 变量名

表达真实含义，避免依赖上下文猜测，避免无语义占位词。

推荐：`enabledChannels`、`retryCount`、`judgeResult`
禁止：`list1`、`tmp`、`res`、`data`

## 3.4 常量名

明确语义，避免魔法值，尽量体现单位或边界。

推荐：`MAX_RETRY_COUNT`、`DEFAULT_TIMEOUT_MILLIS`、`CONTENT_MAX_LENGTH`
不推荐：`TIMEOUT`、`NUM`、`VALUE`

## 3.5 布尔变量名

使用 is / has / can / should / enable 等前缀。

推荐：`isEnabled`、`hasPermission`、`canRetry`、`shouldRefreshCache`
不推荐：`enabledFlag`、`permissionState`、`retry`、`cache`

## 3.6 集合变量名

体现元素是什么、集合类型或业务范围。

推荐：`taskList`、`activeChannels`、`roleSet`、`configMap`
不推荐：`dataList`、`allInfo`、`map1`、`setTmp`

---

# 4. 后缀使用规范

Request / Response、DTO / VO、Entity、Service / Dao / Controller、Factory / Builder 等后缀仅在语义明确时使用：

- 后缀必须体现职责
- 禁止仅为了显得规范而乱加后缀
- 同名不同职责对象必须靠后缀区分，如 `SavePromptTemplateRequest`、`GeoPromptTemplateEntity`、`GeoPromptTemplateDao`

---

# 5. 禁止命名场景

## 5.1 禁止使用无业务语义的通用词

data、info、item、thing、object 无法单独表达业务含义时，禁止直接使用。

## 5.2 禁止使用序号命名

`user1`、`user2`、`list3` 只能表示顺序，不能表达语义。

## 5.3 禁止中英混杂或拼音命名

禁止：`userMingdan`、`dingdanStatus`；除非项目已有明确历史约定，否则统一使用英文命名。

---

# 6. Review 检查项

- 命名是否见名知意
- 是否存在无意义缩写
- 是否存在同义词混用
- 是否存在职责误导
- 布尔命名是否像布尔含义
- 集合命名是否体现元素语义

---

# 7. 最终原则

优秀命名应做到：看名字就知道是什么、在做什么、边界大致在哪里。命名应帮助理解代码，而不是迫使读代码的人反复猜测。
