# Super Business Flow

在 Windows / Java 21 的多仓工作区中，用 **Skill + 静态分析工具 + 结构化配置**，从一个类、一个或多个接口 URL 梳理业务可能链路，发布带 Mermaid 和代码链接的 Markdown 知识。

业务仓库各自保留 Git 历史。这一版分析静态可能路径及条件分支；日志、Trace 和 badcase 的真实执行对照属于后续阶段。CLI 提取事实、管理状态并验证发布，业务语义由公司 Agent 按 Skill 阅读代码完成；单独运行 `java ... run` 只创建分析任务。

## 在个人 PC 开始

首次接入请按 [Windows 多仓工作区部署指南](deployment.md) 操作，包含复制脚本、模块依赖配置、RPC 框架学习和首次业务分析步骤。

1. 首次安装时，将本仓库中的 `.cac/` 工具集成、`docs/super-business-flow-tookit/` 工具包和 `docs/super-business-flow/` 初始项目数据放入工作区根目录。业务仓库可放在其子目录，或由 repo path 指向同级目录；已有 `.cac` 仅合入本工具的 `super-business-flow*` 文件。
2. 在 [repos.yaml](../super-business-flow/project/repos.yaml) 填写实际仓库、模块与 source roots；在 [services.yaml](../super-business-flow/project/services.yaml) 登记已查证的服务身份；在 [settings.yaml](../super-business-flow/project/settings.yaml) 调整并发、重试、上下文和记忆选项。模板中的示例身份不能当成真实配置。
3. 用 Java 21 和 Maven 构建工具。在项目根目录执行：

```powershell
# 查看当前 java 命令使用的 Java 版本，本工具要求 Java 21。
# Maven 实际使用的 JDK 可另用 mvn -v 核对。
java -version

# -f 指定分析工具的 pom.xml；verify 执行编译、测试、打包及验证。
# 成功后在 docs/super-business-flow-tookit/tools/target/ 生成 business-flow-tools.jar。
mvn -f docs/super-business-flow-tookit/tools/pom.xml verify

# -jar 启动刚构建的工具；--help 显示帮助，查看可用命令并确认工具能够启动。
java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar --help
```

依赖解析以每个 Maven 模块自己的 classpath 为单位。可在该模块运行 `mvn dependency:build-classpath -Dmdep.outputFile=<该模块的绝对输出路径>`，将文件写入本项目 `docs/super-business-flow/project/local/` 下，并在模块配置中引用。Windows 上 classpath 使用本机路径分隔符；不要把 15 个仓库的依赖简单拼成一个 classpath。源码生成目录只有确认需要时才加入 source roots。

