// The all-endpoints catalog: one card per entry point, grouped by class, built from each process (js/process.js).
import { esc } from './util.js';
import { buildProcess, indexGraph } from './process.js';

const GROUP_NOUN = { http: ['endpoint', 'endpoints'], scheduled: ['scheduled job', 'scheduled jobs'] };
const DAYS = { SUN: 'Sundays', MON: 'Mondays', TUE: 'Tuesdays', WED: 'Wednesdays', THU: 'Thursdays', FRI: 'Fridays', SAT: 'Saturdays' };
const DAY_NUMBERS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
const MACROS = { '@yearly': 'Runs yearly', '@annually': 'Runs yearly', '@monthly': 'Runs monthly', '@weekly': 'Runs weekly', '@daily': 'Runs daily at 00:00', '@midnight': 'Runs daily at 00:00', '@hourly': 'Runs hourly' };
export const FILTERS = { all: () => true, writes: c => c.summary.writes.length + c.summary.deletes.length > 0, errors: c => c.summary.errorEnds > 0 };

/** Cards for every entry point, grouped by class, plus the project totals for the header. */
export function catalogModel(graph) {
  const index = indexGraph(graph);
  const groups = new Map();
  const tables = new Set(), topics = new Set(), services = new Set();
  const endpoints = graph.nodes.filter(n => n.type === 'endpoint');
  for (const ep of endpoints) {
    const p = buildProcess(graph, ep.id, { index });
    const owner = (ep.label || '').split('.')[0];
    const s = p.summary;
    [...s.reads, ...s.writes, ...s.deletes].forEach(t => tables.add(t));
    s.publishes.forEach(t => topics.add(t));
    s.calls.forEach(t => services.add(t));
    const scheduled = ep.kind === 'scheduled';
    const card = {
      id: ep.id, owner, kind: ep.kind || 'http', handler: p.start.handler, summary: s, sketch: sketch(p),
      verb: scheduled ? scheduleVerb(ep.path) : ep.verb || (ep.kind || 'entry').toUpperCase(),
      title: scheduled ? scheduleTitle(ep.path) : ep.path || ep.http || ep.label,
      schedule: scheduled ? describeSchedule(ep.path) : null,
      text: `${ep.http || ''} ${ep.label} ${[...s.reads, ...s.writes, ...s.deletes, ...s.publishes, ...s.calls].join(' ')}`.toLowerCase(),
    };
    if (!groups.has(owner)) groups.set(owner, []);
    groups.get(owner).push(card);
  }
  return {
    groups: [...groups].map(([owner, cards]) => ({ owner, cards })),
    totals: { entries: endpoints.length, tables: tables.size, topics: topics.size, services: services.size },
  };
}

/** The main path of a process as glyphs: start, then each step, decision and end it passes (error branches skipped). */
export function sketch(p, max = 14) {
  const out = [p.start.kind === 'scheduled' ? 'clock' : 'start'];
  const next = new Map();
  for (const c of p.connectors) {
    if (c.kind !== 'sequence' && c.kind !== 'branch' && c.kind !== 'inferred') continue;
    if (!next.has(c.from)) next.set(c.from, []);
    next.get(c.from).push(c);
  }
  const errors = new Set(p.ends.filter(e => e.kind === 'error').map(e => e.id));
  const seen = new Set(['start']);
  for (let at = 'start'; out.length < max;) {
    const outs = next.get(at) || [];
    const c = outs.find(x => !errors.has(x.to)) || outs[0];
    if (!c || seen.has(c.to)) break;
    seen.add(at = c.to);
    const step = p.steps.find(s => s.id === at);
    if (step) out.push(step.kind === 'subprocess' ? 'sub' : step.kind === 'publish' ? 'publish' : step.inferred ? 'inferred' : 'step');
    else if (p.decisions.some(d => d.id === at)) out.push('decision');
    else { out.push(errors.has(at) ? 'error' : 'end'); break; }
  }
  return out;
}

/** Plain-English schedule for a Spring/Quarkus/Quartz schedule string such as "cron=0 0 3 * * *" or "fixedRate=60000". */
export function describeSchedule(schedule) {
  const s = (schedule || '').trim();
  if (!s) return 'Scheduled';
  if (s.startsWith('quartz:')) return `Quartz job ${s.slice(7)}`;
  const [attr, ...rest] = s.split('=');
  const value = rest.join('=').trim();
  if (attr === 'fixedRate') return /^\d+$/.test(value) ? `Runs every ${duration(Number(value))}` : `Runs at a fixed rate (${value})`;
  if (attr === 'fixedDelay') return /^\d+$/.test(value) ? `Runs ${duration(Number(value))} after each run ends` : `Runs with a fixed delay (${value})`;
  if (attr === 'every') return `Runs every ${value}`;
  if (attr === 'cron') return describeCron(value);
  return `Runs on ${s}`;
}

