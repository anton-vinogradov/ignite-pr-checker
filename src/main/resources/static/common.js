// Helpers of all three pages, loaded before each page's own script. Nothing here runs on load.

const $ = id => document.getElementById(id);

// Quotes too: the result also lands inside attribute values (title="…", data-*="…").
function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// The page's language: English, unless the signed-in user chose Russian in ⚙. RU is then the catalog /ru.json: the
// Russian of each English string, of the page's own and of the texts the server sends in English. RU_SERVER holds
// the catalog's templates of the server's texts, which tr matches.
let LANG = 'en';
let RU = null;
let RU_SERVER = [];
let languageSettled;
// Settles once the page knows its language: what the page draws before that would stay English.
const languageReady = new Promise(done => { languageSettled = done; });
let languageKnown = false;
// The catalog once asked for; see prefetchLanguage.
let ruCatalog = null;

// A string of the page in the page's language. As a tag, T`Queued ${n} suites`, the English is the template as it
// stands, and the catalog's key names the values by their place: "Queued {0} suites". A key too long for a line is
// written out with its values after it: T('Queued {0} suites', n).
function T(strings, ...values) {
    const tagged = Array.isArray(strings);
    const key = tagged ? strings.reduce((out, s, i) => out + '{' + (i - 1) + '}' + s) : strings;
    const ru = RU ? RU.get(key) : undefined;
    const text = ru === undefined ? key : ru;
    return tagged || values.length ? fillIn(text, values) : text;
}

// A string of a table the page builds as it loads, before it knows its language: T() puts it into the language where
// it is shown. The catalog has it as it stands here.
function phrase(english) {
    return english;
}

// A catalog string with its values put in: {0} as it is, {0:tr} in the page's language, and {0|тест|теста|тестов} as
// the form of the word that goes with the number.
function fillIn(text, values) {
    return text.replace(/\{(\w+)(:tr|\|([^{}]*))?\}/g, (whole, id, how, forms) => {
        if (!(id in values)) return whole;
        if (forms !== undefined) return ruForm(Number(values[id]), ...forms.split('|'));
        return how === ':tr' ? tr(values[id]) : String(values[id]);
    });
}

// The form of a Russian word that goes with the number n: 1 тест, 2 теста, 5 тестов, 21 тест, 1,5 теста.
function ruForm(n, one, few, many) {
    if (!Number.isInteger(n)) return Number.isFinite(n) ? few : many;
    const tens = Math.abs(n) % 100, ones = tens % 10;
    if (tens >= 11 && tens <= 14) return many;
    return ones === 1 ? one : ones >= 2 && ones <= 4 ? few : many;
}

// A text the server sent in English, in the page's language: the catalog has it word for word, or as one of the
// server's templates, whose {…} parts stand for its numbers and names. A text made of several, as the server joins
// sentences, a reason's parts or a list, goes part by part. What the catalog does not know stays English.
const trMemo = new Map();
let ruPatternList = null;

function tr(text) {
    if (!RU || text == null || text === '') return text;
    const s = String(text);
    if (trMemo.has(s)) return trMemo.get(s);
    const out = trWhole(s) ?? trParts(s);
    if (trMemo.size > 5000) trMemo.clear();
    trMemo.set(s, out);
    return out;
}

// The text's Russian when the catalog has the whole of it; null when not. A name a template puts in as it is never
// holds a part of a reason: "on {2}" must not take "JDK 17; failed all 3 runs" for the name of a JDK.
function trWhole(s) {
    const exact = RU.get(s);
    if (exact !== undefined) return fillIn(exact, {});
    for (const p of ruPatterns()) {
        const m = p.re.exec(s);
        if (!m) continue;
        const values = {};
        p.ids.forEach((id, i) => { if (!(id in values)) values[id] = m[i + 1]; });
        if (p.asIs.some(id => REASON_JOINS.some(sepAt => splitOutside(values[id], sepAt).length > 1))) continue;
        return fillIn(p.ru, values);
    }
    return null;
}

