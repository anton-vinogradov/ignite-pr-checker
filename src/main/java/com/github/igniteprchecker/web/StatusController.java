package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.EffectiveConfig;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.metrics.HeapDumps;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.metrics.ProcessMemory;
import com.github.igniteprchecker.persist.CacheStore;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.suitefix.SuiteFixService;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-status snapshot (TeamCity/GitHub call metrics + JVM + cache internals) for the status page. Anyone may
 * read the counters, the health and the levels of its problems; the problems' texts and the settings name files and
 * errors on the server, so only signed-in viewers get those. Only the operators get the log messages, who restarted
 * or flushed last, and the operators' names in the settings. The messages tell whose tokens TeamCity or GitHub
 * refused, and an operator's login is what opens Restart, Update and the messages.
 */
@RestController
@RequestMapping("/api")
public class StatusController {
    private final Metrics metrics;
    private final AnalysisCache cache;
    private final Warmer warmer;
    private final GithubClient github;
    private final LogTracker logs;
    private final ServiceHealth health;
    private final com.github.igniteprchecker.tc.RerunTracker tracker;
    private final com.github.igniteprchecker.jira.VisaSubscriptions visaSubs;
    private final com.github.igniteprchecker.jira.StandingVisas standing;
    private final com.github.igniteprchecker.github.PrCommands commands;
    private final AuthInterceptor auth;
    private final AdminActions admin;
    private final CacheStore store;
    private final EffectiveConfig config;

    /** Adding PRs' tests to suites; null for a status page without it. */
    private final SuiteFixService suiteFix;

    private final String version;

    /** The commit the running jar was built from, null when the build did not know it. */
    private final String commit;

    /** Built from a tree with uncommitted changes: the commit alone does not tell the code. */
    private final boolean dirty;

    /** When Spring scheduled the background jobs: each job's first pass is due its initial delay later. */
    private volatile long jobsScheduledAt;

    /** A status page without adding PRs' tests to suites. */
    public StatusController(Metrics metrics, AnalysisCache cache, Warmer warmer, GithubClient github,
        LogTracker logs, ServiceHealth health, com.github.igniteprchecker.tc.RerunTracker tracker,
        com.github.igniteprchecker.jira.VisaSubscriptions visaSubs,
        com.github.igniteprchecker.jira.StandingVisas standing,
        com.github.igniteprchecker.github.PrCommands commands,
        AuthInterceptor auth, AdminActions admin, CacheStore store, EffectiveConfig config,
        ObjectProvider<BuildProperties> buildProps) {
        this(metrics, cache, warmer, github, logs, health, tracker, visaSubs, standing, commands, auth, admin, store,
            config, buildProps, null);
    }

    @Autowired
    public StatusController(Metrics metrics, AnalysisCache cache, Warmer warmer, GithubClient github,
        LogTracker logs, ServiceHealth health, com.github.igniteprchecker.tc.RerunTracker tracker,
        com.github.igniteprchecker.jira.VisaSubscriptions visaSubs,
        com.github.igniteprchecker.jira.StandingVisas standing,
        com.github.igniteprchecker.github.PrCommands commands,
        AuthInterceptor auth, AdminActions admin, CacheStore store, EffectiveConfig config,
        ObjectProvider<BuildProperties> buildProps, SuiteFixService suiteFix) {
        this.metrics = metrics;
        this.cache = cache;
        this.warmer = warmer;
        this.github = github;
        this.logs = logs;
        this.health = health;
        this.tracker = tracker;
        this.visaSubs = visaSubs;
        this.standing = standing;
        this.commands = commands;
        this.auth = auth;
        this.admin = admin;
        this.store = store;
        this.config = config;
        this.suiteFix = suiteFix;
        BuildProperties bp = buildProps.getIfAvailable();
        this.version = bp != null && bp.getVersion() != null ? bp.getVersion() : "dev";
        this.commit = bp != null ? bp.get("commit") : null;
        this.dirty = bp != null && Boolean.parseBoolean(bp.get("dirty"));
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        Optional<SessionCodec.Session> viewer = auth.signedIn(req);
        boolean operator = viewer.filter(v -> admin.mayAdminister(v.username())).isPresent();

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();

        ProcessMemory.Usage memory = ProcessMemory.read();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsedMb", heap.getUsed() / (1024 * 1024));
        jvm.put("heapMaxMb", heapMax / (1024 * 1024));
        jvm.put("heapPeakMb", memory.heapPeakMb());
        jvm.put("rssMb", memory.rssMb());
        jvm.put("rssPeakMb", memory.rssPeakMb());
        jvm.put("hostMemMb", memory.hostMb());
        jvm.put("heapDump", HeapDumps.ofThisJvm().newest());
        jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        jvm.put("cpus", Runtime.getRuntime().availableProcessors());

        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        jvm.put("loadAverage", round2(osBean.getSystemLoadAverage())); // 1-min load avg; -1 if unavailable
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
            jvm.put("processCpuPct", pct(sun.getProcessCpuLoad())); // this JVM's share of total CPU
            jvm.put("systemCpuPct", pct(sun.getCpuLoad()));         // whole-host CPU
        }

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("resultsCached", cache.resultCount());
        app.put("historyCached", cache.historyCount());
        app.put("openPrs", github.prCount());
        app.put("stars", github.starCount());
        app.put("githubRate", github.rateLimit());
        app.put("githubAccount", Optional.ofNullable(github.appAccount())
            .map(a -> viewer.isPresent() ? a : a.anonymous()).orElse(null));
        app.put("githubWrites", Optional.ofNullable(github.writeState())
            .map(w -> viewer.isPresent() ? w : w.anonymous()).orElse(null));
        app.put("pooledTokens", warmer.pooledTokens());
        app.put("lastWarmed", warmer.lastWarmed());
        app.put("warmerRunning", warmer.warming());
        app.put("lastCached", warmer.lastCached());
        app.put("lastFailed", warmer.lastFailed());
        app.put("lastWarmCycleAt", warmer.lastCycleAt());
        app.put("lastCycleMs", warmer.lastCycleMs());
        app.put("firstCycleMs", warmer.firstCycleMs());
        app.put("cyclesCompleted", warmer.cyclesCompleted());
        app.put("cycleStartedAt", warmer.cycleStartedAt());
        app.put("cycleTotal", warmer.cycleTotal());
        app.put("cycleDone", warmer.cycleDone());
        app.put("firstCycleAt", firstCycleAt());

