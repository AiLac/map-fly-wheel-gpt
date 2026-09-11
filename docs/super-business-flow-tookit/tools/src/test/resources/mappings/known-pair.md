# 合成映射测试的独立预期

这些 Java 文件是专用测试夹具，没有模拟完整的真实 RPC 框架。以下配对由测试作者指定；测试不从 discovery 输出反向生成期望。

- caller/demo.TsInnerClient.searchByText 中的 innerTsRpcService 字段调用，远端原始服务名整体为 MapSiteService:MapSearchTextSearchService。
- 在“Java 方法名作为 operation”的合成规则下，它应唯一匹配 provider/demo.TsSearchRpcService.searchByText。
- other/demo.OtherSearchRpcService 具有相同 schema 和方法名，但属于 OtherService，因此不能成为该调用的目标。
- TsInnerClient 实现了调用接口，TsSearchRpcService 没有实现该接口；不能根据 implements 把字段 RPC 解析为本地递归。
- consumer ResponseEntity<String> 与 provider String 的兼容机制在这些文件中没有实现；contract 未验证时，即使身份唯一，也只能得到候选项。
- 两侧 operation=unknown 时不得使用方法名补齐。
- 在显式注解属性 operation 规则下，operation 为 text-v2；这个结果不能作为上述真实公司框架的默认规则。

测试用 verified 状态仅表示此合成测试的已知输入，不表示这份说明能代替实际框架实现和测试证据。
