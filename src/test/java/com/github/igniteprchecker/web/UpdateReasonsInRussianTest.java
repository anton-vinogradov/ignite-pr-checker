package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.Jackson2Defaults;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An operator who chose Russian read why an update failed in English: "Обновление до v1.43.0 не удалось: could not
 * download the jar of v1.43.0". A second operator had updated the service while the first one's page still offered the
 * update, and "Обновить" answered "no update available (current 1.43.0, latest 1.43.0)". The reasons come from
 * update.sh, which install.sh writes, and the refusal from UpdateService, so the check of the server's texts in web/
 * and jira/ did not see them. Each reads in Russian.
 */
class UpdateReasonsInRussianTest {
    /** Two or more English words in a row, a name joined by "-", "." or "/" one word. */
    private static final Pattern ENGLISH_PHRASE =
        Pattern.compile("(?<![\\w/.-])[A-Za-z][a-z'./-]*[a-z](?:,?\\s+[A-Za-z][a-z'./-]*[a-z])+(?![\\w/-])");

    /** The values of the reasons' shell variables: the release asked for, the one that runs, the two digests. */
    private static final Map<String, String> VALUES = Map.of("version", "1.43.0", "running", "1.42.0",
        "actual", "0a".repeat(32), "expected", "1b".repeat(32));

    /** UpdateService's refusal when the service runs the latest release already. */
    private static final String NO_UPDATE = "no update available (current 1.43.0, latest 1.43.0)";

    @Test
    void everyReasonAnUpdateFailsWithReadsInRussian() throws Exception {
        List<String> texts = new ArrayList<>(reasons());
        texts.add(NO_UPDATE);
        String catalog = Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8);
        JsonNode out = PageFunctions.run("common.js", "const CATALOG = " + catalog + ";\n"
            + "const TEXTS = " + Jackson2Defaults.mapper().writeValueAsString(texts) + ";\n" + """
            const sections = Object.entries(CATALOG);
            RU = new Map(sections.flatMap(([, entries]) => Object.entries(entries)));
            RU_SERVER = sections.filter(([name]) => name.startsWith('server')).flatMap(([, e]) => Object.entries(e));
            LANG = 'ru';
            out(TEXTS.map(t => tr(t)));
            """);

        List<String> left = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String ru = out.get(i).asText();
            if (ENGLISH_PHRASE.matcher(ru).find() || !ru.matches("(?s).*\\p{IsCyrillic}.*"))
                left.add(texts.get(i) + " → " + ru);
        }

        assertThat(texts).as("the reasons of install.sh and the refusal").hasSize(8);
        assertThat(left).isEmpty();
    }

    /** Reworded in UpdateService, the refusal must not stay green on the sample above. */
    @Test
    void theSampleRefusalIsUpdateServicesOwn() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/github/igniteprchecker/update/UpdateService.java"),
            UTF_8);

        assertThat(code).contains("\"no update available (current \" + currentVersion + \", latest \" + status.latest()"
            + " + \")\"");
    }

    /** The button of the update says in Russian why the last try failed, and why a new one was refused. */
    @Test
    void theUpdateButtonSaysWhyInRussian() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: true, jiraInCookie: false, lang: 'ru' } });
            page.route('/api/version', { body: { current: '1.42.0', latest: '1.43.0', updateAvailable: true,
                updateFailed: { version: '1.43.0', reason: 'could not download the jar of v1.43.0' } } });
            page.route('/api/update', { status: 400,
                body: { error: 'no update available (current 1.43.0, latest 1.43.0)' } }, 'POST');
            await page.load('?pr=13575');
            await page.settle();
            const title = page.el('updateBtn').title;
            page.el('updateBtn').onclick({ preventDefault() {} });
            await page.settle();
            report({ title, refused: page.el('updateBtn').textContent });
            """);

        assertThat(out.get("title").asText())
            .isEqualTo("Обновление до v1.43.0 не удалось: jar версии v1.43.0 не скачался");
        assertThat(out.get("refused").asText()).isEqualTo("обновлений нет (сейчас 1.43.0, последний выпуск 1.43.0)");
    }

    /** Each {@code fail "…"} of install.sh with its shell variables filled in. */
    private static List<String> reasons() throws Exception {
        Matcher fail = Pattern.compile("\\bfail \"([^\"]*)\"").matcher(Files.readString(Path.of("install.sh"), UTF_8));
        List<String> out = new ArrayList<>();
        while (fail.find()) {
            Matcher variable = Pattern.compile("\\$(\\w+)").matcher(fail.group(1));
            out.add(variable.replaceAll(m -> {
                assertThat(VALUES).as("a value for $" + m.group(1)).containsKey(m.group(1));
                return VALUES.get(m.group(1));
            }));
        }

        return out;
    }
}
