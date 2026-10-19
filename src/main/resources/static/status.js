watchForDeploy();
const languageLearned = learnLanguage();

function fmtDur(s) {
    s = Math.floor(s);
    const d = Math.floor(s / 86400); s %= 86400;
    const h = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60); s %= 60;
    if (d) return T`${d}d ${h}h ${m}m`;
    if (h) return T`${h}h ${m}m`;
    if (m) return T`${m}m ${s}s`;
    return T`${s}s`;
}

// `sub` breaks only between its " · " chunks, and each dot ends the chunk before it: next to an inline-block the
// browser may break the line on either side of a dot that stands between them. `title` explains the card on hover.
function card(k, v, sub, cls, title) {
    const parts = String(sub || '').split(' · ');
    const chunks = parts.map((c, i) => `<span class="seg">${c}${i < parts.length - 1 ? '\u00a0·' : ''}</span>`).join(' ');
    return `<div class="card"${title ? ` title="${esc(title)}"` : ''}><div class="k">${k}</div>`
        + `<div class="v ${cls || ''}">${v}${sub ? ` <small>${chunks}</small>` : ''}</div></div>`;
}

// Traffic-light severity for higher-is-worse values: below warnAt = ok, below badAt = warn, else bad.
function sev(value, warnAt, badAt) {
    return value < warnAt ? 'ok' : value < badAt ? 'warn' : 'bad';
}

// A card whose number agrees with the words after it: `counted` is "3 users with an option on", in the page's
// language. The number is the card's value, and its words start the line under it.
function countedCard(k, counted, rest, cls, title) {
    const [, n, words] = /^([^ ]+) ([\s\S]*)$/.exec(counted);
    return card(k, n, words + (rest || ''), cls, title);
}

function agoShort(ms) {
    if (!ms) return '—';
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return T`${s}s ago`;
    const m = Math.round(s / 60);
    return m < 60 ? T`${m}m ago` : T`${Math.round(m / 60)}h ago`;
}

function secs(ms) {
    if (!ms || ms < 0) return T`${0}s`;
    const s = ms / 1000;
    return T`${fraction(s < 10 ? s.toFixed(1) : Math.round(s))}s`;
}

// Warmer's current state, traffic-lit: warming = amber, failures in the last cycle = red,
// a clean completed cycle = green, nothing to judge yet = neutral.
function warmerCard(a) {
    if (a.warmerRunning) {
        const prog = a.cycleTotal ? T`${a.cycleDone}/${a.cycleTotal} PRs` : '';
        const el = a.cycleStartedAt ? T`${secs(Date.now() - a.cycleStartedAt)} elapsed` : '';
        return card(T('Warmer'), T('warming…'), [prog, el].filter(Boolean).join(' · '), 'warn');
    }
    if (a.lastFailed > 0) {
        return card(T('Warmer'), agoShort(a.lastWarmCycleAt),
            T`${a.lastFailed} warm${a.lastFailed === 1 ? '' : 's'} failed last cycle`, 'bad');
    }
    // Cycles keep ticking with an empty pool but visit nothing — "N cycles run" would read green
    // while everything is actually cold.
    if (!a.pooledTokens)
        return card(T('Warmer'), T('paused'), T('no pooled tokens — nothing to warm with'), 'warn');
    if (a.lastWarmCycleAt) {
        return card(T('Warmer'), agoShort(a.lastWarmCycleAt),
            T`${a.cyclesCompleted} cycle${a.cyclesCompleted === 1 ? '' : 's'} run`, 'ok');
    }
    return idleWarmerCard(a.firstCycleAt);
}

// A first cycle that never starts logs nothing, so the card turns amber a minute after the cycle was due.
function idleWarmerCard(firstAt) {
    const state = firstPassState(firstAt);
    if (state === 'late') return card(T('Warmer'), T('never'), '', 'warn', lateTitle(phrase('cycle'), firstAt));
    return card(T('Warmer'), T('idle'), state === 'off' ? T('no cycle yet') : passText(phrase('cycle'), 0, firstAt));
}

// The initial post-restart warm-up: amber while it's still going, green once done.
function startupCard(a) {
    if (a.firstCycleMs > 0) return card(T('Startup warm-up'), secs(a.firstCycleMs), T('completed'), 'ok');
    if (a.warmerRunning && a.cycleStartedAt)
        return card(T('Startup warm-up'), secs(Date.now() - a.cycleStartedAt) + '…', T('in progress'), 'warn');
    return card(T('Startup warm-up'), '—', T('pending'));
}

// GitHub calls by token: the server names those under users' own PATs "pat:<category>"; the rest run under the
// app (server) token, or none.
function githubCalls(byCategory) {
    const out = { app: {}, pat: {}, appCalls: 0, appFails: 0 };
    for (const [k, v] of Object.entries(byCategory || {})) {
        if (k.startsWith('pat:')) { out.pat[k.slice(4)] = v; continue; }
        out.app[k] = v;
        out.appCalls += v.total;
        out.appFails += v.fail;
    }
    return out;
}

