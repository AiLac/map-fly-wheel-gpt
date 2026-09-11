---
name: super-business-flow-mappings
description: 基于已验证 RPC 规则与服务身份生成调用点到端点的关联，维护独立人工补丁并验证冲突、过期和条件多目标，供静态链路展开使用。
---

# 端点关联

主管 `docs/super-business-flow/mappings/`。先读 [映射契约](../../../docs/super-business-flow-tookit/design/mapping-contract.md)；直接沿用其中的规则、服务身份、生成层、人工层和端点示例，不发明平行格式。

按仓库、模块、服务登记端点，使用稳定 endpoint ID、完整类名和方法签名；调用点还记录接收字段及稳定 callsite ID。行号只用于定位。服务端不必实现消费方接口；外层 wrapper 的 implements 关系也不能覆盖字段上的远端调用证据。

1. 核对 scan facts、framework revision、services 来源与当前快照。规则未知时转 `super-business-flow-frameworks`；不靠名称相似或唯一候选补齐机制。
2. 用 `mappings discover` 生成候选包，保存原始服务标识、端点、证据和诊断。缺失 operation、版本或适配证据时保留不确定状态。
3. 程序员或 Agent 在独立 overrides 文件增加/修复关联，说明理由、证据和预期源指纹。使用映射契约支持的 action；不要直接改生成结果。
4. 用 `mappings resolve` 生成 effective。检查未知端点、冲突、指纹过期、缺失条件和未解决候选；解决不了时询问，禁止最后写入者覆盖。

多目标必须有已知路由依据和条件引用；互斥、并发扇出、失败回退应按真实语义区分。若只是多个候选尚未确定，保留 candidate/问题，不当成条件分支。`condition_ref: null` 只适用于已证明无额外条件的绑定。

人工确认也要带适用快照和依据；它不能静默压过矛盾代码。重新扫描保持人工层，过期补丁必须重验证。只由主代理在短时项目写锁和版本检查下发布 canonical 映射；子任务提交补丁候选。

输出生效关联、保留的未决项及覆盖报告，交给 scenarios。变更 RPC 规则或端点时回到各自主管目录，随后更新 memory 的索引引用；记忆内容不能直接成为端点绑定。
