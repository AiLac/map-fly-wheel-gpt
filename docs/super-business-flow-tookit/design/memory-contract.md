# 项目记忆契约 v1

记忆用于保存跨任务发现、导航和权威知识引用。代码事实的权威来源仍是原始代码与持久证据；RPC 协议、端点映射、场景图分别由其目录负责。记忆既不代替这些文件，也不以旧记忆或 Agent 的重复断言作为独立验证。

## 存储与写入职责

| 路径 | 职责 |
| --- | --- |
| `docs/super-business-flow/memory/entries/<id>.md` | 当前条目，YAML frontmatter 与 Markdown 正文 |
| `docs/super-business-flow/memory/versions/<id>/r000001.md` | 不可变内容版本，含删除墓碑 |
| `docs/super-business-flow/memory/index.json` | 可重建的元数据索引，不包含长正文 |
| `docs/super-business-flow/evidence/` | 跨运行持久化的原始证据；发布记忆前保存 |
| `docs/super-business-flow/.project.lock` | 与发布器共用的短时 OS 文件锁 |

主 Agent 收集子任务候选，再通过 CLI 写入。禁止手改当前条目、版本或索引。每次内容发布先原子写入不可变版本，再原子更新当前条目和索引。版本文件是提交记录；进程中断留下“历史已写、当前未写”的状态时，下次读取自动从最新完整版本修复当前条目。索引丢失可直接重建；历史与当前的内容矛盾则报错，不静默覆盖。

多次运行共享项目锁，锁冲突时退出并提示重试；`--expected-revision` 防止读取之后发生的更新被覆盖。锁文件不需要删除。已创建的历史版本不随查阅、验证、索引重建而改写。文件原子替换受文件系统支持程度影响，不等同于数据库多文件事务或断电耐久承诺。

## 输入规范

`memory put --file` 接受 YAML、JSON，或 Markdown frontmatter 加正文。输入文件保存在 `docs/super-business-flow/` 的相应候选目录；运行内未发布候选可位于约定的 `.temp/run/super-business-flow/<run-id>/`。只有原始证据引用禁止指向 `.temp`。

```yaml
schema_version: 1
id: ts-client-field-navigation
type: navigation
statement: "TsInnerClient 的 RPC 引用字段是继续追踪远端调用的起点。"
scope:
  - "repo:caller"
  - "service:text-search"
state: pending
source_refs:
  - path: docs/super-business-flow/evidence/ts-client/Source.java
    sha256: "<真实文件字节的 64 位 SHA-256；示例占位符不可直接执行>"
canonical_refs:
  - docs/super-business-flow/frameworks/example-rpc/protocol.md
supersedes: []
basis:
  framework_revision: "<适用的规则版本>"
reason: "程序员提供样例后，等待框架扫描确认。"
body: |
  记录适用范围、导航方式和待确认事项；不能从同名方法推断 RPC operation ID。
```

Markdown 输入将 `body` 移到 frontmatter 后的正文。以上是格式说明，未声称示例路径、哈希、规则已存在。运行中的源引用必须使用实际证据与实际摘要。

| 字段 | 约束 |
| --- | --- |
| `schema_version` | 必须为整数 `1` |
| `id` | 稳定安全标识，1–160 字符，字母、数字、点、下划线、短横线，不允许 `..` |
| `type` | 非空类别，例如 `observation`、`navigation`、`decision`、`pitfall`、`canonical_index` |
| `statement` | 一条精简、可验证、限定范围的结论 |
| `scope` | 非空、不重复的字符串数组；建议 `repo:`、`service:`、`scenario:`、`entity:` 前缀；全项目可用 `project:*` |
| `state` | 输入允许 `verified`、`pending`、`stale`；`deleted` 仅由 delete 命令产生 |
| `source_refs` | 非空数组；每项至少包含项目相对 `path` 与文件字节 `sha256`，可补充 `repo_id`、`commit`、`symbol` 等版本定位元数据 |
| `canonical_refs` | 可选权威文档相对路径数组；仅用于导航，不能替代原始证据 |
| `supersedes` | 可选被替代记忆 ID 数组，替代不隐式删除旧条目 |
| `basis` | 可选对象，记录规则、配置、仓库版本等适用依据 |
| `reason` | 可选非空说明，解释新增或更正的理由 |
| `body` | YAML/JSON 中的可选 Markdown 正文；Markdown 输入使用分隔符后的正文 |

`revision` 和所有管理时间字段不允许出现在 put 输入中；由工具生成，避免客户端伪造时间。复制当前条目进行更正时，先生成只含输入字段的候选文件。

