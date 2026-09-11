# v0.1 本地验证记录

执行日期：2026-09-11 UTC。范围：本仓库 Java 工具、Skill 文件规范、文档导航与合成样例。源码与配置摘要见 [验证源清单](validation-source-manifest.json)，测试计数见 [机器结果](validation-results.json)。此记录不宣称公司宿主或真实 POI 项目已验收。

## 工具回归

环境：Linux，Microsoft OpenJDK 21.0.12.1，Maven 3.9.9；目标编译 release 21。使用运行环境提供的网络 CA 和代理下载 Maven 依赖，没有关闭 TLS 校验。目标 PC 只需已有 Java 21、Maven 与公司依赖源，无需本次临时引导文件。

等价标准构建命令：`mvn -f docs/business-flow/tools/pom.xml verify`。实际执行使用临时安装的 Maven/JDK 和本环境代理设置，最终成功打包可执行 JAR。

| 测试类 | 通过数 | 主要观察 |
| --- | ---: | --- |
| ScanServiceTest | 14 | 非 Controller 入口、早返回、lambda、字段遮蔽、缺失依赖、同名注解、同 URL 候选、独立 JAR 版本 |
| MappingServiceTest | 19 | 服务/schema/operation 隔离、包装返回契约、候选保持、4 类人工修订、冲突/指纹/条件路由 |
| RunServiceTest | 10 | 多输入互斥、全局/阶段并发、快照失效、检查点、用户问题、classpath、一致记忆版本、阶段采用 |
| MemoryServiceTest | 10 | 时间/版本分离、更正 CAS、过时/不可用、墓碑、历史恢复、来源限制 |
| PublicationServiceTest | 7 | partial 门禁、证据/条件引用、RPC 调用点校验、映射历史快照、原代码和摘录链接 |
| **合计** | **60** | **0 失败、0 错误、0 跳过** |

测试只证明夹具覆盖范围。租约超时的极端并发、崩溃断电耐久、真实大仓性能及实际宿主模型行为仍需目标环境验收，不能由测试数量代替。

另执行 JAR `--help`、`scan --help`，均成功；对项目 settings/repos/services、示例 graph、框架问题文件执行 JSON Schema 校验，均成功。扫描 [合成 Service](../examples/blank-query/source/SearchService.java) 得到 1 个类、2 个方法、3 个调用、1 个 HTTP 入口；缺少依赖 classpath 的诊断被明确保留。已发布 [示例知识](../knowledge/demo-blank-query/README.md)，状态为 partial，包含未纳入分析的 Spring 参数绑定与异常范围。

## Skill 与独立试用

6 个 Skill 通过 skill-creator 的名称/frontmatter 校验。本地 Markdown 导航检查未发现失效链接。

独立评估者仅获得仓库文件与任务：“class=demo.TsInnerClient、url=/ts-rpc/searchByText，只有脱敏样例、缺少真实框架证据”。评估者只读走查 Skill，未收到预期答案，未创建运行，未修改源文件。

实际行为：识别出 repos/services 尚未登记；区分调用方类与提供方 URL 的交集约束；未把 wrapper implements 认作 RPC 自调用；未把样例 imports 认作真实框架；对 operation/响应适配保持 candidate；对缺失的 search(request) 保留 frontier。下一步应由 frameworks Skill 索取精确 Maven 坐标或实现路径，不应猜测绑定。

评估发现并修复了三项首次使用问题：scan 缺少帮助入口；主 Skill 缺少完整扫描命令与路径转换说明；classpath 示例位置不一致。现在 README/主 Skill 已链接 [扫描契约](scanner-contract.md)，CLI 提供 scan --help，依赖清单统一推荐放 project/local。

另外根据模块审查修复了：扫描/恢复 classpath 解析基准不一致、错误固定可变记忆文件、完成任务残留 frontier、任务依赖越过阶段、RPC 发布缺少调用现场关联检查、历史知识缺少有效映射内容快照。

## 尚待真实接入确认

- Windows、中文/空格路径及公司 `.cac` 宿主的技能/命令/子 Agent 发现与调用。当前只校验了文件规范和 Java 工具。
- 5–15 个真实业务仓库的源码、Maven 模块与部署服务身份。默认配置保持空列表，防止误扫工具自身。
- 自研 RPC 的真实 imports、精确版本、注册/代理逻辑、参数与响应适配、独立已知调用对。用户样例仅作为 candidate 种子保存。
- 真实复杂控制流、动态 Bean、组合/继承映射、异步时序等超出 AST 事实的分析质量。Skill 必须补充代码证据或保留缺口。

下一轮真实接入应从一个小入口开始，依次演练框架共创、自动关联、人工修订、条件业务链路、记忆更新和恢复，而后再扩大到 --all。静态图叠加日志/Trace 与 badcase 优化循环仍属后续阶段。