        LogTracker.Snapshot logSnap = logs.snapshot();
        ServiceHealth.Report report = health.report(logSnap, System.currentTimeMillis());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("commit", commit);
        out.put("dirty", dirty);
        out.put("uptimeSeconds", metrics.uptimeSeconds());
        out.put("startedAt", ManagementFactory.getRuntimeMXBean().getStartTime());
        out.put("health", report.health());
        out.put("logHealth", report.logHealth());
        out.put("healthProblems", viewer.isPresent() ? report.problems() : report.levelsOnly());
        out.put("jvm", jvm);
        out.put("teamcity", metrics.teamcity());
        out.put("github", metrics.github());
        out.put("jira", metrics.jira());
        out.put("http", metrics.http());
        Map<String, Object> watcher = new LinkedHashMap<>(tracker.stats());
        watcher.put("firstRefreshAt", firstPassAt(com.github.igniteprchecker.tc.RerunTracker.FIRST_REFRESH_MS));
        watcher.put("autoVisaArmed", visaSubs.armedCount());
        watcher.put("autoVisaPosted", visaSubs.postedCount());
        watcher.put("autoVisaLastPostedAt", visaSubs.lastPostedAt());
        watcher.put("standingEnrolled", standing.enrolledCount());
        watcher.put("standingPosted", standing.postedCount());
        watcher.put("standingLastSweepAt", standing.lastSweepAt());
        watcher.put("standingLastSweepMs", standing.lastSweepMs());
        watcher.put("standingFirstSweepAt", firstPassAt(com.github.igniteprchecker.jira.StandingVisas.FIRST_SWEEP_MS));
        watcher.put("prCommands", commands.handledCount());
        watcher.put("prCommandsLastPollAt", commands.lastPollAt());
        watcher.put("prCommandsFirstPollAt", firstPassAt(com.github.igniteprchecker.github.PrCommands.FIRST_POLL_MS));
        if (suiteFix != null)
            watcher.put("suiteFix", suiteFixStats());
        out.put("watcher", watcher);
        out.put("app", app);
        out.put("persistence", viewer.isPresent() ? store.status() : store.summary());
        out.put("signedIn", viewer.isPresent());
        out.put("operator", operator);
        out.put("log", operator ? logSnap : logSnap.countsOnly());
        out.put("operatorsNamed", admin.operatorsNamed());
        if (operator)
            out.put("admin", Map.of("last", admin.lastUses()));
        viewer.ifPresent(v -> out.put("config", operator ? config.settings() : config.settingsWithoutOperatorNames()));

        return out;
    }

    /** Spring schedules the jobs once the context is refreshed, after the snapshots are loaded. */
    @EventListener(ContextRefreshedEvent.class)
    void jobsScheduled(ContextRefreshedEvent e) {
        if (jobsScheduledAt == 0)
            jobsScheduledAt = e.getTimestamp();
    }

    /** When a job's first pass is due, or 0 while the jobs are not scheduled yet. */
    private long firstPassAt(long initialDelayMs) {
        return jobsScheduledAt == 0 ? 0 : jobsScheduledAt + initialDelayMs;
    }

    /** When the warmer's first scheduled cycle is due: never with warming off. */
    private long firstCycleAt() {
        return warmer.warms() ? firstPassAt(TimeUnit.MINUTES.toMillis(Warmer.FIRST_CYCLE_MINUTES)) : 0;
    }

    /** The suite-fix sweep's figures, and when its first pass is due: never with automation off. */
    private Map<String, Object> suiteFixStats() {
        Map<String, Object> stats = new LinkedHashMap<>(suiteFix.stats());
        stats.put("firstSweepAt", suiteFix.sweeps() ? firstPassAt(SuiteFixService.FIRST_SWEEP_MS) : 0L);

        return stats;
    }

    /** A CPU-load fraction (0..1) as a whole-number percent, or -1 when the JVM can't measure it yet. */
    private static int pct(double load) {
        return load < 0 ? -1 : (int) Math.round(load * 100);
    }

    private static double round2(double v) {
        return v < 0 ? -1 : Math.round(v * 100) / 100.0;
    }
}
