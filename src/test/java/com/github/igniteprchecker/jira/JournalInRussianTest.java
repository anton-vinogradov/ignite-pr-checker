package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.i18n.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The journal is the one text of ⚙ the server writes in its reader's language: it puts each entry together from what
 * happened when ⚙ asks. A reader who chose Russian reads every kind of entry in Russian, the options by their Russian
 * names in quotes and the tokens from a small letter; the English journal reads as it did.
 */
class JournalInRussianTest {
    private static final Pattern ENGLISH_PHRASE =
        Pattern.compile("(?<![\\w/.@-])[A-Za-z][a-z'./-]*[a-z](?:,?\\s+[A-Za-z][a-z'./-]*[a-z])+(?![\\w/-])");

    private static final SettingsJournal.By LAPTOP = SettingsJournal.By.of("laptop", "Firefox on Linux");

    @Test
    void everyKindOfEntryReadsInRussian() {
        List<SettingsJournal.Entry> entries = List.of(
            SettingsJournal.Entry.switchedOn(LAPTOP, "visa", SettingsJournal.Entry.JIRA),
            SettingsJournal.Entry.switchedOn(LAPTOP, "rerun", null),
            SettingsJournal.Entry.switchedOff(LAPTOP, "commands"),
            SettingsJournal.Entry.token(LAPTOP, SettingsJournal.Entry.TEAMCITY),
            SettingsJournal.Entry.token(LAPTOP, SettingsJournal.Entry.GITHUB),
            SettingsJournal.Entry.linked(LAPTOP, "alice-gh", List.of("commands"), false),
            SettingsJournal.Entry.linked(LAPTOP, "alice-gh", List.of(), true),
            SettingsJournal.Entry.linked(LAPTOP, "alice-gh", List.of(), false),
            SettingsJournal.Entry.unlinked(LAPTOP, "alice-gh", List.of("commands", "style", "suites"), true),
            SettingsJournal.Entry.unlinkedAllOff(LAPTOP, "alice-gh", false),
            SettingsJournal.Entry.moved("alice-gh", List.of("commands")),
            SettingsJournal.Entry.refused(SettingsJournal.Entry.TEAMCITY, List.of()),
            SettingsJournal.Entry.refused(SettingsJournal.Entry.JIRA, List.of("visa")),
            SettingsJournal.Entry.refused(SettingsJournal.Entry.GITHUB, List.of("style", "suites")),
            SettingsJournal.Entry.noToken(SettingsJournal.Entry.JIRA, List.of("visa")),
            SettingsJournal.Entry.unread(List.of(SettingsJournal.Entry.JIRA), SettingsJournal.Entry.KEY_CHANGED),
            SettingsJournal.Entry.unread(List.of(SettingsJournal.Entry.TEAMCITY, SettingsJournal.Entry.GITHUB),
                SettingsJournal.Entry.DAMAGED),
            SettingsJournal.Entry.visa(false, 13575, "IGNITE-29049", 9001, "https://jira/c/1"),
            SettingsJournal.Entry.visa(true, 13575, "IGNITE-29049", 9001, "https://jira/c/1"),
            SettingsJournal.Entry.language(LAPTOP, Lang.EN));

        List<String> left = new ArrayList<>();
        for (SettingsJournal.Entry e : entries) {
            String ru = SettingsJournal.text(e, Lang.RU);
            if (ENGLISH_PHRASE.matcher(ru).find() || !ru.matches("(?s).*\\p{IsCyrillic}.*"))
                left.add(SettingsJournal.text(e) + " → " + ru);
        }

        assertThat(left).isEmpty();
        assertThat(SettingsJournal.text(entries.get(0), Lang.RU))
            .isEqualTo("Включено: «Ставить визу моим прогонам в JIRA». Сохранён новый токен JIRA.");
        assertThat(SettingsJournal.text(entries.get(8), Lang.RU)).isEqualTo("Аккаунт GitHub @alice-gh отвязан. "
            + "Выключено: «Команды в PR», «Чинить checkstyle перед моими прогонами по команде» и «Добавлять в сьюты тесты "
            + "моего PR, которые не запускает ни одна сьюта». Чекер удалил и твой токен GitHub для коммитов.");
        assertThat(SettingsJournal.text(entries.get(8))).isEqualTo("Unlinked GitHub account @alice-gh. PR commands, Fix "
            + "checkstyle before my commanded runs and Add my PR's tests that no suite runs went off. The checker dropped "
            + "your GitHub token for commits too.");
    }

    /** Where a change came from reads in Russian too, the browser's label with it. */
    @Test
    void theBrowserOfAChangeReadsInRussian() {
        SettingsJournal journal = new SettingsJournal(new ObjectMapper());
        journal.add("alice", SettingsJournal.Entry.switchedOn(LAPTOP, "rerun", null));
        journal.add("alice", SettingsJournal.Entry.switchedOn(SettingsJournal.By.of("desk", "a browser on Windows"),
            "rerun", null));
        journal.add("alice", SettingsJournal.Entry.refused(SettingsJournal.Entry.JIRA, List.of("visa")));
        journal.add("alice", SettingsJournal.Entry.switchedOn(SettingsJournal.By.of("laptop", null), "gh", null));

        assertThat(journal.views("alice", "laptop", "Firefox on Linux", Lang.RU)).extracting(SettingsJournal.View::from)
            .containsExactly("из неизвестного браузера, с тем же входом, что в этом браузере", "сделал чекер",
                "из браузера на Windows", "из этого браузера");
    }
}
