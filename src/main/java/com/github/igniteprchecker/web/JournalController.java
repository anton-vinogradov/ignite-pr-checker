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
 * token, the newest first.
 */
@RestController
@RequestMapping("/api")
public class JournalController {
    private final SettingsJournal journal;

    public JournalController(SettingsJournal journal) {
        this.journal = journal;
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
        return new Journal(journal.views(user, login, AuthInterceptor.browser(req)), journal.full(user));
    }
}
