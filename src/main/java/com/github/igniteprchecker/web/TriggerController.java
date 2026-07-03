package com.github.igniteprchecker.web;

import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/** Queues the RunAll chain for a PR on TeamCity, using the logged-in user's token. */
@RestController
@RequestMapping("/api")
public class TriggerController {
    private final TcClient tc;

    public TriggerController(TcClient tc) {
        this.tc = tc;
    }

    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        try {
            TcModel.Build b = tc.triggerRunAllForPr(token, pr);

            return ResponseEntity.ok(Map.of(
                "buildId", b.id(),
                "state", b.state() == null ? "queued" : b.state(),
                "webUrl", b.webUrl() == null ? "" : b.webUrl()));
        }
        catch (RestClientResponseException e) {
            return ResponseEntity.status(502)
                .body(Map.of("error", "TeamCity rejected the trigger (" + e.getStatusCode() + ")"));
        }
    }
}
