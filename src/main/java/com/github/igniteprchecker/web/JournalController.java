package com.github.igniteprchecker.web;

import com.github.igniteprchecker.jira.SettingsJournal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journal in ⚙: what changed in the user's settings, from which browser, and the visas posted with their JIRA
 * token, the newest first, in the language the user reads the pages in.
 */
@RestController
@RequestMapping("/api")
public class JournalController {
    private final SettingsJournal journal;

    private final UserDirectory users;

    public JournalController(SettingsJournal journal, UserDirectory users) {
        this.journal = journal;
        this.users = users;
    }

    /**
     * The user's journal; {@code full}: it is at its limit for a sort of entries, so older entries of that sort are
     * gone or go next.
     */
    public record Journal(List<SettingsJournal.View> entries, boolean full) {
    }

    @GetMapping("/journal")
    public Journal journal(@RequestAttribute(AuthInterceptor.USER_ATTR) String user,
        @RequestAttribute(AuthInterceptor.LOGIN_ATTR) String login, HttpServletRequest req) {
        return new Journal(journal.views(user, login, AuthInterceptor.browser(req), users.language(user)),
            journal.full(user));
    }
}
