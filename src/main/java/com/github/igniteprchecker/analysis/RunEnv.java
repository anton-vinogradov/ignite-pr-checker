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

    /** {@code /opt/java/jdk-open-21}, {@code /usr/lib/jvm/java-8-openjdk-amd64}, {@code /usr/lib/jvm/jdk1.8.0_202}. */
    private static final Pattern JDK_VERSION = Pattern.compile("(?i)(?:jdk|java)\\D{0,12}?(1\\.8|\\d+)");

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

    static String jdkOf(String javaHome) {
        Matcher m = JDK_VERSION.matcher(javaHome);
        if (!m.find())
            return javaHome;

        return "1.8".equals(m.group(1)) ? "8" : m.group(1);
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
