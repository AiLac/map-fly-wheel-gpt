package demo;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Synthetic fixture, not a real POI service. */
@RequestMapping("/map/search/v1/textsearch")
public class SearchService {
    @PostMapping("/searchByText")
    public String searchByText(String query) {
        if (query == null || query.isBlank()) {
            return "EMPTY";
        }
        return normalize(query);
    }

    private String normalize(String query) {
        return query.trim();
    }
}
