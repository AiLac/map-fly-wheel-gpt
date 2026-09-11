# Agent 文件头修正与验证

日期：2026-09-11 UTC。用户要求三个 Agent 文件头符合 Claude Code 格式。

原文件仅有 `description` 和 `mode: subagent`，缺少 Claude Code 要求的 `name`；`mode` 不在其支持的 frontmatter 字段中。依据 [Claude Code 官方规范](https://code.claude.com/docs/en/sub-agents#supported-frontmatter-fields)，为以下文件补充与文件名一致的 `name`，保留原 `description`，移除 `mode`：

- `.cac/agents/super-business-flow-local-tracer.md`
- `.cac/agents/super-business-flow-mechanism-explorer.md`
- `.cac/agents/super-business-flow-reviewer.md`

统一格式示例：

```yaml
---
name: super-business-flow-local-tracer
description: 在冻结快照和有限入口范围内展开本地业务调用、条件和返回，返回可合并的图与证据
---
```

采用最小必填字段，避免为内部宿主写入未经确认的模型 ID 或工具名称。备选方案是显式设置 `model`、`tools`、`permissionMode` 等可选字段，适合已确定运行宿主和权限策略的工程；本次只修正文件格式。正文中的任务范围、禁止继续派发和限定输出目录仍保留，它们是行为指令，不等同于宿主强制权限控制。

验证范围：解析全部三个 YAML 文件头，检查字段集合为 `name`、`description`，名称唯一、仅小写字母和连字符、与文件名一致，描述非空；比较修改前后正文和描述，确认没有业务指令变化。未改 Java 工具，本次不重复 Maven 测试。

目录仍采用用户既定的 `.cac/agents/`；Claude Code 原生项目发现目录是 `.claude/agents/`。本次没有执行公司内部宿主或 Claude Code 的实际加载验证，不据此声称 `.cac` 会被原生 Claude Code 自动发现。
