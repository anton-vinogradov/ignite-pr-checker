package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.i18n.Lang;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Someone with a copy of alice's cookie could switch her options and link their own GitHub account, and nothing in ⚙
 * showed it. Her journal keeps her last 100 switches, 100 saved tokens and 100 other entries, each with when it
 * happened and from which browser, and survives a restart. The file and the answers carry no token.
 */
class SettingsJournalTest {
    private static final SettingsJournal.By LAPTOP = SettingsJournal.By.of("laptop", "Firefox on Linux");

    private static final SettingsJournal.By DESK = SettingsJournal.By.of("desk", "Chrome on Windows");

    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong now = new AtomicLong(1_760_800_000_000L);

    private final SettingsJournal journal = new SettingsJournal(mapper, now::get);

    @Test
    void theNewestHundredStayTheNewestFirst() {
        for (int i = 0; i < 105; i++) {
            now.incrementAndGet();
            journal.add("alice", SettingsJournal.Entry.switchedOn(LAPTOP, i % 2 == 0 ? "rerun" : "gh", null));
        }
        journal.add("bob", SettingsJournal.Entry.switchedOff(DESK, "commands"));

        List<SettingsJournal.Entry> alices = journal.of("alice");

        assertThat(alices).hasSize(SettingsJournal.KEPT_PER_SORT);
        assertThat(alices.get(0).at()).isEqualTo(now.get());
        assertThat(alices.get(99).at()).isEqualTo(now.get() - 99);
        assertThat(journal.of("bob")).singleElement().extracting(SettingsJournal.Entry::kind).isEqualTo("off");
    }

    /**
     * Someone with a copy of alice's cookie linked their own GitHub account to her, then switched an option on and off
     * 150 times: each switch is one request. The link stays in her journal, and so do her visa and her saved token.
     */
    @Test
    void aRunOfSwitchesPushesNoLinkOut() {
        journal.add("alice", SettingsJournal.Entry.visa(false, 13335, "IGNITE-28867", 9389046L, null));
        journal.add("alice", SettingsJournal.Entry.token(LAPTOP, "jira"));
        journal.add("alice", SettingsJournal.Entry.linked(DESK, "mallory-gh", List.of("commands"), false));
        for (int i = 0; i < 150; i++) {
            now.incrementAndGet();
            journal.add("alice", i % 2 == 0 ? SettingsJournal.Entry.switchedOn(DESK, "rerun", null)
                : SettingsJournal.Entry.switchedOff(DESK, "rerun"));
        }

        assertThat(journal.of("alice")).hasSize(SettingsJournal.KEPT_PER_SORT + 3)
            .extracting(SettingsJournal.Entry::kind).endsWith("linked", "token", "visa");
        assertThat(journal.full("alice")).isTrue();
    }

    /** A language switch is a switch too: 150 of them push out only older switches, not the link or the visa. */
    @Test
    void aRunOfLanguageSwitchesPushesNoLinkOut() {
        journal.add("alice", SettingsJournal.Entry.visa(false, 13335, "IGNITE-28867", 9389046L, null));
        journal.add("alice", SettingsJournal.Entry.linked(DESK, "mallory-gh", List.of("commands"), false));
        for (int i = 0; i < 150; i++) {
            now.incrementAndGet();
            journal.add("alice", SettingsJournal.Entry.language(DESK, i % 2 == 0 ? Lang.RU : Lang.EN));
        }

        assertThat(journal.of("alice")).hasSize(SettingsJournal.KEPT_PER_SORT + 2)
            .extracting(SettingsJournal.Entry::kind).endsWith("linked", "visa");
        assertThat(journal.full("alice")).isTrue();
    }

    /** A run of saved tokens pushes out only older saved tokens, not the link. */
    @Test
    void aRunOfSavedTokensPushesNoLinkOut() {
        journal.add("alice", SettingsJournal.Entry.linked(DESK, "mallory-gh", List.of("commands"), false));
        for (int i = 0; i < 150; i++) {
            now.incrementAndGet();
            journal.add("alice", i % 2 == 0 ? SettingsJournal.Entry.switchedOn(DESK, "visa", "jira")
                : SettingsJournal.Entry.token(DESK, "jira"));
        }

        assertThat(journal.of("alice")).hasSize(SettingsJournal.KEPT_PER_SORT + 1)
            .extracting(SettingsJournal.Entry::kind).last().isEqualTo("linked");
    }