4. 从公司 Agent 的项目根目录启动，调用下列命令。`.cac` 是用户内部 OpenCode 分支的约定；本仓库没有访问该宿主，首次接入需要确认它发现 6 个 Skill、1 个 command、3 个 subagent。上游 OpenCode 默认路径为 `.opencode`，不要将此交付误认为上游原生 `.cac` 支持。[OpenCode Skill 说明](https://opencode.ai/docs/skills/)

```text
/super-business-flow-start --class "com.example.TextSearchService"
/super-business-flow-start --url "/map/search/v1/textsearch/searchByText"
/super-business-flow-start --url "/path/a" "/path/b"
/super-business-flow-start --url "/path/a" --url "/path/b"
/super-business-flow-start --class "com.example.TextSearchService" --url "/path/a"
/super-business-flow-start --all
/super-business-flow-start --resume <run-id>
```

`class + url` 取交集；`all` 与入口选择器互斥；`resume` 不接收新的入口选择器。无参数显示帮助。类可位于 Service 等任意层，Skill 会查证其实际暴露入口。相同 URL 在多个服务或 HTTP 方法中出现时，需要进一步定位，不能选一个猜测。

命令文件是提示入口，`$ARGUMENTS` 是参数数据，不执行 shell 插值。Agent 调用 CLI 时应传独立参数，路径含空格也作为一个参数。OpenCode 的 command 用 Markdown 模板承载提示，并支持参数替换；本项目由此组织入口。[OpenCode command 说明](https://opencode.ai/docs/commands/)

## 目录结构说明

以 `D:\poi-workspace\` 为工作区根目录，Agent 从这里启动。下表路径均相对工作区根目录；后续各表再展开对应目录。`<repo-id>`、`<rpc-id>`、`<scenario-id>`、`<run-id>` 和 `<task-id>` 是实际运行时替换的标识，不是固定目录名。部分产物目录会在首次使用相应能力时创建。

| 工作区下的目录 | 内容与职责 | 更新方式 |
| --- | --- | --- |
| `<业务仓库目录>/`，例如 `poi-entry/`、`text-search/` | 待分析的微服务源码；各自保留 `.git`、Maven 模块和 Git 历史 | 由各业务仓库维护，在 `project/repos.yaml` 中登记 |
| `.cac/` | 本工具的 Skill、command 和 subagent 集成 | 与工具包配套更新，保留其他宿主配置 |
| `docs/super-business-flow-tookit/` | 分析工具、Schema、模板、固定示例及工具设计说明 | 随工具版本更新 |
| `docs/super-business-flow/` | 本工程配置、共创 RPC 规则和长期分析产物 | 由项目持续积累，工具升级保留原内容 |
| `.temp/run/super-business-flow/<run-id>/` | 一次运行的工作目录：任务状态、中间结果和恢复检查点 | 按 run 隔离，供执行与恢复使用 |

业务仓库也可以位于工作区的同级目录，由 `docs/super-business-flow/project/repos.yaml` 中的 `path` 显式定位。工作区根目录、工程数据目录和单次运行目录各有用途；创建一次 run 不会创建或合并业务 Git 仓库。

### Agent 集成：`.cac/`

以下路径相对 `.cac/`。每个 Skill 使用与文件夹相同的名称，主 Skill 编排流程，子 Skill 后缀对应产出目录职责。

| 文件或目录 | 职责 |
| --- | --- |
| `skills/super-business-flow/SKILL.md` | 主流程：解析类、URL、全量或恢复输入，编排各阶段 |
| `skills/super-business-flow-frameworks/SKILL.md` | 共创 RPC 协议、规则、验证记录与问题，主管数据根 `frameworks/` |
| `skills/super-business-flow-mappings/SKILL.md` | 生成、人工修订和核验端点关联，主管 `mappings/` |
| `skills/super-business-flow-scenarios/SKILL.md` | 梳理入口、条件分支、返回及未决边界，主管 `scenarios/` |
| `skills/super-business-flow-knowledge/SKILL.md` | 发布业务概述、流程图与代码链接，主管 `knowledge/` |
| `skills/super-business-flow-memory/SKILL.md` | 检索、新增、更正与撤回项目记忆，主管 `memory/` |
| `commands/super-business-flow-start.md` | `/super-business-flow-start` 的参数提示入口 |
| `agents/super-business-flow-local-tracer.md` | 子代理：追踪限定范围的本地业务链路 |
| `agents/super-business-flow-mechanism-explorer.md` | 子代理：调查指定 RPC 或注册机制 |
| `agents/super-business-flow-reviewer.md` | 子代理：独立复核证据、映射和业务图 |

命令名称强调启动入口，主 Skill 名称保持 `super-business-flow`；输入 `--resume` 时由同一个命令恢复流程。命令只加载 Skill 并传递参数，业务分析规范集中维护在 Skill 中。旧安装需要替换 command 文件并刷新宿主，见 [部署与升级说明](deployment.md)。

### 工具集：`docs/super-business-flow-tookit/`

以下路径相对工具根。`tookit` 保留本项目约定的目录拼写；运行业务分析时从这里读取工具与模板，业务产物写到数据根或 run 工作目录。

| 文件或目录 | 内容 |
| --- | --- |
| `README.md`、`deployment.md` | 使用入口及 Windows 安装、配置和升级说明 |
| `design/` | 需求、架构、选型理由、各组件契约、迁移说明及工具验证记录 |
| `schemas/` | 配置、规则、端点、映射、图、证据、记忆输入等 JSON Schema |
| `templates/frameworks/` | RPC 输入、协议、规则、样本、证据、验证和问题模板 |
| `templates/mappings/` | 自动发现、补充端点、人工覆盖、生效映射模板 |
| `templates/scenarios/` | 场景输入、业务图、RPC 边、覆盖和问题模板 |
| `templates/knowledge/` | 业务概述、流程图、服务关系及代码链接模板 |
| `templates/memory/`、`templates/evidence/` | 记忆条目、索引说明及证据记录模板 |
| `templates/tasks/` | 子任务输入、分析说明及机器结果模板 |
| `tools/pom.xml` | Java 21 工具的 Maven 构建入口 |
| `tools/src/main/java/io/superbusinessflow/` | CLI、扫描、端点映射、运行调度、记忆、证据与发布实现 |
| `tools/src/test/java/`、`tools/src/test/resources/` | 行为测试及独立测试夹具 |
| `tools/bf.ps1`、`tools/bf.sh` | 调用已构建 JAR 的 PowerShell / Shell 包装脚本 |
| `tools/target/` | Maven 生成的 JAR、编译文件和测试报告，不进入 Git |
| `examples/blank-query/` | 随工具交付的合成源码、图输入及复现说明；重跑产物另存到数据根或 run |

模板是发行内容。使用时复制到相应任务或产出目录再填写；完整文件规范见 [分类模板](templates/README.md)。

### 工程配置与产出件：`docs/super-business-flow/`

以下路径相对工程数据根。这里既有程序员维护的输入，也有 Agent 和工具生成的结果；升级工具时保留整个目录。

| 文件或目录 | 内容与生成方式 |
| --- | --- |
| `README.md` | 本工程数据与知识的导航入口 |
| `project/repos.yaml` | 实际仓库、模块、源码目录及各模块 classpath 配置 |
| `project/services.yaml` | 已查证的微服务身份及其依据 |
| `project/settings.yaml` | 并发、重试、上下文预算、记忆和输出设置 |
| `project/local/` | 本机依赖清单及本地辅助输入，由 Git 忽略规则排除 |
| `frameworks/<rpc-id>/` | RPC 共创输入与成果：`input.md`、`protocol.md`、`rules.yaml`、`manifest.yaml`、`evidence.jsonl`、`examples/`、`validation.md`、`questions.yaml` |
| `inventory/<repo-id>/<module-id>/` | 推荐的持久扫描目录，保存审核后的 `facts.json`、入口事实和诊断；可按源快照重建 |
| `mappings/generated/` | 工具自动发现的候选与确认关联 |
| `mappings/endpoints/` | 程序员补充的端点定义 |
| `mappings/overrides/` | 带依据与前置指纹的人工修订，重扫不能覆盖 |
| `mappings/effective/` | 自动发现与人工修订合并后的生效视图 |
| `scenarios/<scenario-id>/` | 场景输入、`graph.json`、发布 `manifest.json`、覆盖记录与未决问题 |
| `knowledge/<scenario-id>/README.md` | 指向该场景当前发布版本的知识入口 |
| `knowledge/<scenario-id>/revisions/<graph-hash>/` | 不可变发布包：`overview.md`、`flow.md`、`sequence.md`、`code-links.md`、`graph.json` 和 `mapping-snapshots.json`；存在 RPC 边时保存对应 `mapping-snapshots/` |
| `memory/entries/` | 当前记忆条目，Markdown 正文与时间、状态等元数据 |
| `memory/versions/<memory-id>/` | 不可变记忆版本 `rNNNNNN.md`，含更正与删除墓碑历史 |
| `memory/index.json` | 可重建的记忆元数据索引 |
| `evidence/<hash>/` | 持久证据摘录 `source.txt` 及出处、行号、指纹等 `record.json` |
| `.project.lock` | 工程级短时文件锁，由工具管理 |

子 Skill 的职责目录与名称对应，例如 `super-business-flow-frameworks` 管理 `frameworks/`；`project/`、`inventory/` 和共享 `evidence/` 由主流程与分析工具协作管理，不为每个目录额外创建 Skill。[项目数据入口](../super-business-flow/README.md)

### 单次运行工作目录：`.temp/run/super-business-flow/<run-id>/`

以下路径相对某一次 run。不同运行使用不同 run ID，子代理只写分配给自己的 `tasks/<task-id>/`；审核通过的成果再由主流程发布到工程数据根。

| 文件或目录 | 用途 |
| --- | --- |
| `state.json` | 原子更新的权威运行状态 |
| `snapshot.json` | 冻结的输入、配置、源码与依赖等版本依据 |
| `tasks.json` | 任务及其依赖、状态、执行记录 |
| `frontier.json` | 尚未展开的链路边界和后续工作 |
| `questions.yaml`、`decisions.yaml` | 待确认问题及已记录的决策 |
| `resume.md` | 恢复入口与交接说明 |
| `run.lock` | 当前运行的互斥锁，由工具管理 |
| `history/` | 历史快照及失效前的任务记录 |
| `tasks/<task-id>/` | 子任务按模板写入的范围说明、分析、扫描事实、规则或图草稿、`result.json` 等；具体文件按任务需要生成 |
| `tasks/<task-id>/accepted/` | 工具接收的结果或检查点版本，用于审计与恢复 |

除 `state.json` 外的状态投影由工具生成，不通过手工修改投影确认事实。运行器会在 `.temp/run/super-business-flow/` 下创建 Git 忽略规则。临时目录不能作为长期证据的唯一来源；删除它会失去该 run 的精确恢复能力。详细操作见 [运行契约](design/run-contract.md)。

升级只更新工具根与本工具的 `.cac` 集成，不重新复制初始项目数据；Maven 自身的构建结果仍位于工具根 `tools/target/`。来自旧版 `docs/business-flow/` 的安装先按 [目录迁移说明](design/layout-migration.md) 分类迁移，保留不可变历史及其指纹。

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

以下路径均相对 `docs/super-business-flow/` 数据根：永久知识进入 `knowledge/<scenario-id>/`；业务图进入 `scenarios/<scenario-id>/`；原始证据进入 `evidence/`；项目记忆进入 `memory/`。运行目录固定为项目根目录 `.temp/run/super-business-flow/<run-id>/`，不会作为永久证据唯一来源。

先看 [可复现的合成示例](examples/blank-query/README.md) 及其 [已发布流程图与源码链接](../super-business-flow/knowledge/demo-blank-query/README.md)。你提供的脱敏 RPC 片段已存入 [框架学习输入](../super-business-flow/frameworks/rpc-reference-rest-schema/input.md)，等待真实仓库核验。

## 当前能力边界

静态扫描可提取 Java AST、可解析的符号和显式 Spring 映射事实；它不会证明运行时 Bean 注册、动态分派、反射或 RPC 代理语义。继承/组合映射、构造和初始化流程等诊断由 Agent 继续查证，未查证则保留覆盖缺口。RPC DSL 仅支持契约列出的操作；理解了框架语义不等于工具已经实现相应适配器。

正常请求条件保留各分支；机制和目标身份不确定时，停止受影响分支并询问，其他已确认任务可以继续。存在未解决缺口的知识必须标 `partial`。运行任务全部结束、Schema 校验通过和独立复核通过，分别只证明各自范围，不能替代真实项目业务验收。
