watchForDeploy();

function fmtDur(s) {
    s = Math.floor(s);
    const d = Math.floor(s / 86400); s %= 86400;
    const h = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60); s %= 60;
    if (d) return `${d}d ${h}h ${m}m`;
    if (h) return `${h}h ${m}m`;
    if (m) return `${m}m ${s}s`;
    return `${s}s`;
}

function card(k, v, sub, cls) {
    return `<div class="card"><div class="k">${k}</div><div class="v ${cls || ''}">${v}${sub ? ` <small>${sub}</small>` : ''}</div></div>`;
}

// Traffic-light severity for higher-is-worse values: below warnAt = ok, below badAt = warn, else bad.
function sev(value, warnAt, badAt) {
    return value < warnAt ? 'ok' : value < badAt ? 'warn' : 'bad';
}

function agoShort(ms) {
    if (!ms) return '—';
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return s + 's ago';
    const m = Math.round(s / 60);
    return m < 60 ? m + 'm ago' : Math.round(m / 60) + 'h ago';
}

function secs(ms) {
    if (!ms || ms < 0) return '0s';
    const s = ms / 1000;
    return (s < 10 ? s.toFixed(1) : Math.round(s)) + 's';
}

// Warmer's current state, traffic-lit: warming = amber, failures in the last cycle = red,
// a clean completed cycle = green, nothing to judge yet = neutral.
function warmerCard(a) {
    if (a.warmerRunning) {
        const prog = a.cycleTotal ? `${a.cycleDone}/${a.cycleTotal} PRs` : '';
        const el = a.cycleStartedAt ? secs(Date.now() - a.cycleStartedAt) + ' elapsed' : '';
        return card('Warmer', 'warming…', [prog, el].filter(Boolean).join(' · '), 'warn');
    }
    if (a.lastFailed > 0)
        return card('Warmer', agoShort(a.lastWarmCycleAt), `${a.lastFailed} warm${a.lastFailed === 1 ? '' : 's'} failed last cycle`, 'bad');
    // Cycles keep ticking with an empty pool but visit nothing — "N cycles run" would read green
    // while everything is actually cold.
    if (!a.pooledTokens)
        return card('Warmer', 'paused', 'no pooled tokens — nothing to warm with', 'warn');
    if (a.lastWarmCycleAt)
        return card('Warmer', agoShort(a.lastWarmCycleAt), `${a.cyclesCompleted} cycle${a.cyclesCompleted === 1 ? '' : 's'} run`, 'ok');
    return card('Warmer', 'idle', 'no cycle yet');
}

// The initial post-restart warm-up: amber while it's still going, green once done.
function startupCard(a) {
    if (a.firstCycleMs > 0) return card('Startup warm-up', secs(a.firstCycleMs), 'completed', 'ok');
    if (a.warmerRunning && a.cycleStartedAt) return card('Startup warm-up', secs(Date.now() - a.cycleStartedAt) + '…', 'in progress', 'warn');
    return card('Startup warm-up', '—', 'pending');
}

// Whose GitHub account the checker writes as (GITHUB_TOKEN): the operator has to know, and it must not be
// one that can push to the repo.
function appAccountCard(a) {
    if (!a || a.state === 'unknown')
        return card('App account', '—', 'GitHub has not said whose GITHUB_TOKEN this is yet');
    if (a.state === 'none')
        return card('App account', 'none', 'no GITHUB_TOKEN: read-only, commands of users without a token get no reply', 'warn');
    if (a.state === 'refused')
        return card('App account', 'refused', 'GitHub refuses GITHUB_TOKEN', 'bad');
    return a.canPush
        ? card('App account', '@' + esc(a.login), '⚠ can push to the repo — use an account without write access', 'warn')
        : card('App account', '@' + esc(a.login), 'writes replies, reactions and narration for users without a token', 'ok');
}

