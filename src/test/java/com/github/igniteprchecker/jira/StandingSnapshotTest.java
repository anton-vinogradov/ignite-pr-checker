package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * standing-visas.json holds everyone's encrypted tokens, options and the ids of the living comments.
 * A release that reads it with a loss switches options off or posts a second visa into a ticket, so
 * the file written by v1.20.11 must come back whole, and the mid-run memo must not grow with chains
 * nobody will ever look at again (four on prod, two of them empty).
 */
class StandingSnapshotTest {
    private static final String FIXTURE = "/snapshots/standing-visas-v1.20.11.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, mock(GithubClient.class),
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), tracker, mock(Warmer.class),
        mock(PendingCommits.class));

    @Test
    void theSnapshotOfTheCurrentReleaseReadsBackWhole(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);

        standing.loadFrom(file);

        assertThat(standing.visaOn("avinogradov")).isTrue();
        assertThat(standing.styleFixOn("avinogradov")).isTrue();
        assertThat(standing.actor("avinogradov")).hasValueSatisfying(a -> {
            assertThat(a.tcToken()).isEqualTo("tc-avinogradov");
            assertThat(a.ghToken()).isEqualTo("gh-avinogradov");
            assertThat(a.tz()).isEqualTo("Europe/Moscow");
        });
        assertThat(standing.buildHandled("avinogradov", 13335, 9389046L)).isTrue();
        assertThat(standing.waveStatus(13335, 9389046L)).hasValueSatisfying(w -> assertThat(w.wave()).isEqualTo(1));
        assertThat(standing.rerunOn("nsamelchev")).isTrue();
        assertThat(standing.ghTokenRejected("nsamelchev")).isTrue();
        assertThat(standing.ghLoginOf("nsamelchev")).isEqualTo("NSAmelchev");
        assertThat(standing.visaOn("oldtimer")).as("written before the visa became optional").isTrue();

        Path saved = dir.resolve("saved.json");
        standing.saveTo(saved);

        assertKeeps(mapper.readTree(file.toFile()), mapper.readTree(saved.toFile()), "$");
    }

    @Test
    void aChainNobodyRerunsForLeavesNoMemo(@TempDir Path dir) throws Exception {
        standing.loadFrom(fixture(dir));
        when(tc.buildTriggeredBy(anyString(), eq(9391500L))).thenReturn(Optional.of("stranger"));
        when(tracker.tracks(9391500L)).thenReturn(true);

        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(13700, 9391500L, "IgniteTests24Java8_Cache1",
            9391510L, "Cache 1"));

        assertThat(savedEarlyReruns(dir)).containsExactly("9389046");
    }

    /** 9380000 was superseded long ago; 9391271 is still running; 9389046 is being settled. */
    @Test
    void theMemoOfAChainThatIsNeitherRunningNorSettlingIsDroppedOnSave(@TempDir Path dir) throws Exception {
        Path file = fixture(dir);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode early = (ObjectNode)snap.get("earlyReruns");
        early.putArray("9380000").add("IgniteTests24Java8_Queries1");
        early.putArray("9391271").add("IgniteTests24Java8_Cache2");
        early.putArray("9391300");
        mapper.writeValue(file.toFile(), snap);
        when(tracker.tracks(9391271L)).thenReturn(true);
        when(tracker.tracks(9391300L)).thenReturn(true);
        standing.loadFrom(file);

        assertThat(savedEarlyReruns(dir)).containsExactlyInAnyOrder("9389046", "9391271");
    }

    /**
     * Saving the options asks JIRA for the user's timezone; a GitHub login linked while that call was
     * out used to be overwritten with the copy read before it.
     */
    @Test
    void aSettingsChangeKeepsALoginLinkedWhileItWasBeingSaved(@TempDir Path dir) throws Exception {
        standing.loadFrom(fixture(dir));
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch linked = new CountDownLatch(1);
        when(jira.myself("jira-pat")).thenReturn(Optional.of("oldtimer"));
        when(jira.myTimezone("jira-pat")).thenAnswer(inv -> {
            asked.countDown();
            linked.await(10, TimeUnit.SECONDS);

            return Optional.of("Europe/Moscow");
        });

        Thread save = new Thread(() -> standing.change("oldtimer", "tc-oldtimer", "jira-pat", null,
            new StandingVisas.OptionChange(true, true, false, false)));
        save.start();
        assertThat(asked.await(10, TimeUnit.SECONDS)).isTrue();
        standing.setGhLogin("oldtimer", "old-timer");
        linked.countDown();
        save.join(10_000);

        assertThat(standing.ghLoginOf("oldtimer")).isEqualTo("old-timer");
        assertThat(standing.rerunOn("oldtimer")).isTrue();
    }

    private Path fixture(Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            Files.copy(in, file);
        }

        return file;
    }

    private List<String> savedEarlyReruns(Path dir) throws Exception {
        Path saved = dir.resolve("saved.json");
        standing.saveTo(saved);
        List<String> chains = new ArrayList<>();
        mapper.readTree(saved.toFile()).get("earlyReruns").fieldNames().forEachRemaining(chains::add);

        return chains;
    }

    /** Every value the old file had is in the new one; fields the new release adds are not compared. */
    private static void assertKeeps(JsonNode old, JsonNode now, String path) {
        if (old.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = old.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                assertThat(now.has(f.getKey())).as(path + "." + f.getKey()).isTrue();
                assertKeeps(f.getValue(), now.get(f.getKey()), path + "." + f.getKey());
            }
        }
        else if (old.isArray() && path.endsWith(".enrollments")) {
            assertThat(now.size()).as(path).isEqualTo(old.size());
            for (JsonNode o : old) {
                String user = o.get("username").asText();
                assertKeeps(o, byUsername(now, user), path + "[" + user + "]");
            }
        }
        else
            assertThat(now).as(path).isEqualTo(old);
    }

    private static JsonNode byUsername(JsonNode enrollments, String username) {
        for (JsonNode e : enrollments) {
            if (username.equals(e.get("username").asText()))
                return e;
        }

        throw new AssertionError("no enrollment of " + username);
    }
}