// Whose GitHub account the checker writes as (GITHUB_TOKEN) and what its last check found: the operator has to
// know, the token must be able to write and not expire unnoticed, the account must not push to the repo or the
// checker's own, and what GitHub did not say is unknown, not fine.
function appAccountCard(a) {
    if (!a || a.state === 'unknown')
        return card(T('App account'), '—', T('GitHub has not said whose GITHUB_TOKEN this is yet'));
    if (a.state === 'none') {
        return card(T('App account'), T('none'), T('no GITHUB_TOKEN: the PR comment, PR commands, the checkstyle '
            + 'autofix and tests to suites are off'), 'warn');
    }
    if (a.state === 'refused')
        return card(T('App account'), T('refused'), T('GitHub refuses GITHUB_TOKEN'), 'bad');
    return card(T('App account'), '@' + esc(a.login), appAccountFound(a.checks || []), appAccountLevel(a.checks || []));
}

// Whether the app account's writes go: GitHub may refuse one (a block, a locked PR) or make them wait, and the writes
// for a PR or a user may reach their hourly cap. Those that wait go out later, so a wait is amber.
function appWritesCard(w) {
    if (!w) return '';
    const said = [];
    if (w.refusedAt) said.push(w.refused ? esc(tr(w.refused)) : T('GitHub refused a write: log in to see what it said'));
    if (w.pausedUntil) said.push(T`GitHub rate-limits them until ${timeOf(w.pausedUntil)}: they go out then`);
    if ((w.capped || []).length)
        said.push(T`hourly cap reached for ${w.capped.map(c => esc(tr(c))).join(', ')}: the rest wait`);
    if (!said.length) return card(T('App writes'), T('ok'), T('none refused or waiting'), 'ok');
    return card(T('App writes'), w.refusedAt ? T('refused') : T('waiting'), said.join(' · '),
        w.refusedAt ? 'bad' : 'warn');
}

function appAccountFound(checks) {
    if (!checks.length) return T('nothing is known of its token yet');
    const said = checks.filter(c => c.text).map(c => esc(tr(c.text)));
    return said.length ? said.join(' · ') : T('log in on the main page to see what its check found');
}

// The card's colour: the worst check; an unknown one leaves it grey, as nothing says it is fine.
function appAccountLevel(checks) {
    const has = level => checks.some(c => c.level === level);
    if (has('error')) return 'bad';
    if (has('warn')) return 'warn';
    return !checks.length || has('unknown') ? '' : 'ok';
}

// Snapshots of the state on disk: off is amber only when it was meant to be on; a durable file
// that could not be read at startup, or a save that keeps failing, is red until dealt with.
function persistenceCard(p) {
    if (!p) return '';
    if (!p.active) return card(T('Snapshots'), T('off'), esc(tr(p.off)), p.enabled ? 'warn' : '');
    const bad = (p.unreadable || []).map(u => T`${esc(u.file)} unreadable, kept as ${esc(u.keptAs)}`)
        .concat(Object.entries(p.failingSaves || {}).map(([f, why]) => T`saving ${esc(f)} fails: ${esc(why)}`));
    const count = bad.length || p.problems || 0;
    if (count) {
        return card(T('Snapshots'), countOf(count, 'problem'), bad.length
            ? bad.join(' · ') : count === 1 ? T('log in on the main page to see it')
                : T('log in on the main page to see them'), 'bad');
    }
    const day = p.lastBackup && p.lastBackup.replace(/^cache-|\.zip$/g, '');
    return card(T('Snapshots'), T('active'), day ? T`last backup ${esc(day)}` : T('no backup yet'), 'ok');
}

// The page learns of an out-of-memory error from the dump alone: the JVM exits without a word in the service's log.
function heapDumpCard(dump) {
    if (!dump) return '';
    const left = Math.max(0, Math.round((dump.deletedAt - Date.now()) / 1000));
    return card(T('Out of memory'), agoShort(dump.writtenAt),
        left >= 60 ? T`heap dump deleted in ${fmtDur(left)}` : T('heap dump deleted within 10 min'), 'warn',
        T('The JVM ran out of memory and restarted. Its heap dump holds decrypted tokens, so the server deletes it two'
        + ' days after the JVM wrote it.'));
}

// The version alone does not tell a local build's code: the commit does, unless the tree had changes.
function versionCard(d) {
    if (!d.commit) return card(T('Version'), esc(d.version));
    const commit = esc(d.commit.slice(0, 7));
    return card(T('Version'), esc(d.version), d.dirty ? T`commit ${commit} + uncommitted changes` : T`commit ${commit}`,
        d.dirty ? 'warn' : '');
}