// Snapshots of the state on disk: off is amber only when it was meant to be on; a durable file
// that could not be read at startup, or a save that keeps failing, is red until dealt with.
function persistenceCard(p) {
    if (!p) return '';
    if (!p.active) return card('Snapshots', 'off', esc(p.off), p.enabled ? 'warn' : '');
    const bad = (p.unreadable || []).map(u => `${esc(u.file)} unreadable, kept as ${esc(u.keptAs)}`)
        .concat(Object.entries(p.failingSaves || {}).map(([f, why]) => `saving ${esc(f)} fails: ${esc(why)}`));
    const count = bad.length || p.problems || 0;
    if (count) return card('Snapshots', count + (count === 1 ? ' problem' : ' problems'),
        bad.length ? bad.join(' · ') : `log in on the main page to see ${count === 1 ? 'it' : 'them'}`, 'bad');
    const day = p.lastBackup && p.lastBackup.replace(/^cache-|\.zip$/g, '');
    return card('Snapshots', 'active', day ? 'last backup ' + esc(day) : 'no backup yet', 'ok');
}

// The version alone does not tell a local build's code: the commit does, unless the tree had changes.
function versionCard(d) {
    if (!d.commit) return card('Version', esc(d.version));
    return card('Version', esc(d.version), 'commit ' + esc(d.commit.slice(0, 7))
        + (d.dirty ? ' + uncommitted changes' : ''), d.dirty ? 'warn' : '');
}

// The settings name paths on the server and the operators, so only signed-in viewers get them.
function configRows(d) {
    if (!d.config) return '<div class="muted">log in on the main page to see the settings</div>';
    return Object.entries(d.config).map(([k, v]) =>
        `<div class="catrow"><span class="catname">${esc(k)}</span><span class="catval">${esc(v)}</span></div>`).join('');
}

function lastCycleCard(a) {
    if (!a.lastWarmCycleAt) return card('Last cycle', '—', 'none yet');
    return card('Last cycle', secs(a.lastCycleMs), `${a.lastWarmed} recomputed · ${a.lastCached} cached`);
}

// Anonymous viewers get the problems' levels only: the texts name files and errors on the server.
function healthProblems(d) {
    const problems = d.healthProblems || [];
    if (problems.every(p => p.text)) return problems;
    const n = problems.length;
    return [{ level: problems.some(p => p.level === 'error') ? 'error' : 'warn',
        text: `${n} problem${n === 1 ? '' : 's'} — log in on the main page to see ${n === 1 ? 'it' : 'them'}` }];
}

