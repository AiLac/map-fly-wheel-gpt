package demo;

import fixture.rpc.RestSchema;
import fixture.rpc.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RestSchema(schemaId = "tsRpc")
@RequestMapping("/ts-rpc/")
public class TsSearchRpcService {
    @Operation(id = "text-v2")
    @PostMapping(path = "searchByText")
    public String searchByText(String request) {
        return request;
    }
}
