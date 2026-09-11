# 静态扫描器的证据契约与能力边界

## 实际调用与路径转换

```powershell
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar scan --help
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar scan --repo-id caller --module-id application --source-root ../caller-repo/application/src/main/java --classpath-file docs/super-business-flow/project/local/caller-application.cp --out .temp/run/super-business-flow/RUN_ID/tasks/TASK_ID/facts.json
```

`--source-root` 可重复，`--classpath-file` 可省略（会报告依赖解析缺口）。CLI 路径相对于 `--project` 或当前项目根，也接受绝对路径。配置中的 module.source_roots / classpath_file 相对 repo.path：先计算绝对 `repoRoot = projectRoot.resolve(repo.path)`，再计算 `repoRoot.resolve(module.source_roots[i])`；classpath 同理，然后作为独立参数传给 CLI。**不要把模块相对路径直接当作项目相对路径。** 例如 repo.path=../caller-repo、source_roots=[application/src/main/java]，CLI 应传 ../caller-repo/application/src/main/java。

输出必须位于项目 `docs/super-business-flow/` 或约定运行目录，不能写入 `docs/super-business-flow-tookit/`。子任务使用自己的 tasks/<id>/facts.json；主 Agent 审核后才把采纳内容发布到持久目录。CLI 不修改扫描源码、不调用 Maven、不执行应用。


`ScanService` 生成可复查的 Java 源码事实，供 Agent 继续梳理业务链路。它不输出“该请求实际执行过这些调用”的结论，扫描结果的 `coverage.business_flow_complete` 固定为 `false`。任务图、业务图、源码 AST 是三种不同结构，不能相互替代。

## 为什么采用模块隔离的 AST 扫描

Java 21 + JavaParser 3.28.2 读取源码结构；每次扫描创建独立的 Symbol Solver，仅装入该模块源码、JDK 与显式 Maven classpath。不同仓库可使用同一依赖的不同版本，将所有 JAR 合并会产生错误的方法绑定。测试使用两个同名依赖类的独立 JAR，验证前一次扫描的类路径不会污染后一次扫描。

扫描器初始化时关闭 Javassist 的 `ClassPool.cacheOpenedJarFile`，防止扫描完成后 JAR URL 缓存继续占用依赖文件。该设置作用于本工具进程的 Javassist，不改变各模块独立的 Symbol Solver；不在单次扫描结束后恢复，以免并发扫描重新启用缓存。代价是可能增加 JAR 打开次数，当前没有性能基准。工具按独立 CLI 进程运行；若未来嵌入其他 JVM，应重新评估此全局设置。验证详见 [文件句柄修复记录](validation-jar-handles.md)。

| 方案 | 收益 | 代价与适用边界 |
|---|---|---|
| 当前：AST 事实 + 模块 classpath + Agent 复查 | 不依赖运行环境，能保留源码证据；复杂机制可以停下确认 | 依赖缺失、反射、注册机制需要追加调查 |
| 仅全文搜索与模型阅读 | 启动快，适合寻找入口和框架实现 | 同名注解、重载、局部变量遮蔽容易误判，不承担确定性绑定 |
| 编译器/字节码全程序分析 | 类型及部分调用图更深入 | 多仓库编译和生成代码成本高，仍不能证明配置与运行时路由 |
| 日志/Trace | 证明一次执行经过的路径 | 需要运行数据；未出现的分支不代表不存在，属于后续阶段 |

## 输出事实如何使用

