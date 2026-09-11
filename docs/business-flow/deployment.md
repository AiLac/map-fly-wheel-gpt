# Windows 多仓工作区部署

本指南将现有工具包接入公司基于 OpenCode 的 Agent。目标环境为 Windows、JDK 21、Maven；宿主目录按项目约定使用 `.cac`。无需部署服务端，也无需修改业务仓库的 Maven 配置来引入本工具。

推荐把 `.cac` 和 `docs/business-flow` 安装在多个业务仓库的公共工作目录，并从该目录启动 Agent。这样一次任务可以访问所有相关仓库，共享 RPC 规则、映射与项目记忆；每个业务仓库仍保留自己的 Git 历史。

## 1. 确定工作区与安装位置

下文以 `D:\poi-workspace` 为工作区根目录，`poi-entry`、`text-search` 为两个示例业务仓库名。请换成自己的实际路径和模块名。

| 路径 | 用途 |
| --- | --- |
| `D:\poi-workspace\poi-entry\` | 独立的入口服务 Git 仓库 |
| `D:\poi-workspace\text-search\` | 独立的下游服务 Git 仓库 |
| `D:\poi-workspace\.cac\skills\super-business-flow*\` | 6 个 Skill |
| `D:\poi-workspace\.cac\commands\super-business-flow.md` | 主流程斜杠命令 |
| `D:\poi-workspace\.cac\agents\super-business-flow-*.md` | 3 个子代理 |
| `D:\poi-workspace\docs\business-flow\` | 工具、配置、模板和长期知识 |
| `D:\poi-workspace\.temp\run\super-business-flow\<run-id>\` | 运行时检查点，由工具创建 |

也可以直接以克隆后的 `map-fly-wheel-gpt` 目录为工作区，在 `repos.yaml` 中用 `../poi-entry` 等路径访问其同级仓库。这样便于直接跟踪工具仓库更新，但需要宿主允许读取工作区外的业务源码；公共父目录方案更贴合当前多仓集中放置的约定。

## 2. 获取并复制工具包

以下是**首次安装**的 PowerShell 示例。工具包先克隆到 `D:\tools`，再将所需文件复制到工作区。脚本遇到已有的同名工具包目录会停止；如果已经安装，请看文末的升级说明。

```powershell
$ErrorActionPreference = 'Stop'
$toolkit = 'D:\tools\map-fly-wheel-gpt'
$workspace = 'D:\poi-workspace'
New-Item -ItemType Directory -Force 'D:\tools', $workspace | Out-Null
git clone https://github.com/AiLac/map-fly-wheel-gpt.git $toolkit
if ($LASTEXITCODE -ne 0) { throw '工具包克隆失败，请检查 Git 输出。' }

# 先检查目标，避免覆盖已安装版本或项目知识。
$flowTarget = Join-Path $workspace 'docs\business-flow'
if (Test-Path -LiteralPath $flowTarget) { throw '已存在 business-flow，请按升级说明合并。' }
foreach ($group in @('skills', 'commands', 'agents')) {
    Get-ChildItem -LiteralPath "$toolkit\.cac\$group" -Filter 'super-business-flow*' | ForEach-Object {
        $destination = Join-Path "$workspace\.cac\$group" $_.Name
        if (Test-Path -LiteralPath $destination) { throw "目标已存在：$destination" }
    }
}

New-Item -ItemType Directory -Force "$workspace\docs" | Out-Null
Copy-Item -LiteralPath "$toolkit\docs\business-flow" -Destination "$workspace\docs" -Recurse
foreach ($group in @('skills', 'commands', 'agents')) {
    New-Item -ItemType Directory -Force "$workspace\.cac\$group" | Out-Null
    Copy-Item -Path "$toolkit\.cac\$group\super-business-flow*" -Destination "$workspace\.cac\$group" -Recurse
}
Set-Location -LiteralPath $workspace
```

也可以手工复制相同路径。已有 `.cac` 时，仅合入 `super-business-flow*` 文件和目录，保留工程已有的配置。必须同时复制 `docs/business-flow`；Skill 会读取其中的契约、模板并调用 Java 工具，单独复制 `SKILL.md` 无法完成流程。

## 3. 检查环境并构建分析工具

在工作区根目录执行：

```powershell
Set-Location -LiteralPath 'D:\poi-workspace'
java -version
mvn -v
mvn -f docs/business-flow/tools/pom.xml verify
java -jar docs/business-flow/tools/target/business-flow-tools.jar --help
```

`java -version` 和 `mvn -v` 显示的 Java 版本都应为 21。构建成功后生成 `docs/business-flow/tools/target/business-flow-tools.jar`。如果公司使用 Maven 镜像、代理或私服，沿用已有 Maven 配置；本工具不需要 Python、Node.js 或 Docker。

可先运行一个不依赖真实仓库的扫描检查：

```powershell
java -jar docs/business-flow/tools/target/business-flow-tools.jar scan --repo-id demo --module-id main --source-root docs/business-flow/examples/blank-query/source --out .temp/run/super-business-flow/install-check/facts.json
```

成功时会生成 `facts.json`。该合成样例没有配置 Spring 依赖，依赖解析诊断是预期现象；这一步只验证扫描器能够启动和输出事实，不代表真实 RPC 或整个 Agent 流程已通过验证。[示例说明](examples/blank-query/README.md)

## 4. 注册业务仓库和 Maven 模块

编辑 [project/repos.yaml](project/repos.yaml)。默认 `repos: []` 有意保持为空，避免把工具自身当成业务工程扫描。

下面示例假定入口仓库的 Maven 模块目录为 `app`，下游仓库的模块目录为 `service`：

```yaml
schema_version: 1
repos:
  - id: poi-entry
    path: poi-entry
    modules:
      - id: app
        source_roots: [app/src/main/java]
        classpath_file: ../docs/business-flow/project/local/poi-entry-app.cp
  - id: text-search
    path: text-search
    modules:
      - id: service
        source_roots: [service/src/main/java]
        classpath_file: ../docs/business-flow/project/local/text-search-service.cp
