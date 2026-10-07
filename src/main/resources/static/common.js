// Helpers of all three pages, loaded before each page's own script. Nothing here runs on load.

const $ = id => document.getElementById(id);

// Quotes too: the result also lands inside attribute values (title="…", data-*="…").
function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
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
    if (has(b.leftSec)) return spent + ' · ' + fmtLeft(b.leftSec) + ' left';
    if (has(b.startSec)) return spent + ' · starts ' + fmtLeft(b.startSec);
    return spent;
}

// "12m" / "1h 05m" — time already spent, stated plainly (no ~: this part is measured, not guessed).
function fmtSpent(sec) {
    if (sec == null || sec < 0) return '';
    if (sec < 60) return '<1m';
    const m = Math.round(sec / 60);
    return m < 60 ? `${m}m` : `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

// "~18m" / "~1h 05m" — TeamCity's estimate of time left for a running build.
function fmtLeft(sec) {
    if (sec == null || sec < 0) return '';
    if (sec < 60) return '~<1m';
    const m = Math.round(sec / 60);
    return m < 60 ? `~${m}m` : `~${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

// TeamCity URLs carry the test-name hash in its unsigned 64-bit form; the REST id is signed.
function unsignedTestId(id) {
    try { return (BigInt(id) & 0xFFFFFFFFFFFFFFFFn).toString(); } catch (e) { return id; }
}

// Show only "Class.method[params]" (or just "Class" for a class-level failure): drop the
// package/namespace (Java lower-case OR .NET PascalCase), keeping dots inside (...)/[...] intact.
function shortTestName(name) {
    let s = name.includes(': ') ? name.slice(name.indexOf(': ') + 2) : name;
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

// The .NET and C++ suites run NUnit and Boost tests, not JUnit ones: each gets the tool its suite runs it with.
const LANGUAGE = { java: 'Java', dotnet: '.NET, C#', cpp: 'C++' };

function platformOf(suite, name) {
    if (/PlatformNet/i.test(suite || '') || /^Apache\.Ignite\./.test(name || '')) return 'dotnet';
    if (/Platform(CPP|CCMake)|Odbc/i.test(suite || '') || /^Ignite(Core|ThinClient|Odbc)Test: /.test(name || '')) return 'cpp';
    return 'java';
}

// What the message and stack trace say the failure is (the server reads them, not the stdout after them, where every
// Ignite test mentions timeouts). `failedRuns` names the runs the test failed every one of ("all 3 runs of this code"):
// a re-run is only worth suggesting without them.
function kindLabel(kind, failedRuns) {
    if (kind === 'hang') return '⌛ hang — the test ran out of time; the test\'s thread in the thread dump shows where it waits';
    if (kind === 'assertion') return '⚖ assertion — likely a real logic failure';
    if (kind !== 'environment') return null;
    return failedRuns
        ? `♻ environment/timing — but it failed ${failedRuns}, so a re-run alone is unlikely to pass`
        : '♻ environment/timing — a re-run may pass';
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
    btn.textContent = ok ? 'copied' : 'copy failed';
    setTimeout(() => { btn.textContent = idle; }, flashMs);
}

// The failure output as the server sent it, without the label shown above it.
async function copyDetails(btn) {
    const pre = btn.closest('.details').querySelector('pre');
    await copyText(pre.dataset.raw || pre.textContent, btn, 'copy', 1500);
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
