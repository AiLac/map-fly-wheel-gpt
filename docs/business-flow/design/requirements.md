# 已确认需求与验收边界

本文件记录用户已经确认的任务范围，供后续 Agent 恢复工作时使用。约束变更须有新的用户依据；不从历史草案或示例推导新的授权。

## 使用场景

用户希望从地图 POI 检索的一次业务动作入口出发，了解请求处理、条件路径、跨服务 RPC 和返回，生成开发人员可阅读、AI 可追溯复用的知识。输入提供方 URL 或全限定类名，类可以位于 Service 等任意层。分析的是业务场景的静态可能链路。

| 项目 | 已确认要求 |
| --- | --- |
| 工作区 | 5–15 个独立 Git 仓库放在统一目录；不合并 Git 历史 |
| 技术环境 | Java / Spring；目标机器 Windows，JDK 21；可取得 Maven 依赖 |
| Agent 宿主 | 公司基于 OpenCode 的 Agent；配置目录 `.cac` |
| 方案形态 | Skill + 本地分析工具 + 结构化配置，先跑通个人 PC 流程 |
| 输出 | Markdown 业务概述、流程图、代码跳转链接，保留机器可读图和证据 |
| 输入方式 | `--class`、单个/多个/重复 `--url`、`--all`、`--resume <run-id>` |
| Skill 名字 | 一律 `super-business-flow` 前缀，子 Skill 后缀与工作目录一致 |
| 目录 | Agent 集成放 `.cac/`；其他持久产物放 `docs/business-flow/` |
| 运行目录 | 最新明确约定为 `.temp/run/super-business-flow/<run-id>/` |
| 配置 | 总并发与阶段限制等可配置，配置不能绕过不确定性停止规则 |
| RPC 知识 | 程序员给出示例/提示，独立 frameworks Skill 扫描代码共创规则 |
| 端点关联 | 自动生成 + 独立人工修訂 + 生效视图，支持补充遗漏和更正错误 |
| 项目记忆 | 自动新增/更新；创建和更新时间；使用中发现过时可更正/删除 |
| 提交方式 | 产物及过程设计统一提交到 `AiLac/map-fly-wheel-gpt` |

## 六个 Skill 的职责

| Skill | 主管目录 | 完成物 |
| --- | --- | --- |
| `super-business-flow` | `docs/business-flow/` | 输入归一、阶段调度、检查点、汇总 |
| `super-business-flow-frameworks` | `frameworks/<rpc-id>/` | 精简协议、可执行规则、证据和验证 |
| `super-business-flow-mappings` | `mappings/` | 服务端点与调用点关联及人工修订 |
| `super-business-flow-scenarios` | `scenarios/<scenario-id>/` | 静态业务图、条件、frontier 与覆盖 |
| `super-business-flow-knowledge` | `knowledge/<scenario-id>/` | 可追溯的业务文档与 Mermaid |
| `super-business-flow-memory` | `memory/` | 带时间、版本、证据与删除墓碑的记忆 |

## 不确定性停止规则

正常输入值引起的 `if/else` 不需要向用户确认，分析保存双方可能路径。若机制、候选入口、注入实现、远端身份或缺失业务语义无法以代码/规则查证，则记录证据和明确问题，停止受影响分支，不能挑选“最像”的候选或用低置信度包装猜测。独立且证据充分的范围可以继续。

类与 URL 同时输入是交集约束；相互冲突时不擅自改成并集。`--all` 覆盖已登记仓库和已识别机制，必须列出未支持的暴露方式，不能宣称已发现所有运行时入口。实际服务端可以不实现消费接口；封装类 implements 不能覆盖 RPC 字段的接收者证据。

## 本次交付与后续规划

本次交付包括可运行本地工具、6 个 Skill、受控任务和记忆流程、规范模板、示例及设计记录。示例验证工具行为，不是对公司私有项目的已完成分析。公司 RPC 规则须在后续使用 frameworks Skill 时结合真实代码学习。

后续 badcase 输入可携带预期结果、日志和 Trace，将实际执行证据叠加到本次静态图；再分析差异、提出优化建议并验证效果。当前不实现日志平台、向量数据库、图数据库、服务部署、生产改动或自动优化闭环。
