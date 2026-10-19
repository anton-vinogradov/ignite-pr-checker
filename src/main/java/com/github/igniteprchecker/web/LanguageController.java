package com.github.igniteprchecker.web;

import com.github.igniteprchecker.i18n.Lang;
import com.github.igniteprchecker.jira.SettingsJournal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The language of the pages, which the user chooses in ⚙: the account's, so the same in every browser they log in from.
 * A change goes into their journal with the browser it came from, as any change in ⚙ does.
 */
@RestController
@RequestMapping("/api")
public class LanguageController {
    private final UserDirectory users;

    private final SettingsJournal journal;

    public LanguageController(UserDirectory users, SettingsJournal journal) {
        this.users = users;
        this.journal = journal;
    }

    /** The language chosen: "en" or "ru". */
    public record Choice(String lang) {
    }

    @PostMapping("/language")
    public ResponseEntity<?> choose(@RequestBody(required = false) Choice choice,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String user, HttpServletRequest req) {
        if (choice == null || choice.lang() == null || !Lang.of(choice.lang()).code().equals(choice.lang()))
            return ResponseEntity.badRequest().body(Map.of("error", "no such language: use en or ru"));

        Lang lang = Lang.of(choice.lang());
        if (users.language(user) != lang) {
            users.language(user, lang);
            journal.add(user, SettingsJournal.Entry.language(AuthInterceptor.by(req), lang));
        }

        return ResponseEntity.ok(Map.of("lang", lang.code()));
    }
}
