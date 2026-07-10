package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.health.LogTracker;
import com.github.igniteprchecker.metrics.Metrics;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public service-status snapshot (TeamCity/GitHub call metrics + JVM + cache internals) for the status page. */
@RestController
@RequestMapping("/api")
public class StatusController {
    private final Metrics metrics;
    private final AnalysisCache cache;
    private final Warmer warmer;
    private final GithubClient github;
    private final LogTracker logs;
    private final com.github.igniteprchecker.tc.RerunTracker tracker;
    private final com.github.igniteprchecker.jira.VisaSubscriptions visaSubs;
    private final com.github.igniteprchecker.jira.StandingVisas standing;
    private final String version;

    public StatusController(Metrics metrics, AnalysisCache cache, Warmer warmer, GithubClient github,
        LogTracker logs, com.github.igniteprchecker.tc.RerunTracker tracker,
        com.github.igniteprchecker.jira.VisaSubscriptions visaSubs,
        com.github.igniteprchecker.jira.StandingVisas standing,
        ObjectProvider<BuildProperties> buildProps) {
        this.metrics = metrics;
        this.cache = cache;
        this.warmer = warmer;
        this.github = github;
        this.logs = logs;
        this.tracker = tracker;
        this.visaSubs = visaSubs;
        this.standing = standing;
        BuildProperties bp = buildProps.getIfAvailable();
        this.version = bp != null && bp.getVersion() != null ? bp.getVersion() : "dev";
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsedMb", heap.getUsed() / (1024 * 1024));
        jvm.put("heapMaxMb", heapMax / (1024 * 1024));
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

        LogTracker.Snapshot logSnap = logs.snapshot();
        String health = logSnap.errors() > 0 ? "error" : logSnap.warnings() > 0 ? "warn" : "ok";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("uptimeSeconds", metrics.uptimeSeconds());
        out.put("health", health);
        out.put("jvm", jvm);
        out.put("teamcity", metrics.teamcity());
        out.put("github", metrics.github());
        out.put("http", metrics.http());
        Map<String, Object> watcher = new LinkedHashMap<>(tracker.stats());
        watcher.put("autoVisaArmed", visaSubs.armedCount());
        watcher.put("autoVisaPosted", visaSubs.postedCount());
        watcher.put("autoVisaLastPostedAt", visaSubs.lastPostedAt());
        watcher.put("standingEnrolled", standing.enrolledCount());
        watcher.put("standingPosted", standing.postedCount());
        watcher.put("standingLastSweepAt", standing.lastSweepAt());
        watcher.put("standingLastSweepMs", standing.lastSweepMs());
        out.put("watcher", watcher);
        out.put("app", app);
        out.put("log", logSnap);

        return out;
    }

    /** A CPU-load fraction (0..1) as a whole-number percent, or -1 when the JVM can't measure it yet. */
    private static int pct(double load) {
        return load < 0 ? -1 : (int) Math.round(load * 100);
    }

    private static double round2(double v) {
        return v < 0 ? -1 : Math.round(v * 100) / 100.0;
    }
}