function describeCron(expr) {
  if (MACROS[expr]) return MACROS[expr];
  const f = expr.split(/\s+/);
  if (f.length === 5) f.unshift('0');
  if (f.length !== 6) return `Runs on cron ${expr}`;
  const [sec, min, hour, dom, mon, dow] = f;
  const num = x => /^\d+$/.test(x);
  const any = x => x === '*' || x === '?';
  const step = x => /^\*\/(\d+)$/.exec(x)?.[1];
  const time = () => `${hour.padStart(2, '0')}:${min.padStart(2, '0')}${sec === '0' ? '' : ':' + sec.padStart(2, '0')}`;
  const everyDay = any(dom) && any(mon) && any(dow);
  if (num(sec) && num(min) && num(hour) && everyDay) return `Runs daily at ${time()}`;
  if (num(sec) && num(min) && num(hour) && any(dom) && any(mon)) {
    const days = dayNames(dow);
    if (days) return `Runs ${days} at ${time()}`;
  }
  if (num(sec) && num(min) && num(hour) && num(dom) && any(mon) && any(dow)) return `Runs monthly on day ${dom} at ${time()}`;
  if (num(sec) && num(min) && any(hour) && everyDay) return `Runs hourly at :${min.padStart(2, '0')}`;
  if (num(sec) && num(min) && step(hour) && everyDay) return `Runs every ${plural(step(hour), 'hour')}`;
  if (num(sec) && step(min) && any(hour) && everyDay) return `Runs every ${plural(step(min), 'minute')}`;
  if (step(sec) && any(min) && any(hour) && everyDay) return `Runs every ${plural(step(sec), 'second')}`;
  return `Runs on cron ${expr}`;
}

function dayNames(dow) {
  const name = d => (/^\d$/.test(d) ? DAY_NUMBERS[Number(d)] : d.toUpperCase().slice(0, 3));
  if (/^(MON-FRI|1-5)$/i.test(dow)) return 'on weekdays';
  if (/^(SAT,SUN|SUN,SAT|0,6|6,0|6,7)$/i.test(dow)) return 'on weekends';
  const days = dow.split(',').map(name);
  return days.every(d => DAYS[d]) ? 'on ' + days.map(d => DAYS[d]).join(', ') : null;
}

function duration(ms) {
  if (ms % 3600000 === 0) return plural(ms / 3600000, 'hour');
  if (ms % 60000 === 0) return plural(ms / 60000, 'minute');
  if (ms % 1000 === 0) return plural(ms / 1000, 'second');
  return `${ms} ms`;
}

const plural = (n, unit) => (Number(n) === 1 ? unit : `${n} ${unit}s`);

function scheduleVerb(path) {
  const attr = (path || '').split(/[=:]/)[0];
  return attr === 'cron' ? 'CRON' : attr === 'quartz' ? 'QUARTZ' : attr ? 'TIMER' : 'JOB';
}

function scheduleTitle(path) {
  const p = path || '';
  return p.startsWith('cron=') ? p.slice(5) : p || 'scheduled';
}

