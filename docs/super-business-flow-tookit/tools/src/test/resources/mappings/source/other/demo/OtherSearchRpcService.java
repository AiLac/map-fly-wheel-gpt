package demo;

import fixture.rpc.RestSchema;
import org.springframework.web.bind.annotation.PostMapping;

@RestSchema(schemaId = "tsRpc")
public class OtherSearchRpcService {
    @PostMapping(path = "otherSearch")
    public String searchByText(String request) { return request; }
}
