package com.github.igniteprchecker.web;

/**
 * RunAll's replace got half way: the user's previous chains are cancelled, and queuing the new one failed
 * with the cause. The answer must say the previous chain is gone, or the page reads as if it still ran.
 */
class ReplacementNotQueuedException extends RuntimeException {
    private final int replaced;

    ReplacementNotQueuedException(int replaced, RuntimeException cause) {
        super(cause);
        this.replaced = replaced;
    }

    /** How many of the user's chains were cancelled. */
    int replaced() {
        return replaced;
    }
}