// The settings name paths on the server, so only signed-in viewers get them.
function configRows(d) {
    if (!d.config) return `<div class="muted">${T('log in on the main page to see the settings')}</div>`;
    return Object.entries(d.config).map(([k, v]) =>
        `<div class="catrow"><span class="catname">${esc(k)}</span><span class="catval">${esc(tr(v))}</span></div>`).join('');
}

// "45s", "3m": how long ago a pass started, or how long until the first one is due.
function spanShort(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    return s < 90 ? T`${s}s` : T`${Math.round(s / 60)}m`;
}

// A minute after the first pass of a job was due, its "never" turns amber: a job that hangs or stopped logs nothing.
const FIRST_PASS_GRACE_MS = 60000;

// Where a job stands before its first pass since the start: 'soon' until the pass is due, 'due' for a minute after
// that, 'late' from then on, and 'off' when the job does not run on this server (no time from the server).
function firstPassState(firstAt) {
    if (!firstAt) return 'off';
    const now = Date.now();
    return firstAt > now ? 'soon' : now - firstAt < FIRST_PASS_GRACE_MS ? 'due' : 'late';
}

function lateTitle(noun, firstAt) {
    return T('The first {0} was due at {1} and has not started: the job may be stuck.', T(noun), timeOf(firstAt));
}

// "sweep 2m ago", "first sweep in 1m", "first sweep due now", or "sweep never", amber once the first is late.
function passText(noun, lastAt, firstAt) {
    const pass = T(noun);
    if (lastAt) return T`${pass} ${spanShort(Date.now() - lastAt)} ago`;
    const state = firstPassState(firstAt);
    if (state === 'soon') return T`first ${pass} in ${spanShort(firstAt - Date.now())}`;
    if (state === 'due') return T`first ${pass} due now`;
    if (state === 'late') return T`${pass} <span class="late" title="${esc(lateTitle(noun, firstAt))}">never</span>`;
    return T`${pass} never`;
}

// The watcher refreshes every 20 s, and its card says when it last did; a minute old is amber.
function lastRefreshCard(w) {
    if (w.lastRefreshAt) {
        return card(T('Last refresh'), T`${spanShort(Date.now() - w.lastRefreshAt)} ago`,
            w.lastRefreshMs ? T`${w.lastRefreshMs}ms` : '', Date.now() - w.lastRefreshAt < 60000 ? 'ok' : 'warn');
    }
    const state = firstPassState(w.firstRefreshAt);
    if (state === 'late')
        return card(T('Last refresh'), T('never'), '', 'warn', lateTitle(phrase('refresh'), w.firstRefreshAt));
    if (state === 'off') return card(T('Last refresh'), T('never'));
    return card(T('Last refresh'), '—', passText(phrase('refresh'), 0, w.firstRefreshAt));
}

// For the operator only: the users whose options wait for new tokens since the server key was changed. No card while
// nobody waits.
function keyChangeCard(n) {
    if (!n) return '';
    return countedCard(T('New tokens wanted'), T`${n} user${n === 1 ? '' : 's'} whose options wait`, '', 'warn', n === 1
        ? T('The server key was changed, so the checker could no longer read the tokens it stored. The options of '
            + 'this user wait until they log in again and save the tokens ⚙ asks them for.')
        : T('The server key was changed, so the checker could no longer read the tokens it stored. The options of '
            + 'these users wait until they log in again and save the tokens ⚙ asks them for.'));
}

// Paused: users with the option on whom the last sweep did not act for. Before the first sweep nobody counts as paused.
function suiteFixCard(f) {
    if (!f) return '';
    const paused = f.lastSweepAt && f.acting != null ? Math.max(0, (f.users ?? 0) - f.acting) : 0;
    const users = f.users ?? 0;
    return countedCard(T('Tests added to suites'), countOf(f.pushed ?? 0, 'commit'),
        ' · ' + T`${users} user${users === 1 ? '' : 's'} with the option on`
        + (paused ? ' · ' + T`${paused} paused` : '') + ' · ' + passText(phrase('sweep'), f.lastSweepAt, f.firstSweepAt),
        paused ? 'warn' : '',
        paused ? (paused === 1 ? T('The last sweep did not act for one user with the option on.')
            : T`The last sweep did not act for ${paused} users with the option on.`)
            + ' ' + T('The reason is one of these: TeamCity or GitHub refuses their token, a GitHub account or token '
            + 'is missing, or they switched the option on after the sweep.') : '');
}

function lastCycleCard(a) {
    if (!a.lastWarmCycleAt) return card(T('Last cycle'), '—', T('none yet'));
    return card(T('Last cycle'), secs(a.lastCycleMs), T`${a.lastWarmed} recomputed · ${a.lastCached} cached`);
}

// Anonymous viewers get the problems' levels only: the texts name files and errors on the server.
function healthProblems(d) {
    const problems = d.healthProblems || [];
    if (problems.every(p => p.text)) return problems.map(p => ({ level: p.level, text: tr(p.text) }));
    const n = problems.length;
    return [{ level: problems.some(p => p.level === 'error') ? 'error' : 'warn',
        text: n === 1 ? T`${n} problem — log in on the main page to see it`
            : T`${n} problems — log in on the main page to see them` }];
}

