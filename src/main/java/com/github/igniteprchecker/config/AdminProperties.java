package com.github.igniteprchecker.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who operates the instance: the TeamCity usernames ({@code PRC_ADMINS}, comma-separated) allowed to restart,
 * update and flush the service and to list its users. Empty means no one is singled out: any logged-in user
 * may, within cooldowns. Names compare case-insensitively.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(List<String> logins) {
    public AdminProperties {
        logins = logins == null ? List.of() : logins.stream()
            .filter(Objects::nonNull)
            .map(l -> l.trim().toLowerCase(Locale.ROOT))
            .filter(l -> !l.isEmpty())
            .distinct()
            .toList();
    }
}
