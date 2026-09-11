# 分类模板

模板是创建规范文件的起点。`<...>` 是待填的数据，空 evidence、空业务图和 candidate 不是已完成成果。复制到对应主管目录后，用当前真实源码、规则和问题填充；机器数据以 `schemas/` 和各契约为准。含占位符的模板不能用于确认绑定或发布 complete。

模板从 `docs/super-business-flow-tookit/templates/` 读取；下表业务目标路径均相对 `docs/super-business-flow/`。子任务模板复制到 `.temp/run/super-business-flow/<run-id>/tasks/<task-id>/`。业务运行不修改工具集中的原始模板，Schema 位于 `docs/super-business-flow-tookit/schemas/`。

| 目标文件 | 模板 | 用法 |
| --- | --- | --- |
| `frameworks/<rpc-id>/input.md` | [frameworks/input.md](frameworks/input.md) | 保留程序员原始示例和提示 |
| `protocol.md` | [frameworks/protocol.md](frameworks/protocol.md) | 精简语义，不复制大量实现 |
| `rules.yaml` | [frameworks/rules.yaml](frameworks/rules.yaml) | 仅有限 DSL 支持的提取规则 |
| `evidence.jsonl` | [frameworks/evidence.jsonl](frameworks/evidence.jsonl) | 一行一个原始证据记录 |
| `examples/<case-id>.md` | [frameworks/examples/case.md](frameworks/examples/case.md) | 独立正负例与期望依据 |
| `validation.md` | [frameworks/validation.md](frameworks/validation.md) | 实际验证结果与覆盖 |
| `questions.yaml` | [frameworks/questions.yaml](frameworks/questions.yaml) | 可回答的问题与影响范围 |
| `manifest.yaml` | [frameworks/manifest.yaml](frameworks/manifest.yaml) | 版本、适用性与能力状态 |
| `mappings/generated/*.yaml` | [mappings/generated.yaml](mappings/generated.yaml) | discover 输出形状，通常由工具生成 |
| `mappings/endpoints/*.yaml` | [mappings/endpoints.yaml](mappings/endpoints.yaml) | 程序员补充遗漏端点 |
| `mappings/overrides/*.yaml` | [mappings/overrides.yaml](mappings/overrides.yaml) | 独立、有证据和前置指纹的修订 |
| `mappings/effective/*.yaml` | [mappings/effective.yaml](mappings/effective.yaml) | resolve 输出形状，不手改 |
| `scenarios/<id>/input.md` | [scenarios/input.md](scenarios/input.md) | 入口、业务动作、范围和排除 |
| `graph.json` | [scenarios/graph.json](scenarios/graph.json) | 按发布契约汇总实际图 |
| RPC 边片段 | [scenarios/rpc-edge.yaml](scenarios/rpc-edge.yaml) | 关联已确认调用现场与有效端点，复制到图数组 |
| `questions.yaml` | [scenarios/questions.yaml](scenarios/questions.yaml) | 未决入口/机制/目标 |
| `coverage.md` | [scenarios/coverage.md](scenarios/coverage.md) | 覆盖口径与未完成边界 |
| `knowledge/<id>/.../overview.md` | [knowledge/overview.md](knowledge/overview.md) | Agent 业务文字结构 |
| `flow.md` | [knowledge/flow.md](knowledge/flow.md) | 流程图分组规范 |
| `sequence.md` | [knowledge/sequence.md](knowledge/sequence.md) | 有依据的服务交互/时序 |
| `code-links.md` | [knowledge/code-links.md](knowledge/code-links.md) | 可复查的符号与代码引用 |
| 记忆输入文件 | [memory/entry.md](memory/entry.md) | 交给 memory put；时间/版本由工具管理 |
| `memory/index.json` | [memory/index.json](memory/index.json) | 工具生成索引的说明性起点 |
| `evidence/<hash>/record.json` | [evidence/record.json](evidence/record.json) | evidence 命令生成，不手编指纹 |
| 子任务范围说明 | [tasks/input.md](tasks/input.md) | 只加载本任务需要的上下文 |
| 子任务业务分析 | [tasks/analysis.md](tasks/analysis.md) | 分开事实、解释、frontier 和复核 |
| 子任务机器结果 | [tasks/result.json](tasks/result.json) | 按运行契约提交 task finish |

任务模板位于运行任务目录时属于临时状态；要被映射/记忆/知识长期引用的源证据必须先持久化到 `docs/super-business-flow/evidence/`。Publisher 生成的版本目录不可手工原位润色；文字或图有变化时按发布契约形成新版本。