// The dot shows the worst of the problems and the log, so the tooltip names both.
function healthTitle(d, problems) {
    const reasons = problems.map(p => p.text);
    if (d.logHealth === 'error') reasons.push(T`error logged ${agoShort(d.log.lastErrorAt)}`);
    else if (d.logHealth === 'warn') reasons.push(T`warning logged ${agoShort(d.log.lastWarningAt)}`);
    return reasons.length ? reasons.join('; ') : T('healthy');
}

// The whole heap now; the old generation's peak since the start is what -Xmx has to hold.
const HEAP_TITLE = phrase('The first number is the whole heap in use now. Old-gen peak is the most the old generation '
    + 'has held since the server started. Set -Xmx large enough to hold this peak.');

function render(d) {
    const tc = d.teamcity, gh = d.github, j = d.jvm;
    const t = tc.lastHour, g = gh.lastHour;
    const tcRate = t.total ? (100 * t.ok / t.total) : 100;
    const az = (d.http && d.http.byCategory && d.http.byCategory['/api/analyze']) || null;
    const problems = healthProblems(d);
    $('healthDot').className = 'hdot ' + (d.health || 'ok');
    $('healthDot').title = healthTitle(d, problems);
    $('problems').innerHTML = problems.map(p => `<div class="problem ${esc(p.level)}">${esc(p.text)}</div>`).join('');
    $('systemCards').innerHTML = [
        versionCard(d),
        card(T('Uptime'), fmtDur(d.uptimeSeconds)),
        card('CPU', j.systemCpuPct == null || j.systemCpuPct < 0 ? '—' : j.systemCpuPct + '%',
            j.processCpuPct >= 0 ? T`${j.processCpuPct}% this app` : T('host'),
            j.systemCpuPct >= 0 ? sev(j.systemCpuPct, 50, 80) : ''),
        card(T('Load avg'), j.loadAverage == null || j.loadAverage < 0 ? '—' : fraction(j.loadAverage),
            T`on ${j.cpus} cpu`,
            j.loadAverage >= 0 ? sev(j.loadAverage / j.cpus, 0.7, 1.001) : ''),
        card(T('Heap'), j.heapUsedMb, T`/ ${j.heapMaxMb} MB` + (j.heapPeakMb > 0 ? T` · old-gen peak ${j.heapPeakMb} MB` : ''),
            j.heapMaxMb ? sev(100 * j.heapUsedMb / j.heapMaxMb, 60, 85) : '', T(HEAP_TITLE)),
        card(T('Process memory'), j.rssMb >= 0 ? j.rssMb : '—', j.rssMb >= 0
            ? T`MB resident · peak ${j.rssPeakMb}` + (j.hostMemMb > 0 ? T` · host ${j.hostMemMb} MB` : '')
            : T('not measured here')),
        card(T('Threads'), j.threads, T`· ${j.cpus} cpu`),
        heapDumpCard(j.heapDump),
        persistenceCard(d.persistence),
    ].join('');

    $('appCards').innerHTML = [
        card(T('Analyze (1h)'), az ? `${az.avgLatencyMs}` : '—',
            az ? T`ms avg · ${az.maxLatencyMs} max · ${az.total} calls` : T('no requests'),
            az ? sev(az.avgLatencyMs, 500, 2000) : ''),
        card(T('Open PRs'), d.app.openPrs),
    ].join('');

    const w = d.watcher || {};
    $('watcherCards').innerHTML = [
        card(T('Tracked builds'), w.tracked ?? 0,
            `${countOf(w.chains ?? 0, 'RunAll')} · ${countOf(w.suites ?? 0, 'suite')}`,
            (w.tracked ?? 0) > 0 ? 'ok' : ''),
        lastRefreshCard(w),
        card(T('RunAlls finished'), w.chainFinishes ?? 0, T('since start')),
        countedCard(T('Standing options'), T`${w.standingEnrolled ?? 0} users with an option on`,
            ' · ' + T`${w.standingPosted ?? 0} JIRA visa${w.standingPosted === 1 ? '' : 's'} posted`
            + ' · ' + passText(phrase('sweep'), w.standingLastSweepAt, w.standingFirstSweepAt)
            + (w.standingLastSweepMs ? ' (' + secs(w.standingLastSweepMs) + ')' : '')),
        countedCard(T('PR commands'), T`${w.prCommands ?? 0} /run-all handled`,
            ' · ' + passText(phrase('poll'), w.prCommandsLastPollAt, w.prCommandsFirstPollAt)),
        suiteFixCard(w.suiteFix),
        keyChangeCard(w.waitingAfterKeyChange),
    ].join('');

    $('warmerCards').innerHTML = [
        warmerCard(d.app),
        startupCard(d.app),
        lastCycleCard(d.app),
        card(T('Pooled tokens'), d.app.pooledTokens, d.app.pooledTokens ? '' : T('warmer paused — log in'),
            d.app.pooledTokens ? 'ok' : 'warn'),
        card(T('Results cached'), d.app.resultsCached),
        countedCard(T('History cached'), T`${d.app.historyCached} tests`),
    ].join('');

    $('tcCards').innerHTML = [
        card(T('TeamCity (1h)'), num(t.total),
            T`${fraction(tcRate.toFixed(1))}% ok · ${num(tc.sinceStart)} total`,
            t.total ? (tcRate >= 99 ? 'ok' : tcRate >= 95 ? 'warn' : 'bad') : ''),
        card(T('TC latency (1h)'), `${t.avgLatencyMs}`, T`ms avg · ${t.maxLatencyMs} max`,
            t.total ? sev(t.avgLatencyMs, 500, 2000) : ''),
    ].join('');

    const { app: ghApp, pat: ghPat, appCalls, appFails } = githubCalls(gh.byCategory);
    $('ghCards').innerHTML = [
        card(T('GitHub (1h)'), num(g.total), T`${g.fail} failed · ${num(gh.sinceStart)} total`,
            g.total ? (g.fail === 0 ? 'ok' : g.fail <= 5 ? 'warn' : 'bad') : ''),
        card(T('App token (1h)'), num(appCalls), T`${appFails} failed · server-side calls`,
            appCalls ? (appFails === 0 ? 'ok' : 'warn') : ''),
        appAccountCard(d.app.githubAccount),
        appWritesCard(d.app.githubWrites),
        card(T('Stars'), d.app.stars < 0 ? '—' : d.app.stars),
        card('GitHub API', d.app.githubRate && d.app.githubRate.limit ? d.app.githubRate.remaining : '—',
            d.app.githubRate && d.app.githubRate.limit ? T`/ ${d.app.githubRate.limit} left` : '',
            d.app.githubRate && d.app.githubRate.limit
                ? (100 * d.app.githubRate.remaining / d.app.githubRate.limit > 50 ? 'ok'
                    : 100 * d.app.githubRate.remaining / d.app.githubRate.limit > 10 ? 'warn' : 'bad') : ''),
    ].join('');

    const ji = d.jira || { lastHour: { total: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 }, sinceStart: 0 };
    const jh = ji.lastHour;
    $('jiraCards').innerHTML = [
        card(T('JIRA (1h)'), num(jh.total), T`${jh.fail} failed · ${num(ji.sinceStart)} total`,
            jh.total ? (jh.fail === 0 ? 'ok' : 'warn') : ''),
        card(T('JIRA latency (1h)'), jh.total ? `${jh.avgLatencyMs}` : '—',
            jh.total ? T`ms avg · ${jh.maxLatencyMs} max` : T('no calls'),
            jh.total ? sev(jh.avgLatencyMs, 1000, 3000) : ''),
    ].join('');

    $('logCards').innerHTML = [
        card(T('Log issues'), d.log.errors,
            T`${d.log.warnings} warn · ${d.log.clientMistakes} bad requests · since start`,
            d.health === 'error' ? 'bad' : d.health === 'warn' ? 'warn' : 'ok'),
    ].join('');

    const codes = m => Object.entries(m || {}).map(([s, n]) => `<code>${s === '0' ? T('network') : s}</code>×${n}`)
        .join(', ');
    const hour = codes(tc.byStatusHour), total = codes(tc.byStatus);
    $('tcByStatus').innerHTML =
        T`Failures by status <span class="muted">· HTTP code × times · last hour</span>: ${hour || T('none')}`
        + (total ? T` <span class="muted">· since app start: ${total}</span>` : '');

    renderCats($('tcCats'), tc.byCategory || {});
    renderCats($('ghCatsApp'), ghApp);
    renderCats($('ghCatsPat'), ghPat);
    renderCats($('jiraCats'), ji.byCategory || {});
    renderCats($('httpCats'), (d.http && d.http.byCategory) || {});
    renderLog($('logList'), d);
    $('configList').innerHTML = configRows(d);
    renderAdmin(d);

    drawStacked($('tcStack'), $('stackLegend'), tc.perMinuteByCategory || {});
    $('updated').textContent = T`updated ${timeOf(Date.now())}`;
}