// The text split at its sentences, else at " · ", "; " or ", " outside brackets, each part in the page's language.
function trParts(s) {
    for (const sepAt of PART_SEPARATORS) {
        const parts = splitOutside(s, sepAt);
        if (parts.length > 1) return parts.map(p => 'sep' in p ? p.sep : tr(p.text)).join('');
    }
    return s;
}

// Where a text's parts meet, by how long the separator at i is: the space after a sentence's end, then the signs the
// server joins the parts of a reason and the items of a list with.
const sentenceEnd = (s, i) => s[i] === ' ' && /[.!?]/.test(s[i - 1] || '') && /[A-Z⚠]/.test(s[i + 1] || '') ? 1 : 0;
const sign = text => (s, i) => s.startsWith(text, i) ? text.length : 0;
const REASON_JOINS = [sign(' · '), sign('; ')];
const PART_SEPARATORS = [sentenceEnd, ...REASON_JOINS, sign(', ')];

// The text's parts, {text}, and the separators between them, {sep}; a separator inside brackets does not split.
function splitOutside(s, sepAt) {
    const out = [];
    let depth = 0, from = 0;
    for (let i = 0; i < s.length; i++) {
        if (s[i] === '(' || s[i] === '[') depth++;
        else if ((s[i] === ')' || s[i] === ']') && depth > 0) depth--;
        const len = depth === 0 ? sepAt(s, i) : 0;
        if (!len) continue;
        out.push({ text: s.slice(from, i) }, { sep: s.slice(i, i + len) });
        from = i + len;
        i = from - 1;
    }
    out.push({ text: s.slice(from) });
    return out;
}

// The server's templates as patterns, the one with the most fixed words first. {0:n} stands for a number only.
function ruPatterns() {
    if (ruPatternList) return ruPatternList;
    ruPatternList = [];
    for (const [key, ru] of RU_SERVER) {
        const parts = key.split(/\{(\w+(?::n)?)\}/);
        if (parts.length < 3) continue;
        const fixed = parts.filter((_, i) => i % 2 === 0).join('');
        const source = parts.map((p, i) => i % 2 === 0 ? p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
            : p.endsWith(':n') ? '(\\d+)' : '([\\s\\S]*?)').join('');
        const ids = parts.filter((_, i) => i % 2).map(p => p.replace(/:n$/, ''));
        ruPatternList.push({ re: new RegExp('^' + source + '$'), ids, ru, weight: fixed.length,
            asIs: ids.filter(id => !ru.includes('{' + id + ':tr}')) });
    }
    ruPatternList.sort((a, b) => b.weight - a.weight);
    return ruPatternList;
}

// The attributes a reader sees or follows: a link into the docs leads to the docs in the page's language.
const TRANSLATED_ATTRS = ['title', 'aria-label', 'placeholder', 'href'];

// Puts the page's own HTML into the page's language: each text and each attribute the catalog has, with its runs of
// white space read as one space. Code stays as it is.
function translateDom(node) {
    for (const child of node.childNodes || []) {
        if (child.nodeType === 3) {
            const m = /^(\s*)([\s\S]*?)(\s*)$/.exec(child.nodeValue);
            const ru = m[2] ? RU.get(m[2].replace(/\s+/g, ' ')) : undefined;
            if (ru !== undefined) child.nodeValue = m[1] + ru + m[3];
        } else if (child.nodeType === 1 && !/^(script|style|code|svg)$/i.test(child.tagName)) {
            for (const name of TRANSLATED_ATTRS) {
                const value = child.getAttribute(name);
                const ru = value ? RU.get(value.replace(/\s+/g, ' ')) : undefined;
                if (ru !== undefined) child.setAttribute(name, ru);
            }
            translateDom(child);
        }
    }
}

// The catalog's sections as one map, a string with one Russian wherever it is shown, and the entries of the sections
// of the server's texts ("server: …"), which give tr its templates.
async function fetchCatalog() {
    try {
        const r = await fetch('/ru.json');
        if (!r.ok) return null;
        const sections = Object.entries(await r.json());
        return { all: new Map(sections.flatMap(([, entries]) => Object.entries(entries))),
            server: sections.filter(([name]) => name.startsWith('server')).flatMap(([, entries]) => Object.entries(entries)) };
    } catch (e) {
        return null;
    }
}

