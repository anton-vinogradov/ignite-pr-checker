package com.github.igniteprchecker.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports Off when Caddy does not serve the proxy route and its status endpoint. */
@RestController
public class GitProxyStatusController {
    @GetMapping("/api/git-proxy/status")
    public ProxyStatus status() {
        return new ProxyStatus(false, "none", List.of());
    }

    public record ProxyStatus(boolean enabled, String engine, List<String> repositories) { }
}
