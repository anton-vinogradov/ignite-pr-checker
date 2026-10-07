package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.PrTests;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves how a PR's own new and changed test classes ran in its RunAll, using the logged-in user's TeamCity token. */
@RestController
@RequestMapping("/api")
public class PrTestsController {
    private final PrTestRuns runs;

    public PrTestsController(PrTestRuns runs) {
        this.runs = runs;
    }

    /** {@code running}: the chain {@code build} is still going, so the answer can still change. */
    @GetMapping("/pr-tests")
    public PrTests prTests(@RequestParam int pr, @RequestParam long build,
        @RequestParam(defaultValue = "false") boolean running,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return runs.of(token, pr, build, running);
    }
}
