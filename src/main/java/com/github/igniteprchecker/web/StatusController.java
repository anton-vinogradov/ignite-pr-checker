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
    private final String version;

    public StatusController(Metrics metrics, AnalysisCache cache, Warmer warmer, GithubClient github,
        LogTracker logs, ObjectProvider<BuildProperties> buildProps) {
        this.metrics = metrics;
        this.cache = cache;
        this.warmer = warmer;
        this.github = github;
        this.logs = logs;
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

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("resultsCached", cache.resultCount());
        app.put("historyCached", cache.historyCount());
        app.put("openPrs", github.prCount());
        app.put("stars", github.starCount());
        app.put("githubRate", github.rateLimit());
        app.put("pooledTokens", warmer.pooledTokens());
        app.put("lastWarmed", warmer.lastWarmed());

        LogTracker.Snapshot logSnap = logs.snapshot();
        String health = logSnap.errors() > 0 ? "error" : logSnap.warnings() > 0 ? "warn" : "ok";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("uptimeSeconds", metrics.uptimeSeconds());
        out.put("health", health);
        out.put("jvm", jvm);
        out.put("teamcity", metrics.teamcity());
        out.put("github", metrics.github());
        out.put("app", app);
        out.put("log", logSnap);

        return out;
    }
}