```

| 字段 | 路径基准或含义 |
| --- | --- |
| `repos[].id` | 稳定仓库标识，用于证据与映射引用 |
| `repos[].path` | 相对工作区根目录，也可填写绝对路径 |
| `modules[].id` | 在本仓库内唯一的模块标识 |
| `modules[].source_roots` | 相对对应仓库根目录；必须指向存在的源码目录 |
| `modules[].classpath_file` | 相对对应仓库根目录，不是相对模块目录或 `repos.yaml` |

单模块仓库通常填写 `source_roots: [src/main/java]`，并使用该仓库根目录下的 `pom.xml`。有多个模块就分别注册；确认需要分析的生成源码，应先用工程既有流程生成，再加入对应 source roots。

在工作区根目录，为示例中的两个模块分别生成依赖清单：

```powershell
$flowLocal = Join-Path (Get-Location).Path 'docs/business-flow/project/local'
New-Item -ItemType Directory -Force $flowLocal | Out-Null
mvn -f .\poi-entry\app\pom.xml dependency:build-classpath "-Dmdep.outputFile=$flowLocal\poi-entry-app.cp"
mvn -f .\text-search\service\pom.xml dependency:build-classpath "-Dmdep.outputFile=$flowLocal\text-search-service.cp"
```

请检查每次 Maven 命令是否成功、对应清单是否存在。若模块依赖尚未发布的本地组件，先按业务工程已有方式完成依赖准备，再重新生成。依赖变化后也需要重新生成清单。

每个模块使用自己的 classpath，避免跨仓依赖版本被混合。Maven 的 `dependency:build-classpath` 通过 `mdep.outputFile` 写入清单；参数说明见 [Maven 官方文档](https://maven.apache.org/plugins/maven-dependency-plugin/build-classpath-mojo.html)。清单中通常是 Maven 生成的绝对路径；若手工写相对路径，其基准是清单文件所在目录。`project/local/` 已被工具包的 Git 忽略规则排除。

可以暂时省略 `classpath_file` 来收集源码事实，但外部依赖符号可能无法解析；这些缺口需要补依赖或查证，不能据此推断 RPC 目标。[扫描契约](design/scanner-contract.md)

## 5. 设置并发与记忆

在 [project/settings.yaml](project/settings.yaml) 中按需修改，首次可沿用默认值：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `execution.max_parallel_tasks` | `3` | 全局任务并发上限 |
| `execution.phase_limits` | 各阶段单独配置 | 限制框架调查、场景分析等阶段的并发 |
| `context.max_task_tokens` | `24000` | 单任务上下文预算，供 Agent 组织任务 |
| `context.max_memory_items` | `10` | 单次检索记忆数量上限 |
| `memory.enabled` / `auto_record` | `true` / `true` | 启用记忆与运行中关键发现记录 |

并发还受宿主实际子代理能力和模型额度约束。[运行契约](design/run-contract.md) 说明具体生效范围与恢复规则。[project/services.yaml](project/services.yaml) 用于登记实际服务身份，可以在后续框架学习与映射阶段共同完善；不要用仓库名猜测运行时服务注册名。

## 6. 从工作区根目录启动公司 Agent

在 `D:\poi-workspace` 使用你平时启动公司 Agent 的方式，安装后重新启动或按宿主提供的方式刷新。不要从某一个业务仓库的子目录开始首次接入。

让 Agent 检查是否发现以下能力：

- 6 个 Skill：`super-business-flow`、`super-business-flow-frameworks`、`super-business-flow-mappings`、`super-business-flow-scenarios`、`super-business-flow-knowledge`、`super-business-flow-memory`。
- 1 个 command：`/super-business-flow`。
- 3 个 subagent：`super-business-flow-local-tracer`、`super-business-flow-mechanism-explorer`、`super-business-flow-reviewer`。

可以先输入 `/super-business-flow` 查看用法。若没有识别，检查宿主工作目录、`.cac` 发现规则及现有 Skill/子代理权限配置。

这里的 `.cac` 来自公司 Agent 约定，尚未在该内部宿主实测。上游 OpenCode 文档采用 `.opencode` 等发现路径，不能据此保证内部 fork 的目录解析完全一致。[OpenCode Skills](https://opencode.ai/docs/skills/)、[Commands](https://opencode.ai/docs/commands/)

## 7. 先共创并核验 RPC 框架规则

目前提供的 `rpc-reference-rest-schema` 是脱敏示例的学习起点，尚不是已确认规则。先补充真实注解 imports、Maven 依赖版本、注册或代理代码线索，以及已知调用对；已有输入位于 [frameworks/rpc-reference-rest-schema/input.md](frameworks/rpc-reference-rest-schema/input.md)。

在 Agent 对话中输入如下任务即可；本版本没有另建 `/super-business-flow-frameworks` 斜杠命令：

```text
请加载并执行 super-business-flow-frameworks。
工作区：D:\poi-workspace
RPC ID：rpc-reference-rest-schema
先读取 docs/business-flow/frameworks/rpc-reference-rest-schema/input.md，
结合 poi-entry、text-search 仓库和实际版本的 Maven 依赖源码，
查证注解全限定名、服务注册、客户端代理、operation 选择及参数/响应适配。
生成精简协议、规则、证据、验证记录和待确认问题，整理已查证的服务身份。
缺少证据或存在多种解释时向我确认。
```

把示例仓库名换成实际名称。不同 RPC 实现分别建立 `frameworks/<rpc-id>/`。机制尚不明确时可以继续收集证据，但不能把猜测写进生效规则。框架学习不要求预先存在端点映射；映射阶段再结合已验证规则、服务身份和源码生成候选。[端点关联与人工修订格式](design/mapping-contract.md)

## 8. 跑通第一个业务入口

建议先选一个熟悉的 URL，检查入口定位、RPC 对应关系和条件分支，再扩大范围。这样能尽早发现规则或仓库配置缺口。

在 **Agent 对话中**执行：

```text
/super-business-flow --url "/map/search/v1/textsearch/searchByText"
```

其他支持的输入：

```text
/super-business-flow --class "com.company.SearchService"
/super-business-flow --url "/path/a" "/path/b"
/super-business-flow --url "/path/a" --url "/path/b"
/super-business-flow --class "com.company.SearchService" --url "/path/a"
/super-business-flow --all
/super-business-flow --resume <run-id>
```

`--class` 与 `--url` 组合表示对**同一个入口取交集**，不是调用方到提供方的起止点；类可以位于 Service 等任意层。`--all` 扫描已注册范围内的入口，不能与入口筛选参数混用。恢复执行时使用真实 run ID，不再附加新的入口参数。

主 Skill 编排事实扫描、机制查证、端点关联、场景追踪、复核及文档发布。直接运行 Java CLI 的 `run` 子命令只创建任务和检查点，不会自行调用模型完成业务分析。

正常的请求条件保留为分支；遇到无法确认的框架规则、目标或分派机制时，Agent 应提出具体问题并暂停受影响分支。回答后可通过 `--resume` 继续；尚有缺口的知识只能标记为 `partial`。

## 9. 查看产物和维护安装

| 产物 | 工作区内的位置 |
| --- | --- |
| 业务概述、流程图和代码链接 | `docs/business-flow/knowledge/<scenario-id>/README.md` 及版本文档 |
| 可复用的结构化业务图 | `docs/business-flow/scenarios/<scenario-id>/graph.json` |
| 自动发现、人工覆盖、生效端点关联 | `docs/business-flow/mappings/generated/`、`overrides/`、`effective/` |
| 带创建/更新时间的项目记忆 | `docs/business-flow/memory/` |
| 持久证据 | `docs/business-flow/evidence/` 及各分类的规范证据文件 |
| 运行状态、问题和检查点 | `.temp/run/super-business-flow/<run-id>/` |

记忆通过专用 Skill 和工具维护引用、版本与时间；发现过时内容后根据证据修正或撤回，删除保留审计记录。[记忆契约](design/memory-contract.md)

升级时先保存当前版本与项目修改，在工具包源目录获取新版本，逐项比较并合入 `.cac`、`tools/`、`schemas/`、`templates/` 及设计说明，再重新构建工具。**保留并合并**已有 `project/` 配置、`frameworks/` 规则、`mappings/` 人工修订、`scenarios/`、`knowledge/`、`memory/` 和 `evidence/`；不要用新的空配置覆盖已积累的工程知识。模式或契约变化时先阅读差异，再决定迁移。

本指南根据当前源码核对了命令、目录和配置路径。工具的既有 Java 21 验证记录见 [交付验证](design/validation-2026-09-11.md)；Windows PowerShell 安装过程、公司内部 `.cac` 宿主及真实 RPC 仍需要在你的环境完成首次接入验证。
