package demo;

import example.rpc.RestSchema;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;

@RestSchema(schemaId = "tsRpc")
@RequestMapping(value = "/ts-rpc/")
public class TsSearchRpcService {
    @PostMapping(path = "searchByText")
    public FusionResponseDTO searchByText(FusionRequestDTO request) {
        return search(request); // Missing on purpose: do not invent the downstream body.
    }
}
