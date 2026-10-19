package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.i18n.Lang;
import com.github.igniteprchecker.i18n.Texts;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Each user's journal in ⚙: what changed in their settings and who changed it, and each visa the checker posted or
 * edited with their JIRA token. An entry says when, and which login made the change, by its id and the label of its
 * browser; an entry without a login is the checker's own, such as a token a service refused. Of each user's switches
 * of options, saved tokens and other entries the newest {@link #KEPT_PER_SORT} of each are kept: a switch costs one
 * request, and a hundred of them must not push the link of a GitHub account or a visa out. An entry names options,
 * services and GitHub accounts, never a token or a part of one. The file keeps what happened, not the words: ⚙ gets
 * the words when it asks, in the language of its reader. Saved within a second ({@code journal.json}), so a restart
 * loses no entry.
 */
@Component
public class SettingsJournal implements SnapshotCache {
    /** How many entries of each {@link Sort} a user keeps; the oldest of that sort go first. */
    public static final int KEPT_PER_SORT = 100;

    /** The names ⚙ gives the options, by the keys the settings use. */
    private static final Map<String, String> OPTIONS = Map.of(
        "visa", "Visa my runs in JIRA",
        "rerun", "Re-run failed suites of my runs",
        "gh", "Post my runs' verdicts in the PR",
        "style", "Fix checkstyle before my commanded runs",
        "commands", "PR commands",
        "suites", "Add my PR's tests that no suite runs");

    /** The names ⚙ gives the tokens, by service. */
    private static final Map<String, String> TOKENS = Map.of(
        Entry.TEAMCITY, "TeamCity token",
        Entry.JIRA, "JIRA token",
        Entry.GITHUB, "GitHub token for commits");

    private final ObjectMapper mapper;

    private final LongSupplier clock;

    /** Each user's entries, the oldest first. */
    private final Map<String, Deque<Entry>> byUser = new ConcurrentHashMap<>();

    @Autowired
    public SettingsJournal(ObjectMapper mapper) {
        this(mapper, System::currentTimeMillis);
    }

    SettingsJournal(ObjectMapper mapper, LongSupplier clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Adds the entry to the user's journal, made now. */
    public void add(String user, Entry entry) {
        Deque<Entry> entries = byUser.computeIfAbsent(user, u -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(entry.madeAt(clock.getAsLong()));
            trim(entries);
        }
    }

    /** Whether the user's journal is at its limit for a sort of entries: a new one of that sort pushes the oldest out. */
    public boolean full(String user) {
        Deque<Entry> entries = byUser.get(user);
        if (entries == null)
            return false;

        synchronized (entries) {
            Map<Sort, Integer> bySort = new EnumMap<>(Sort.class);
            entries.forEach(e -> bySort.merge(sortOf(e), 1, Integer::sum));

            return bySort.values().stream().anyMatch(n -> n >= KEPT_PER_SORT);
        }
    }

    /** Drops the oldest entries of each sort past {@link #KEPT_PER_SORT}; {@code entries} go the oldest first. */
    private static void trim(Deque<Entry> entries) {
        Map<Sort, Integer> seen = new EnumMap<>(Sort.class);
        for (Iterator<Entry> it = entries.descendingIterator(); it.hasNext(); ) {
            if (seen.merge(sortOf(it.next()), 1, Integer::sum) > KEPT_PER_SORT)
                it.remove();
        }
    }

    private static Sort sortOf(Entry e) {
        boolean switched = Entry.ON.equals(e.kind()) || Entry.OFF.equals(e.kind()) || Entry.LANGUAGE.equals(e.kind());
        if (switched && e.tokens() == null)
            return Sort.SWITCH;

        return switched || Entry.TOKEN.equals(e.kind()) ? Sort.TOKEN : Sort.OTHER;
    }

    /** The user's entries, the newest first. */
    public List<Entry> of(String user) {
        Deque<Entry> entries = byUser.get(user);
        if (entries == null)
            return List.of();

        synchronized (entries) {
            List<Entry> out = new ArrayList<>(entries);
            Collections.reverse(out);

            return out;
        }
    }

    /**
     * The user's entries as ⚙ shows them in English, the newest first, to the browser labelled {@code browser} in the
     * login {@code login}.
     */
    public List<View> views(String user, String login, String browser) {
        return views(user, login, browser, Lang.EN);
    }

    /**
     * The user's entries as ⚙ shows them in {@code lang}, the newest first, to the browser labelled {@code browser} in
     * the login {@code login}. A copy of a cookie is the same login: when the browser of an entry of this login names
     * itself otherwise, the entry says so.
     */
    public List<View> views(String user, String login, String browser, Lang lang) {
        return of(user).stream().map(e -> view(e, login, browser, lang)).toList();
    }

    private static View view(Entry e, String login, String browser, Lang lang) {
        boolean here = e.login() != null && e.login().equals(login);
        String from = e.browser() == null ? Texts.t(lang, "by the checker")
            : here && e.browser().equals(browser) ? Texts.t(lang, "from this browser")
            : here ? Texts.t(lang, "{0}, in the login of this browser", fromBrowser(lang, e.browser()))
            : fromBrowser(lang, e.browser());
        boolean linked = e.url() != null && e.issue() != null;

        return new View(e.at(), text(e, lang), from, linked ? e.issue() : null, linked ? e.url() : null);
    }

    /** What the entry says happened, in a sentence or two, in English. */
    static String text(Entry e) {
        return text(e, Lang.EN);
    }

    /** What the entry says happened, in a sentence or two, in {@code lang}. */
    static String text(Entry e, Lang lang) {
        return switch (e.kind()) {
            case Entry.ON -> e.tokens() == null ? Texts.t(lang, "Switched on {0}.", options(e, lang))
                : Texts.t(lang, "Switched on {0} with a new {1}.", options(e, lang), tokens(e, lang));
            case Entry.OFF -> Texts.t(lang, "Switched off {0}.", options(e, lang));
            case Entry.TOKEN -> e.tokens().contains(Entry.TEAMCITY)
                ? Texts.t(lang, "Saved a new TeamCity token for your options.")
                : Texts.t(lang, "Saved a new {0}.", tokens(e, lang));
            case Entry.LINKED -> e.tokens() != null
                ? Texts.t(lang, "Linked GitHub account @{0} with its GitHub token for commits.", e.account())
                : e.options() != null
                    ? Texts.t(lang, "Linked GitHub account @{0} and switched on {1}.", e.account(), options(e, lang))
                    : Texts.t(lang, "Linked GitHub account @{0}.", e.account());
            case Entry.UNLINKED -> joined((Entry.ALL_OFF.equals(e.why())
                    ? Texts.t(lang, "Unlinked GitHub account @{0}, as all your options are off.", e.account())
                    : Texts.t(lang, "Unlinked GitHub account @{0}.", e.account())), wentOff(e, lang),
                e.tokens() == null ? "" : Texts.t(lang, "The checker dropped your {0} too.", tokens(e, lang)));
            case Entry.MOVED -> joined(Texts.t(lang, "Another ci2 user linked GitHub account @{0}, so it is no longer"
                + " linked to you.", e.account()), wentOff(e, lang));
            case Entry.REFUSED -> e.tokens().contains(Entry.TEAMCITY)
                ? Texts.t(lang, "TeamCity refused the token your options use. They wait until you log in again.")
                : joined(e.tokens().contains(Entry.JIRA)
                    ? Texts.t(lang, "JIRA refused your {0}, so the checker dropped it.", tokens(e, lang))
                    : Texts.t(lang, "GitHub refused your {0}, so the checker dropped it.", tokens(e, lang)),
                    wentOff(e, lang));
            case Entry.NO_TOKEN -> Texts.t(lang, "The checker had no {0}, so {1} went off.", tokens(e, lang),
                options(e, lang));
            case Entry.UNREAD -> unread(e, lang);
            case Entry.VISA -> Texts.t(lang, "Posted a visa to {0} for PR #{1}, RunAll {2}.", e.issue(), e.pr(),
                e.build());
            case Entry.VISA_EDITED -> Texts.t(lang, "Edited the visa in {0} for PR #{1}, RunAll {2}.", e.issue(),
                e.pr(), e.build());
            case Entry.LANGUAGE -> Lang.of(e.lang()) == Lang.RU ? Texts.t(lang, "Switched the language to Russian.")
                : Texts.t(lang, "Switched the language to English.");
            default -> Texts.t(lang, "A change this version of the checker cannot describe.");
        };
    }

    private static String unread(Entry e, Lang lang) {
        boolean one = e.tokens().size() == 1;
        if (Entry.KEY_CHANGED.equals(e.why())) {
            return one ? Texts.t(lang, "The server key was changed, so the checker dropped your stored {0}: it could no"
                + " longer read it. Your options wait until you store a new one.", tokens(e, lang))
                : Texts.t(lang, "The server key was changed, so the checker dropped your stored {0}: it could no longer"
                    + " read them. Your options wait until you store new ones.", tokens(e, lang));
        }

        return one ? Texts.t(lang, "The checker dropped your stored {0}: it could not read it. Your options wait until"
            + " you store a new one.", tokens(e, lang))
            : Texts.t(lang, "The checker dropped your stored {0}: it could not read them. Your options wait until you"
                + " store new ones.", tokens(e, lang));
    }

    /** The sentences of an entry, one space between them; an empty one is left out. */
    private static String joined(String... sentences) {
        return String.join(" ", Arrays.stream(sentences).filter(s -> !s.isEmpty()).toList());
    }

    private static String wentOff(Entry e, Lang lang) {
        return e.options() == null ? "" : Texts.t(lang, "{0} went off.", capitalized(options(e, lang)));
    }

    /** The options of the entry by their names in ⚙; in Russian, each name in quotes, as a name in a sentence. */
    private static String options(Entry e, Lang lang) {
        return listed(e.options().stream().map(o -> OPTIONS.getOrDefault(o, o))
            .map(name -> lang == Lang.RU ? "«" + Texts.t(lang, name) + "»" : name).toList(), lang);
    }

    /** The tokens of the entry by their names in ⚙, as a sentence says them: in Russian from a small letter. */
    private static String tokens(Entry e, Lang lang) {
        return listed(e.tokens().stream().map(t -> Texts.t(lang, TOKENS.getOrDefault(t, t)))
            .map(name -> lang == Lang.RU ? name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1) : name)
            .toList(), lang);
    }

    /** "a", "a and b", "a, b and c". */
    private static String listed(List<String> names, Lang lang) {
        if (names.size() <= 1)
            return String.join("", names);

        return Texts.t(lang, "{0} and {1}", String.join(", ", names.subList(0, names.size() - 1)),
            names.get(names.size() - 1));
    }

    /** "from Firefox on Linux": where a change came from, by the label {@code BrowserLabel} gave its browser. */
    private static String fromBrowser(Lang lang, String label) {
        if (label.equals(By.UNKNOWN))
            return Texts.t(lang, "from unknown browser");
        if (label.startsWith("a browser on "))
            return Texts.t(lang, "from a browser on {0}", label.substring("a browser on ".length()));

        int on = label.indexOf(" on ");

        return on < 0 ? Texts.t(lang, "from {0}", label)
            : Texts.t(lang, "from {0} on {1}", label.substring(0, on), label.substring(on + 4));
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public String fileName() {
        return "journal.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Map<String, List<Entry>> users = new LinkedHashMap<>();
        byUser.forEach((user, entries) -> {
            synchronized (entries) {
                users.put(user, List.copyOf(entries));
            }
        });
        Snapshots.writeAtomic(mapper, file, new Snapshot(users));
    }

    /**
     * Reads the journals back. An entry added before the file was read, by another part of the state as it loaded,
     * stays, in its place by time.
     */
    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Snapshot s = mapper.readValue(file.toFile(), Snapshot.class);
        if (s.users() == null)
            return;

        s.users().forEach((user, loaded) -> {
            Deque<Entry> entries = byUser.computeIfAbsent(user, u -> new ArrayDeque<>());
            synchronized (entries) {
                List<Entry> all = new ArrayList<>(loaded);
                all.addAll(entries);
                all.sort(Comparator.comparingLong(Entry::at));
                entries.clear();
                all.forEach(entries::addLast);
                trim(entries);
            }
        });
    }

    /**
     * What an entry is, for the limit: an option switched on or off without a token, a token saved, with a switch or
     * on its own, or anything else: a link, a refusal, a visa.
     */
    private enum Sort {
        SWITCH, TOKEN, OTHER
    }

    /** The file: each user's entries, the oldest first. */
    private record Snapshot(Map<String, List<Entry>> users) {
    }

    /**
     * An entry as ⚙ shows it: when, what happened, and from which browser or by the checker. {@code linkText}, the
     * ticket of a visa, links to {@code url}, the visa's comment in JIRA; both null when the entry links nowhere.
     */
    public record View(long at, String text, String from, String linkText, String url) {
    }

    /**
     * Who made a change: the {@code login}, by its id, and the label of its browser. A change without one was the
     * checker's own.
     */
    public record By(String login, String browser) {
        /** The label of a login whose browser is not known. */
        static final String UNKNOWN = "unknown browser";

        /** A login whose request named no browser label. */
        public static By of(String login, String browser) {
            return new By(login, browser == null || browser.isBlank() ? UNKNOWN : browser);
        }
    }

    /**
     * One entry: when ({@code at}), what happened ({@code kind}), and who did it, the {@code login} and its {@code
     * browser}, both null for the checker. The options, the tokens by service, the GitHub {@code account}, the visa's
     * {@code pr}, {@code issue}, {@code build} and the {@code url} of its comment are there for the kinds that name
     * them. {@code why}: why the checker could not read a token, {@link #KEY_CHANGED} or {@link #DAMAGED}; {@link
     * #ALL_OFF} for an account unlinked with the last option. {@code lang}: the code of the language the user chose
     * for the pages.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(long at, String kind, String login, String browser, List<String> options, List<String> tokens,
        String why, String account, Integer pr, String issue, Long build, String url, String lang) {
        /** Options switched on, with the new token of the first one when it brought one. */
        static final String ON = "on";

        /** Options switched off. */
        static final String OFF = "off";

        /** A new token saved without a switch. */
        static final String TOKEN = "token";

        /** A GitHub account linked: by Link GitHub, or by its token for commits. */
        static final String LINKED = "linked";

        /** The GitHub account unlinked. */
        static final String UNLINKED = "unlinked";

        /** Another user linked the GitHub account the user had. */
        static final String MOVED = "moved";

        /** A service refused a stored token. */
        static final String REFUSED = "refused";

        /** Options switched off for want of a token. */
        static final String NO_TOKEN = "no-token";

        /** Stored tokens the checker could not read, dropped. */
        static final String UNREAD = "unread";

        /** A visa posted. */
        static final String VISA = "visa";

        /** A visa edited in place. */
        static final String VISA_EDITED = "visa-edited";

        /** The language of the pages chosen. */
        static final String LANGUAGE = "lang";

        public static final String TEAMCITY = "teamcity";

        public static final String JIRA = "jira";

        public static final String GITHUB = "github";

        /** The tokens were encrypted with another key: the server's key was changed since. */
        public static final String KEY_CHANGED = "key-changed";

        /** The tokens were encrypted with this key, and are damaged. */
        public static final String DAMAGED = "damaged";

        /** The GitHub account went with the last option the user switched off. */
        static final String ALL_OFF = "all-off";

        static Entry switchedOn(By by, String option, String token) {
            return new Entry(0, ON, by, List.of(option), token == null ? null : List.of(token), null, null);
        }

        static Entry switchedOff(By by, String option) {
            return new Entry(0, OFF, by, List.of(option), null, null, null);
        }

        static Entry token(By by, String token) {
            return new Entry(0, TOKEN, by, null, List.of(token), null, null);
        }

        static Entry linked(By by, String account, List<String> switchedOn, boolean byToken) {
            return new Entry(0, LINKED, by, orNull(switchedOn), byToken ? List.of(GITHUB) : null, null, account);
        }

        static Entry unlinked(By by, String account, List<String> off, boolean tokenDropped) {
            return new Entry(0, UNLINKED, by, orNull(off), tokenDropped ? List.of(GITHUB) : null, null, account);
        }

        static Entry unlinkedAllOff(By by, String account, boolean tokenDropped) {
            return new Entry(0, UNLINKED, by, null, tokenDropped ? List.of(GITHUB) : null, ALL_OFF, account);
        }

        static Entry moved(String account, List<String> off) {
            return new Entry(0, MOVED, null, orNull(off), null, null, account);
        }

        static Entry refused(String token, List<String> off) {
            return new Entry(0, REFUSED, null, orNull(off), List.of(token), null, null);
        }

        static Entry noToken(String token, List<String> off) {
            return new Entry(0, NO_TOKEN, null, off, List.of(token), null, null);
        }

        static Entry unread(List<String> tokens, String why) {
            return new Entry(0, UNREAD, null, null, tokens, why, null);
        }

        static Entry visa(boolean edited, int pr, String issue, long build, String url) {
            return new Entry(0, edited ? VISA_EDITED : VISA, null, null, null, null, null, null, pr, issue, build,
                url, null);
        }

        /** The user chose the language of the pages in ⚙. */
        public static Entry language(By by, Lang lang) {
            return new Entry(0, LANGUAGE, by.login(), by.browser(), null, null, null, null, null, null, null, null,
                lang.code());
        }

        private Entry(long at, String kind, By by, List<String> options, List<String> tokens, String why,
            String account) {
            this(at, kind, by == null ? null : by.login(), by == null ? null : by.browser(), options, tokens, why,
                account, null, null, null, null, null);
        }

        private static List<String> orNull(List<String> list) {
            return list == null || list.isEmpty() ? null : List.copyOf(list);
        }

        Entry madeAt(long time) {
            return new Entry(time, kind, login, browser, options, tokens, why, account, pr, issue, build, url,
                lang);
        }
    }
}