// Asks for the Russian catalog as the page opens when Russian was chosen at the last opening (theme.js): the page then
// does not wait for it after it learns its user's language.
function prefetchLanguage() {
    if (document.documentElement.getAttribute('data-lang-hint') === 'ru') ruCatalog = fetchCatalog();
}

// The language of the signed-in user, or of nobody: English. Russian translates what the page holds; the page draws
// the rest in it. A page keeps the language it learned first: see speaksOtherThan. The choice is kept for the next
// opening, which stays hidden until it knows its language.
async function useLanguage(lang) {
    if (lang === 'ru' && !RU) {
        const catalog = await (ruCatalog || (ruCatalog = fetchCatalog()));
        if (catalog) {
            [RU, RU_SERVER] = [catalog.all, catalog.server];
            LANG = 'ru';
            document.documentElement.setAttribute('lang', 'ru');
            translateDom(document.body);
        }
    }
    keepLanguage(lang);
    document.documentElement.removeAttribute('data-lang-hint');
    languageKnown = true;
    languageSettled();
}

// The language for the next opening of the page, which waits hidden for Russian (theme.js).
function keepLanguage(lang) {
    try {
        if (lang === 'ru') localStorage.setItem('lang', 'ru');
        else localStorage.removeItem('lang');
    } catch (e) { /* private mode */ }
}

// Whether the page already speaks a language other than lang. It cannot switch in place: its own HTML is translated
// where it stands, and what it drew in the old language stays so.
function speaksOtherThan(lang) {
    return languageKnown && (lang === 'ru') !== (LANG === 'ru');
}

// Opens the page again in lang.
function reopenIn(lang) {
    keepLanguage(lang);
    location.reload();
}

// The language of a page without a login form, the status page or the flaky board: the one its signed-in reader chose,
// else English. Such a page draws what it fetched once this settles, or it would draw English on a Russian page.
async function learnLanguage() {
    prefetchLanguage();
    let lang = null;
    try {
        const r = await fetch('/api/page-language', { cache: 'no-store' });
        if (r.ok) lang = (await r.json()).lang;
    } catch (e) { /* the page stays English */ }
    await useLanguage(lang);
    document.title = T(document.title);
}

// A name that opens a group of ⚙, such as "Токен JIRA", as a sentence says it: "токен JIRA".
function inSentence(name) {
    return LANG === 'ru' ? name.charAt(0).toLowerCase() + name.slice(1) : name;
}

// What the page writes for a coding assistant stays English, whatever the page's language.
function inEnglish(make) {
    const saved = [LANG, RU];
    [LANG, RU] = ['en', null];
    try {
        return make();
    } finally {
        [LANG, RU] = saved;
    }
}

// "1 result", "3 results".
function countOf(n, noun) {
    return T(['', ' ' + noun, ''], n, n === 1 ? '' : 's');
}

// "0.4", in Russian "0,4".
function fraction(n) {
    return LANG === 'ru' ? String(n).replace('.', ',') : String(n);
}

// "1,234", as the page's language writes numbers.
function num(n) {
    return n.toLocaleString(LANG === 'ru' ? 'ru-RU' : undefined);
}

// "12:05:30", as the page's language writes a time.
function timeOf(ms) {
    return new Date(ms).toLocaleTimeString(LANG === 'ru' ? 'ru-RU' : undefined);
}

// The states of a run TeamCity names, as the chips show them.
const RUN_STATES = { queued: phrase('queued'), running: phrase('running') };

function runState(state) {
    return RUN_STATES[state] ? T(RUN_STATES[state]) : state;
}

// A button disabled while its request runs drops the keyboard focus to the page. The returned function gives the focus
// back once the button is enabled again, unless the focus has moved on meanwhile.
function keepFocus(btn) {
    const had = document.activeElement === btn;
    return () => {
        if (had && (!document.activeElement || document.activeElement === document.body)) btn.focus();
    };
}

