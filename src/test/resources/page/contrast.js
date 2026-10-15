// Works out, from a page's own CSS, the contrast of text on its background in every theme, and reports the
// pairs under 4.5:1 (WCAG AA for body text). Usage: node contrast.js <page.html>; prints JSON
// { pairs, failures: [{ theme, element, color, background, ratio }] }.
//
// The stylesheets the page links (in its own directory) come first, then its <style>, as the browser reads them.
// Every selector of every rule describes an element to check: ".tabs .act.on" is a selected tab inside .tabs,
// "button:hover" a hovered button. Each such element is also checked hovered, and with its ::before and ::after
// when they hold text (the Terminal brackets). For each of them the rules are cascaded as a browser would
// (specificity, then order; a theme's rules carry its html[data-theme] prefix), the text colour is inherited down
// the chain, and the background is composited from the page's down to the element's.
'use strict';

const fs = require('fs');
const path = require('path');

const MIN_RATIO = 4.5;

// Boxes that carry no text: bars, dots and strips painted with a colour that no letter sits on.
const NO_TEXT = ['.trend a', '.trend a.clean', '.run-p', '.run-f', '.catfill', '.catbar', '.hdot', '.hdot.ok',
    '.hdot.warn', '.hdot.error', '.dot', '.resizer', '.resizer.dragging', '.star-ico'];

// A class-only selector says nothing about the tag; these classes are always on a button or a link, whose own rules
// (the neutral fill, the accent) then take part.
const TAG_OF = { button: ['act', 'why', 'suite-rerun', 'mini-btn', 'linklike', 'quiet', 'cancel', 'refresh', 'copy',
    'pane-toggle', 'pane-expand', 'settings-btn', 'theme-sel'],
    a: ['ext', 'help', 'live-tag', 'brand', 'star-btn', 'live', 'pr-link', 'update-notes', 'pr-open-any', 'update-btn'] };

// Text that sits on a fill no rule of its own names: the badges on the selected or hovered row of the PR list, the
// copy button on the failure panel, links and muted text inside a card or the login note. Each fill is paired with
// every rule of the page whose selector starts with one of its contents, so a new badge is checked on both rows.
const INSIDE = {
    '.pr-list li.sel, .pr-list li:hover': ['.pr-link', '.pr-open-any', '.pr-num', '.pr-ttl', '.pr-badge', '.my-tag'],
    '.login-pr': ['p', '.muted', 'a'],
    '.details': ['pre', '.copy'],
    '.jira-panel': ['.muted', 'a'],
    '.pane-head': ['a.help', '.pr-legend'],
    '.card': ['.muted', 'a', '.reason', '.tag-doubt', '.tag-flaky', '.live'],
    '.section': ['.catname', '.muted', 'a', '.lbprs a', '.flake', '.lbsuite'],
};

const pagePath = process.argv[2];
const html = fs.readFileSync(pagePath, 'utf8');
const sheets = [...html.matchAll(/<link rel="stylesheet" href="\/([^"]+)">/g)]
    .map(m => fs.readFileSync(path.join(path.dirname(pagePath), m[1]), 'utf8'));
for (const m of html.matchAll(/<style>([\s\S]*?)<\/style>/g)) sheets.push(m[1]);

// --- CSS, parsed into nested blocks: { prelude, decls: [[prop, value]], children: [block] } ---