// Problems outlive restarts, so older ones carry their day.
function logTime(t) {
    const d = new Date(t);
    const locale = LANG === 'ru' ? 'ru-RU' : [];
    if (d.toDateString() === new Date().toDateString()) return timeOf(t);
    return d.toLocaleDateString(locale, { month: 'short', day: 'numeric' }) + ' '
        + d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}

// Without PRC_ADMINS nobody gets the messages, so the page says how to name an operator.
const NO_OPERATOR = phrase('The server setting <code>PRC_ADMINS</code> names no operators, so nobody sees the log '
    + 'messages here. To name operators, set <code>PRC_ADMINS</code> in <code>/etc/ignite-pr-checker/env</code> to '
    + 'their TeamCity usernames, comma-separated, and restart the service. Until then, read the messages on the server '
    + 'with <code>journalctl -u ignite-pr-checker</code>.');

// Only the operators get the messages; the others see how many there are on the card above. The server's start time,
// not the browser's clock, sets this run's problems apart from earlier ones.
function renderLog(el, d) {
    const log = d.log || { recent: [] }, startedAt = d.startedAt;
    const recent = log.recent || [];
    if (d.operatorsNamed === false) { el.innerHTML = `<div class="muted">${T(NO_OPERATOR)}</div>`; return; }
    if (!d.operator && (log.errors || log.warnings || log.clientMistakes || log.lastErrorAt || log.lastWarningAt)) {
        el.innerHTML = `<div class="muted">${d.signedIn ? T('Only operators see the messages.')
            : T('Only operators see the messages. Log in on the main page if you are one.')}</div>`;
        return;
    }
    if (!recent.length) { el.innerHTML = `<div class="muted">${T('no warnings or errors logged')}</div>`; return; }
    const restart = recent.findIndex(e => e.t < startedAt);
    el.innerHTML = recent.map((e, i) => (i === restart
        ? `<div class="logsep">${T`— service started ${esc(logTime(startedAt))} —`}</div>` : '')
        + `<div class="logrow${e.client ? ' client' : ''}"${e.client
        ? ` title="${T('a bad request turned away — does not affect health')}"` : ''}>
        <span class="logtime">${esc(logTime(e.t))}</span>
        <span class="loglevel lvl-${e.level.toLowerCase()}">${e.level}</span>
        <span class="loglogger" title="${esc(e.logger)}">${esc(e.logger)}</span>
        <span class="logmsg">${esc(e.message)}</span>
    </div>`).join('');
}

