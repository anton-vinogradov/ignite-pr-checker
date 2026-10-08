package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.github.GithubClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ignite's own check that every test class is in a test suite, as GitHub Actions ran it on a commit: the step "Run
 * abandoned tests checks." of the job "Check java code on JDK 17". A class in no suite is never run by CI, and the
 * step fails naming it. PR 13335's SslRenewalTest and SslContextReloadTest had no runs in the RunAll the checker read,
 * and the page put that down to "an abstract base, or a class no suite runs"; SecurityTestSuite held them, and they got
 * these names in commits pushed after that run. The checker asks Ignite's check rather than guessing.
 *
 * <p>Two GitHub calls a commit, its checks and the job's steps, and the job's log when the step failed: the log names
 * the classes. A finished check of a commit does not change and is kept for 6 hours; one still going, or one GitHub
 * could not tell, for 2 minutes.
 */
public final class AbandonedTestsCheck {
    /** The job of Ignite's workflow "Code Style, Abandoned Tests, Javadocs", named with its JDK. */
    static final String JOB = "Check java code";

    /** The job's step that fails when a test class is in no suite. */
    static final String STEP = "Run abandoned tests checks";

    private static final long FINISHED_MS = 6 * 3600_000L;

    private static final long UNSETTLED_MS = 2 * 60_000L;

    private static final Logger log = LoggerFactory.getLogger(AbandonedTestsCheck.class);

    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");

    /** "2026-10-01T09:20:01.5000000Z ", which GitHub puts before every line of a job's log. */
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT[\\d:]{8}(?:\\.\\d+)?Z ");

    /** Maven's "[ERROR] " before every line of the failure it reports at the end. */
    private static final Pattern LEVEL = Pattern.compile("^\\[(?:ERROR|WARNING|INFO)] ?");

    private static final Pattern COLOR = Pattern.compile("\u001B\\[[;\\d]*m");

    private static final Pattern LIST = Pattern.compile("List of non-suited classes \\((\\d+) items\\):");

    private static final Pattern CLASS = Pattern.compile("\t([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*");

    private final GithubClient github;

    private final TtlCache<String, Outcome> finished;

    private final TtlCache<String, Outcome> unsettled;

    AbandonedTestsCheck(GithubClient github, LongSupplier nowMs) {
        this.github = github;
        this.finished = new TtlCache<>(FINISHED_MS, nowMs);
        this.unsettled = new TtlCache<>(UNSETTLED_MS, nowMs);
    }

    /** What Ignite's check says of commit {@code sha}. */
    public Outcome of(String sha) {
        Optional<Outcome> was = finished.peek(sha).or(() -> unsettled.peek(sha));
        if (was.isPresent())
            return was.get();

        Outcome now = lookUp(sha);
        (now.settled() ? finished : unsettled).put(sha, now);

        return now;
    }

    /** Sweeps out expired outcomes (see TtlCache.evictExpired). */
    void evictExpired() {
        finished.evictExpired();
        unsettled.evictExpired();
    }

    private Outcome lookUp(String sha) {
        if (!SHA.matcher(sha).matches())
            return Outcome.unknown(null, null, "the PR's head is not a commit id", true);

        Optional<GithubClient.CheckRun> job;
        try {
            job = github.checkRuns(sha).stream().filter(r -> r.name() != null && r.name().startsWith(JOB))
                .max(Comparator.comparingLong(GithubClient.CheckRun::id));
        }
        catch (RuntimeException e) {
            return Outcome.unknown(sha, null, "GitHub could not be asked", false);
        }
        if (job.isEmpty()) {
            return Outcome.unknown(sha, null, "GitHub shows no " + JOB + " job for it (Ignite's workflow does not run "
                + "on a PR that conflicts with master)", false);
        }

        String url = onGithub(job.get().htmlUrl());
        if ("queued".equals(job.get().status()))
            return new Outcome(State.RUNNING, sha, url, null, List.of(), false);

        GithubClient.Job steps;
        try {
            steps = github.job(job.get().id());
        }
        catch (RuntimeException e) {
            return Outcome.unknown(sha, url, "GitHub could not be asked", false);
        }
        boolean jobDone = "completed".equals(steps.status());
        Optional<GithubClient.Job.Step> step = steps.steps().stream()
            .filter(s -> s.name() != null && s.name().startsWith(STEP)).findFirst();
        if (step.isEmpty()) {
            return jobDone ? Outcome.unknown(sha, url, "its job has no step \"" + STEP + "\"", false)
                : new Outcome(State.RUNNING, sha, url, null, List.of(), false);
        }
        if (!"completed".equals(step.get().status()))
            return new Outcome(State.RUNNING, sha, url, null, List.of(), false);

        String conclusion = String.valueOf(step.get().conclusion());

        return switch (conclusion) {
            case "success" -> new Outcome(State.PASSED, sha, url, null, List.of(), true);
            case "skipped" -> skipped(sha, url, "an earlier step of its job failed");
            case "cancelled" -> skipped(sha, url, "its job was cancelled");
            case "failure" -> jobDone ? failed(sha, url, job.get().id())
                : new Outcome(State.RUNNING, sha, url, null, List.of(), false);
            default -> Outcome.unknown(sha, url, "its step ended as " + conclusion, false);
        };
    }

