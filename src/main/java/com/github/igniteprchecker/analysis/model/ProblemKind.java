package com.github.igniteprchecker.analysis.model;

import java.util.List;

/**
 * What ended a suite run, by TeamCity's type of the problem: {@code said} is how a broken suite's problems name it,
 * {@code letter} how a master run of the suite is kept. A run with several problems is of the first of these kinds
 * it has: a run that timed out or failed to compile exits with an error code as well.
 */
public enum ProblemKind {
    COMPILATION('C', "compilation error", "TC_COMPILATION_ERROR"),
    TIMEOUT('T', "execution timeout", "TC_EXECUTION_TIMEOUT"),
    CRASH('O', "JVM crash / out of memory", "TC_OOME", "TC_JVM_CRASH"),
    EXIT_CODE('X', "non-zero exit code", "TC_EXIT_CODE"),
    DEPENDENCY('D', "failed dependency", "SNAPSHOT_DEPENDENCY_ERROR", "SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS_TYPE"),
    /** A problem TeamCity types another way, or a run it named no problem of. */
    OTHER('U', "another problem");

    private final char letter;

    private final String said;

    private final List<String> types;

    ProblemKind(char letter, String said, String... types) {
        this.letter = letter;
        this.said = said;
        this.types = List.of(types);
    }

    public char letter() {
        return letter;
    }

    public String said() {
        return said;
    }

    public static ProblemKind of(String type) {
        for (ProblemKind kind : values()) {
            if (kind.types.contains(type))
                return kind;
        }

        return OTHER;
    }
}