// The dot shows the worst of the problems and the log, so the tooltip names both.
function healthTitle(d, problems) {
    const reasons = problems.map(p => p.text);
    if (d.logHealth === 'error') reasons.push('error logged ' + agoShort(d.log.lastErrorAt));
    else if (d.logHealth === 'warn') reasons.push('warning logged ' + agoShort(d.log.lastWarningAt));
    return reasons.length ? reasons.join('; ') : 'healthy';
}

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
        card('Uptime', fmtDur(d.uptimeSeconds)),
        card('CPU', j.systemCpuPct == null || j.systemCpuPct < 0 ? '—' : j.systemCpuPct + '%',
            j.processCpuPct >= 0 ? `${j.processCpuPct}% this app` : 'host',
            j.systemCpuPct >= 0 ? sev(j.systemCpuPct, 50, 80) : ''),
        card('Load avg', j.loadAverage == null || j.loadAverage < 0 ? '—' : j.loadAverage,
            `on ${j.cpus} cpu`,
            j.loadAverage >= 0 ? sev(j.loadAverage / j.cpus, 0.7, 1.001) : ''),
        card('Heap', j.heapUsedMb, `/ ${j.heapMaxMb} MB` + (j.heapPeakMb > 0 ? ` · old-gen peak ${j.heapPeakMb} MB` : ''),
            j.heapMaxMb ? sev(100 * j.heapUsedMb / j.heapMaxMb, 60, 85) : ''),
        card('Process memory', j.rssMb >= 0 ? j.rssMb : '—', j.rssMb >= 0
            ? `MB resident · peak ${j.rssPeakMb}` + (j.hostMemMb > 0 ? ` · host ${j.hostMemMb} MB` : '') : 'not measured here'),
        card('Threads', j.threads, `· ${j.cpus} cpu`),
        persistenceCard(d.persistence),
    ].join('');

    $('appCards').innerHTML = [
        card('Analyze (1h)', az ? `${az.avgLatencyMs}` : '—',
            az ? `ms avg · ${az.maxLatencyMs} max · ${az.total} calls` : 'no requests',
            az ? sev(az.avgLatencyMs, 500, 2000) : ''),
        card('Open PRs', d.app.openPrs),
    ].join('');

    const w = d.watcher || {};
    const agoW = ms => !ms ? 'never' : Math.round((Date.now() - ms) / 1000) < 90
        ? Math.round((Date.now() - ms) / 1000) + 's ago' : Math.round((Date.now() - ms) / 60000) + 'm ago';
    $('watcherCards').innerHTML = [
        card('Tracked builds', w.tracked ?? 0, `${w.chains ?? 0} chains · ${w.suites ?? 0} suites`,
            (w.tracked ?? 0) > 0 ? 'ok' : ''),
        card('Last refresh', agoW(w.lastRefreshAt), w.lastRefreshMs ? w.lastRefreshMs + 'ms' : '',
            w.lastRefreshAt && Date.now() - w.lastRefreshAt < 60000 ? 'ok' : w.lastRefreshAt ? 'warn' : ''),
        card('Chain finishes', w.chainFinishes ?? 0, 'since start'),
        card('Auto-visa armed', w.autoVisaArmed ?? 0,
            `${w.autoVisaPosted ?? 0} posted${w.autoVisaLastPostedAt ? ' · last ' + agoW(w.autoVisaLastPostedAt) : ''}`),
        card('Standing visas', w.standingEnrolled ?? 0,
            `users · ${w.standingPosted ?? 0} posted · sweep ${agoW(w.standingLastSweepAt)}${w.standingLastSweepMs ? ' (' + w.standingLastSweepMs + 'ms)' : ''}`),
        card('PR commands', w.prCommands ?? 0,
            `/run-all handled · poll ${agoW(w.prCommandsLastPollAt)}`),
    ].join('');

    $('warmerCards').innerHTML = [
        warmerCard(d.app),
        startupCard(d.app),
        lastCycleCard(d.app),
        card('Pooled tokens', d.app.pooledTokens, d.app.pooledTokens ? '' : 'warmer paused — log in',
            d.app.pooledTokens ? 'ok' : 'warn'),
        card('Results cached', d.app.resultsCached),
        card('History cached', d.app.historyCached, 'tests'),
    ].join('');

    $('tcCards').innerHTML = [
        card('TeamCity (1h)', t.total.toLocaleString(),
            `${tcRate.toFixed(1)}% ok · ${tc.sinceStart.toLocaleString()} total`,
            t.total ? (tcRate >= 99 ? 'ok' : tcRate >= 95 ? 'warn' : 'bad') : ''),
        card('TC latency (1h)', `${t.avgLatencyMs}`, `ms avg · ${t.maxLatencyMs} max`,
            t.total ? sev(t.avgLatencyMs, 500, 2000) : ''),
    ].join('');

    // Categories under users' own PATs; everything else runs under the app (server) token.
    const PAT_CATS = new Set(['user', 'prComment', 'prCommentEdit', 'react']);
    const ghApp = {}, ghPat = {};
    let appCalls = 0, appFails = 0;
    for (const [k, v] of Object.entries(gh.byCategory || {})) {
        if (PAT_CATS.has(k)) { ghPat[k] = v; }
        else { ghApp[k] = v; appCalls += v.total; appFails += v.fail; }
    }
    $('ghCards').innerHTML = [
        card('GitHub (1h)', g.total.toLocaleString(), `${g.fail} failed · ${gh.sinceStart.toLocaleString()} total`,
            g.total ? (g.fail === 0 ? 'ok' : g.fail <= 5 ? 'warn' : 'bad') : ''),
        card('App token (1h)', appCalls.toLocaleString(), `${appFails} failed · server-side calls`,
            appCalls ? (appFails === 0 ? 'ok' : 'warn') : ''),
        appAccountCard(d.app.githubAccount),
        card('Stars', d.app.stars < 0 ? '—' : d.app.stars),
        card('GitHub API', d.app.githubRate && d.app.githubRate.limit ? d.app.githubRate.remaining : '—',
            d.app.githubRate && d.app.githubRate.limit ? `/ ${d.app.githubRate.limit} left` : '',
            d.app.githubRate && d.app.githubRate.limit
                ? (100 * d.app.githubRate.remaining / d.app.githubRate.limit > 50 ? 'ok'
                    : 100 * d.app.githubRate.remaining / d.app.githubRate.limit > 10 ? 'warn' : 'bad') : ''),
    ].join('');

    const ji = d.jira || { lastHour: { total: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 }, sinceStart: 0 };
    const jh = ji.lastHour;
    $('jiraCards').innerHTML = [
        card('JIRA (1h)', jh.total.toLocaleString(), `${jh.fail} failed · ${ji.sinceStart.toLocaleString()} total`,
            jh.total ? (jh.fail === 0 ? 'ok' : 'warn') : ''),
        card('JIRA latency (1h)', jh.total ? `${jh.avgLatencyMs}` : '—',
            jh.total ? `ms avg · ${jh.maxLatencyMs} max` : 'no calls',
            jh.total ? sev(jh.avgLatencyMs, 1000, 3000) : ''),
    ].join('');

    $('logCards').innerHTML = [
        card('Log issues', d.log.errors,
            `${d.log.warnings} warn · ${d.log.clientMistakes} bad requests · since start`,
            d.health === 'error' ? 'bad' : d.health === 'warn' ? 'warn' : 'ok'),
    ].join('');

    const codes = m => Object.entries(m || {}).map(([s, n]) => `<code>${s === '0' ? 'network' : s}</code>×${n}`).join(', ');
    const hour = codes(tc.byStatusHour), total = codes(tc.byStatus);
    $('tcByStatus').innerHTML =
        'Failures by status <span class="muted">· HTTP code × times · last hour</span>: ' + (hour || 'none')
        + (total ? ` <span class="muted">· since app start: ${total}</span>` : '');

    renderCats($('tcCats'), tc.byCategory || {});
    renderCats($('ghCatsApp'), ghApp);
    renderCats($('ghCatsPat'), ghPat);
    renderCats($('jiraCats'), ji.byCategory || {});
    renderCats($('httpCats'), (d.http && d.http.byCategory) || {});
    renderLog($('logList'), d);
    $('configList').innerHTML = configRows(d);
    renderAdmin(d.admin);

    drawStacked($('tcStack'), $('stackLegend'), tc.perMinuteByCategory || {});
    $('updated').textContent = 'updated ' + new Date().toLocaleTimeString();
}