    private static Outcome skipped(String sha, String url, String reason) {
        return new Outcome(State.SKIPPED, sha, url, reason, List.of(), true);
    }

    /** The step failed: its log names the classes in no suite. */
    private Outcome failed(String sha, String url, long jobId) {
        Optional<Optional<List<String>>> read;
        try {
            read = github.jobLog(jobId, AbandonedTestsCheck::nonSuited);
        }
        catch (RuntimeException e) {
            log.warn("could not read the log of job {} of commit {}: {}", jobId, sha, e.toString());

            return Outcome.unknown(sha, url, "GitHub did not give its log", false);
        }
        if (read.isEmpty())
            return Outcome.unknown(sha, url, "reading its log takes the checker's GitHub token", true);
        if (read.get().isEmpty())
            return Outcome.unknown(sha, url, "its step failed, and its log names no class", true);

        return new Outcome(State.FAILED, sha, url, null, read.get().get(), true);
    }

    /**
     * The classes in no test suite, as the log of Ignite's job names them: after "List of non-suited classes (N
     * items):", one per line, each after a tab. The exec plugin prints the list as a warning, and Maven again in the
     * error it ends with; a list cut short is passed over for the next one. Empty when no whole list is found.
     */
    static Optional<List<String>> nonSuited(Stream<String> log) {
        Iterator<String> lines = log.iterator();
        while (lines.hasNext()) {
            Matcher list = LIST.matcher(content(lines.next()));
            if (!list.matches())
                continue;

            int count = Integer.parseInt(list.group(1));
            List<String> classes = new ArrayList<>();
            while (classes.size() < count && lines.hasNext()) {
                Matcher cls = CLASS.matcher(content(lines.next()));
                if (!cls.matches())
                    break;

                classes.add(cls.group(1));
            }
            if (count > 0 && classes.size() == count)
                return Optional.of(List.copyOf(classes));
        }

        return Optional.empty();
    }

    /** A log line without GitHub's timestamp, Maven's level and colors. */
    private static String content(String line) {
        String plain = COLOR.matcher(line).replaceAll("");

        return LEVEL.matcher(TIMESTAMP.matcher(plain).replaceFirst("")).replaceFirst("");
    }

    /** The job's page, when GitHub gave one on github.com. */
    private static String onGithub(String url) {
        return url != null && url.startsWith("https://github.com/") ? url : null;
    }

    /**
     * What the check says of commit {@code sha}: FAILED with the {@code classes} in no suite, or why it says nothing
     * ({@code reason}). {@code url} is the job on GitHub, null when not known. {@code settled}: asking again would
     * get the same.
     */
    public record Outcome(State state, String sha, String url, String reason, List<String> classes,
        boolean settled) {
        static Outcome unknown(String sha, String url, String reason, boolean settled) {
            return new Outcome(State.UNKNOWN, sha, url, reason, List.of(), settled);
        }

        /** Whether the check tells of every class: it passed, or it failed naming the ones in no suite. */
        boolean decided() {
            return state == State.PASSED || state == State.FAILED;
        }
    }
}
