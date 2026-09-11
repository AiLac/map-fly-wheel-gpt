# Super Business Flow

在 Windows / Java 21 的多仓工作区中，用 **Skill + 静态分析工具 + 结构化配置**，从一个类、一个或多个接口 URL 梳理业务可能链路，发布带 Mermaid 和代码链接的 Markdown 知识。

业务仓库各自保留 Git 历史。这一版分析静态可能路径及条件分支；日志、Trace 和 badcase 的真实执行对照属于后续阶段。CLI 提取事实、管理状态并验证发布，业务语义由公司 Agent 按 Skill 阅读代码完成；单独运行 `java ... run` 只创建分析任务。

## 在个人 PC 开始

首次接入请按 [Windows 多仓工作区部署指南](deployment.md) 操作，包含复制脚本、模块依赖配置、RPC 框架学习和首次业务分析步骤。

1. 将本仓库中的 `.cac/` 与 `docs/business-flow/` 放在作为项目根目录的工作区中，业务仓库可放在其子目录，或由 repo path 指向同级目录。
2. 在 [repos.yaml](project/repos.yaml) 填写实际仓库、模块与 source roots；在 [services.yaml](project/services.yaml) 登记已查证的服务身份；在 [settings.yaml](project/settings.yaml) 调整并发、重试、上下文和记忆选项。模板中的示例身份不能当成真实配置。
3. 用 Java 21 和 Maven 构建工具。在项目根目录执行：

```powershell
java -version
mvn -f docs/business-flow/tools/pom.xml verify
java -jar docs/business-flow/tools/target/business-flow-tools.jar --help
```

依赖解析以每个 Maven 模块自己的 classpath 为单位。可在该模块运行 `mvn dependency:build-classpath -Dmdep.outputFile=<该模块的绝对输出路径>`，将文件写入本项目 `docs/business-flow/` 下，并在模块配置中引用。Windows 上 classpath 使用本机路径分隔符；不要把 15 个仓库的依赖简单拼成一个 classpath。源码生成目录只有确认需要时才加入 source roots。

4. 从公司 Agent 的项目根目录启动，调用下列命令。`.cac` 是用户内部 OpenCode 分支的约定；本仓库没有访问该宿主，首次接入需要确认它发现 6 个 Skill、1 个 command、3 个 subagent。上游 OpenCode 默认路径为 `.opencode`，不要将此交付误认为上游原生 `.cac` 支持。[OpenCode Skill 说明](https://opencode.ai/docs/skills/)

```text
/super-business-flow --class "com.example.TextSearchService"
/super-business-flow --url "/map/search/v1/textsearch/searchByText"
/super-business-flow --url "/path/a" "/path/b"
/super-business-flow --url "/path/a" --url "/path/b"
/super-business-flow --class "com.example.TextSearchService" --url "/path/a"
/super-business-flow --all
/super-business-flow --resume <run-id>
```

`class + url` 取交集；`all` 与入口选择器互斥；`resume` 不接收新的入口选择器。无参数显示帮助。类可位于 Service 等任意层，Skill 会查证其实际暴露入口。相同 URL 在多个服务或 HTTP 方法中出现时，需要进一步定位，不能选一个猜测。

命令文件是提示入口，`$ARGUMENTS` 是参数数据，不执行 shell 插值。Agent 调用 CLI 时应传独立参数，路径含空格也作为一个参数。OpenCode 的 command 用 Markdown 模板承载提示，并支持参数替换；本项目由此组织入口。[OpenCode command 说明](https://opencode.ai/docs/commands/)

## 第一次 RPC 学习

先使用 `super-business-flow-frameworks`，给出 RPC 示例、已知调用对和实现线索。它在 `frameworks/<rpc-id>/` 保存输入、精简协议、规则、证据、独立样本和问题。没有已生成映射也可运行。

对当前脱敏示例，可以观察到调用字段的 `microserviceName`、`schemaId` 和提供方的 HTTP 路由写法，但还不能证明原始服务字符串的解释、operation 规则或 `ResponseEntity<T>` 的适配。示例在实际 imports、依赖版本、注册与代理机制未查证前保持 candidate。

端点关联分别存放自动生成、人工覆盖和生效视图，避免重扫覆盖人工修订。[查看完整端点关联格式与命令](design/mapping-contract.md)。

```yaml
schema_version: 1
bindings:
  - id: caller-service.ts-inner-client.search-by-text
    source_fingerprint: "<发现结果中的当前指纹>"
    framework: {id: rpc-reference-rest-schema, rule_revision: "<待验证>"}
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
      reason: 尚未查证服务身份、operation 选择及返回适配
      evidence_refs: [ev-user-sample]
```

这段用于解释字段，含占位符，不可直接作为已确认配置。多个待选目标放在 `candidate_targets`；已知条件路由才进入带条件的 `targets`。完整文件还包含端点及可校验的证据目录。

## 阅读和维护

| 需要做的事 | 入口 |
| --- | --- |
| 了解已经确认的需求与边界 | [需求](design/requirements.md) |
| 了解组件、数据流、任务拆分和上下文治理 | [架构](design/architecture.md) |
| 查看选型理由和备选方案 | [决策记录](design/decisions.md) |
| 调用扫描器、转换仓库相对路径、了解事实与诊断 | [扫描契约](design/scanner-contract.md) |
| 管理运行、恢复、全局并发和任务状态 | [运行契约](design/run-contract.md) |
| 学习 RPC、生成及手工修订端点映射 | [映射契约](design/mapping-contract.md) |
| 检索、修正、删除带时间与版本的记忆 | [记忆契约](design/memory-contract.md) |
| 准备业务图、持久证据与知识发布 | [发布契约](design/publication-contract.md) |
| 新建规范文件 | [分类模板](templates/README.md) |
| 检查交付是否可信、记录实际验证 | [验收](design/acceptance.md) |

永久知识进入 `knowledge/<scenario-id>/`；业务图进入 `scenarios/<scenario-id>/`；原始证据进入 `evidence/`；项目记忆进入 `memory/`。运行目录固定为项目根目录 `.temp/run/super-business-flow/<run-id>/`，不会作为永久证据唯一来源。

先看 [可复现的合成示例](examples/blank-query/README.md) 及其 [已发布流程图与源码链接](knowledge/demo-blank-query/README.md)。你提供的脱敏 RPC 片段已存入 [框架学习输入](frameworks/rpc-reference-rest-schema/input.md)，等待真实仓库核验。

## 当前能力边界

静态扫描可提取 Java AST、可解析的符号和显式 Spring 映射事实；它不会证明运行时 Bean 注册、动态分派、反射或 RPC 代理语义。继承/组合映射、构造和初始化流程等诊断由 Agent 继续查证，未查证则保留覆盖缺口。RPC DSL 仅支持契约列出的操作；理解了框架语义不等于工具已经实现相应适配器。

正常请求条件保留各分支；机制和目标身份不确定时，停止受影响分支并询问，其他已确认任务可以继续。存在未解决缺口的知识必须标 `partial`。运行任务全部结束、Schema 校验通过和独立复核通过，分别只证明各自范围，不能替代真实项目业务验收。
