package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ⚙ listed six switches in a row, each with a paragraph that mixed what it does with the token it needs and how that
 * token is kept, and the token forms sat at the bottom, apart from their options. The options now stand in four groups
 * by what they need, each group opening with the state of that access and the button that changes it.
 */
class SettingsGroupsTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    private static final Pattern TOGGLE = Pattern.compile("id=\"(\\w+Toggle)\"");

    private static final Pattern OPTION =
        Pattern.compile("<label class=\"settings-row\">(.*?)</label>", Pattern.DOTALL);

    private static final String SETTINGS = """
        const SETTINGS = { visa: false, rerun: false, gh: false, style: false, commands: false, login: null,
            jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
            tcTokenRejected: false, suites: false, loginUnproven: false, githubAvailable: true,
            appLogin: 'ignite-pr-checker', githubLinkable: true, ghTokenNote: null };
        """;

    private static final String STATES = """
        await page.el('settingsBtn').onclick();
        await page.settle();
        report({ tc: page.el('tcState').textContent, jira: page.el('jiraState').textContent,
            jiraBtn: page.el('jiraTokenBtn').textContent, account: page.el('ghLoginState').textContent,
            token: page.el('ghTokenState').textContent, tokenBtn: page.el('ghTokenBtn').textContent,
            jiraGone: !page.el('jiraTokenGone').classList.contains('hidden'),
            jiraForm: !page.el('settingsJira').classList.contains('hidden'),
            ghGone: !page.el('ghTokenGone').classList.contains('hidden'),
            ghForm: !page.el('settingsGh').classList.contains('hidden'),
            tcGone: !page.el('tcTokenGone').classList.contains('hidden') });
        """;

    @Test
    void eachOptionStandsInTheGroupOfWhatItNeeds() throws IOException {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("groupTc", List.of("autoRerunToggle", "ghCommentToggle"));
        groups.put("groupJira", List.of("standingVisaToggle"));
        groups.put("settingsLogin", List.of("commandsToggle"));
        groups.put("groupGhToken", List.of("styleFixToggle", "suiteFixToggle"));
        Map<String, String> heads = Map.of("groupTc", "TeamCity login", "groupJira", "JIRA token", "settingsLogin",
            "GitHub account", "groupGhToken", "GitHub token for commits");

        String panel = between(read(), "<div id=\"settingsPanel\"", "<div id=\"cmdHint\"");
        int last = -1;
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            String group = group(g.getKey());
            assertThat(group).as(g.getKey()).startsWith("<section id=\"" + g.getKey() + "\"")
                .contains("<p class=\"settings-access\"><b>" + heads.get(g.getKey()) + "</b>:");
            assertThat(TOGGLE.matcher(group).results().map(m -> m.group(1)).toList()).as(g.getKey())
                .isEqualTo(g.getValue());
            assertThat(panel.indexOf("<section id=\"" + g.getKey() + "\"")).as("groups in order").isGreaterThan(last);
            last = panel.indexOf("<section id=\"" + g.getKey() + "\"");
        }
        assertThat(TOGGLE.matcher(panel).results().count()).as("no option outside a group").isEqualTo(6);
        assertThat(group("groupTc")).contains("id=\"tcTokenGone\"", "id=\"tcRelogin\"");
        assertThat(group("groupJira")).contains("id=\"jiraState\"", "id=\"jiraTokenBtn\"", "id=\"settingsJira\"",
            "id=\"jiraTokenGone\"");
        assertThat(group("settingsLogin")).contains("id=\"ghLoginState\"", "id=\"ghLink\"", "id=\"ghUnlink\"",
            "id=\"ghLoginUnproven\"", "id=\"ghLinkOff\"");
        assertThat(group("groupGhToken")).contains("id=\"ghTokenState\"", "id=\"ghTokenBtn\"", "id=\"settingsGh\"",
            "id=\"ghTokenGone\"", "id=\"ghTokenNote\"");
    }

    /** What each option does is one short sentence: how it does it is in the tour, behind How these work. */
    @Test
    void eachOptionIsOneShortSentence() throws IOException {
        Matcher option = OPTION.matcher(between(read(), "<div id=\"settingsPanel\"", "<div id=\"cmdHint\""));
        int options = 0;
        while (option.find()) {
            options++;
            String text = option.group(1).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
            String does = text.substring(text.indexOf(" — ") + 3);
            assertThat(does.split("\\. ")).as(text).hasSize(1);
            assertThat(does.split(" ")).as(text).hasSizeLessThanOrEqualTo(25);
        }
        assertThat(options).isEqualTo(6);
        assertThat(read()).contains(">How these work</a>");
    }

    @Test
    void nothingSavedNorLinkedIsSaid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            await page.load('?pr=13575');
            """ + STATES);

        assertThat(out.get("tc").asText()).isEqualTo("alice");
        assertThat(out.get("jira").asText()).isEqualTo("not saved");
        assertThat(out.get("jiraBtn").asText()).isEqualTo("Save");
        assertThat(out.get("account").asText()).isEqualTo("not linked");
        assertThat(out.get("token").asText()).isEqualTo("not saved");
        assertThat(out.get("tokenBtn").asText()).isEqualTo("Save");
        assertThat(out.get("jiraForm").asBoolean()).isFalse();
        assertThat(out.get("ghForm").asBoolean()).isFalse();
    }

    @Test
    void savedTokensAndALinkedAccountAreSaid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { visa: true, jiraStored: true,
                commands: true, login: 'alice-v', suites: true, ghStored: true,
                ghTokenNote: 'Your saved token is a classic one: it can push to every public repository.' }) }, 'GET');
            await page.load('?pr=13575');
            """ + STATES);

        assertThat(out.get("jira").asText()).isEqualTo("saved");
        assertThat(out.get("jiraBtn").asText()).isEqualTo("Replace");
        assertThat(out.get("account").asText()).isEqualTo("@alice-v, linked by your GitHub token for commits");
        assertThat(out.get("token").asText()).isEqualTo("saved, classic");
        assertThat(out.get("tokenBtn").asText()).isEqualTo("Replace");
    }

    /** Each refusal shows in its group with what to do, and the form to do it with is open. */
    @Test
    void refusedTokensAreSaidWhereTheyAreReplaced() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SETTINGS + """
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { rerun: true,
                jiraTokenRejected: true, ghTokenRejected: true, tcTokenRejected: true }) }, 'GET');
            await page.load('?pr=13575');
            """ + STATES);

        assertThat(out.get("jira").asText()).isEqualTo("rejected");
        assertThat(out.get("token").asText()).isEqualTo("rejected");
        assertThat(out.get("jiraGone").asBoolean()).isTrue();
        assertThat(out.get("jiraForm").asBoolean()).isTrue();
        assertThat(out.get("ghGone").asBoolean()).isTrue();
        assertThat(out.get("ghForm").asBoolean()).isTrue();
        assertThat(out.get("tcGone").asBoolean()).isTrue();
    }

    /** The section of ⚙ with this id, as the page's source has it. */
    static String group(String id) throws IOException {
        return between(read(), "<section id=\"" + id + "\"", "</section>");
    }

    private static String read() throws IOException {
        return Files.readString(PAGE, UTF_8);
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as(from).isNotNegative();

        return text.substring(start, text.indexOf(to, start));
    }
}
