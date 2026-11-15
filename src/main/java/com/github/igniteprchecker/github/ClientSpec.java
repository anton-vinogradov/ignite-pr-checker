package com.github.igniteprchecker.github;

public record ClientSpec(String key, String label, String repo, String suite, String igniteVcsRoot,
    String clientVcsRoot) {
    public String branch(int pr) {
        return "pull/" + pr + (key.equals("php") ? "/head" : "");
    }

    public String suiteLabel() {
        String standard = switch (key) {
            case "python" -> "IgniteTests24Java8_WiPThinClientPython";
            case "php" -> "IgniteTests24Java8_ThinClientPhp";
            case "nodejs" -> "IgniteTests24Java8_ThinClientNodeJs";
            default -> null;
        };
        return suite.equals(standard) ? "Thin client: " + label : suite;
    }
}
