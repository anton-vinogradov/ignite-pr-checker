package com.github.igniteprchecker.web;

/**
 * RunAll's replace got half way: the user's previous chains are cancelled, and queuing the new one failed
 * with the cause. The answer must say the previous chain is gone, or the page reads as if it still ran. {@code
 * cancelled} says so of another run than RunAll, null for RunAll.
 */
class ReplacementNotQueuedException extends RuntimeException {
    private final int replaced;

    private final String cancelled;

    ReplacementNotQueuedException(int replaced, RuntimeException cause) {
        this(replaced, cause, null);
    }

    ReplacementNotQueuedException(int replaced, RuntimeException cause, String cancelled) {
        super(cause);
        this.replaced = replaced;
        this.cancelled = cancelled;
    }

    /** What the answer opens with: which runs were cancelled; null for RunAll's chains. */
    String cancelled() {
        return cancelled;
    }

    /** How many of the user's chains were cancelled. */
    int replaced() {
        return replaced;
    }
}