    /** A journal short of the limit of each sort is not full: ⚙ does not say that older entries are gone. */
    @Test
    void aJournalShortOfEachLimitIsNotFull() {
        for (int i = 0; i < SettingsJournal.KEPT_PER_SORT - 1; i++) {
            journal.add("alice", SettingsJournal.Entry.switchedOn(DESK, "rerun", null));
            journal.add("alice", SettingsJournal.Entry.token(DESK, "jira"));
        }

        assertThat(journal.full("alice")).isFalse();
        assertThat(journal.full("bob")).isFalse();
    }

    /** alice reads her journal on the laptop: her change there is this browser's, the others name their browser. */
    @Test
    void eachEntrySaysWhereItCameFrom() {
        journal.add("alice", SettingsJournal.Entry.switchedOn(LAPTOP, "visa", "jira"));
        journal.add("alice", SettingsJournal.Entry.switchedOff(DESK, "rerun"));
        journal.add("alice", SettingsJournal.Entry.linked(SettingsJournal.By.of("laptop", "Chrome on Linux"),
            "mallory-gh", List.of("commands"), false));
        journal.add("alice", SettingsJournal.Entry.refused("jira", List.of("visa")));

        assertThat(journal.views("alice", "laptop", "Firefox on Linux"))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly(
                tuple("JIRA refused your JIRA token, so the checker dropped it. Visa my"
                    + " runs in JIRA went off.", "by the checker"),
                tuple("Linked GitHub account @mallory-gh and switched on PR commands.",
                    "from Chrome on Linux, in the login of this browser"),
                tuple("Switched off Re-run failed suites of my runs.",
                    "from Chrome on Windows"),
                tuple("Switched on Visa my runs in JIRA with a new JIRA token.",
                    "from this browser"));
    }

    @Test
    void eachKindSaysWhatHappened() {
        assertThat(texts(
            SettingsJournal.Entry.token(LAPTOP, "github"),
            SettingsJournal.Entry.token(LAPTOP, "teamcity"),
            SettingsJournal.Entry.linked(LAPTOP, "alice-gh", List.of(), true),
            SettingsJournal.Entry.linked(LAPTOP, "alice-gh", List.of(), false),
            SettingsJournal.Entry.unlinked(LAPTOP, "alice-gh", List.of("style", "commands", "suites"), true),
            SettingsJournal.Entry.moved("alice-gh", List.of("commands")),
            SettingsJournal.Entry.refused("teamcity", List.of()),
            SettingsJournal.Entry.refused("github", List.of("style", "suites")),
            SettingsJournal.Entry.noToken("jira", List.of("visa")),
            SettingsJournal.Entry.unread(List.of("jira", "github"), "key-changed"),
            SettingsJournal.Entry.visa(false, 13335, "IGNITE-28867", 9389046L, "https://issues.example/c1"),
            SettingsJournal.Entry.visa(true, 13335, "IGNITE-28867", 9389046L, "https://issues.example/c1")))
            .containsExactly(
                "Saved a new GitHub token for commits.",
                "Saved a new TeamCity token for your options.",
                "Linked GitHub account @alice-gh with its GitHub token for commits.",
                "Linked GitHub account @alice-gh.",
                "Unlinked GitHub account @alice-gh. Fix checkstyle before my commanded runs, PR commands and Add my"
                    + " PR's tests that no suite runs went off. The checker dropped your GitHub token for commits too.",
                "Another ci2 user linked GitHub account @alice-gh, so it is no longer linked to you. PR commands went"
                    + " off.",
                "TeamCity refused the token your options use. They wait until you log in again.",
                "GitHub refused your GitHub token for commits, so the checker dropped it. Fix checkstyle before my"
                    + " commanded runs and Add my PR's tests that no suite runs went off.",
                "The checker had no JIRA token, so Visa my runs in JIRA went off.",
                "The server key was changed, so the checker dropped your stored JIRA token and GitHub token for"
                    + " commits: it could no longer read them. Your options wait until you store new ones.",
                "Posted a visa to IGNITE-28867 for PR #13335, RunAll 9389046.",
                "Edited the visa in IGNITE-28867 for PR #13335, RunAll 9389046.");
    }