// Problems outlive restarts, so older ones carry their day.
function logTime(t) {
    const d = new Date(t);
    if (d.toDateString() === new Date().toDateString()) return d.toLocaleTimeString();
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' '
        + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

// The server's start time, not the browser's clock, sets this run's problems apart from earlier ones.
function renderLog(el, d) {
    const log = d.log || { recent: [] }, startedAt = d.startedAt;
    const recent = log.recent || [];
    if (!d.signedIn && (log.errors || log.warnings || log.clientMistakes || log.lastErrorAt || log.lastWarningAt)) {
        el.innerHTML = '<div class="muted">log in on the main page to see the messages</div>';
        return;
    }
    if (!recent.length) { el.innerHTML = '<div class="muted">no warnings or errors logged</div>'; return; }
    const restart = recent.findIndex(e => e.t < startedAt);
    el.innerHTML = recent.map((e, i) => (i === restart
        ? `<div class="logsep">— service started ${esc(logTime(startedAt))} —</div>` : '')
        + `<div class="logrow${e.client ? ' client' : ''}"${e.client
        ? ' title="a bad request turned away — does not affect health"' : ''}>
        <span class="logtime">${esc(logTime(e.t))}</span>
        <span class="loglevel lvl-${e.level.toLowerCase()}">${e.level}</span>
        <span class="loglogger" title="${esc(e.logger)}">${esc(e.logger)}</span>
        <span class="logmsg">${esc(e.message)}</span>
    </div>`).join('');
}

function renderAdmin(a) {
    $('restartBtn').hidden = $('flushBtn').hidden = !(a && a.canAdminister);
    const last = (a && a.last) || {};
    const restart = [last.restart && ['restart', last.restart], last.update && ['update', last.update]]
        .filter(Boolean).sort((x, y) => y[1].at - x[1].at)[0];
    $('restartLast').textContent = restart ? `last ${restart[0]}: ${restart[1].by} · ${agoShort(restart[1].at)}` : '';
    $('flushLast').textContent = last.flush ? `last flush: ${last.flush.by} · ${agoShort(last.flush.at)}` : '';
}

async function errorText(r) {
    const body = await r.json().catch(() => null);
    return (body && body.error) || 'HTTP ' + r.status;
}

function renderCats(el, byCategory) {
    const entries = Object.entries(byCategory);
    if (!entries.length) { el.innerHTML = '<div class="muted">no calls yet</div>'; return; }
    const max = Math.max(1, ...entries.map(([, s]) => s.total));
    el.innerHTML = entries.map(([name, s]) => {
        const pct = 100 * s.total / max;
        const fail = s.fail ? ` · <span class="rate-bad">${s.fail} failed</span>` : '';
        return `<div class="catrow">
            <div class="catname">${name}</div>
            <div class="catbar"><div class="catfill" style="width:${pct.toFixed(1)}%"></div></div>
            <div class="catval">${s.total.toLocaleString()} <small>${s.avgLatencyMs}ms${fail}</small></div>
        </div>`;
    }).join('');
}

const CAT_COLORS =['#1565c0', '#2e7d32', '#c0392b', '#e0a000', '#6a1b9a', '#00838f',
    '#8d6e63', '#ad1457', '#7cb342', '#f4511e', '#0277bd', '#9e9d24'];

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
    for (let k = 0; k <= 4; k++) {
        const y = pad.t + ch - (ch * k / 4);
        ctx.strokeStyle = 'rgba(128,128,128,0.18)'; ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(cssW - pad.r, y); ctx.stroke();
        ctx.fillStyle = '#999'; ctx.fillText(Math.round(max * k / 4), 2, y + 3);
    }

    const bw = cw / N;
    for (let i = 0; i < N; i++) {
        let yTop = pad.t + ch;
        cats.forEach((c, ci) => {
            const v = (byCat[c] || [])[i] || 0;
            if (!v) return;
            const h = ch * v / max;
            ctx.fillStyle = CAT_COLORS[ci % CAT_COLORS.length];
            ctx.fillRect(pad.l + i * bw + 0.5, yTop - h, Math.max(1, bw - 1), h);
            yTop -= h;
        });
    }
    ctx.fillStyle = '#999'; ctx.fillText('-60m', pad.l, cssH - 4); ctx.fillText('now', cssW - pad.r - 18, cssH - 4);
    legendEl.innerHTML = cats.map((c, ci) =>
        `<span><span class="dot" style="background:${CAT_COLORS[ci % CAT_COLORS.length]}"></span>${c}</span>`).join('');
}