| 数据 | 已实现的依据 | 使用限制 |
|---|---|---|
| `classes` / `methods` | 类、成员、类型、声明注解及方法签名 | 类型解析失败保留源码写法；方法声明不是实际 Bean |
| 注解 | 显式全名、单条精确导入或 Symbol Solver；属性保留 `values` 和 `raw_values` | 通配符导入且无法解析时不猜包名；常量和配置表达式不伪装成字符串值 |
| `calls` | 调用发生点、接收字段、参数类型、可解析的声明目标 | `declaration_only` 不等于运行时实现类；不会因为包装器 `implements` 就绑定到自己 |
| `conditions` | if/else、三元表达式、短路表达式、循环区域、catch、switch 和前置明显早退 | 是带源码位置的条件观察，未做 SSA、完整 CFG 或可满足性求解；不能据此枚举实际执行顺序 |
| `methods[].exits` | return/throw 及其所在条件区域 | lambda 内 return 属于延迟上下文；不能当作外层方法返回 |
| `endpoints` | 精确识别的 Spring 直接声明，组合字面量类路径与方法路径 | 仅 `declared_spring_mapping`；不验证是否注册、网关前缀、context-path 或实际 HTTP 请求 |
| `evidence` | 真实源码路径、行范围、SHA-256 | 解析与哈希读取同一份字节；发布时仍需验证快照与持久化证据 |
| `diagnostics` | 解析错误、缺依赖、未解析调用、路由候选等 | Agent 必须纳入覆盖缺口与问题清单，不能忽略后声称完整 |

`scan --out` 会将项目内证据转换成项目相对路径，并限制输出在 `docs/super-business-flow/` 或 `.temp/run/super-business-flow/`。直接调用 Java `scan(...)` API 得到绝对源码路径，发布者应结合配置中的仓库根目录正规化。

ID 使用仓库/模块、类/方法及调用表达式的同表达式出现序号，不把行号作为业务实体身份。增加空行或说明性注释不会改变方法和调用 ID；源码哈希与证据 ID 会变化。变更方法签名、调用表达式或插入同名重复调用仍可能改变相关 ID，应进入快照失效流程。

## 必须保留的歧义

- 入口不依赖 `Controller` 后缀或 Spring stereotype。Service 层只要有已识别的明确声明，也进入入口候选清单；是否被自研框架真正注册，由对应框架规则判断。
- `RpcReference` 的原始服务名与 `schemaId` 只作为字段注解事实保留。冒号含义、operation 选择、响应包装适配由 `frameworks` Skill 学习；扫描器不包含这组自研 RPC 的通用假设。
- 参数或局部变量遮蔽 RPC 字段时，不套用字段注解。provider 不实现 caller 接口，也不影响后续显式端点匹配。
- 两个方法声明同一 URL 时保留两者，并产生消歧诊断。`params`、`headers`、`consumes`、`produces` 等条件保留原始属性，Agent 应据此细化选择。
- 有 `@RequestMapping` 类前缀但表达式不可确定时，不生成仅有方法后缀的假 URL。类级通配符与子路径组合暂不计算，防止误用简单字符串拼接替代框架路径规则。
- Spring HTTP method 条件按 `RequestMethodsRequestCondition.combine` 的集合并集处理，未声明条件由另一侧约束；不能取交集。该事实来自 [Spring 官方 API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/condition/RequestMethodsRequestCondition.html)。此处只覆盖标准直接注解；内部 fork 改写的语义须另行验证。
- lambda 中调用标为 `deferred_lambda`，具体执行时机未知。方法名 `submit`、`join` 等仅触发导航提示，不证明异步启动、汇聚或等待关系。
- 局部/匿名类、构造器与 initializer 的业务流暂列缺口，不能把内部代码误放到外层方法调用序列。方法引用也保留待分析诊断。
- 组合注解、接口/父类继承映射、反射、动态注册、生成代理、跨过程数据变换和完整异常流，由 Agent 按证据进一步展开。扫描器的有限守卫不等于业务图已经完成。

## 验证夹具

`tools/src/test/resources/scanner/` 是合成源码，故意包含无 Spring/RPC 依赖的精确导入和缺失下游实现。它验证未知行为保留未知，不代表用户项目的已确认协议。

`ScanServiceTest` 覆盖 Service 层入口、代码证据、条件与早退、lambda、局部/匿名类型隔离、RPC 包装器、字段遮蔽、同名假注解、未知前缀、URL 组合及候选歧义、注释位移、语法错误、模块 JAR 版本隔离和带空格路径。是否执行通过及具体环境见交付验证记录。
