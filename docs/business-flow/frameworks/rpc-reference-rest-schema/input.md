# 程序员提供的 RPC 种子样例

状态：candidate。来源：本项目需求讨论中的脱敏片段。真实注解 imports、依赖版本、框架注册/代理实现、服务注册配置尚未提供。该文件用于后续执行 `super-business-flow-frameworks`，不是已生效的规则。

## 调用方

```java
@Slf4j
@Component
public class TsInnerClient implements IInnerTsRpcService {
    private static TsInnerClient tsInnerClient;

    @RpcReference(microserviceName = "MapSiteService:MapSearchTextSearchService", schemaId = "tsRpc")
    private IInnerTsRpcService innerTsRpcService;

    @PostConstruct
    private void init() {
        log.info("tsInnerClient init");
        tsInnerClient = this;
    }

    public TsInnerClient() {}

    public static synchronized TsInnerClient getInstance() { return tsInnerClient; }

    @Override
    public ResponseEntity<FusionResponseDTO> searchByText(FusionRequestDTO request) {
        return innerTsRpcService.searchByText(request);
    }
}
```

## 提供方

```java
@RestSchema(schemaId = "tsRpc")
@RequestMapping(value = "/ts-rpc/", produces = MediaType.APPLICATION_JSON_VALUE)
public class TsSearchRpcService {
    @PostMapping(path = "searchByText")
    public FusionResponseDTO searchByText(@RequestBody @Valid FusionRequestDTO request) {
        log.info("searchByText rpc start, requestId:{}", request.getRequestId());
        return search(request);
    }
}
```

## 扫描任务

优先从真实仓库补全注解全限定名、Maven 依赖版本，再追踪：字段代理注入 → 服务/schema/operation 选择 → 服务端注册 → 参数及响应适配。必要时读取可获取的 Maven 精确版本 sources JAR。独立已知调用对与负例验证完成后才生成 verified 规则。

## 当前不能推断的内容

- 冒号如何解释、是否有命名空间或部署版本，均未知。
- 方法名是否就是 operation ID、重载如何区分，均未知。
- `ResponseEntity<T>` 与 `T` 是否经框架适配，尚无证据。
- 封装类 implements 同一接口不等于 RPC 字段指向自己；提供方也未声明 implements 消费者接口。
- `search(request)` 的下游实现未给出，不能补写召回、排序等业务步骤。

格式示例见 [端点关联契约](../../design/mapping-contract.md)。真实 repo/module/service 身份由程序员与 Agent 扫描确认，不使用这里的示例标识冒充实际项目。