let last = null;
async function tick() {
    try {
        const r = await fetch('/api/status', { cache: 'no-store' });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        last = await r.json();
        $('err').textContent = '';
        render(last);
    } catch (e) {
        // A failed poll with data already on screen is usually a redeploy/restart blip: stay calm,
        // keep the cards, and let the next poll clear this. Alarm only when there's nothing to show.
        $('err').textContent = last ? 'connection lost — retrying…' : 'status unavailable: ' + e.message;
    }
}

window.addEventListener('resize', () => {
    if (!last) return;
    drawStacked($('tcStack'), $('stackLegend'), last.teamcity.perMinuteByCategory || {});
});
// Users tab: active (last 15 min) vs everyone seen. The endpoint is auth-guarded, so
// anonymous viewers get a hint instead of names.
const ACTIVE_MS = 15 * 60 * 1000;
function agoMs(ms) {
    const s = (Date.now() - ms) / 1000;
    return s < 90 ? Math.round(s) + 's ago' : s < 5400 ? Math.round(s / 60) + 'm ago'
        : s < 129600 ? Math.round(s / 3600) + 'h ago' : Math.round(s / 86400) + 'd ago';
}
async function loadUsers() {
    const row = (u, verb) => `<div class="catrow"><span class="catname">${u.name.replace(/[&<>"]/g, '')}</span>`
        + `<span class="catval">${verb} ${agoMs(u.lastSeen)} · ${u.logins} login${u.logins === 1 ? '' : 's'} · first seen ${agoMs(u.firstSeen)}</span></div>`;
    try {
        const r = await fetch('/api/users', { cache: 'no-store' });
        if (r.status === 401 || r.status === 403) {
            $('usersActive').innerHTML = $('usersSeen').innerHTML = r.status === 401
                ? '<div class="muted">log in on the main page to see who is here</div>'
                : '<div class="muted">only the operator can see who uses the service</div>';
            return;
        }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const list = await r.json();
        const active = list.filter(u => Date.now() - u.lastSeen <= ACTIVE_MS);
        const seen = list.filter(u => Date.now() - u.lastSeen > ACTIVE_MS);
        $('usersActive').innerHTML = active.map(u => row(u, 'last active')).join('') || '<div class="muted">nobody right now</div>';
        $('usersSeen').innerHTML = seen.map(u => row(u, 'last seen')).join('') || '<div class="muted">nobody else yet</div>';
    } catch (e) {
        $('usersActive').innerHTML = '<div class="muted">could not load users — try again in a moment</div>';
        $('usersSeen').innerHTML = '';
    }
}
let usersTimer = null;
function setStatusTab(users) {
    document.body.classList.toggle('users-mode', users);
    $('tabStatus').classList.toggle('on', !users);
    $('tabUsers').classList.toggle('on', users);
    if (usersTimer) { clearInterval(usersTimer); usersTimer = null; }
    if (users) { loadUsers(); usersTimer = setInterval(loadUsers, 15000); }
}
$('tabStatus').addEventListener('click', () => setStatusTab(false));
$('tabUsers').addEventListener('click', () => setStatusTab(true));

