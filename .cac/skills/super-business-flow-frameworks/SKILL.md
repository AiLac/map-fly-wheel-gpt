---
name: super-business-flow-frameworks
description: 根据程序员给出的 RPC 示例和提示，独立扫描注解、注册器、代理和依赖源码，共创可验证的框架协议与有限匹配规则，供业务链路和端点映射使用。
---

# RPC 框架学习

主管 `docs/super-business-flow/frameworks/<rpc-id>/`。可在主流程之前独立执行；不要求已经存在端点映射。读 [框架流程](../../../docs/super-business-flow-tookit/design/architecture.md)；写可执行规则时读 [映射契约](../../../docs/super-business-flow-tookit/design/mapping-contract.md)；文件起点见 [模板目录](../../../docs/super-business-flow-tookit/templates/README.md)。

接收程序员提供的代码、提示、已知调用对和框架线索，保存到 `input.md`。先用实际 imports、注解声明和依赖坐标/版本识别实现。缺少框架身份时可以继续收集证据，但不能把同名注解当成某公开框架，也不能从一个成功调用对推广为通用规则。

沿证据链调查：注解解析 → 扫描/注册 → 客户端代理 → 服务身份 → schema/operation 选择 → 请求参数和响应适配 → 配置来源及覆盖顺序。优先按符号和依赖定位，必要时读取精确版本的 Maven sources JAR。源码缺失或多个版本冲突时记录问题，禁止用最新公开版本替代当前依赖。

特别检查：调用字段与外层包装类实现同一接口；服务端是否必须实现消费接口；别名/重载；相同 schema 跨服务重名；字符串常量；泛型和响应包装。保留原始 service 标识，验证前不解释冒号含义，不假设 Java 方法名就是 operation ID，不默认 `ResponseEntity<T>` 与 `T` 可互换。

把成果拆为：精简 `protocol.md`、有限 DSL `rules.yaml`、`manifest.yaml`、持久 `evidence.jsonl`、原始 `examples/`、`validation.md` 和 `questions.yaml`。协议说明语义；规则只写当前工具确实支持的提取/匹配操作。语义已理解但 DSL 不支持时标记工具缺口，不伪造规则实现。

用独立已知调用对、框架自身测试或留出真实样本校验，并加入至少一个会错误匹配的负例。模型用自己生成的规则再生成期望结果不算验证。每条结论区分代码观察、用户确认、待验证解释；规则只有在身份、适用版本和匹配语义有证据时才可标记可用。

在任务范围与预算内可委派 `super-business-flow-mechanism-explorer` 做机制调查，并由 `super-business-flow-reviewer` 检查结论。主代理合并规范；子代理只提交任务产物。遇到未知规则时给出候选、证据和准确问题，停止受影响规则。不要通过降低置信度使猜测进入生效映射。

完成时列出已学习能力、适用版本、验证样本、未支持能力及待确认问题；新规则通过规范路径发布后，再让 memory 更新引用。脱敏演示样例不能声明为用户真实仓库已验证规则。
