# 证据与知识发布契约

## 原始证据持久化

```powershell
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar evidence --source ../caller/src/main/java/example/TsInnerClient.java --symbol 'example.TsInnerClient#searchByText(FusionRequestDTO)' --line-start 12 --line-end 20
```

返回 `{schema_version:1,id,path,sha256,source:{path,sha256,line_start,line_end,symbol}}`。外层 path 指向项目内 `docs/super-business-flow/evidence/<hash>/source.txt`，外层 SHA 是摘录内容的指纹；source 中保留原文件全量指纹和位置。同目录 `record.json` 是证据元数据。先持久化相关摘录，再将返回对象放入图的 evidence 数组。不要复制无关配置或整个仓库。持久化哈希证明摘录完整性，不能证明业务解释正确。

## 图与发布

```powershell
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar validate --file docs/super-business-flow/scenarios/text-search/graph.json --schema docs/super-business-flow-tookit/schemas/graph.schema.json
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar publish --graph docs/super-business-flow/scenarios/text-search/graph.json --scenario-id text-search
# 更新已有发布必须带 manifest 中当前 revision
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar publish --graph docs/super-business-flow/scenarios/text-search/graph.json --scenario-id text-search --expected-revision 1
```

顶层必填：schema_version、scenario_id、title、overview、entry、nodes、edges、conditions、evidence、coverage。详见 [JSON Schema](../schemas/graph.schema.json)。nodes 保存有证据的业务节点（id/kind/label/evidence_refs），edges 保存 from/to/kind/condition_refs/evidence_refs；调用边与控制流边以 kind 区分，不能把 Java AST 遍历顺序当成执行时序。未知调用放 coverage.frontier，未知机制放 coverage.open_questions，禁止 candidate 边冒充已确认关系。

RPC 边还需 binding_id、mapping_ref（项目 effective 文件 path/sha256），来源节点需匹配 binding 的 callsite_id，目标节点需 endpoint_id。发布器核对 effective 中 confirmed 调用点、目标与条件，候选和过期映射不能发布为 RPC 事实。历史版本同时复制有效映射到 mapping-snapshots/，并记录 mapping-snapshots.json，避免后续重生成导致旧图丢失映射依据；重发布仍需当前映射及其来源重新核验。业务条件必须引用 conditions 中有源证据的表达式。

coverage.status 为 partial 或 complete。complete 必须没有 frontier/open_questions，并含 `review:{status:passed,reviewer:<标识>,evidence_refs:[<审查记录证据>]}`；接受的排除项另存 accepted_exclusions。人工“接受排除”需要原决定记录，不能用空数组擦除任务范围。工具验证结构与引用；Scope 是否完整、业务含义是否正确由 scenarios/knowledge Skill 与独立审查负责。

发布采用项目短文件锁、revision 前置条件、按图哈希的不可变版本目录；先写完整 bundle，再更新入口文档。输出：

- `scenarios/<id>/graph.json`、`manifest.json`：当前结构化图与版本。
- `knowledge/<id>/revisions/<hash>/overview.md`：业务概述及缺口。
- `flow.md`：有条件标注的 Mermaid 图。
- `sequence.md`：已确认服务交互表，不推断全局时序。复杂、有因果证据的时序图由 Agent 按模板补充。
- `code-links.md`：持久化源摘录链接、原符号与原行号。
- `graph.json`：此版本的完整机器可读图。
- `knowledge/<id>/README.md`：当前版本入口。

大图由 Agent 在发布前分成场景/子流程，保留整体导航；工具的 flow 渲染不自动剪枝。多个独立条件不能为缩图而丢弃。v1 不做全路径枚举、运行时跟踪或语义证明。