证据路径可以使用 `/`，工具也接受 Windows `\` 分隔符；不接受绝对路径。兄弟 Git 仓库可用项目相对 `../repo-a/...`，但必须匹配 `project/repos.yaml` 中显式登记的仓库。程序检查真实路径与符号链接的归属，允许 `evidence/` 或登记仓库内的原始文件；拒绝 `.temp`、`.git`、`docs/super-business-flow-tookit/` 工具发行文件、记忆自身以及 `docs/super-business-flow/` 内非 evidence 的生成知识作为原始证据。依赖源码或未提交的源文件应先保存到持久 evidence，并记录原始出处；仅有当前工作树路径的记忆，在切换版本或仓库缺失时可能暂时不可用。

## 状态与时间

工具核对来源存在性与字节哈希，**不能核对自然语言结论是否由代码推出**。`verified` 是 Agent/程序员完成语义验证后的声明，工具还要求所有证据当前可读且摘要匹配。哈希校验通过不会把 pending 自动提升为 verified，也不会把 stale 自动恢复。

| 情况 | 行为 |
| --- | --- |
| 来源全部匹配 | 更新验证时间；保留原状态 |
| 来源摘要变化 | 自动标记 stale、创建新版本、保留原摘要与变化详情，停止检索复用 |
| 来源缺失或不可读 | `verification.status=unavailable`，保留原状态与内容版本；停止检索复用，不作为结论错误的证据 |
| 缺失与摘要变化同时发生 | 记录每项问题，状态 stale；已有摘要变化足以证明依据过期 |
| 有明确的新代码证据 | Agent 修改候选结论及来源，使用 CAS put 更正；工具不会自行编造新结论 |
| 确认错误、重复或被取代 | CAS delete 产生墓碑，保留历史与删除原因 |

| 管理字段 | 生命周期 |
| --- | --- |
| `revision` | 从 1 开始；结论、正文、范围、依据或状态变化才递增 |
| `created_at` | 首次创建时间，更正与删除不改变 |
| `updated_at` | 当前内容版本产生时间；查阅、重复 put、成功验证、暂时不可用不改变 |
| `last_verified_at` | 最近一次所有来源摘要匹配时间；指依据检查，不代替语义审查 |
| `last_used_at` | 最近一次进入 retrieve 返回结果的时间；不产生内容版本 |
| `deleted_at` | 墓碑创建时间，其余状态为 null |
| `verification` | 审计元数据，包含 `status`、`checked_at` 与 `issues`，变化不单独创建内容版本 |

所有时间为 UTC ISO8601。验证/使用时间只更新当前条目和索引；历史版本保留其写入时的时间。没有变化的重复 put 保持内容版本；对摘要已经变化的条目进行检索或 verify 可产生一次 stale 版本，后续重复检查不持续递增。

## 命令与返回值

以下 `flow` 代表 `java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar`；Windows PowerShell 传入空格路径时使用双引号。

```text
flow memory put --file docs/super-business-flow/memory-candidate.yaml
flow memory put --file docs/super-business-flow/memory-candidate.yaml --expected-revision 1
flow memory retrieve --query "RPC wrapper" --scope "repo:caller" --limit 5
flow memory verify --id ts-client-field-navigation
flow memory delete --id ts-client-field-navigation --expected-revision 2 --reason "已有更准确的权威导航"
flow memory index
```

首次创建可不传 expected revision，或传 `0` 表示期望不存在；已有 ID 的任何 put 都必须传当前 revision。删除必须传 revision 和原因。删除后不复用同一 ID；需要恢复知识时创建新 ID，并用 supersedes 指向墓碑。

put/verify/delete 返回 `{schema_version, action, entry}`；entry 包含管理元数据及正文。retrieve 返回 `{schema_version, enabled, limit, items, excluded}`；`items` 是可用 verified 条目的完整内容，`excluded` 给出相关但不能使用的 ID、状态和验证状态。query 按空白分词、忽略大小写、要求所有词出现在 ID/类别/结论/范围/正文中；scope 精确匹配单个范围标签，不推断范围包含关系。没有 query 则按范围或全部候选检索，ID 排序保证稳定性；仅返回前 limit 条，并更新这些条目的 last_used_at。匹配查询的候选都进行来源检查，未选中记录不会标记已使用。

limit 默认读取 `context.max_memory_items`，缺省为 10；命令值覆盖默认，范围 1–1000。`memory.enabled=false` 时 retrieve 返回空 items，维护命令仍可用于检查与清理。检索使用逐条扫描和术语匹配，适合个人 PC 的精简项目记忆；条目规模或检索质量要求增长时可再替换索引实现，不改变 ID、证据和版本契约。

index 返回并保存 `{schema_version, generated_at, entries}`。每项是当前条目的 frontmatter 加 `entry_path`，不含正文。重建索引不检查全部来源新鲜度；运行时实际使用必须通过 retrieve/verify，并把实际消费的 `(id, revision)` 固定到运行检查点，不能把索引中的 verified 当作当前来源已验证。

## 方案权衡

采用每条 Markdown 加历史文件，便于开发人员审阅和 Git 追踪；相比 SQLite，缺少跨文件事务，但通过不可变版本提交记录、短锁与可重建索引支撑当前个人运行范围。采用可复现的范围/术语检索，部署依赖少且容易解释；相比向量检索，语义召回有限，因此 Skill 应按实体和同义词做少量按需查询。删除采用墓碑，避免破坏旧运行引用；物理清理不属于 v1 常规流程。

自动更正只在新证据已经明确支持替代结论时执行。来源变动的检测是程序职责，结论的修正是 Agent 与程序员共同承担的语义职责；无法判断时保留 stale 和待确认问题。
