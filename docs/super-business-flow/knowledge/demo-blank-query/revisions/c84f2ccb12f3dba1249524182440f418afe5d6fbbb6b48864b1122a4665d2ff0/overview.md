# 文本入口的空值与非空分支（合成示例）

状态：**partial**。本图描述业务场景的静态可能链路，不能证明某次请求实际执行的路径。

入口接收文本参数。query 为 null 或空白时返回 EMPTY；否则调用 normalize 去除首尾空白后返回。源码未展示 RPC、POI 召回或排序，这些能力不在此例中。

## 范围与缺口

入口：{"class_name":"demo.SearchService","url":"/map/search/v1/textsearch/searchByText","http_method":"POST"}

- open_questions: []
- frontier: [{"scope":"Spring 参数绑定与全局异常处理","reason":"此演示仅分析方法内部控制流，未提供 Spring 应用配置"}]
- accepted_exclusions: []

[流程图](flow.md) · [服务交互](sequence.md) · [代码和证据](code-links.md) · [结构化图](graph.json)
