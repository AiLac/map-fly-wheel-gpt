# 运行、任务和恢复契约 v1

本地 Java CLI 管理任务状态与版本，`.cac` 主 Agent 执行业务分析和子任务调度。`run` 的返回结果是队列准备就绪，不是链路分析完成。每个模块先产生 inventory 任务，随后是 frameworks、mappings、scenarios、knowledge；可通过 task add 按入口或边界继续拆分。

## 输入

Agent 对话中的统一入口为 `/super-business-flow-start`，例如 `/super-business-flow-start --resume RUN_ID`；它加载主 Skill `super-business-flow` 编排业务分析。下面的 Java CLI 只管理确定性工具操作与任务状态，不替代 Agent 执行。

以下 `flow` 表示 `java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar`；可使用 `tools/bf.ps1` 或 `tools/bf.sh` 缩短命令。项目根由当前目录或 `--project ROOT` 指定。

```text
flow --class com.example.SearchService
flow --url /map/search/v1/textsearch/searchByText /map/search/v1/suggest
flow --url /path/a --url /path/b
flow --class com.example.SearchService --url /path/a
flow --all --max-parallel-tasks 3 --phase-limit inventory=2
flow --resume RUN_ID --max-parallel-tasks 2
```

class 与 URL 组合为交集；all 与入口选择器互斥；resume 与新入口互斥。重复 URL 去重，查询参数不决定业务分支；保留路径编码与尾部斜杠，不猜测网关前缀。绝对 HTTP URL 只提取 provider path，跨服务同路径仍需证据消歧。无参数显示帮助。类名不要求 Controller 等后缀；是否是入口由扫描事实和 Agent 对注册机制的核验决定。

新运行参数优先级：CLI > `project/settings.yaml` > 默认值。调度项：max_parallel_tasks、max_retries、task_timeout_seconds 和 `--phase-limit PHASE=N`。上下文项：`--max-task-tokens`、`--max-memory-items`。阶段名 inventory/frameworks/mappings/scenarios/knowledge/review。运行器限制领取数和阶段数；token 使用量由宿主/主 Agent 监控，达到预算前 checkpoint。CLI 不测量模型 token。

## 配置与依赖

`project/repos.yaml` 显式列出 repo id/path 和 module id/source_roots/classpath_file。源根、classpath 文件相对 repo 根；classpath **文件内容**相对该文件父目录，可以按平台路径分隔符或换行分隔，建议 Maven 输出绝对路径。各模块独立 classpath，禁止合并所有仓库依赖。示例：

```yaml
schema_version: 1
repos:
  - id: caller
    path: ../caller-repo
    modules:
      - id: application
        source_roots: [application/src/main/java]
        classpath_file: ../map-fly-wheel-gpt/docs/super-business-flow/project/local/caller-application.cp
```

上例假设工具工作区目录名是 map-fly-wheel-gpt；实际按你的目录填写。建议把 classpath 文件统一生成到工具项目的 `docs/super-business-flow/project/local/`，并将其真实位置登记到 classpath_file；配置也接受绝对路径。PowerShell 示例：

```powershell
New-Item -ItemType Directory -Force docs/super-business-flow/project/local
$flowClasspath = Join-Path (Resolve-Path docs/super-business-flow/project/local).Path 'caller-application.cp'
mvn -f ../caller-repo/application/pom.xml dependency:build-classpath "-Dmdep.outputFile=$flowClasspath"
```

目标机 Maven 可下载公司依赖；失败应记录缺口。需要生成源码时先按该项目已有构建方式生成，再显式添加其源根。

## 状态文件

`.temp/run/super-business-flow/<run-id>/state.json` 是原子更新的权威状态。snapshot.json、tasks.json、frontier.json、questions.yaml、decisions.yaml、resume.md 是可重建投影；history 保留旧快照/失效任务，tasks/<id>/ 是分配给某个子任务的输出目录。运行目录自动创建忽略规则；无需把大体量临时状态提交 Git。本次业务分析中被采纳的发现和证据必须发布到 `docs/super-business-flow/`；工具自身的设计与契约维护在 `docs/super-business-flow-tookit/design/`。

