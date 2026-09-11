# 合成示例：文本参数分支

此例是可复现的工具演示，**不是用户 POI 项目的分析结果**。源码是带 Spring 显式映射的普通 Service，展示不按类名后缀认入口、条件分支、方法调用、返回和可点击源码证据。Spring 参数绑定、异常处理未纳入分析，因此知识状态为 partial。

构建后可在项目根运行：

```powershell
java -jar docs/business-flow/tools/target/business-flow-tools.jar scan --repo-id demo --module-id main --source-root docs/business-flow/examples/blank-query/source --out docs/business-flow/examples/blank-query/facts.json
java -jar docs/business-flow/tools/target/business-flow-tools.jar evidence --source docs/business-flow/examples/blank-query/source/SearchService.java --symbol 'demo.SearchService#searchByText(java.lang.String)' --line-start 7 --line-end 19
java -jar docs/business-flow/tools/target/business-flow-tools.jar validate --file docs/business-flow/examples/blank-query/graph.json --schema docs/business-flow/schemas/graph.schema.json
```

`graph.json` 是依据源码人工/Agent 汇总的结构化业务解释；scan 只提供事实，不直接推导自然语言业务结论。publish 首次无需 expected-revision；重发时读取 `scenarios/demo-blank-query/manifest.json` 的当前 revision 后传入该值。

查看 [已生成知识入口](../../knowledge/demo-blank-query/README.md)。RPC 跨仓发现及人工修订的独立正反例见 `tools/src/test/resources/mappings/` 和 MappingServiceTest；真实框架仍需通过 frameworks Skill 共创核验。
