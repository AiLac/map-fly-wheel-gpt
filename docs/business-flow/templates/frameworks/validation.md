# 框架验证记录

- framework_id/revision：`<当前版本>`
- 执行时间与环境：`<UTC 时间、JDK、模块依赖>`
- 规则来源：`<精确规则与指纹>`
- 状态：candidate / verified / partial

| case_id | 独立期望来源 | 实际结果 | 差异 | evidence_refs |
| --- | --- | --- | --- | --- |

## 结论适用范围

逐项记录服务身份、schema、operation、参数/响应适配及配置优先级。没有覆盖的机制保留 unknown。

## 反例和复核

说明错误同名注解、跨服务相同 schema、重载/别名、wrapper 接收者等已覆盖反例。独立 reviewer 的结论不替代原始证据。
