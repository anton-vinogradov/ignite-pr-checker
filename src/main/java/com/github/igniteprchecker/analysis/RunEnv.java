package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conditions a test run was made under that change how an Ignite test behaves: the JDK (from the
 * build's {@code env.JAVA_HOME}) and {@code TEST_SCALE_FACTOR}, which shrinks test workloads. Some
 * nightly master RunAlls run on JDK 21 and all at scale 1.0, while PR chains run on JDK 17 at 0.1. A
 * condition TeamCity did not report is null, and an unknown condition matches any.
 *
 * @param jdk   the JDK's major version ("17"), or the raw {@code JAVA_HOME} when it names none.
 * @param scale the test scale factor as TeamCity holds it ("0.1").
 */
record RunEnv(String jdk, String scale) {
    static final RunEnv UNKNOWN = new RunEnv(null, null);

    /** A path element naming a JDK: {@code jdk-open-21}, {@code temurin-21-jdk-amd64}, {@code jdk1.8.0_202}. */
    private static final Pattern JDK_DIR = Pattern.compile("(?i)[^/\\\\]*(?:jdk|java)[^/\\\\]*");

    private static final Pattern VERSION = Pattern.compile("1\\.8|\\d+");

    /** The conditions of the build a run was made in. */
    static RunEnv of(TcModel.BuildRef build) {
        if (build == null || build.resultingProperties() == null || build.resultingProperties().property() == null)
            return UNKNOWN;

        String jdk = null;
        String scale = null;
        for (TcModel.Property p : build.resultingProperties().property()) {
            if (p.value() == null || p.value().isBlank())
                continue;
            if (TcModel.JAVA_HOME.equals(p.name()))
                jdk = jdkOf(p.value().strip());
            else if (TcModel.TEST_SCALE_FACTOR.equals(p.name()))
                scale = p.value().strip();
        }

        return new RunEnv(jdk, scale);
    }

    /**
     * The first number in the path element that names a JDK. Not the first number after "jdk": in
     * {@code temurin-21-jdk-amd64} that is the architecture, and JDK 17 and 21 would both read as 64.
     */
    static String jdkOf(String javaHome) {
        Matcher dir = JDK_DIR.matcher(javaHome);
        while (dir.find()) {
            Matcher v = VERSION.matcher(dir.group());
            if (v.find())
                return "1.8".equals(v.group()) ? "8" : v.group();
        }

        return javaHome;
    }

    boolean sameJdk(RunEnv other) {
        return jdk == null || other.jdk == null || jdk.equals(other.jdk);
    }

    boolean sameAs(RunEnv other) {
        return sameJdk(other) && (scale == null || other.scale == null || scale.equals(other.scale));
    }

    /** Whether the scale factor of both is known and differs. */
    boolean otherScaleThan(RunEnv other) {
        return scale != null && other.scale != null && !scale.equals(other.scale);
    }

    /** "JDK 17", or null when the JDK is unknown. */
    String jdkLabel() {
        return jdk == null ? null : "JDK " + jdk;
    }
}
