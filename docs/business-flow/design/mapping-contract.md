# 端点关联与有限 RPC 规则 DSL（v1）

本文件是 `mappings discover/resolve` 的可执行契约。自动发现层、人工修订层、生效层分别保存，工具不覆盖人工文件。`confirmed` 表示在声明的代码和规则版本下有依据的静态关联，不表示请求实际经过此端点。

## 命令与文件职责

```powershell
# facts 来自 scan；也可用相同 schema 合并多个模块的 facts 数组。
java -jar docs/business-flow/tools/target/business-flow-tools.jar mappings discover --facts docs/business-flow/inventory/facts.json --rules docs/business-flow/frameworks/ts-rpc/rules.yaml --services docs/business-flow/project/services.yaml --out docs/business-flow/mappings/generated/ts-rpc.yaml
java -jar docs/business-flow/tools/target/business-flow-tools.jar mappings resolve --generated docs/business-flow/mappings/generated/ts-rpc.yaml --endpoints docs/business-flow/mappings/endpoints/manual.yaml --overrides docs/business-flow/mappings/overrides/ts-rpc.yaml --out docs/business-flow/mappings/effective/ts-rpc.yaml
```

`--overrides` 可以重复；各文件中的补丁一起校验，顺序不能改变冲突结果。`--endpoints` 是补充端点目录，最小内容是 `schema_version: 1`、`endpoints: []`。discover 自带发现的 `endpoints`，不必重复拷贝。resolve 允许相同 ID、完全相同内容的端点跨输入引用，但拒绝同一列表重复 ID 与同 ID 不同内容。

CLI 强制 discover 输出到 `mappings/generated/`，resolve 输出到 `mappings/effective/`；两者也可先输出到指定 `.temp/run/super-business-flow/<run-id>/` 下。输出不能覆盖任何输入文件。写入采用输出文件锁与原子替换，写入前再次比较所有输入指纹，处理中发生修改则停止重跑。

| 文件 | 职责 |
| --- | --- |
| `frameworks/<rpc-id>/rules.yaml` | 从实际框架学习并验证的有限提取规则 |
| `project/services.yaml` | 显式登记 repo/module 到服务 ID、原始服务名的关系 |
| `mappings/generated/<rpc-id>.yaml` | 工具提取的端点、关联、候选项与证据 |
| `mappings/endpoints/*.yaml` | 补充的端点目录 |
| `mappings/overrides/*.yaml` | 带证据和版本前置条件的人工补丁 |
| `mappings/effective/<rpc-id>.yaml` | 合并验证后的生效视图；候选项仍为候选项 |

## 规则包：只执行明确支持的提取策略

下面是**合成示例**，注解全限定名、operation 规则与包装类型适配均不是用户项目的已知事实。程序员先提供已知调用对，frameworks Skill 查证真实实现、版本和独立样本后才能把状态改为 verified。

```yaml
schema_version: 1
framework:
  id: rpc-reference-rest-schema
  revision: v1-example
  status: candidate                 # verified 才可能自动确认关联
  evidence_refs: [ev-framework]
consumer:
  annotation: example.rpc.RpcReference
  service_attribute: microserviceName
  schema_attribute: schemaId
  operation:
    source: unknown                 # 不默认使用 Java 方法名
    evidence_refs: []
provider:
  annotation: example.rpc.RestSchema
  schema_attribute: schemaId
  exposure_annotations:
    - org.springframework.web.bind.annotation.PostMapping
  operation:
    source: unknown
    evidence_refs: []
contract:
  status: unknown                   # 不默认认为 ResponseEntity<T> 与 T 兼容
  evidence_refs: []
evidence:
  - id: ev-framework
    path: docs/business-flow/frameworks/ts-rpc/input.md
    sha256: "<64 位真实 SHA256>"
```

支持的 operation.source：

| 位置 | source | 读取内容 |
| --- | --- | --- |
| consumer | `invoked_method_name` | 调用事实的 `method_name`；必须明确验证此机制 |
| consumer | `annotation_attribute` | 接收者字段上的全限定 `annotation` 的字面量 `attribute` |
| provider | `method_name` | 提供方方法声明名；必须明确验证此机制 |
| provider | `annotation_attribute` | 提供方方法上全限定 `annotation` 的字面量 `attribute` |
| 两者 | `unknown` | operation_id 保持 null，关联不能自动确认 |

