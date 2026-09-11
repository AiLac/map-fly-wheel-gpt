---
name: super-business-flow-memory
description: 在业务链路分析中按需检索、记录、验证和更正项目记忆，管理创建更新时间、版本和删除墓碑，保存跨任务发现及权威知识索引。
---

# 项目记忆

主管 `docs/super-business-flow/memory/`。先读 [记忆契约](../../../docs/super-business-flow-tookit/design/memory-contract.md)；输入文件沿用其规范。CLI 管理时间、revision、历史版本和索引，不手改 `entries/`、`versions/` 或 `index.json`。

开始任务时以当前 repo/service/scenario/entity 检索少量记录，遵守配置预算，只加载相关正文。`retrieve` 的有效记录仍要按适用范围解释；实际采用的 `(id, revision)` 通过运行 checkpoint 固定。新生成记忆在显式刷新检查点或后续任务使用，不能悄悄改写进行中的推理前提。

在子任务完成、用户确认、阶段检查点和最终汇总时收集新增/更新候选。适合记忆：跨任务导航、框架和映射的权威文档引用、稳定代码观察、适用范围明确的决策、已验证陷阱。不整篇复制场景文档；RPC 协议和端点关系先更新 frameworks/mappings，再更新引用。

每条结论必须有范围和持久 source_refs。针对当前源码的结论同时引用已登记仓库中的实时源文件及持久摘录，便于发现版本变化；只有摘录不能证明当前源码仍适用。先保存证据，再用 `memory put --file ...` 发布。证据充分且适用版本清楚的观察可以自动记录为 verified；假设保持 pending，不能参与绑定。用户确认保存确认内容、时间和范围，不能扩展为所有版本的代码事实。

已有记录更新须带 `--expected-revision`；冲突时重新读取并合并，禁止覆盖他人更新。工具保持 `created_at`，内容/状态/依据变化才更新 `updated_at`；查阅更新 `last_used_at`，验证更新 `last_verified_at`，删除记录 `deleted_at`。

使用中发现证据变化，先 verify。新代码提供明确替代结论时自动修正并保存新依据；无法确定时标为 stale/待确认，停止使用原结论。仓库缺失、版本切换或暂时无法访问只说明 unavailable，不能当作事实已被证伪。

确认记忆已错误、重复或被明确取代时，可以用 `memory delete --id ... --expected-revision ... --reason ...` 自动逻辑删除并保留墓碑。物理清理不属于 v1 常规操作。删除 canonical 规则/映射不是本 Skill 的权限范围。

项目级锁与版本比较协调多运行写入；子代理只返回记忆候选，主代理验证后写入。最终只报告实际新增、更正、失效或删除的条目，不把“已检索”表述为全部项目知识已验证。
