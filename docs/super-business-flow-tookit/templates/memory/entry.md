---
schema_version: 1
id: example-finding
type: code-observation
statement: "<范围明确、可由证据核验的发现>"
scope:
  - "repo:<repo-id>"
  - "service:<service-id>"
state: pending
source_refs:
  - path: "<已登记仓库中的项目相对源码路径>"
    sha256: "<原文件真实 SHA256>"
  - path: "docs/super-business-flow/evidence/<hash>/source.txt"
    sha256: "<持久摘录真实 SHA256>"
canonical_refs: []
supersedes: []
basis:
  code_versions: {}
  framework_revisions: {}
  mapping_revisions: {}
reason: "<新增/修正依据>"
---

说明发现的适用范围、证据支持的内容及例外。不要把候选假设写成已确认机制。

本文件是 put 的输入；不填写 created_at、updated_at、last_verified_at、last_used_at、deleted_at 和 revision，这些由 CLI 管理。针对当前代码的记忆应保留实时源码引用及持久摘录；只有摘录只能证明历史材料未变化。