// "· ~18m" (time to finish) or "· starts ~12m" (queued, only the start is predictable).
function chipEta(b) {
    // An estimate says when a run ends; the elapsed/waited part says what it has cost so far —
    // a chain an hour deep in the agent queue reads exactly like a fresh one without it.
    // The tracker sends null where TeamCity said nothing, and `null >= 0` is true in JS —
    // without this guard a chip renders the empty " ·  left".
    const has = v => v != null && v >= 0;
    const spent = b.state === 'running'
        ? (has(b.elapsedSec) ? ' ' + fmtSpent(b.elapsedSec) : '')
        : (has(b.waitedSec) ? ' ' + fmtSpent(b.waitedSec) : '');
    if (has(b.leftSec)) return T`${spent} · ${fmtLeft(b.leftSec)} left`;
    if (has(b.startSec)) return T`${spent} · starts ${fmtLeft(b.startSec)}`;
    return spent;
}

// "12m" / "1h 05m" — time already spent, stated plainly (no ~: this part is measured, not guessed).
function fmtSpent(sec) {
    if (sec == null || sec < 0) return '';
    if (sec < 60) return T('<1m');
    const m = Math.round(sec / 60);
    return m < 60 ? T`${m}m` : T`${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

// "~18m" / "~1h 05m" — TeamCity's estimate of time left for a running build.
function fmtLeft(sec) {
    if (sec == null || sec < 0) return '';
    if (sec < 60) return T('~<1m');
    const m = Math.round(sec / 60);
    return m < 60 ? T`~${m}m` : T`~${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

// TeamCity URLs carry the test-name hash in its unsigned 64-bit form; the REST id is signed.
function unsignedTestId(id) {
    try { return (BigInt(id) & 0xFFFFFFFFFFFFFFFFn).toString(); } catch (e) { return id; }
}

// The work directory of a TeamCity agent inside a test's parameters ("/opt/teamcity/work/425d7af4e9aa99d9/", a
// Windows one too): the same on every row. Stops at brackets, commas and spaces, so only the path goes, not the
// parameter around it. The server's CauseClusters.AGENT_WORK_DIR is the same rule: the causes' titles and the
// names in the PR comment and the visa read as the rows do.
const AGENT_WORK_DIR = /[^\s()\[\],]*?[\\/]work[\\/][0-9a-f]{8,}[\\/]/g;

// Show only "Class.method[params]" (or just "Class" for a class-level failure): drop the
// package/namespace (Java lower-case OR .NET PascalCase), keeping dots inside (...)/[...] intact.
function shortTestName(name) {
    let s = (name.includes(': ') ? name.slice(name.indexOf(': ') + 2) : name).replace(AGENT_WORK_DIR, '');
    const noMethod = s.endsWith('.'); // class-level failure, e.g. "pkg.SomeTest."
    let depth = 0, seg = '', segs = [];
    for (const ch of s) {
        if (ch === '(' || ch === '[') depth++;
        else if (ch === ')' || ch === ']') depth = Math.max(0, depth - 1);
        if (ch === '.' && depth === 0) { segs.push(seg); seg = ''; }
        else seg += ch;
    }
    segs.push(seg);
    segs = segs.filter(x => x.length); // drop the empty tail left by a trailing dot
    return segs.slice(noMethod ? -1 : -2).join('.');
}

// A fence longer than any run of backticks inside, so quoted text cannot close it early.
function fenced(text) {
    const longest = Math.max(0, ...(String(text).match(/`+/g) || []).map(run => run.length));
    const fence = '`'.repeat(Math.max(3, longest + 1));
    return fence + '\n' + text + '\n' + fence;
}

// The .NET, C++, Python, Node.js and PHP suites run NUnit, Boost, pytest, the Node.js client's tests and PHPUnit, not
// JUnit ones: each gets the tool its suite runs it with.
const LANGUAGE = { java: 'Java', dotnet: '.NET, C#', cpp: 'C++', python: 'Python', nodejs: 'Node.js', php: 'PHP' };

// By the suite's id or the test's name: a pytest name reads "test_module.test_function", a PHPUnit one
// "tests: Apache\Ignite\Tests\CacheTestCase: testCreateCache".
function platformOf(suite, name) {
    if (/PlatformNet/i.test(suite || '') || /^Apache\.Ignite\./.test(name || '')) return 'dotnet';
    if (/Platform(CPP|CCMake)|Odbc/i.test(suite || '') || /^Ignite(Core|ThinClient|Odbc)Test: /.test(name || '')) return 'cpp';
    if (/Python/i.test(suite || '') || /^test_\w+\.test_\w+/.test(name || '')) return 'python';
    if (/Node\.?js/i.test(suite || '')) return 'nodejs';
    if (/Php/i.test(suite || '') || /^tests: Apache\\Ignite\\Tests\\/.test(name || '')) return 'php';
    return 'java';
}

// What the message and stack trace say the failure is (the server reads them, not the stdout after them, where every
// Ignite test mentions timeouts). `failedRuns` names the runs the test failed every one of ("all 3 runs of this code"):
// a re-run is only worth suggesting without them.
function kindLabel(kind, failedRuns) {
    if (kind === 'hang') return T('⌛ hang — the test ran out of time; the test\'s thread in the thread dump shows where it waits');
    if (kind === 'assertion') return T('⚖ assertion — likely a real logic failure');
    if (kind !== 'environment') return null;
    return failedRuns
        ? T`environment/timing — but it failed ${failedRuns}, so a re-run alone is unlikely to pass`
        : T('environment/timing — a re-run may pass');
}

// The labels the failure lists of the PR page and the flaky board share: the prompt copy, and the toggle of a
// failure panel while it is closed and while it is open ([label, title]).
const AI_PROMPT = phrase('copy AI prompt');
const FAILURE_CLOSED = [phrase('failure ▸'), phrase('Show the failure message and stack trace.')];
const FAILURE_OPEN = [phrase('failure ▾'), phrase('Hide the failure message.')];

// The toggle of a failure panel reads as the panel stands.
function paintFailureToggle(btn, open, closed = FAILURE_CLOSED, opened = FAILURE_OPEN) {
    const [label, title] = open ? opened : closed;
    btn.setAttribute('aria-expanded', String(open));
    btn.textContent = T(label);
    btn.title = T(title);
}

// Clipboard write with the legacy fallback (no async-clipboard permission); flashes the button label.
async function copyText(text, btn, idle, flashMs = 1800) {
    let ok = false;
    try { await navigator.clipboard.writeText(text); ok = true; }
    catch (e) {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        try { ok = document.execCommand('copy'); } catch (e2) { /* stays not-ok */ }
        ta.remove();
    }
    btn.textContent = ok ? T('copied') : T('copy failed');
    setTimeout(() => { btn.textContent = idle; }, flashMs);
}

// The failure output as the server sent it, without the label shown above it; `idle` is the button's own label.
async function copyDetails(btn, idle = 'copy') {
    const pre = btn.closest('.details').querySelector('pre');
    await copyText(pre.dataset.raw || pre.textContent, btn, idle, 1500);
}

// A long-lived tab can't hot-swap its own code: when a deploy changes this page, offer a reload.
function watchForDeploy() {
    let baseline = null;
    async function check() {
        try {
            const r = await fetch(location.pathname, { method: 'HEAD', cache: 'no-store' });
            const lm = r.headers.get('last-modified');
            if (!lm) return;
            if (baseline === null) { baseline = lm; return; }
            if (lm !== baseline) $('reloadPill').classList.remove('hidden');
        } catch (e) { /* offline blip: try again next tick */ }
    }
    check();
    setInterval(check, 120000);
    $('reloadPill').onclick = () => location.reload();
}

// theme.js already applied the effective theme; here we only label the select and persist explicit choices (a
// system-derived default is never written to storage, so an untouched setting keeps following the OS).
function wireThemeToggle() {
    const sel = $('theme-toggle');
    sel.value = document.documentElement.getAttribute('data-theme') || 'light';
    sel.addEventListener('change', () => {
        const t = sel.value;
        document.documentElement.setAttribute('data-theme', t);
        try { localStorage.setItem('theme', t); } catch (e) { /* private mode */ }
    });
}
