package demo;

import org.springframework.http.ResponseEntity;

public interface IInnerTsRpcService {
    ResponseEntity<FusionResponseDTO> searchByText(FusionRequestDTO request);
}
