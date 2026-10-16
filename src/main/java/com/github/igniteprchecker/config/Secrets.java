package com.github.igniteprchecker.config;

/**
 * How a secret shows in the text of an object that holds it: whether it is there, never its value. A record prints all
 * its fields, and Spring logs the body of a request it reads at DEBUG.
 */
public final class Secrets {
    private Secrets() {
    }

    /** "***" for a secret, "null" for none. */
    public static String masked(String secret) {
        return secret == null ? "null" : "***";
    }
}