function parseCss(text) {
    const src = text.replace(/\/\*[\s\S]*?\*\//g, '');
    const root = { prelude: '', decls: [], children: [] };
    const stack = [root];
    let buf = '', quote = null;
    for (const ch of src) {
        if (quote) { buf += ch; if (ch === quote) quote = null; continue; }
        if (ch === '"' || ch === "'") { quote = ch; buf += ch; continue; }
        const top = stack[stack.length - 1];
        if (ch === '{') { const block = { prelude: buf.trim(), decls: [], children: [] }; top.children.push(block); stack.push(block); buf = ''; }
        else if (ch === ';') { pushDecl(top, buf); buf = ''; }
        else if (ch === '}') { pushDecl(top, buf); buf = ''; stack.pop(); }
        else buf += ch;
    }
    return root;
}

function pushDecl(block, text) {
    const i = text.indexOf(':');
    if (i < 0 || !text.trim()) return;
    block.decls.push([text.slice(0, i).trim(), text.slice(i + 1).replace(/!important/, '').trim()]);
}

const THEME = /^html\[data-theme="([a-z]+)"\]/;

// Flattens the sheets into rules of { theme | null, selector, decls, order } and the tokens of each theme.
function flatten(sheetTexts) {
    const rules = [], tokens = { light: {} }, themes = new Set(['light']);
    let order = 0;
    const addRule = (theme, selector, decls) => {
        for (const one of selector.split(/,(?![^(]*\))/).map(s => s.trim()).filter(Boolean))
            rules.push({ theme, selector: one, decls, order: order++ });
    };
    const addTokens = (theme, decls) => {
        tokens[theme] ||= {};
        for (const [p, v] of decls) if (p.startsWith('--')) tokens[theme][p] = v;
    };
    for (const text of sheetTexts) {
        for (const block of parseCss(text).children) {
            const p = block.prelude;
            if (p.startsWith('@')) continue; // narrow-screen layout, keyframes, reduced motion: no colours of their own
            const themed = THEME.exec(p);
            if (p === ':root') { addTokens('light', block.decls); continue; }
            if (themed) {
                themes.add(themed[1]);
                addTokens(themed[1], block.decls);
                if (block.decls.some(([k]) => !k.startsWith('--'))) addRule(themed[1], p, block.decls);
                for (const child of block.children)
                    addRule(themed[1], child.prelude.split(/,(?![^(]*\))/).map(s => p + ' ' + s.trim()).join(', '), child.decls);
                continue;
            }
            addRule(null, p, block.decls);
            for (const child of block.children)
                addRule(null, child.prelude.split(/,(?![^(]*\))/).map(s => p + ' ' + s.trim()).join(', '), child.decls);
        }
    }
    for (const t of themes) tokens[t] = Object.assign({}, tokens.light, tokens[t] || {});
    return { rules, tokens, themes: [...themes] };
}

// --- selectors: compounds joined by ' ' (descendant) or '>' (child) ---

function parseCompound(text) {
    const c = { tag: null, id: null, classes: [], attrs: {}, states: [], nots: [], specificity: 0 };
    const re = /\*|[a-zA-Z][\w-]*|#[\w-]+|\.[\w-]+|\[([\w-]+)(?:="([^"]*)")?\]|::?[\w-]+(?:\(([^)]*)\))?/g;
    let m;
    while ((m = re.exec(text))) {
        const t = m[0];
        if (t === '*') continue;
        else if (t[0] === '#') { c.id = t.slice(1); c.specificity += 100; }
        else if (t[0] === '.') { c.classes.push(t.slice(1)); c.specificity += 10; }
        else if (t[0] === '[') { c.attrs[m[1]] = m[2] === undefined ? null : m[2]; c.specificity += 10; }
        else if (t.startsWith('::')) { c.pseudo = t.slice(2); }
        else if (t[0] === ':') {
            const name = t.slice(1).replace(/\(.*$/, '');
            if (name === 'not') { const inner = parseCompound(m[3]); c.nots.push(inner); c.specificity += inner.specificity; }
            else if (name === 'root') { c.tag = 'html'; c.specificity += 10; }
            else { c.states.push(name); c.specificity += 10; }
        }
        else { c.tag = t.toLowerCase(); c.specificity += 1; }
    }
    return c;
}

// A pseudo-element is read as a child of its element: "a::before" is the compound "a" and then the compound of the
// ::before box, which only a ::before rule matches.
function parseSelector(text) {
    const parts = text.replace(/\s*>\s*/g, ' > ').trim().split(/\s+/);
    const compounds = [];
    let child = false;
    for (const part of parts) {
        if (part === '>') { child = true; continue; }
        const c = parseCompound(part);
        c.child = child;
        child = false;
        compounds.push(c);
        if (c.pseudo) {
            compounds.push(pseudoCompound(c.pseudo));
            delete c.pseudo;
        }
    }
    return compounds;
}

function pseudoCompound(name) {
    return { tag: null, id: null, classes: [], attrs: {}, states: [], nots: [], specificity: 1, pseudo: name, child: true };
}

function compoundMatches(c, el) {
    if ((c.pseudo || null) !== (el.pseudo || null)) return false;
    if (c.tag && c.tag !== el.tag) return false;
    if (c.id && c.id !== el.id) return false;
    if (!c.classes.every(k => el.classes.includes(k))) return false;
    for (const [k, v] of Object.entries(c.attrs)) {
        if (!(k in el.attrs)) return false;
        if (v !== null && el.attrs[k] !== v) return false;
    }
    if (!c.states.every(s => el.states.includes(s))) return false;
    return !c.nots.some(n => compoundMatches(n, el));
}

// Right-to-left, as a browser matches: the last compound is the element, the others its ancestors.
function matches(compounds, chain, at, ci) {
    if (ci === undefined) ci = compounds.length - 1;
    if (!compoundMatches(compounds[ci], chain[at])) return false;
    if (ci === 0) return true;
    if (compounds[ci].child) return at > 0 && matches(compounds, chain, at - 1, ci - 1);
    for (let a = at - 1; a >= 0; a--)
        if (matches(compounds, chain, a, ci - 1)) return true;
    return false;
}

// --- colours ---

function parseColor(value) {
    const v = value.trim().toLowerCase();
    if (v === 'transparent' || v === 'none') return { r: 0, g: 0, b: 0, a: 0 };
    let m = /^#([0-9a-f]{3,8})$/.exec(v);
    if (m) {
        let h = m[1];
        if (h.length <= 4) h = [...h].map(x => x + x).join('');
        const n = parseInt(h.slice(0, 6), 16);
        return { r: n >> 16, g: (n >> 8) & 255, b: n & 255, a: h.length === 8 ? parseInt(h.slice(6), 16) / 255 : 1 };
    }
    m = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(v);
    if (m) return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] };
    const named = { white: '#fff', black: '#000' }[v];
    return named ? parseColor(named) : null;
}

