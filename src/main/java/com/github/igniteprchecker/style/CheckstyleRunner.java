package com.github.igniteprchecker.style;

import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

/**
 * Runs the target repo's OWN checkstyle configuration in-process on a handful of files — no clone,
 * no maven. The config and its suppressions are fetched from the repo's master (cached for an hour);
 * custom repo-specific checks whose classes we don't ship (org.apache.ignite.tools.*) are stripped:
 * CI still enforces them, we just can't see or fix them here.
 */
@Component
public class CheckstyleRunner {
    /** Custom in-repo check modules our classpath can't instantiate. */
    private static final Pattern CUSTOM_MODULE =
        Pattern.compile("<module name=\"org\\.apache\\.ignite[^\"]*\">.*?</module>|<module name=\"org\\.apache\\.ignite[^\"]*\"\\s*/>", Pattern.DOTALL);

    private final GithubClient github;
    private final GithubProperties props;

    private volatile String configXml;
    private volatile String suppressionsXml;
    private volatile long configTs;

    public CheckstyleRunner(GithubClient github, GithubProperties props) {
        this.github = github;
        this.props = props;
    }

    /**
     * Checks the given files (repo-relative path -> content); the returned violations carry those
     * same relative paths. Files are materialized under a temp mirror so suppression path patterns
     * keep matching.
     */
    public List<Violation> check(java.util.Map<String, String> files) throws Exception {
        loadConfig();

        Path root = Files.createTempDirectory("prc-style");
        try {
            List<File> targets = new ArrayList<>();
            for (var e : files.entrySet()) {
                Path f = root.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
                targets.add(f.toFile());
            }
            Path suppressions = root.resolve("checkstyle-suppressions.xml");
            Files.writeString(suppressions, suppressionsXml, StandardCharsets.UTF_8);

            Properties expansion = new Properties();
            expansion.setProperty("checkstyle.suppressions.file", suppressions.toString());
            Configuration config = ConfigurationLoader.loadConfiguration(
                new InputSource(new java.io.StringReader(configXml)),
                new PropertiesExpander(expansion),
                ConfigurationLoader.IgnoredModulesOptions.OMIT);

            List<Violation> out = new ArrayList<>();
            Checker checker = new Checker();
            try {
                checker.setModuleClassLoader(Checker.class.getClassLoader());
                checker.configure(config);
                checker.addListener(new AuditListener() {
                    @Override public void auditStarted(AuditEvent evt) {
                    }

                    @Override public void auditFinished(AuditEvent evt) {
                    }

                    @Override public void fileStarted(AuditEvent evt) {
                    }

                    @Override public void fileFinished(AuditEvent evt) {
                    }

                    @Override public void addError(AuditEvent evt) {
                        String rule = evt.getSourceName();
                        rule = rule.substring(rule.lastIndexOf('.') + 1);
                        if (rule.endsWith("Check"))
                            rule = rule.substring(0, rule.length() - 5);
                        out.add(new Violation(root.relativize(Path.of(evt.getFileName())).toString(),
                            evt.getLine(), evt.getColumn(), rule, evt.getMessage()));
                    }

                    @Override public void addException(AuditEvent evt, Throwable throwable) {
                        // a file checkstyle itself chokes on is left untouched
                    }
                });
                checker.process(targets);
            }
            finally {
                checker.destroy();
            }

            return out;
        }
        finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void loadConfig() {
        if (configXml != null && System.currentTimeMillis() - configTs < 3600_000)
            return;

        String cfg = github.rawFile(props.repo(), "master", "checkstyle/checkstyle.xml");
        String supp = github.rawFile(props.repo(), "master", "checkstyle/checkstyle-suppressions.xml");
        configXml = CUSTOM_MODULE.matcher(cfg).replaceAll("");
        suppressionsXml = supp;
        configTs = System.currentTimeMillis();
    }

    /** One checkstyle finding: where (repo-relative path, 1-based line/column), which rule, and why. */
    public record Violation(String path, int line, int col, String rule, String message) {
    }
}
