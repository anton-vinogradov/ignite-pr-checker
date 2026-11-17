package com.github.igniteprchecker.web;

import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Current default-branch evidence for a broken suite, separate from the PR's saved verdict and re-run policy. */
@RestController
public class MasterSuiteController {
    private final TcClient tc;

    public MasterSuiteController(TcClient tc) {
        this.tc = tc;
    }

    /** One metadata read, in TeamCity's newest-started order; no history is represented by build ID zero. */
    @GetMapping("/api/master-suite")
    public MasterSuite masterSuite(@RequestParam String suite,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        if (!suite.matches("[A-Za-z0-9_-]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid suite ID");

        return tc.latestMasterSuiteRun(token, suite).map(MasterSuiteController::of)
            .orElseGet(() -> new MasterSuite(0, 0, null, List.of()));
    }

    private static MasterSuite of(TcModel.Build build) {
        List<TcModel.ProblemOccurrence> problems = build.problemOccurrences() == null
            || build.problemOccurrences().problemOccurrence() == null ? List.of()
                : build.problemOccurrences().problemOccurrence();
        List<String> types = problems.stream().map(p -> p == null || p.type() == null || p.type().isBlank()
                ? "" : p.type().strip()).distinct().sorted().toList();

        return new MasterSuite(build.id(), TcDates.epochSeconds(build.finishDate()), build.status(), types);
    }

    /** Empty type marks an unknown TeamCity problem, so the page cannot discard it and claim an exact match. */
    public record MasterSuite(long buildId, long finishedAt, String status, List<String> problemTypes) {
    }
}