function over(top, bottom) {
    const a = top.a;
    return { r: top.r * a + bottom.r * (1 - a), g: top.g * a + bottom.g * (1 - a), b: top.b * a + bottom.b * (1 - a), a: 1 };
}

function luminance({ r, g, b }) {
    const lin = c => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function ratio(fg, bg) {
    const l1 = luminance(fg), l2 = luminance(bg);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

function hex(c) {
    return '#' + [c.r, c.g, c.b].map(x => Math.round(x).toString(16).padStart(2, '0')).join('');
}

// The colour in a background shorthand, if any.
function backgroundColor(value) {
    const m = /rgba?\([^)]*\)|#[0-9a-f]{3,8}\b|\btransparent\b|\bnone\b/i.exec(value);
    return m ? parseColor(m[0]) : null;
}

function resolveVars(value, tokens) {
    let v = value, guard = 0;
    while (/var\(/.test(v) && guard++ < 10)
        v = v.replace(/var\((--[\w-]+)(?:,\s*([^()]*))?\)/g, (all, name, fallback) => tokens[name] ?? fallback ?? '');
    return v;
}

// --- the cascade for one element chain ---

function cascade(rules, theme, chain, at, tokens) {
    const hits = [];
    for (const r of rules) {
        if (r.theme && r.theme !== theme) continue;
        const compounds = r.compounds;
        if (matches(compounds, chain, at)) hits.push({ r, spec: compounds.reduce((s, c) => s + c.specificity, 0) });
    }
    hits.sort((x, y) => x.spec - y.spec || x.r.order - y.r.order);
    const style = {};
    for (const { r } of hits)
        for (const [p, v] of r.decls) style[p] = resolveVars(v, tokens);
    return style;
}

function describe(chain) {
    let text = '';
    for (const el of chain.slice(2)) {
        if (el.pseudo) { text += '::' + el.pseudo; continue; }
        text += (text ? ' ' : '') + (el.tag || '') + (el.id ? '#' + el.id : '') + el.classes.map(c => '.' + c).join('')
            + Object.entries(el.attrs).map(([k, v]) => v === null ? `[${k}]` : `[${k}="${v}"]`).join('')
            + el.states.map(s => ':' + s).join('');
    }
    return text;
}

const { rules, tokens, themes } = flatten(sheets);
for (const r of rules) r.compounds = parseSelector(r.selector);

function tagOf(classes) {
    for (const [tag, known] of Object.entries(TAG_OF))
        if (classes.some(c => known.includes(c))) return tag;
    return null;
}

// The INSIDE pairs: each fill with every selector of the page that starts with one of the fill's contents.
function compositesOf(rulesOfPage) {
    const own = [...new Set(rulesOfPage.map(r => r.selector.replace(THEME, '').trim()))];
    const composites = [];
    for (const [fills, contents] of Object.entries(INSIDE))
        for (const fill of fills.split(',').map(s => s.trim()))
            for (const s of own)
                if (contents.some(c => s.startsWith(c) && !/[\w-]/.test(s.charAt(c.length))))
                    composites.push(fill + ' ' + s);
    return composites;
}

// Every selector names an element to check, read as a chain under html > body; "a::before" names the element a.
function elementsOf(rulesOfPage) {
    const seen = new Map();
    const selectors = rulesOfPage.map(r => ({ selector: r.selector, compounds: r.compounds }))
        .concat(compositesOf(rulesOfPage).map(s => ({ selector: s, compounds: parseSelector(s) })));
    for (const r of selectors) {
        if (/[+~]|:has\(/.test(r.selector)) continue;
        if (NO_TEXT.includes(r.selector.replace(THEME, '').replace(/::[\w-]+/g, '').trim())) continue;
        let compounds = r.compounds.filter(c => !c.pseudo)
            .map(c => ({ tag: c.tag || tagOf(c.classes), id: c.id, classes: [...c.classes], attrs: { ...c.attrs }, states: [...c.states] }));
        if (compounds[0].tag === 'html') compounds = compounds.slice(1);
        if (!compounds.length || compounds.some(c => c.tag === null && !c.id && !c.classes.length && !Object.keys(c.attrs).length)) continue;
        if (compounds[0].tag === 'body') compounds = compounds.slice(1);
        if (!compounds.length) continue;
        for (const hover of [false, true]) {
            const chain = [{ tag: 'html', id: null, classes: [], attrs: {}, states: [] }, { tag: 'body', id: null, classes: [], attrs: {}, states: [] },
                ...compounds.map(c => ({ ...c, classes: [...c.classes], attrs: { ...c.attrs }, states: [...c.states] }))];
            const el = chain[chain.length - 1];
            if (hover && !el.states.includes('hover')) el.states.push('hover');
            const key = describe(chain);
            if (!seen.has(key)) seen.set(key, chain);
        }
    }
    return [...seen.values()];
}

// The element's own background over its parents', and its text colour, inherited unless set.
function paint(style, bg, color) {
    const own = style['background-color'] ?? (style.background !== undefined ? backgroundColor(style.background) : null);
    const ownColor = typeof own === 'string' ? parseColor(own) : own;
    if (ownColor && ownColor.a > 0) bg = over(ownColor, bg);
    if (style.color && style.color !== 'inherit') { const c = parseColor(style.color); if (c) color = over(c, bg); }
    return { bg, color };
}

function dimmed(style) {
    return style.opacity !== undefined && +style.opacity < 1;
}

// A ::before or ::after shows text only with a content of its own that is not empty.
function hasText(content) {
    const v = (content ?? '').trim();
    return v !== '' && v !== 'none' && v !== 'normal' && !/^(['"])\1$/.test(v);
}

const failures = [];
let pairs = 0;

function check(theme, chain, color, bg) {
    if (!color) return;
    pairs++;
    const r = ratio(color, bg);
    if (r < MIN_RATIO || process.env.CONTRAST_ALL)
        failures.push({ theme, element: describe(chain), color: hex(color), background: hex(bg), ratio: Math.round(r * 100) / 100 });
}

for (const theme of themes) {
    const t = tokens[theme];
    for (const chain of elementsOf(rules)) {
        chain[0].attrs['data-theme'] = theme;
        let bg = { r: 255, g: 255, b: 255, a: 1 }, color = null, faded = false;
        for (let at = 0; at < chain.length; at++) {
            const style = cascade(rules, theme, chain, at, t);
            faded ||= dimmed(style);
            ({ bg, color } = paint(style, bg, color));
        }
        if (faded) continue;
        check(theme, chain, color, bg);
        for (const name of ['before', 'after']) {
            const box = [...chain, { tag: null, id: null, classes: [], attrs: {}, states: [], pseudo: name }];
            const style = cascade(rules, theme, box, chain.length, t);
            if (!hasText(style.content) || dimmed(style)) continue;
            const painted = paint(style, bg, color);
            check(theme, box, painted.color, painted.bg);
        }
    }
}
if (process.env.CONTRAST_ALL) failures.sort((a, b) => a.ratio - b.ratio);
process.stdout.write(JSON.stringify({ pairs, themes, failures }));
