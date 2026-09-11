---
name: super-business-flow-scenarios
description: 从确认的业务入口沿本地代码和显式 RPC 关联展开静态可能路径，整理条件、返回、异常、异步边界和覆盖缺口，形成有证据的场景图。
---

# 场景分析

主管 `docs/super-business-flow/scenarios/<scenario-id>/`。读 [图与发布契约](../../../docs/super-business-flow-tookit/design/publication-contract.md) 和 [任务协作规范](../../../docs/super-business-flow-tookit/design/architecture.md)，按 [场景模板](../../../docs/super-business-flow-tookit/templates/scenarios/input.md) 确定入口和范围。

使用固定代码快照、已验证 framework revisions 和 effective mappings。从用户类/URL实际定位业务入口，不要求命名或注解 stereotype；继承/注册无法解析时继续查代码或提问。分析对象是业务可能路径，不能声称某请求实际执行了图中步骤。

先找参数验证、上下文构造和顶层阶段，再沿返回值、业务关键字段与远端调用按需展开。对每条边保存 source evidence，区分直接声明解析、已确认实现选择、条件路由和未决目标。接口注入、多态、反射或动态配置有多候选时，不用“最像”的实现补链。

保留 if/else、早返回、循环、catch/finally、fallback、异步启动/汇合/聚合及相关字段变化。一般请求条件保留双方与源表达式，不询问运行时取值。循环和回边用图表示，不无限枚举路径；发现异步调用不能把源码顺序直接当执行顺序。不能确认执行时序时标注边界。

使用 `super-business-flow-local-tracer` 按入口阶段或有限 frontier 拆分工作，分包提供必要代码和证据引用。一个 repo 不自动对应一个子代理。任务图可以是 DAG，业务图允许环。子代理输出 nodes/edges/guards、claims、evidence、frontier 和问题；由主代理统一验证并合并。

将代码可证事实与业务解释分开。业务阶段名称可以归纳，但解释必须引用实际处理、输入输出或字段证据；缺少领域含义时询问，不从类名臆测召回/排序算法。已知外部系统终点与因缺失仓库而停止的边界要分开。

遇到不确定机制/目标停止受影响分支，记录问题与后续最小查证动作，继续独立路径。预算到达时做 checkpoint，保留 frontier，禁止为了“完整”删除边界。最终输出 graph、场景范围、字段摘要和覆盖报告，状态 complete/partial 与实质覆盖一致，再交 knowledge。
