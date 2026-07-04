package com.github.igniteprchecker.web;

import com.github.igniteprchecker.tc.TcClient;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves a single failed test's details (message/stack trace) for the inline "why?" expander. */
@RestController
@RequestMapping("/api")
public class TestDetailsController {
    private final TcClient tc;

    public TestDetailsController(TcClient tc) {
        this.tc = tc;
    }

    @GetMapping("/test-details")
    public Map<String, String> details(@RequestParam String occ,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        String details = tc.testDetails(token, occ);

        return Map.of("details", details == null ? "" : details);
    }
}
