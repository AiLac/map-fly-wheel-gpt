# Super Business Flow 项目数据

这里保存本工程的配置、共创规则与分析产物。工具升级应保留整个目录；工具代码、模板和工具设计说明位于相邻的 [`super-business-flow-tookit/`](../super-business-flow-tookit/README.md)。

## 查找项目内容

| 目录 | 内容 | 主要维护者 |
| --- | --- | --- |
| `project/` | 仓库、模块、服务、并发和记忆配置；`local/` 保存本机依赖清单 | 程序员与主 Skill |
| `frameworks/<rpc-id>/` | RPC 输入、精简协议、规则、证据、验证和待确认问题 | `super-business-flow-frameworks` 与程序员 |
| `inventory/` | 源码事实、入口目录与解析诊断 | 分析工具；可按源码快照重建 |
| `mappings/` | 自动关联、补充端点、人工覆盖和生效视图 | `super-business-flow-mappings` 与程序员 |
| `scenarios/<scenario-id>/` | 条件业务图、入口、覆盖和未决问题 | `super-business-flow-scenarios` |
| `knowledge/<scenario-id>/` | 业务概述、Mermaid 流程图、代码链接和发布版本 | `super-business-flow-knowledge` |
| `memory/` | 可复用发现、创建/更新时间、历史版本与删除记录 | `super-business-flow-memory` |
| `evidence/` | 发布结论可复查的持久源证据 | 分析工具与对应 Skill |

部分目录在首次运行后创建。主 Skill `super-business-flow` 统筹本数据根，子 Skill 后缀与职责目录对应。

新工程先填写 [仓库配置](project/repos.yaml)，按需调整 [运行设置](project/settings.yaml)，由真实代码查证后补充 [服务身份](project/services.yaml)。现有 [脱敏 RPC 输入](frameworks/rpc-reference-rest-schema/input.md) 是框架学习起点；[合成业务知识示例](knowledge/demo-blank-query/README.md) 用于演示产物形式，两者都不代表已完成真实工程分析。

## 保存和升级

首次安装使用随工具交付的初始配置和示例；之后本目录归工程维护，不以新版本的初始数据覆盖已有内容。人工修订留在专属文件中，重扫只按契约重建相应生成结果。已发布版本、证据、记忆历史及其指纹应按各自契约维护，不能用全局替换修改。

任务中间状态位于工作区根目录 `.temp/run/super-business-flow/<run-id>/`。临时目录不会替代这里的持久知识和证据；工具自身构建文件位于工具根的 `tools/target/`。

- [工具使用指南](../super-business-flow-tookit/README.md)
- [Windows 部署与升级](../super-business-flow-tookit/deployment.md)
- [从旧版目录迁移](../super-business-flow-tookit/design/layout-migration.md)
- [分类模板](../super-business-flow-tookit/templates/README.md)
