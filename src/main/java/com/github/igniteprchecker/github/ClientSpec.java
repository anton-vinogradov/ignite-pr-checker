package com.github.igniteprchecker.github;

public record ClientSpec(String key, String label, String repo, String suite, String igniteVcsRoot,
    String clientVcsRoot) {
    public static final String EXTENSIONS_SUITE = "IgniteExtensions_Tests_RunAllTests";
    public static final String EXTENSIONS_BUILD = "IgniteExtensions_Tests_Build";

    public boolean composite() {
        return key.equals("extensions");
    }

    /** The configuration that owns the Ignite root, which Extensions takes through its Build dependency. */
    public String igniteSuite() {
        return composite() ? EXTENSIONS_BUILD : suite;
    }

    public String branch(int pr) {
        return "pull/" + pr + (composite() ? "/" : key.equals("php") ? "/head" : "");
    }

    public String suiteLabel() {
        String standard = switch (key) {
            case "python" -> "IgniteTests24Java8_WiPThinClientPython";
            case "php" -> "IgniteTests24Java8_ThinClientPhp";
            case "nodejs" -> "IgniteTests24Java8_ThinClientNodeJs";
            case "extensions" -> EXTENSIONS_SUITE;
            default -> null;
        };
        return suite.equals(standard) ? composite() ? "Extensions: All Tests" : "Thin client: " + label : suite;
    }
}