/** Draw the catalog into `el`; `filter` is a FILTERS key, `query` the search text. Returns the number of cards shown. */
export function renderCatalog(el, model, { filter = 'all', query = '', view = 'catalog' } = {}) {
  const term = query.trim().toLowerCase();
  const keep = c => FILTERS[filter](c) && (term.length < 2 || c.text.includes(term));
  const t = model.totals;
  const n = (k, one, many = one + 's') => `${k} ${k === 1 ? one : many}`;
  const totals = [n(t.entries, 'entry point'), n(t.tables, 'table'), t.topics && n(t.topics, 'topic'), t.services && n(t.services, 'HTTP service')].filter(Boolean).join(' · ');
  const seg = (attr, value, label, current) =>
    `<button data-${attr}="${value}" class="${value === current ? 'active' : ''}" aria-pressed="${value === current}">${label}</button>`;
  let shown = 0;
  const groups = model.groups.map(g => {
    const cards = g.cards.filter(keep);
    shown += cards.length;
    if (!cards.length) return '';
    const kinds = new Set(cards.map(c => c.kind));
    const noun = kinds.size === 1 && GROUP_NOUN[[...kinds][0]] ? GROUP_NOUN[[...kinds][0]] : ['entry point', 'entry points'];
    return `<section class="cat-group"><h2><span class="mono">${esc(g.owner)}</span><small>${cards.length} ${cards.length === 1 ? noun[0] : noun[1]}</small></h2>
      <div class="cat-grid">${cards.map(card).join('')}</div></section>`;
  }).join('');
  el.innerHTML = `<div class="cat-head">
      <div><h1>All endpoints</h1><span class="cat-totals">${esc(totals)}</span></div>
      <div class="seg" role="group" aria-label="Show">${seg('cat-filter', 'all', 'All', filter)}${seg('cat-filter', 'writes', 'Writes data', filter)}${seg('cat-filter', 'errors', 'Has errors', filter)}</div>
      <div class="seg" role="group" aria-label="Layout">${seg('cat-view', 'catalog', 'Catalog', view)}${seg('cat-view', 'map', 'Map', view)}</div>
    </div>${groups || `<p class="cat-empty">No entry point matches${term.length >= 2 ? ` “${esc(query.trim())}”` : ''}.</p>`}`;
  return shown;
}

function card(c) {
  const s = c.summary;
  const chips = (label, list, cls) => (list.length ? `<span class="cat-k">${label}</span><span class="cat-chips">${list.map(t => `<span class="${cls}">${esc(t)}</span>`).join('')}</span>` : '');
  const rows = chips('Reads', s.reads, 'chip-r') + chips('Writes', s.writes, 'chip-w') + chips('Deletes', s.deletes, 'chip-d') +
    chips('Publishes', s.publishes, 'chip-a') + chips('Calls', s.calls, 'chip-a');
  const count = (k, one, many, cls = '') => (k ? `<span class="${cls}">${k} ${k === 1 ? one : many}</span>` : '');
  const foot = [c.schedule ? `<span>${esc(c.schedule)}</span>` : '', count(s.decisions, 'decision', 'decisions'),
    count(s.errorEnds, 'error end', 'error ends', 'err'), count(s.subprocesses, 'sub-process', 'sub-processes')].join('');
  return `<button class="cat-card" data-open="${esc(c.id)}" aria-label="Open the process of ${esc(c.verb)} ${esc(c.title)}">
    <span class="cat-top"><span class="verb ${esc(c.verb)}">${esc(c.verb)}</span><b class="mono">${esc(c.title)}</b><small class="mono">${esc(c.handler)}</small></span>
    ${sketchSvg(c.sketch)}
    ${rows ? `<span class="cat-rows">${rows}</span>` : ''}
    ${foot ? `<span class="cat-foot">${foot}</span>` : ''}
  </button>`;
}

const GLYPH_W = { start: 10, clock: 10, end: 10, error: 10, publish: 10, decision: 12, step: 20, inferred: 20, sub: 20 };

function sketchSvg(glyphs) {
  let x = 1;
  const parts = [];
  glyphs.forEach((g, i) => {
    if (i) { parts.push(`<path class="g-line" d="M${x} 8h6"/>`); x += 6; }
    const w = GLYPH_W[g];
    const cx = x + w / 2;
    if (g === 'start' || g === 'clock') parts.push(`<circle class="g-start" cx="${cx}" cy="8" r="5"/>${g === 'clock' ? `<path class="g-start" d="M${cx} 5.5V8l1.6 1.2"/>` : ''}`);
    else if (g === 'end') parts.push(`<circle class="g-end" cx="${cx}" cy="8" r="5"/>`);
    else if (g === 'error') parts.push(`<circle class="g-error" cx="${cx}" cy="8" r="5"/>`);
    else if (g === 'publish') parts.push(`<circle class="g-async" cx="${cx}" cy="8" r="5"/><circle class="g-async thin" cx="${cx}" cy="8" r="3"/>`);
    else if (g === 'decision') parts.push(`<path class="g-decision" d="M${cx} 2 ${cx + 6} 8 ${cx} 14 ${cx - 6} 8Z"/>`);
    else {
      parts.push(`<rect class="g-${g}" x="${x}" y="2.5" width="20" height="11" rx="3"/>`);
      if (g === 'sub') parts.push(`<path class="g-sub" d="M${cx} 5.5v5M${cx - 2.5} 8h5"/>`);
    }
    x += w;
  });
  return `<svg class="cat-sketch" width="${x + 1}" height="16" viewBox="0 0 ${x + 1} 16" aria-hidden="true">${parts.join('')}</svg>`;
}
