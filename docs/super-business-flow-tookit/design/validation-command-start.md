# 命令入口更名验证

日期：2026-09-11 UTC。按用户确认，将命令入口改为 `/super-business-flow-start`，主 Skill 名称保持 `super-business-flow`。命令负责加载 Skill 与传递参数，业务分析规范继续集中在 Skill 中；设计取舍见 [决策记录](decisions.md)。

变更包括 command 文件名与描述、主 Skill 的入口说明、README 中的目录表和示例、Windows 部署步骤，以及 Java 运行器生成的 `resume.md`。参数语法、业务分析规则、工程数据根和 run 目录均沿用现有契约。

验证环境为 Linux、Microsoft OpenJDK 21.0.12.1、Maven 3.9.9。执行 `mvn -f docs/super-business-flow-tookit/tools/pom.xml -Dtest=RunServiceTest verify`，运行相关的 11 项测试全部通过，并重新构建 JAR。

另在隔离的合成工程中用新 JAR 创建 run，再恢复同一 run，检查两次生成的 `resume.md` 都包含 `/super-business-flow-start --resume <实际 run-id>`，状态仍位于 `.temp/run/super-business-flow/<run-id>/`。此检查验证入口提示与运行器集成，没有执行真实业务分析。

静态检查确认：只有一个 `super-business-flow-start` command；它引用现有主 Skill；六个 Skill 名称与文件夹一致；修改后的主 Skill 通过 skill-creator 格式校验；当前使用示例及文档相对链接已同步。旧版机器验证清单保留当时的文件名和指纹，不通过改写历史记录消除旧名称。

旧安装需替换 command 文件并刷新宿主，详见 [部署说明](../deployment.md)。公司内部 `.cac` 宿主、Windows 和真实 RPC 工程未在本次实测；工具版本升级仍遵循已有的恢复限制。