快照保存归一化入口、有效配置、源码/配置/Git HEAD、模块 classpath 及依赖字节、规则/映射、已用记忆版本。业务仓库可位于兄弟目录。恢复时源输入变化会保守重排全部分析阶段；旧任务结果保留历史，不混入新快照。无输入变化时只恢复未完成队列。resume 使用冻结语义配置；编辑 settings 文件被记录为输入变化，但不会自动接受新的语义/上下文设置，需要新运行。调度覆盖可以应用并写入事件。

## 领取和结果提交

```text
flow task list --run-id RUN_ID
flow task claim --run-id RUN_ID --worker main
flow task claim --run-id RUN_ID --worker tracer-1 --task-id TASK_ID
flow task heartbeat --run-id RUN_ID --task-id TASK_ID --worker main --lease-token TOKEN
flow task finish --run-id RUN_ID --task-id TASK_ID --worker main --lease-token TOKEN --result .temp/run/super-business-flow/RUN_ID/tasks/TASK_ID/result.json
flow task checkpoint --run-id RUN_ID --task-id TASK_ID --worker main --lease-token TOKEN --file .temp/run/super-business-flow/RUN_ID/tasks/TASK_ID/result.json
```

claim 返回 task（含 run_id/snapshot_id/id/lease.token）和 output_directory。只能提交该目录内真实文件，文件指纹必须一致；旧租约、外来任务输出和快照不匹配会被拒绝。任务结果固定格式见 [result 模板](../templates/tasks/result.json)。status 为 completed/blocked/failed；checkpoint 会保存 frontier 后重新排队，带问题则阻塞。completed 必须有可审阅 output_files，questions/frontier 均为空。工具检查身份和文件完整性，主 Agent 仍要审核证据是否支持结论。

任务超时释放租约并计入 failures；初次执行后允许 max_retries 次失败重试，超过预算成为 failed。长任务应 heartbeat；上下文不足应 checkpoint。主 Agent 领取后再派发子 Agent；所有子任务共用队列并发预算，不再递归创建未登记的子 Agent。

动态拆分：`task add --run-id ID --file <JSON>`，内容为 `{schema_version:1,tasks:[{id,phase,objective,dependencies:[<task-id>],scope:{...}}]}`。拒绝重复 ID、缺失依赖、依赖循环和依赖更晚阶段的任务。业务图中的递归和循环不受任务 DAG 限制。

## 用户确认和阶段采用

blocked 结果附 `{id,question,...}`。`task answer --run-id ID --file <JSON>` 的内容为 `{schema_version:1,question_id,answer,answered_by,source:"user"}`。只有真实用户回答才能写 source:user，工具不替代交互身份验证。回答完相关问题后任务重新排队，仍需重新分析，不能直接标完成。

子 Agent 只写任务目录。主 Agent 先审核、finish 当前阶段的暂存结果，再把已验证规则/映射发布到相应目录；最后使用阶段屏障采用新版本：

```text
flow task changes --run-id ID
flow task adopt --run-id ID --file docs/super-business-flow/project/local/adopt.json
```

adopt 文件：`{schema_version:1,expected_snapshot_id,phase:"frameworks"或"mappings",reason,evidence_refs:[...],changes:[<完整复制 task changes 的项目记录>]}`。必须没有活动任务，且 changes 与当前实际差异精确一致，只接受该阶段目录变化；保留已审核前驱，失效后继。源码、服务登记、依赖和项目设置变化用 resume 重新核验，不能借 adopt 绕过。

## 记忆版本固定

先调用 memory retrieve/verify；只在派发前或无活动任务的阶段屏障执行 `task use-memory --run-id ID --file <JSON>`。文件为 `{schema_version:1,entries:[{id,revision,path,sha256}]}`，path 必须是 `docs/super-business-flow/memory/versions/<id>/rNNNNNN.md` 的不可变版本。工具重新核验当前记忆状态与源证据，拒绝 stale、pending、unavailable 或不匹配版本。读取导致的 last_used_at 变化不会改写已固定的版本。新任务只读本次实际采用的记忆；单条固定后要改用其他版本则新建运行。索引不是证据。

所有任务完成后状态为 ready_for_review；最终发布还要通过知识 Skill 的范围审查与 publish 校验。未知机制只阻塞受影响分支，可添加独立任务继续已确认工作；不能通过清空范围或把缺口记为 completed 宣称链路完整。
