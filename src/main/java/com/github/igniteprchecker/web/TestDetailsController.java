package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.FailureDetails;
import com.github.igniteprchecker.analysis.FailureOutput;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a single failed test's details (message/stack trace, cut to size) for the inline "why?" expander
 * and the "ai" prompts, with {@code kind}: "hang", "assertion", "environment", or "" when the message and
 * stack trace show none of them.
 */
@RestController
@RequestMapping("/api")
public class TestDetailsController {
    private final FailureDetails failures;

    public TestDetailsController(FailureDetails failures) {
        this.failures = failures;
    }

    @GetMapping("/test-details")
    public Map<String, String> details(@RequestParam String occ,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        String details = failures.of(token, occ);
        FailureOutput.Kind kind = FailureOutput.kind(details);

        return Map.of("details", details == null ? "" : details, "kind", kind == null ? "" : kind.wire());
    }
}