// Restart, Flush, who used them last and the Users tab are for the operators, and the server tells only them who.
function renderAdmin(d) {
    $('restartBtn').hidden = $('flushBtn').hidden = $('viewTabs').hidden = !d.operator;
    if (!d.operator && document.body.classList.contains('users-mode')) setStatusTab(false);
    const last = (d.admin && d.admin.last) || {};
    const restart = [last.restart && ['restart', last.restart], last.update && ['update', last.update]]
        .filter(Boolean).sort((x, y) => y[1].at - x[1].at)[0];
    $('restartLast').textContent = !restart ? '' : restart[0] === 'update'
        ? T`last update: ${restart[1].by} · ${agoShort(restart[1].at)}`
        : T`last restart: ${restart[1].by} · ${agoShort(restart[1].at)}`;
    $('flushLast').textContent = last.flush ? T`last flush: ${last.flush.by} · ${agoShort(last.flush.at)}` : '';
}

async function errorText(r) {
    const body = await r.json().catch(() => null);
    return (body && tr(body.error)) || 'HTTP ' + r.status;
}

function renderCats(el, byCategory) {
    const entries = Object.entries(byCategory);
    if (!entries.length) { el.innerHTML = `<div class="muted">${T('no calls yet')}</div>`; return; }
    const max = Math.max(1, ...entries.map(([, s]) => s.total));
    el.innerHTML = entries.map(([name, s]) => {
        const pct = 100 * s.total / max;
        const fail = s.fail ? T` · <span class="rate-bad">${s.fail} failed</span>` : '';
        return `<div class="catrow">
            <div class="catname">${name}</div>
            <div class="catbar"><div class="catfill" style="width:${pct.toFixed(1)}%"></div></div>
            <div class="catval">${num(s.total)} <small>${T`${s.avgLatencyMs}ms`}${fail}</small></div>
        </div>`;
    }).join('');
}

// The categories TcClient records its calls under, each with its colour on the light card and on the dark ones. A
// script picked the colours: any two of a theme differ by a CIEDE2000 distance of 16.9 or more, each stands at 3:1 or
// more against its cards, and the most frequent categories got the colours picked first, which lie farthest apart. A
// category keeps its colour from hour to hour, whatever else is on the chart.
const CATEGORY_COLOURS = {
    baseline: ['#877800', '#4bf0d2'],
    branchFailures: ['#4b4b3c', '#699678'],
    branchMoves: ['#9687d2', '#e1d200'],
    branchRuns: ['#879687', '#968796'],
    buildState: ['#876969', '#c3d2ff'],
    cancel: ['#ff00f0', '#ffa5a5'],
    chainEta: ['#00785a', '#e100ff'],
    deps: ['#a5000f', '#ff2d4b'],
    details: ['#874b00', '#87960f'],
    failedTests: ['#00a500', '#00ff00'],
    findBuild: ['#0096c3', '#2dd2ff'],
    finishedChains: ['#1e00ff', '#b4d296'],
    history: ['#e1781e', '#c37800'],
    masterFail: ['#006978', '#0096a5'],
    otherBranchRuns: ['#ff0000', '#968769'],
    prRuns: ['#962d96', '#ff96f0'],
    prTests: ['#c31e5a', '#d25a96'],
    queueTop: ['#2d5a00', '#ff5a0f'],
    recentChains: ['#0f4b87', '#6978ff'],
    revision: ['#2d69ff', '#ffb469'],
    runningChains: ['#00a596', '#00b42d'],
    suiteRun: ['#5a4b69', '#0096e1'],
    trigger: ['#a58769', '#c3e1e1'],
    userBuilds: ['#ff5a78', '#e1d2c3'],
    whoami: ['#b46996', '#b47869'],
};
const TC_CATEGORIES = Object.keys(CATEGORY_COLOURS);

