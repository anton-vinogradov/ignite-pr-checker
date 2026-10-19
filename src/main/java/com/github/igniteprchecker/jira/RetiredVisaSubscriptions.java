package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.config.PersistProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes, at the start, the file in which earlier versions kept the armed one-shot Auto visas, {@value #FILE}, and
 * the copies of it beside it: each subscription in it holds its user's JIRA token, encrypted. Visas come only from the
 * standing option, so nothing reads the file, and a token left in it would stay on the disk for good.
 */
@Component
public class RetiredVisaSubscriptions {
    private static final Logger log = LoggerFactory.getLogger(RetiredVisaSubscriptions.class);

    /** The file of the one-shot Auto visas in the cache directory. */
    static final String FILE = "visa-subs.json";

    private final PersistProperties props;

    public RetiredVisaSubscriptions(PersistProperties props) {
        this.props = props;
    }

    /** A cache directory that cannot be read or written is reported; the service starts all the same. */
    @PostConstruct
    void removeOnStart() {
        Path dir = Path.of(props.dir());
        if (!props.enabled() || !Files.isDirectory(dir))
            return;

        List<Path> files;
        try (Stream<Path> listed = Files.list(dir)) {
            files = listed.filter(f -> retired(f.getFileName().toString())).toList();
        }
        catch (IOException | RuntimeException e) {
            log.warn("could not look for the one-shot Auto visas of earlier versions in {}: {}", dir, e.toString());

            return;
        }

        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
                log.info("removed {}: one-shot Auto visas of an earlier version, with the JIRA tokens they held",
                    f.getFileName());
            }
            catch (IOException e) {
                log.warn("could not remove {}, which holds JIRA tokens of one-shot Auto visas: {}", f, e.toString());
            }
        }
    }

    /** The file itself, and the copies kept of it: before a build ran, unreadable, half-written. */
    static boolean retired(String name) {
        return name.equals(FILE) || name.startsWith(FILE + ".");
    }
}
