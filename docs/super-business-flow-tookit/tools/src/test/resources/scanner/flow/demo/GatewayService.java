package demo;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;

// Service layer is intentional: endpoint detection must not use a Controller suffix.
@RequestMapping("/map/search/v1/textsearch")
public class GatewayService {
    @PostMapping("searchByText")
    public String route(boolean cache, String query) {
        if (query == null) return reject();
        if (cache) return cached(query);
        else audit();
        return search(query);
    }

    public String conditional(boolean preferA) {
        return preferA ? fromA() : fromB();
    }

    public boolean conditionalCall(boolean enabled) {
        return enabled && check();
    }

    public void deferred(boolean enabled) {
        if (enabled) {
            Runnable job = () -> audit();
            register(job);
        }
    }

    public void scoped() {
        class Local {
            Local() { localOnly(); }
            void localOnly() { audit(); }
        }
        Runnable unused = new Runnable() {
            { anonymousInitializer(); }
            public void run() { audit(); }
            void anonymousInitializer() { audit(); }
        };
        audit();
    }

    private String reject() { return "invalid"; }
    private String cached(String query) { return query; }
    private String search(String query) { return query; }
    private String fromA() { return "A"; }
    private String fromB() { return "B"; }
    private boolean check() { return true; }
    private void audit() {}
    private void register(Runnable job) {}
}
