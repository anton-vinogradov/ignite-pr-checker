package com.github.igniteprchecker.persist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Takes the tokens out of a copy of the state: a JSON snapshot file, or a zip of them. Everything else in the copy
 * stays, so restoring it brings the state back, and each token taken is marked: the field {@code <name>Removed} is
 * true next to the emptied field {@code <name>}. A field that holds a token, encrypted or not, is one of
 * {@link #FIELDS}, in whatever file and however deep.
 */
public final class TokenScrub {
    /**
     * The fields of the snapshot files that hold a token: the standing options' three, and the one of a one-shot Auto
     * visa, which copies made by earlier versions keep in visa-subs.json.
     */
    public static final Set<String> FIELDS = Set.of("tcToken", "jiraToken", "ghToken", "token");

    /** Ends the name of the field that marks a token as taken out. */
    public static final String REMOVED = "Removed";

    /** Jackson 3's own defaults refuse a file with more after its JSON, which Jackson 2 read up to there. */
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private TokenScrub() {
    }

    /** Rewrites {@code copy}, a JSON file or a zip of them, without its tokens; false when it held none. */
    public static boolean removeFrom(Path copy, long now) throws IOException {
        return copy.getFileName().toString().endsWith(".zip") ? fromZip(copy, now) : fromFile(copy, now);
    }

    private static boolean fromFile(Path copy, long now) throws IOException {
        try (InputStream in = Files.newInputStream(copy)) {
            if (!holdsTokens(in))
                return false;
        }
        catch (StreamReadException e) {
            throw new NotJson(null, e);
        }

        Path tmp = copy.resolveSibling(copy.getFileName() + ".tmp");
        try (InputStream in = Files.newInputStream(copy); OutputStream out = Files.newOutputStream(tmp)) {
            out.write(withoutTokens(in, now));
        }
        replace(tmp, copy);

        return true;
    }

    private static boolean fromZip(Path zip, long now) throws IOException {
        Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
        try (ZipFile z = new ZipFile(zip.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(z.entries());
            Set<String> holding = new HashSet<>();
            for (ZipEntry e : entries) {
                try (InputStream in = z.getInputStream(e)) {
                    if (holdsTokens(in))
                        holding.add(e.getName());
                }
                catch (StreamReadException ex) {
                    throw new NotJson(e.getName(), ex);
                }
            }
            if (holding.isEmpty())
                return false;

            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                for (ZipEntry e : entries) {
                    ZipEntry copy = new ZipEntry(e.getName());
                    copy.setTime(e.getTime());
                    out.putNextEntry(copy);
                    try (InputStream in = z.getInputStream(e)) {
                        if (holding.contains(e.getName()))
                            out.write(withoutTokens(in, now));
                        else
                            in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
        }
        replace(tmp, zip);

        return true;
    }

    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads the JSON as a stream, so a large cache in a backup costs no memory to look through. It reads to the end: a
     * copy cut short after its first token is found here, not halfway through rewriting it. Text that is not JSON is a
     * {@link StreamReadException}; a read the disk failed is an {@link IOException}, and the copy is tried again.
     */
    static boolean holdsTokens(InputStream in) throws IOException {
        boolean holds = false;
        try (JsonParser p = JSON.createParser(in)) {
            for (JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                if (t == JsonToken.PROPERTY_NAME && FIELDS.contains(p.currentName())
                    && p.nextToken() == JsonToken.VALUE_STRING)
                    holds = true;
            }
        }
        catch (JacksonIOException e) {
            throw e.getCause();
        }

        return holds;
    }

    /**
     * Only the files that hold tokens get here, and those are the small ones that hold the state. Any failure is an
     * {@link IOException}: the copy is tried again.
     */
    static byte[] withoutTokens(InputStream in, long now) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(in);
        }
        catch (JacksonIOException e) {
            throw e.getCause();
        }
        catch (JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
        scrub(root, now);

        return JSON.writeValueAsBytes(root);
    }

    private static void scrub(JsonNode node, long now) {
        if (node instanceof ObjectNode o) {
            for (String field : FIELDS) {
                if (!o.path(field).isString())
                    continue;

                o.putNull(field);
                o.put(field + REMOVED, true);
                markRefused(o, field, now);
            }
        }

        node.forEach(child -> scrub(child, now));
    }

    /**
     * A build that does not know the marks takes a missing TeamCity token for one it cannot decrypt, and drops the
     * user's options at their next finished run; for a token TeamCity refused, it pauses them. So the copy also says
     * the token was refused, {@code tcToken} by {@code tcRejectedAt}, where the record has that field and no refusal
     * yet.
     */
    private static void markRefused(ObjectNode o, String field, long now) {
        if (!field.endsWith("Token"))
            return;

        String refused = field.substring(0, field.length() - "Token".length()) + "RejectedAt";
        if (o.has(refused) && o.get(refused).asLong(0) <= 0)
            o.put(refused, now);
    }

    /** A copy, or a file in a zip, that is not JSON: no field in it can be told from the text around it. */
    public static final class NotJson extends IOException {
        /** {@code entry}: the file of the zip that is not JSON; null for the copy itself. */
        NotJson(String entry, StreamReadException cause) {
            super((entry == null ? "it" : "its " + entry) + " is not JSON (" + cause.getOriginalMessage() + ")", cause);
        }
    }
}
