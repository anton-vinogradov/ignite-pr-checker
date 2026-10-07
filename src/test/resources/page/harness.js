// Runs a page's main script in node against a fake DOM, a fake clock and a routed fetch, then runs a
// scenario and prints what it reports as JSON. Usage: node harness.js <page.html> <scenario.js>.
// A scenario is the body of an async function receiving `page` (see below) and `report(obj)`.
'use strict';

const fs = require('fs');
const vm = require('vm');

const [, , pagePath, scenarioPath] = process.argv;
const html = fs.readFileSync(pagePath, 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const mainScript = scripts[scripts.length - 1];

function stripTags(s) {
    return String(s).replace(/<[^>]*>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');
}

// The classes each element starts with, so "is it hidden?" answers as the real page would.
const initialClasses = new Map();
for (const m of html.matchAll(/<[a-zA-Z0-9]+\b([^>]*)>/g)) {
    const id = /\bid="([^"]+)"/.exec(m[1]);
    const cls = /\bclass="([^"]*)"/.exec(m[1]);
    if (id) initialClasses.set(id[1], cls ? cls[1].split(/\s+/).filter(Boolean) : []);
}

// As in a browser, whatever goes into an element's dataset comes back as a string.
function fakeDataset() {
    return new Proxy({}, { set(target, key, value) { target[key] = String(value); return true; } });
}

function fakeElement(id) {
    const classes = new Set(initialClasses.get(id) || []);
    const el = {
        id, dataset: fakeDataset(), style: {}, attributes: {}, listeners: {}, children: [],
        value: '', disabled: false, checked: false, title: '', href: '', open: false, scrollTop: 0,
        htmlWrites: 0, classLog: [], _html: '', _text: '',
        get innerHTML() { return this._html; },
        set innerHTML(v) { this._html = String(v); this._text = stripTags(v); this.htmlWrites++; },
        get textContent() { return this._text; },
        set textContent(v) { this._text = String(v ?? ''); this._html = this._text; },
        classList: {
            add(...cs) { for (const c of cs) { classes.add(c); el.classLog.push('+' + c); } },
            remove(...cs) { for (const c of cs) { classes.delete(c); el.classLog.push('-' + c); } },
            toggle(c, force) {
                const on = force === undefined ? !classes.has(c) : !!force;
                if (on) this.add(c); else this.remove(c);
                return on;
            },
            contains: c => classes.has(c),
        },
        addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
        removeEventListener() {},
        querySelectorAll: () => [],
        querySelector: () => null,
        closest: () => null,
        contains: () => false,
        focus() { doc.activeElement = el; },
        blur() {}, select() {}, remove() {}, appendChild() {},
        setAttribute(k, v) { this.attributes[k] = String(v); },
        getAttribute(k) { return k in this.attributes ? this.attributes[k] : null; },
        getBoundingClientRect: () => ({ left: 0, top: 0, width: 0, height: 0 }),
    };
    return el;
}

const els = new Map();
const byId = id => {
    if (!els.has(id)) els.set(id, fakeElement(id));
    return els.get(id);
};
const docListeners = {};
const winListeners = {};
const doc = {
    hidden: false,
    activeElement: null,
    getElementById: byId,
    querySelector: sel => byId('query:' + sel),
    querySelectorAll: () => [],
    addEventListener(type, fn) { (docListeners[type] ||= []).push(fn); },
    createElement: () => fakeElement(''),
    documentElement: fakeElement('html'),
    body: fakeElement('body'),
};

let clock = Date.parse('2026-10-07T12:00:00Z');
let nextTimer = 1;
const timers = new Map();
function addTimer(fn, ms, repeat) {
    const id = nextTimer++;
    timers.set(id, { id, fn, ms: ms || 0, at: clock + (ms || 0), repeat });
    return id;
}

