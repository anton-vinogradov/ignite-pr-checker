package com.github.igniteprchecker.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** What may be shown or kept of a secret. */
public final class Secrets {
    private Secrets() {
    }

    /**
     * How a secret shows in the text of an object that holds it: "***" for a secret, "null" for none. A record prints
     * all its fields, and Spring logs the body of a request it reads at DEBUG.
     */
    public static String masked(String secret) {
        return secret == null ? "null" : "***";
    }

    /** The SHA-256 of a token, in hex: enough to know the token again, of no use to act with. */
    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
