# 依赖 JAR 文件占用修复与验证

日期：2026-09-11 UTC。修复基线：`294ac0990999c23ba7762d030e2206c57b880683`。

## 用户现象与根因

Windows 下运行 `mvn -f docs/super-business-flow-tookit/tools/pom.xml verify`，62 项测试中断言失败为 0、错误为 1。错误发生在 `ScanServiceTest.perModuleClasspathDoesNotLeakDependencyVersionsAcrossScans` 完成后的 JUnit 临时目录清理：两个 `dependency.jar` 仍被占用。`compilerVersion` 弃用警告不是这次构建失败的原因。

核对实际依赖 JavaParser 3.28.2 与 Javassist 3.31.0-GA 的字节码：`JarTypeSolver` 通过 `ClassPool` 读取 JAR；`JarClassPath.openClassfile()` 默认使用带缓存的 JAR URL 连接。该缓存可在类文件流关闭后继续保留文件句柄。现有 `URLClassLoader.close()` 只负责类目录加载器，不能清除此缓存。Javassist 的 [官方缓存选项说明](https://www.javassist.org/html/javassist/ClassPool.html#cacheOpenedJarFile) 描述了这一开关。

## 修复与取舍

`ScanService` 在类初始化时设置 `ClassPool.cacheOpenedJarFile = false`，早于创建任何 JAR solver；POM 显式声明实际使用的 Javassist 3.31.0-GA。保持模块独立 solver，不改变依赖版本。该开关属于工具进程内 Javassist 的全局设置，不按扫描任务切换或恢复，避免并发任务互相干扰；没有修改整个 JVM 的 URL 协议默认缓存。

| 方案 | 取舍 |
| --- | --- |
| 采用：关闭 Javassist JAR 连接缓存 | 保留现有字节码解析能力，改动小；可能增加文件打开次数，尚未量化性能影响。适用当前独立 CLI，未来嵌入宿主 JVM 时需重新评估。 |
| 自建具有明确关闭生命周期的 JAR 类型解析器 | 可把资源管理限制在单次扫描内，但需要维护类型、嵌套类和模块解析兼容性；当前修复不引入该复杂度。 |
| 跳过测试、关闭 JUnit 清理或等待 GC | 不能保证实际扫描释放文件，会掩盖资源问题，因此不采用。 |

## 回归验证

环境：Linux、Microsoft OpenJDK 21.0.12.1、Maven 3.9.9，使用已缓存的依赖执行离线构建。

1. 先扩展现有模块隔离测试，不改生产代码：扫描后检查 `/proc/self/fd` 中不再持有这两个依赖 JAR，再尝试删除文件。Linux 允许删除打开的文件，因此仅依赖 JUnit 清理无法覆盖这个缺陷；Windows 则由显式删除和 JUnit 清理检查文件占用。
2. 修复前单测执行结果：1 项测试、1 项断言失败，消息为 `Scan retained an open dependency JAR`，确认回归断言能捕获原有缺陷。
3. 应用生产修复后执行完整 `verify`：62 项测试通过，Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`，已重新生成工具 JAR。测试同时保留原有两版本依赖解析结果断言。

此次没有在原生 Windows 上执行构建，不能把 Linux 文件句柄检查等同于 Windows 实测。用户更新工具目录后应在原工程重跑相同 `verify` 命令。原始日志中的个人路径未写入仓库；已有交付验证记录保持原始历史状态。
