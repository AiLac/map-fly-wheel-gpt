# v1 实现协作契约

状态：已确认范围内的实现约定。所有路径以工作区项目根目录为基准，业务仓库保留各自 Git 历史。工具在 `docs/super-business-flow-tookit/tools/`，Java 包 `io.superbusinessflow`，Java 21 + Maven，JavaParser 3.28.2、Jackson 2.18.2、JUnit 5.11.4。

## 工具接口

`Main` 解析全局 `--project <root>`（默认当前目录），首个参数为子命令；以 `--class`、`--url`、`--all`、`--resume` 开始时默认 `run`。各服务提供 `public static JsonNode execute(Path project, List<String> args) throws Exception`。Main 以 UTF-8 JSON 输出结果；使用错误退出 2，执行失败退出 1。服务可抛 `IllegalArgumentException` 描述可操作的错误。不得调用 LLM 或猜测业务机制。

共用 `Data` API：`JSON`、`YAML` ObjectMapper；`read(Path)` 按后缀读 YAML/JSON；`write(Path, JsonNode)` 同目录临时文件 + 原子替换；`object()`、`array()`；`sha256(byte[])`；`fingerprint(Path)`；`id(String)` 校验安全文件名；`lock(Path)` 返回 AutoCloseable 的 OS 文件锁（不自动删除锁文件）；`require(boolean,String)`；`now()` UTC ISO8601。路径和参数使用结构化列表，不拼接 shell。

服务归属：`scan` → `ScanService`；`mappings` → `MappingService`；`run`、`task` → `RunService`（task 分发时 args 加前缀 task）；`memory` → `MemoryService`；`validate`、`evidence`、`publish` → 主实现。

## 基本数据

所有 JSON/YAML 根对象使用 `schema_version: 1`。结构化数据采用 snake_case。证据引用必须能落到真实源文件或持久化证据，禁止只引用临时运行目录。业务图与执行任务图分离。

设置文件 `docs/super-business-flow/project/settings.yaml`：`execution.max_parallel_tasks=3`、`execution.phase_limits`、`execution.max_retries=2`、`execution.task_timeout_seconds=900`、`context.max_memory_items=10`、`context.max_task_tokens=24000`、`memory.enabled=true`。

仓库文件 `docs/super-business-flow/project/repos.yaml`：`repos: [{id, path, modules: [{id, source_roots, classpath_file}]}]`。repo path 相对于项目根；module source_roots、classpath_file 相对于 repo 根（模块 Maven classpath 独立）。未配置仓库时提示配置，不扫描工具自身。

## 模块边界

扫描器：`scan --repo-id ID --module-id ID --source-root PATH`（可重复）`[--classpath-file PATH] --out PATH`。输出 `classes`、`methods`、`calls`、`endpoints`、`conditions`、`evidence`、`diagnostics` 数组；每项有稳定 `id`，类 `class_name`，方法 `method_signature`，调用 `caller_method_id`/`expression`/`receiver_field`/`target_signature`/`resolution`/`condition_refs`/`evidence_refs`。提供 `ScanService.scan(String repoId,String moduleId,List<Path> sourceRoots,Path classpathFile)` 供编排调用。端点只在有依据时确认，显式注解导入、完整 URL 组合；无法解析的表达式、元注解、动态注册必须报告，禁止同名注解冒充已知框架。

映射器：`mappings discover --facts FILE --rules FILE --services FILE --out FILE`；`mappings resolve --generated FILE --endpoints FILE [--overrides FILE] --out FILE`。规则为显式、有限可执行匹配 DSL，不能执行任意代码。人工层独立保存，过期 fingerprint 与冲突报错。运行器不得将 candidate 转换为 confirmed。

运行器：默认 run 接受约定 selectors，run 状态 `.temp/run/super-business-flow/<run-id>/`；冻结输入、设置、仓库源/配置/规则/映射/已用记忆版本。初次 run 创建任务、快照和恢复说明；`task claim/finish` 控制总并发、状态、依赖与输出，主 Agent 负责驱动 scan→frameworks→mappings→scenarios→knowledge。CLI 不伪装已经执行 Agent 业务分析。恢复发现变化时保守失效化并重新排队；不沿用旧完成状态。

记忆器：`memory put --file FILE [--expected-revision N]`；`memory retrieve [--query TEXT] [--limit N]`；`memory verify --id ID`；`memory delete --id ID --expected-revision N --reason TEXT`；`memory index`。每条 Markdown frontmatter + 正文，保留版本/墓碑，可重建索引，创建/更新/验证/使用/删除时间独立；source_refs 包含项目相对 `path` 和 `sha256`，缺失仓库不等于证伪。

发布器：验证人工/Agent 汇总图 `nodes/edges/conditions/evidence_refs/coverage`、问题与证据后生成 Markdown、Mermaid、代码超链接。未完成图只能发布明确 partial 状态；记忆不能作为原始证据。示例业务图须标注合成夹具，不能冒充用户项目扫描结论。

## 交付原则

六个 Skill：主流程及 frameworks/mappings/scenarios/knowledge/memory。`.cac` 中只放 Agent 集成；规范、模板、实现、夹具与工具验证记录在 `docs/super-business-flow-tookit/`，工程配置与业务产物在 `docs/super-business-flow/`。三个根目录常量统一由 `Data.TOOLKIT_ROOT`、`Data.DATA_ROOT`、`Data.RUN_ROOT` 定义；运行产物不写回工具集。参考官方 OpenCode 语法，但 `.cac` 加载能力属于用户内部 fork，须在该宿主验收，不能声称在本环境测试过。
