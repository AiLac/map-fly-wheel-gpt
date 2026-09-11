package demo;
import org.springframework.http.ResponseEntity;
public interface IInnerTsRpcService {
    ResponseEntity<String> searchByText(String request);
}
