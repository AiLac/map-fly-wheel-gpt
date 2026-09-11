package demo;

import fixture.rpc.RpcReference;
import org.springframework.http.ResponseEntity;

/** Synthetic protocol fixture; it is not a finding about a company RPC implementation. */
public class TsInnerClient implements IInnerTsRpcService {
    @RpcReference(microserviceName = "MapSiteService:MapSearchTextSearchService", schemaId = "tsRpc", operation = "text-v2")
    private IInnerTsRpcService innerTsRpcService;

    public ResponseEntity<String> searchByText(String request) {
        return innerTsRpcService.searchByText(request);
    }
}