除 `unknown` 外，每个 operation 规则都须有 `evidence_refs`，其原始证据须可定位且当前指纹一致。`annotation_attribute` 必须显式填写全限定 `annotation` 和 `attribute`。不执行表达式、脚本或任意类加载。未实现的策略报错，需由工具扩展或人工有证据的补丁处理；不能仅凭模型“理解了语义”让 CLI 当成支持。

`consumer.annotation` 匹配字段，`provider.annotation` 匹配类。两者以及 `exposure_annotations` 必须是全限定名，且扫描事实 `resolved: true`、`full_name` 精确一致。同名短注解无效。提供方只选取显式 `exposure_annotations` 标记的直接声明方法；继承、组合注解、注册表和动态代理不能靠此 DSL 自动补齐，需新适配器或保留缺口。字面量缺失、占位符、常量未解析等保持候选。

`contract.status: verified` 连同证据表示已对当前规则适用范围验证参数与响应契约。工具不会自行解包 `ResponseEntity`，也不会比较类名相似度来证明适配。框架、两侧 operation、contract、服务登记及相关源事实都具有有效证据，且服务/schema/operation 唯一匹配时，才自动 confirmed。

运行 discover 时，工具还会记录实际 rules/services 配置文件的指纹。它们只能补充原始证据，不能用“文件中写了 verified”给缺少独立证据的声明背书。后续 resolve 会检查这些配置的当前指纹；修改规则后应先重新 discover。

## 服务登记

```yaml
schema_version: 1
services:
  - service_id: caller-service
    repo_id: caller-repo
    module_id: caller-module
    status: verified
    microservice_names_raw: []
    evidence_refs: [ev-services]
  - service_id: text-search-service
    repo_id: provider-repo
    module_id: provider-module
    status: verified
    microservice_names_raw:
      - "MapSiteService:MapSearchTextSearchService"
    evidence_refs: [ev-services]
evidence:
  - id: ev-services
    path: docs/business-flow/project/service-registration.md
    sha256: "<64 位真实 SHA256>"
```

原始服务字符串整体匹配，冒号不被擅自解释。一个 repo/module 在此版本中只登记一个服务；同一个服务可占多个模块。原始名字可以指向同一服务的多个模块，但不能无条件指向两个服务；后者应提交待确认问题或显式路由适配规则。服务状态缺省为 candidate。

## 用户示例的候选关联（尚未验证）

```yaml
schema_version: 1
bindings:
  - id: caller-service.ts-inner-client.search-by-text
    source_fingerprint: "<发现时由工具生成的 SHA256>"
    framework:
      id: rpc-reference-rest-schema
      rule_revision: "<待验证版本>"
    caller:
      repo_id: caller-repo
      module_id: caller-module
      service_id: caller-service
      class_name: com.example.TsInnerClient
      method_signature: searchByText(com.example.FusionRequestDTO)
      callsite_id: cs-ts-inner-search-01
      receiver_field: innerTsRpcService
      interface_name: com.example.IInnerTsRpcService
      invoked_method: searchByText(com.example.FusionRequestDTO)
    remote_identity:
      microservice_name_raw: "MapSiteService:MapSearchTextSearchService"
      schema_id: tsRpc
      operation_id: null
    targets: []
    candidate_targets:
      - endpoint_id: ep-ts-rpc-search-by-text
        condition_ref: null
    resolution:
      status: candidate
      origin: user_example
      reason: 缺少真实仓库身份、注解导入、operation 选择及返回适配证据
      evidence_refs: [ev-user-sample]
endpoints:
  - id: ep-ts-rpc-search-by-text
    role: provider
    repo_id: provider-repo
    module_id: provider-module
    service_id: text-search-service
    class_name: com.example.TsSearchRpcService
    method_signature: searchByText(com.example.FusionRequestDTO)
    rpc:
      framework_id: rpc-reference-rest-schema
      schema_id: tsRpc
      operation_id: null
    http:
      method: POST
      path: /ts-rpc/searchByText
    evidence_refs: [ev-user-sample]
evidence: [] # 实际使用须加入 ev-user-sample 的持久化源文件与真实指纹
```

端点身份包含 service/schema/operation 与符号。不会因为 schemaId 一样就把不同服务合并。调用字段带 @RpcReference 时，不因封装类自己 implements 同一接口而把远程调用误判成递归。

实际生成器另外保留 `remote_identity.service_id`（只能由显式服务登记得出）与端点 `rpc.microservice_names_raw`，用于人工修订时检查已知身份冲突。URL 只有一个时提供 `http`；多个静态 HTTP 映射保存在 `http_routes`，共用一个 RPC 方法端点，不因 HTTP 路径数量制造 RPC 歧义。