    @Test
    void aVisaLinksItsTicketToTheComment() {
        journal.add("alice", SettingsJournal.Entry.visa(false, 13335, "IGNITE-28867", 9389046L,
            "https://issues.example/browse/IGNITE-28867?focusedCommentId=1#comment-1"));
        journal.add("alice", SettingsJournal.Entry.visa(false, 13461, "IGNITE-28972", 9390001L, null));

        assertThat(journal.views("alice", "laptop", "Firefox on Linux"))
            .extracting(SettingsJournal.View::linkText, SettingsJournal.View::url)
            .containsExactly(tuple(null, null),
                tuple("IGNITE-28867",
                    "https://issues.example/browse/IGNITE-28867?focusedCommentId=1#comment-1"));
    }

    /**
     * The checker restarts. Another part of the state, read before the journal, has already added an entry: the
     * journal keeps it next to those it reads back, by time.
     */
    @Test
    void aRestartLosesNoEntry(@TempDir Path dir) throws Exception {
        journal.add("alice", SettingsJournal.Entry.switchedOn(LAPTOP, "rerun", null));
        now.addAndGet(60_000);
        journal.add("alice", SettingsJournal.Entry.linked(LAPTOP, "alice-gh", List.of("commands"), false));
        Path file = dir.resolve("journal.json");
        journal.saveTo(file);
        SettingsJournal restarted = new SettingsJournal(mapper, now::get);
        now.addAndGet(60_000);
        restarted.add("alice", SettingsJournal.Entry.unread(List.of("teamcity"), "key-changed"));

        restarted.loadFrom(file);

        assertThat(restarted.of("alice")).extracting(SettingsJournal.Entry::kind)
            .containsExactly("unread", "linked", "on");
    }

    /**
     * The checker restarts with a full run of switches on disk and one more added before the file was read: the oldest
     * switch goes, the link stays.
     */
    @Test
    void aRestartKeepsTheLimitOfEachSort(@TempDir Path dir) throws Exception {
        journal.add("alice", SettingsJournal.Entry.linked(DESK, "mallory-gh", List.of("commands"), false));
        for (int i = 0; i < SettingsJournal.KEPT_PER_SORT; i++) {
            now.incrementAndGet();
            journal.add("alice", SettingsJournal.Entry.switchedOn(DESK, "rerun", null));
        }
        Path file = dir.resolve("journal.json");
        journal.saveTo(file);
        SettingsJournal restarted = new SettingsJournal(mapper, now::get);
        now.incrementAndGet();
        restarted.add("alice", SettingsJournal.Entry.switchedOff(LAPTOP, "rerun"));

        restarted.loadFrom(file);

        assertThat(restarted.of("alice")).hasSize(SettingsJournal.KEPT_PER_SORT + 1)
            .extracting(SettingsJournal.Entry::kind).startsWith("off").endsWith("linked");
    }

    /** The file of this release, as it reads back: a renamed field would leave the journal of every user empty. */
    @Test
    void theJournalFileReadsBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.json");
        String saved = "{\"users\":{\"alice\":[{\"at\":1760800000000,\"kind\":\"on\",\"login\":\"laptop\","
            + "\"browser\":\"Firefox on Linux\",\"options\":[\"visa\"],\"tokens\":[\"jira\"]},{\"at\":1760800060000,"
            + "\"kind\":\"visa\",\"pr\":13335,\"issue\":\"IGNITE-28867\",\"build\":9389046,\"url\":"
            + "\"https://issues.example/c1\"},{\"at\":1760800120000,\"kind\":\"unread\",\"tokens\":[\"teamcity\"],"
            + "\"why\":\"key-changed\"}]}}";
        Files.writeString(file, saved);

        journal.loadFrom(file);
        journal.saveTo(dir.resolve("again.json"));

        assertThat(journal.of("alice")).hasSize(3);
        assertThat(mapper.readTree(dir.resolve("again.json").toFile())).isEqualTo(mapper.readTree(saved));
    }

    /** A kind a later version writes, read after a rollback: the entry stays, in words that claim nothing. */
    @Test
    void aKindOfALaterVersionSaysItCannotBeDescribed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.json");
        Files.writeString(file, "{\"users\":{\"alice\":[{\"at\":1760800000000,\"kind\":\"language\",\"lang\":"
            + "\"ru\"}]}}");

        journal.loadFrom(file);

        assertThat(journal.views("alice", "laptop", "Firefox on Linux")).singleElement()
            .extracting(SettingsJournal.View::text).isEqualTo("A change this version of the checker cannot describe.");
    }

    private static List<String> texts(SettingsJournal.Entry... entries) {
        return Arrays.stream(entries).map(SettingsJournal::text).toList();
    }
}