$('restartBtn').addEventListener('click', async () => {
    if (!confirm('Restart the service?\n\nIt will be unavailable for ~10 seconds; in-flight analyses are dropped (caches are saved).')) return;
    const btn = $('restartBtn'), msg = $('restartMsg');
    btn.disabled = true;
    msg.textContent = 'restarting…';
    let accepted = false;
    try {
        const r = await fetch('/api/restart', { method: 'POST' });
        if (r.status === 401) { msg.textContent = 'log in on the main page first'; btn.disabled = false; return; }
        if (r.status === 403 || r.status === 429) { msg.textContent = await errorText(r); btn.disabled = false; return; }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        accepted = true;
    } catch (e) { msg.textContent = 'restart request failed'; btn.disabled = false; }
    if (!accepted) return;
    const t0 = Date.now();
    const timer = setInterval(async () => {
        try {
            const v = await fetch('/api/version', { cache: 'no-store' });
            if (v.ok) { clearInterval(timer); msg.textContent = 'back up'; setTimeout(() => location.reload(), 800); return; }
        } catch (e) { /* still down */ }
        if (Date.now() - t0 > 120000) { clearInterval(timer); msg.textContent = 'still down after 2 minutes — check the server'; }
    }, 2000);
});

$('flushBtn').addEventListener('click', async () => {
    if (!confirm('Clear cached analysis results and per-test history?\nThe next analysis will recompute from scratch (extra TeamCity load).')) return;
    const btn = $('flushBtn'), msg = $('flushMsg');
    btn.disabled = true; msg.textContent = 'flushing…';
    try {
        const r = await fetch('/api/flush-caches', { method: 'POST', cache: 'no-store' });
        if (r.status === 401) { msg.textContent = 'log in on the main page to flush'; return; }
        if (r.status === 403 || r.status === 429) { msg.textContent = await errorText(r); return; }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        msg.textContent = `cleared ${c.results} result(s), ${c.history} history entr${c.history === 1 ? 'y' : 'ies'}`;
        tick();
    } catch (e) {
        msg.textContent = 'flush failed: ' + e.message;
    } finally {
        btn.disabled = false;
    }
});

wireThemeToggle();

tick();
setInterval(tick, 5000);