// A name not listed above gets a hue of its own from its name, a golden angle per step.
function catColor(name) {
    const onLight = document.documentElement.getAttribute('data-theme') === 'light';
    if (Object.hasOwn(CATEGORY_COLOURS, name)) return CATEGORY_COLOURS[name][onLight ? 0 : 1];
    let i = TC_CATEGORIES.length;
    for (const ch of String(name)) i = (i * 31 + ch.charCodeAt(0)) % 997;
    return `hsl(${Math.round(i * 137.508) % 360}, 60%, ${(onLight ? 28 : 58) + (i % 2 ? 10 : 0)}%)`;
}

// Stacked per-minute bars: each minute split into category-coloured segments (last hour).
function drawStacked(canvas, legendEl, byCat) {
    const cats = Object.keys(byCat);
    const dpr = window.devicePixelRatio || 1;
    const cssW = canvas.clientWidth || 880, cssH = 200;
    canvas.width = cssW * dpr; canvas.height = cssH * dpr;
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);

    const pad = { l: 34, r: 6, t: 6, b: 16 }, N = 60;
    const cw = cssW - pad.l - pad.r, ch = cssH - pad.t - pad.b;
    const totals = new Array(N).fill(0);
    cats.forEach(c => (byCat[c] || []).forEach((v, i) => totals[i] += v));
    const max = Math.max(1, ...totals);

    ctx.font = '10px system-ui';
    const muted = getComputedStyle(document.documentElement).getPropertyValue('--muted').trim();
    for (let k = 0; k <= 4; k++) {
        const y = pad.t + ch - (ch * k / 4);
        ctx.strokeStyle = 'rgba(128,128,128,0.18)'; ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(cssW - pad.r, y); ctx.stroke();
        ctx.fillStyle = muted; ctx.fillText(Math.round(max * k / 4), 2, y + 3);
    }

    const bw = cw / N;
    for (let i = 0; i < N; i++) {
        let yTop = pad.t + ch;
        cats.forEach(c => {
            const v = (byCat[c] || [])[i] || 0;
            if (!v) return;
            const h = ch * v / max;
            ctx.fillStyle = catColor(c);
            ctx.fillRect(pad.l + i * bw + 0.5, yTop - h, Math.max(1, bw - 1), h);
            yTop -= h;
        });
    }
    const now = T('now');
    ctx.fillStyle = muted; ctx.fillText(T('-60m'), pad.l, cssH - 4);
    ctx.fillText(now, cssW - pad.r - (LANG === 'ru' ? ctx.measureText(now).width : 18), cssH - 4);
    legendEl.innerHTML = cats.map(c => `<span><span class="dot" style="background:${catColor(c)}"></span>${esc(c)}</span>`).join('');
}

let last = null;
async function tick() {
    try {
        const r = await fetch('/api/status', { cache: 'no-store' });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const status = await r.json();
        await languageLearned;
        last = status;
        $('err').textContent = '';
        render(last);
    } catch (e) {
        await languageLearned;
        // A failed poll with data already on screen is usually a redeploy/restart blip: stay calm,
        // keep the cards, and let the next poll clear this. Alarm only when there's nothing to show.
        $('err').textContent = last ? T('connection lost — retrying…') : T`status unavailable: ${e.message}`;
    }
}

