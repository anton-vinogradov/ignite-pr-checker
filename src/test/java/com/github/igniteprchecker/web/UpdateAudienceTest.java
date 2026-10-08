package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The tour said the top bar shows Update and its what's new link to everyone once a release is out. On an instance
 * with PRC_ADMINS, a user who may not operate it sees neither; the tour now says who does.
 */
class UpdateAudienceTest {
    @Test
    void aUserWhoMayNotOperateTheServiceSeesNoUpdate() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'bob', jira: false, github: false, admin: false } });
            page.route('/api/version', { body: { current: '1.21.0', latest: '1.21.1', updateAvailable: true,
                notesUrl: 'https://github.com/anton-vinogradov/ignite-pr-checker/releases/tag/v1.21.1' } });
            await page.load('');
            report({ button: !page.el('updateBtn').classList.contains('hidden'),
                notes: !page.el('updateNotes').classList.contains('hidden') });
            """);

        assertThat(out.get("button").asBoolean()).isFalse();
        assertThat(out.get("notes").asBoolean()).isFalse();
    }

    @Test
    void theToursSayWhoSeesUpdate() throws Exception {
        assertThat(collapsed("docs/features.md")).contains("when a newer release is out, those who may operate the "
            + "service see **Update to vX.Y.Z** in the top bar, and a **what's new** link to its notes.");
        assertThat(collapsed("docs/features.ru.md")).contains("когда выходит новый релиз, те, кому можно управлять "
            + "сервисом, видят в шапке **Update to vX.Y.Z** и ссылку **what's new** на его заметки.");
    }

    private static String collapsed(String file) throws Exception {
        return Files.readString(Path.of(file), UTF_8).replaceAll("\\s+", " ");
    }
}