const routes = [];
const fetches = [];
async function fakeFetch(url, opts = {}) {
    const method = (opts.method || 'GET').toUpperCase();
    fetches.push({ url, method });
    const route = routes.filter(r => url.startsWith(r.prefix) && (!r.method || r.method === method))
        .sort((a, b) => b.prefix.length - a.prefix.length)[0];
    let answer = route ? route.answer : { status: 404, body: {} };
    if (typeof answer === 'function') answer = answer(url, method);
    const status = answer.status || 200;
    const body = JSON.parse(JSON.stringify(answer.body === undefined ? {} : answer.body));
    return {
        ok: status >= 200 && status < 300, status,
        json: async () => body,
        headers: { get: () => null },
    };
}

const storage = new Map();
const location = { search: '', pathname: '/', reload() {} };
const pushed = [];
const ctx = {
    document: doc,
    console,
    location,
    history: {
        pushState(state, title, url) {
            pushed.push(url);
            const q = String(url).indexOf('?');
            location.search = q >= 0 ? String(url).slice(q) : '';
        },
        replaceState() {},
    },
    localStorage: {
        getItem: k => (storage.has(k) ? storage.get(k) : null),
        setItem: (k, v) => storage.set(k, String(v)),
        removeItem: k => storage.delete(k),
    },
    navigator: { clipboard: { writeText: async () => {} } },
    matchMedia: q => ({ matches: page.narrow && /max-width/.test(q), addEventListener() {}, addListener() {} }),
    fetch: fakeFetch,
    setTimeout: (fn, ms) => addTimer(fn, ms, false),
    setInterval: (fn, ms) => addTimer(fn, ms, true),
    clearTimeout: id => timers.delete(id),
    clearInterval: id => timers.delete(id),
    confirm: () => true,
    alert: () => {},
    innerWidth: 1280,
    addEventListener(type, fn) { (winListeners[type] ||= []).push(fn); },
    URLSearchParams,
};
ctx.window = ctx;
vm.createContext(ctx);
vm.runInContext('Date.now = () => __clock();', Object.assign(ctx, { __clock: () => clock }));

async function settle() {
    for (let i = 0; i < 30; i++)
        await new Promise(r => setImmediate(r));
}

const page = {
    narrow: false,
    el: byId,
    run: code => vm.runInContext(code, ctx),
    route(prefix, answer, method) {
        const i = routes.findIndex(r => r.prefix === prefix && r.method === method);
        if (i >= 0) routes.splice(i, 1);
        routes.push({ prefix, answer, method });
    },
    fetches: prefix => fetches.filter(f => f.url.startsWith(prefix)),
    pushed,
    location,
    settle,
    timers: () => [...timers.values()].map(t => ({ ms: t.ms, repeat: t.repeat, in: t.at - clock })),
    now: () => clock,
    async load(search) {
        location.search = search || '';
        vm.runInContext(mainScript, ctx);
        await settle();
    },
    // Moves the clock forward, firing every timer that falls due on the way.
    async tick(ms) {
        const until = clock + ms;
        for (;;) {
            const due = [...timers.values()].filter(t => t.at <= until).sort((a, b) => a.at - b.at)[0];
            if (!due) break;
            clock = Math.max(clock, due.at);
            if (due.repeat) due.at = clock + Math.max(1, due.ms);
            else timers.delete(due.id);
            due.fn();
            await settle();
        }
        clock = until;
    },
    async setHidden(hidden) {
        doc.hidden = hidden;
        for (const fn of docListeners.visibilitychange || []) fn();
        await settle();
    },
    async popstate() {
        for (const fn of winListeners.popstate || []) fn();
        await settle();
    },
    async keydown(key, target) {
        doc.activeElement = target || doc.body;
        const e = { key, target: doc.activeElement, preventDefault() {}, ctrlKey: false, metaKey: false, altKey: false };
        for (const fn of docListeners.keydown || []) fn(e);
        await settle();
    },
};

(async () => {
    const out = {};
    const report = obj => Object.assign(out, obj);
    const scenario = fs.readFileSync(scenarioPath, 'utf8');
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    await new AsyncFunction('page', 'report', scenario)(page, report);
    process.stdout.write(JSON.stringify(out));
})().catch(e => {
    console.error(e && e.stack || e);
    process.exit(1);
});