function redrawChart() {
    if (!last) return;
    drawStacked($('tcStack'), $('stackLegend'), last.teamcity.perMinuteByCategory || {});
}
window.addEventListener('resize', redrawChart);
// Users tab: active (last 15 min) vs everyone seen. The endpoint is auth-guarded, so
// anonymous viewers get a hint instead of names.
const ACTIVE_MS = 15 * 60 * 1000;
function agoMs(ms) {
    const s = (Date.now() - ms) / 1000;
    return s < 90 ? T`${Math.round(s)}s ago` : s < 5400 ? T`${Math.round(s / 60)}m ago`
        : s < 129600 ? T`${Math.round(s / 3600)}h ago` : T`${Math.round(s / 86400)}d ago`;
}
// `logins` counts the logins at the form since the user first came, not the browsers logged in now: only the user sees
// those, in ⚙.
async function loadUsers() {
    const times = n => n === 1 ? T('once') : T`${n} times`;
    const said = (u, active) => active
        ? T`last active ${agoMs(u.lastSeen)} · logged in ${times(u.logins)} · first seen ${agoMs(u.firstSeen)}`
        : T`last seen ${agoMs(u.lastSeen)} · logged in ${times(u.logins)} · first seen ${agoMs(u.firstSeen)}`;
    const row = (u, active) => `<div class="catrow"><span class="catname">${u.name.replace(/[&<>"]/g, '')}</span>`
        + `<span class="catval">${said(u, active)}</span></div>`;
    const muted = text => `<div class="muted">${text}</div>`;
    try {
        const r = await fetch('/api/users', { cache: 'no-store' });
        if (r.status === 401 || r.status === 403) {
            $('usersActive').innerHTML = $('usersSeen').innerHTML = r.status === 401
                ? muted(T('log in on the main page to see who is here'))
                : muted(T('only the operator can see who uses the service'));
            return;
        }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const list = await r.json();
        const active = list.filter(u => Date.now() - u.lastSeen <= ACTIVE_MS);
        const seen = list.filter(u => Date.now() - u.lastSeen > ACTIVE_MS);
        $('usersActive').innerHTML = active.map(u => row(u, true)).join('') || muted(T('nobody right now'));
        $('usersSeen').innerHTML = seen.map(u => row(u, false)).join('') || muted(T('nobody else yet'));
    } catch (e) {
        $('usersActive').innerHTML = muted(T('could not load users — try again in a moment'));
        $('usersSeen').innerHTML = '';
    }
}
let usersTimer = null;
function setStatusTab(users) {
    document.body.classList.toggle('users-mode', users);
    $('tabStatus').classList.toggle('on', !users);
    $('tabUsers').classList.toggle('on', users);
    $('tabStatus').setAttribute('aria-pressed', String(!users));
    $('tabUsers').setAttribute('aria-pressed', String(users));
    if (usersTimer) { clearInterval(usersTimer); usersTimer = null; }
    if (users) { loadUsers(); usersTimer = setInterval(loadUsers, 15000); }
}
$('tabStatus').addEventListener('click', () => setStatusTab(false));
$('tabUsers').addEventListener('click', () => setStatusTab(true));

$('restartBtn').addEventListener('click', async () => {
    if (!confirm(T('Restart the service?\n\nIt will be unavailable for ~10 seconds; in-flight analyses are dropped '
        + '(caches are saved).'))) return;
    const btn = $('restartBtn'), msg = $('restartMsg');
    const refocus = keepFocus(btn);
    btn.disabled = true;
    msg.textContent = T('restarting…');
    let accepted = false;
    try {
        const r = await fetch('/api/restart', { method: 'POST' });
        if (r.status === 401) { msg.textContent = T('log in on the main page first'); btn.disabled = false; refocus(); return; }
        if (r.status === 403) { msg.textContent = await errorText(r); btn.disabled = false; refocus(); return; }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        accepted = true;
    } catch (e) { msg.textContent = T('restart request failed'); btn.disabled = false; refocus(); }
    if (!accepted) return;
    const t0 = Date.now();
    const timer = setInterval(async () => {
        try {
            const v = await fetch('/api/version', { cache: 'no-store' });
            if (v.ok) { clearInterval(timer); msg.textContent = T('back up'); setTimeout(() => location.reload(), 800); return; }
        } catch (e) { /* still down */ }
        if (Date.now() - t0 > 120000) {
            clearInterval(timer);
            msg.textContent = T('still down after 2 minutes — check the server');
        }
    }, 2000);
});

$('flushBtn').addEventListener('click', async () => {
    if (!confirm(T('Clear cached analysis results and per-test history?\nThe next analysis will recompute from scratch '
        + '(extra TeamCity load).'))) return;
    const btn = $('flushBtn'), msg = $('flushMsg');
    const refocus = keepFocus(btn);
    btn.disabled = true; msg.textContent = T('flushing…');
    try {
        const r = await fetch('/api/flush-caches', { method: 'POST', cache: 'no-store' });
        if (r.status === 401) { msg.textContent = T('log in on the main page to flush'); return; }
        if (r.status === 403) { msg.textContent = await errorText(r); return; }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        msg.textContent = T`cleared ${countOf(c.results, 'result')}, ${c.history} history entr${c.history === 1 ? 'y' : 'ies'}`;
        tick();
    } catch (e) {
        msg.textContent = T`flush failed: ${e.message}`;
    } finally {
        btn.disabled = false;
        refocus();
    }
});

wireThemeToggle();
$('theme-toggle').addEventListener('change', redrawChart);

tick();
setInterval(tick, 5000);
