package demo;

import example.rpc.RpcReference;
import org.springframework.http.ResponseEntity;

// Synthetic fixture based on the user's redacted shape; not a real framework contract.
public class TsInnerClient implements IInnerTsRpcService {
    @RpcReference(microserviceName = "MapSiteService:MapSearchTextSearchService", schemaId = "tsRpc")
    private IInnerTsRpcService innerTsRpcService;

    @Override
    public ResponseEntity<FusionResponseDTO> searchByText(FusionRequestDTO request) {
        return innerTsRpcService.searchByText(request);
    }

    public ResponseEntity<FusionResponseDTO> localShadow(IInnerTsRpcService innerTsRpcService,
                                                        FusionRequestDTO request) {
        return innerTsRpcService.searchByText(request);
    }
}