## 人工覆盖：保留、校验、显式冲突

```yaml
schema_version: 1
overrides:
  - id: fix-ts-search-target
    action: replace_targets
    binding_id: caller-service.ts-inner-client.search-by-text
    expected_source_fingerprint: "<复制 generated 的当前 source_fingerprint>"
    targets:
      - endpoint_id: ep-ts-rpc-search-by-text
        condition_ref: null
    reason: 已核对当前框架实现和服务登记，附独立已知调用对
    evidence_refs: [ev-reviewed-pair]
evidence:
  - id: ev-reviewed-pair
    path: docs/business-flow/evidence/ts-reviewed-pair.md
    sha256: "<64 位真实 SHA256>"
```

所有补丁都要求 `id`、`reason`、有效 `evidence_refs`；修改现有 binding 还要求匹配 `expected_source_fingerprint`。`replace_targets` 可以将有证据的候选人工确认，但不能违背已经解析出的服务/schema/operation 身份；遇到这种冲突应先修正规则或服务登记再重生成，不允许偷偷强制覆盖。未解析的身份保持 null，不伪造 operation。

支持 4 个 action：

| action | 必填载荷 | 结果 |
| --- | --- | --- |
| `add_endpoint` | `endpoint: {id, role: provider, repo_id, module_id, service_id, class_name, method_signature, rpc, evidence_refs}` | 在端点目录加入遗漏端点 |
| `add_binding` | `binding: {id, source_fingerprint, framework, caller, remote_identity, targets, resolution}` | 加入遗漏关联；补丁证据并入 resolution |
| `replace_targets` | `binding_id, expected_source_fingerprint, targets` | 原位修订目标，origin=manual_override |
| `disable_binding` | `binding_id, expected_source_fingerprint` | 保留记录，status=disabled，清空 targets |

同一轮两个补丁触碰同一 binding/endpoint 均报冲突；不存在“最后一个覆盖前一个”。`add_binding` 的 caller、framework、remote_identity 字段结构与自动层一致，`source_fingerprint` 必须是对应调用现场的真实源指纹，供后续修订前置检查；确认状态要求至少一个目标。

自动层的 `source_fingerprint` 是整个输入 facts、规则、服务登记的规范化内容及调用身份的组合 SHA256；v1 采用保守失效化，因此同批次其他源事实改变也可能要求重新审阅补丁。人工 `add_binding` 尚未经过发现器，其 `source_fingerprint` 要填对应调用现场源文件的 SHA256，且该文件证据必须出现在 `caller.evidence_refs` 或 `resolution.evidence_refs` 中。工具校验引用和文件指纹，人工仍需核对符号与调用现场确实一致。

多个候选不属于多个已确认分支。一个 binding 若有多个 targets，每个都必须带非空 `condition_ref`，在顶层 `conditions` 中有 `{id, expression, evidence_refs}`，并且调用方明确声明 `routing: {mode: conditional, evidence_refs: [...]}`。这个声明及条件必须有实际证据。工具验证引用存在与证据，不宣称证明了布尔条件互斥或完整穷举。单个目标可以无条件。候选项放 `candidate_targets`，不能被主流程当成生效边。

有效层含 `bindings`、`endpoints`、`conditions`、`evidence`、`diagnostics`、`applied_overrides`。已确认关联必须有引用有效的目标和证据，候选不被自动升级。调用、规则、服务登记等来源改变会改变自动 source_fingerprint；旧补丁停止应用，提示重新核验。

## 为什么分层

生成与修订分离可避免重扫丢失人工知识；生效视图让 Agent 读取单一确定结果。备选是直接编辑生成文件，初期简单，但无法区分扫描变化与人工意图。有限 DSL 便于审计并能拒绝未实现的规则；备选是通用脚本插件，表达力更强，但引入代码执行和版本治理成本，留待真实机制需要时扩展。

证据记录采用顶层 `evidence: [{id,path,sha256,...}]`，引用为 ID。工具检查文件存在、指纹一致。发现阶段允许真实仓库绝对源路径；发布长期知识前要经过持久化证据导入。它检查可追溯性与新鲜度，不能代替程序员/框架测试对语义正确性的验证。

扫描器的嵌套 `evidence[].source` 在发现时规范化成上述扁平记录，行号信息保留。证据缺失、指纹过期、ID 冲突会停止合并；候选项也不能引用不存在的证据。`.temp` 中的记录可供临时导航；用于 confirmed 关联、条件或人工补丁前，必须先导入持久化证据并改写引用。
