# v0.2 目录拆分验证

本记录对应 [目录拆分提交 5afb795](https://github.com/AiLac/map-fly-wheel-gpt/commit/5afb79582123d71e0951724c6df5940ff2acf1c2)。机器结果与源清单保留当时的路径和指纹；后续命令入口更名见 [独立验证记录](validation-command-start.md)。

日期：2026-09-11 UTC。范围：按用户指定的 `docs/super-business-flow-tookit/` 与 `docs/super-business-flow/` 拆分工具发行内容和工程数据。`.cac` Skill 名称及运行根目录保持不变。[源码与配置指纹](validation-layout-source-manifest.json)、[机器验证结果](validation-layout-results.json)。

## 构建与行为验证

Linux 环境使用 Microsoft OpenJDK 21.0.12.1、Maven 3.9.9，从新的 `tools/pom.xml` 构建 v0.2.0 JAR。首次离线 `clean verify` 因未缓存 clean 插件而停止；将旧 target 移至忽略的本地构建目录后，使用现有依赖离线执行 `verify`，重新编译全部源码和测试并成功打包。未更改业务工程或下载新的业务依赖。

| 测试类 | 通过数 |
| --- | ---: |
| ScanServiceTest | 14 |
| MappingServiceTest | 19 |
| RunServiceTest | 11 |
| MemoryServiceTest | 11 |
| PublicationServiceTest | 7 |
| **合计** | **62** |

0 失败、0 错误、0 跳过。针对拆分增加或扩展的行为检查：扫描输出不能覆盖工具模板；工作区自身作为登记仓库时，工具文件和已发布扫描结果不被误认为业务配置变化，但实际框架规则变化仍会失效化运行；工具模板不能因工作区被登记为仓库就成为真实业务记忆的原始证据。

六个 Skill 均通过 skill-creator 的命名/frontmatter 校验。Skill 从工具根读取规范、模板和 Java CLI，工程配置和长期结果使用数据根；命令与三个子代理的引用同步更新。全体本地 Markdown 跳转及当前示例的证据、源码哈希另行核对。

独立子代理按“从一个 URL 启动分析，随后升级工具”的接入任务，只读检查 Skill、命令、Java 实现和测试，并实际执行 JAR 帮助及 Skill 链接检查。复核结论为 pass，未发现目录迁移阻断性回归；明确指出运行快照未固定工具版本，因此部署与迁移说明保留跨版本恢复限制。此复核未执行真实业务分析。

## 新布局示例发布

通过新 JAR 实际执行 `--help`、合成 Service 扫描、证据持久化、图 Schema 校验和知识发布。扫描事实为 1 个类、2 个方法、1 个 HTTP 入口；未提供 Spring classpath 的 1 项诊断明确保留。

| 内容 | 位置 |
| --- | --- |
| 工具附带源码与图输入 | [合成示例](../examples/blank-query/README.md) |
| 本次扫描事实 | [facts.json](../../super-business-flow/inventory/demo/main/facts.json) |
| 当前业务图与发布版本 | [manifest.json](../../super-business-flow/scenarios/demo-blank-query/manifest.json) |
| 当前知识入口 | [业务知识](../../super-business-flow/knowledge/demo-blank-query/README.md) |

当前发布 revision 为 2，状态仍为 `partial`；示例没有扩展业务范围或宣称完成真实 RPC 分析。新源路径参与证据身份计算，图经工具重新发布生成新指纹与源码跳转；旧合成发布保留在 [迁移前 Git 提交](https://github.com/AiLac/map-fly-wheel-gpt/tree/11ebd29bc28fbe3aee08141ab472a08a60bd9e84/docs/business-flow/knowledge/demo-blank-query)。本次没有迁移用户真实运行数据。

## 已知范围

旧版 [验证记录](validation-2026-09-11.md) 的机器结果与源码清单保持原始字节，作为历史记录；其中的旧路径不再是当前运行配置。目录迁移过程和升级边界见 [迁移说明](layout-migration.md)。

运行快照未固定工具与 Skill 的版本，本次不保证跨版本无缝 `--resume`。升级前使用原版本完成或归档旧 run，升级后新建 run。Windows PowerShell 安装、公司内部 `.cac` 宿主以及真实自研 RPC 尚未实测；本记录只证明当前环境中的工具行为与仓库引用。
