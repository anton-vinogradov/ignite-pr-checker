package com.github.igniteprchecker.update;

import com.github.igniteprchecker.config.UpdateProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads the deployment helper's public status without changing the installation. */
@Component
public class GithubDeployStatus {
    private static final Set<String> STATES = Set.of("idle", "downloading", "starting", "succeeded", "failed",
        "rolled_back");

    private static final Set<String> DETAILS = Set.of("download-failed", "artifact-invalid", "startup-failed",
        "health-check-failed", "rollback-failed", "interrupted", "service-unavailable");

    private final Path file;

    private final ObjectMapper mapper;

    public GithubDeployStatus(UpdateProperties props, ObjectMapper mapper) {
        file = Path.of(props.jarPath()).toAbsolutePath().resolveSibling("github-deploy.json");
        this.mapper = mapper;
    }

    /** Null means no deployment has been recorded; an unreadable record never exposes its error or local path. */
    public Map<String, Object> snapshot() {
        try {
            JsonNode data = mapper.readTree(Files.readString(file));
            String state = text(data, "state");
            if (!STATES.contains(state == null ? "" : state))
                throw new IllegalArgumentException("unknown deployment state");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", state);
            for (String key : List.of("runNumber", "runAttempt")) {
                JsonNode value = data.get(key);
                if (value != null && !value.isNull()) {
                    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
                        throw new IllegalArgumentException("invalid deployment run identifier");
                    out.put(key, value.longValue());
                }
            }
            for (String key : List.of("version", "lastSuccessVersion")) {
                String value = text(data, key);
                if (value != null) {
                    if (!value.matches("[0-9A-Za-z][0-9A-Za-z._+\\-]{0,199}"))
                        throw new IllegalArgumentException("invalid deployment version");
                    out.put(key, value);
                }
            }
            String commit = text(data, "commit");
            if (commit != null) {
                if (!commit.matches("[0-9a-f]{40}"))
                    throw new IllegalArgumentException("invalid deployment commit");
                out.put("commit", commit);
            }
            for (String key : List.of("startedAt", "finishedAt", "lastSuccessAt")) {
                String value = text(data, key);
                if (value != null)
                    out.put(key, Instant.parse(value).toString());
            }
            String detail = text(data, "detail");
            if (detail != null) {
                if (!DETAILS.contains(detail))
                    throw new IllegalArgumentException("unknown deployment detail");
                out.put("detail", detail);
            }
            return out;
        }
        catch (NoSuchFileException e) {
            return null;
        }
        catch (IOException | RuntimeException e) {
            return Map.of("state", "unavailable", "detail", "status-unreadable");
        }
    }

    private static String text(JsonNode data, String key) {
        JsonNode value = data.get(key);
        if (value == null || value.isNull())
            return null;
        if (!value.isTextual())
            throw new IllegalArgumentException("deployment field is not text");
        return value.asText().isBlank() ? null : value.asText();
    }
}
