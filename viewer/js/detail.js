// Right-hand detail panel for the selected node, or for a process step (the node plus the call that reached it).
import { esc, basename, joinPath } from './util.js';
import { highlight } from './highlight.js';
import { splitCall } from './process.js';
import { getState, saveState } from './state.js';

const IDE_LINKS = [
  ['VS Code', (abs, line) => `vscode://file/${encodeURI(abs.replace(/\\/g, '/'))}:${line || 1}`],
  ['IntelliJ', (abs, line) => `idea://open?file=${encodeURIComponent(abs)}&line=${line || 1}`],
  ['Cursor', (abs, line) => `cursor://file/${encodeURI(abs.replace(/\\/g, '/'))}:${line || 1}`],
];
const ENTRY_KINDS = { http: 'HTTP endpoint', scheduled: 'Scheduled job', listener: 'Message listener' };
const LAYER_KINDS = { repository: 'Repository call', service: 'Service call', controller: 'Controller method' };
const SINK_GROUPS = [['Reads', op => op === 'SELECT'], ['Writes', op => op !== 'SELECT' && op !== 'DELETE'], ['Deletes', op => op === 'DELETE']];
const DB_ICON = '<svg class="d-ico" viewBox="0 0 16 16" aria-hidden="true"><ellipse cx="8" cy="3.5" rx="5" ry="2"/><path d="M3 3.5v9c0 1.1 2.2 2 5 2s5-.9 5-2v-9M3 8c0 1.1 2.2 2 5 2s5-.9 5-2"/></svg>';
const EXT_ICON = '<svg class="d-ico" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6"/><path d="M2 8h12M8 2c2 2 2 10 0 12M8 2c-2 2-2 10 0 12"/></svg>';
const OPEN_ICON = '<svg class="d-ico" viewBox="0 0 16 16" aria-hidden="true"><path d="M9 2.5h4.5V7M13.5 2.5 7.5 8.5M12 9.5v4H2.5V4h4"/></svg>';

export class DetailPanel {
  constructor(el, handlers) {
    this.el = el;
    this.h = handlers;
  }

  clear(message) {
    this.el.innerHTML = `<div class="empty">${message || 'Click a node to see its conditions, mutations, lineage and source.'}</div>`;
  }

  /**
   * ctx.via is the edge that reached the node in the process view (with its call `line`), ctx.caller its source node.
   * Without them the panel describes the node on its own, as the call graph shows it.
   */
  show(n, ctx) {
    const { graph, incoming, outgoing, collapsed, focused, via, caller, inProcess } = ctx;
    const head = describe(n, via, caller, incoming);
    const p = [];
    p.push(`<div class="d-head"><span class="eyebrow">${esc(head.kind)}</span><h2>${esc(head.title)}</h2>${head.sub ? `<span class="sub">${head.sub}</span>` : ''}</div>`);

    const badges = [n.framework, n.tech, n.module].filter(Boolean).map(b => `<span class="badge">${esc(b)}</span>`);
    if (n.confidence && n.confidence !== 'high') badges.push(`<span class="badge warn" title="FlowLens could not fully resolve this">${esc(n.confidence)} confidence</span>`);
    if (n.diff) badges.push(`<span class="badge diff-${esc(n.diff)}">${esc(n.diff)}</span>`);
    if (badges.length) p.push(`<div class="badges">${badges.join('')}</div>`);

    const src = via && caller?.source ? { node: caller, line: via.line } : { node: n, line: null };
    const loc = src.node.file ? { file: src.node.file, line: src.line || src.node.line } : null;
    const buttons = [];
    if (loc && graph.sourcePath && !graph.ref) buttons.push(ideButtons(joinPath(graph.sourcePath, loc.file), loc.line));
    if (incoming.length) buttons.push(`<button data-act="callers" aria-expanded="false">Callers <span class="count">${incoming.length}</span></button>`);
    if (buttons.length) p.push(`<div class="d-buttons">${buttons.join('')}</div>`);
    if (incoming.length) p.push(`<ul class="list d-callers" hidden>${incoming.map(([e, s]) => navItem(e, s, '←')).join('')}</ul>`);

    if (!inProcess) {
      const actions = [`<button data-act="focus">${focused ? 'Exit focus' : 'Focus neighbourhood'}</button>`];
      if (outgoing.length) actions.push(`<button data-act="collapse">${collapsed ? 'Expand' : 'Collapse'} subtree</button>`);
      if (n.type === 'db' || n.type === 'external') actions.push(`<button data-act="table">Who uses ${esc(n.table)}?</button>`);
      p.push(`<div class="actions">${actions.join('')}</div>`);
    }

    const sinks = n.type === 'db' || n.type === 'external' ? [n] : outgoing.map(([, t]) => t).filter(t => t.type === 'db' || t.type === 'external');
    for (const [title, test] of SINK_GROUPS) {
      const rows = sinks.filter(t => t.type === 'db' && test(t.op));
      if (rows.length) p.push(block(title, rows.map(sinkRow).join('')));
    }
    const ext = sinks.filter(t => t.type === 'external');
    if (ext.length) p.push(block(n.type === 'external' ? 'System' : 'Calls out', ext.map(sinkRow).join('')));
    if (n.detail) p.push(block(n.type === 'db' ? 'Query' : 'Call', `<pre class="sql">${highlight(n.detail, 'sql')}</pre>`));

    if (n.conditions?.length) p.push(section('Branch conditions', n.conditions.map(c => `<li class="cond">${esc(c)}</li>`).join('')));
    const lineage = lineageFor(n, caller, graph);
    if (lineage.length) {
      p.push(block('Field lineage', `<div class="lineage">${lineage.map(l =>
        `<span title="set in ${esc(l.via || '')}">${esc(l.from)}</span><span class="arrow">→</span><b>${esc(l.to)}</b>`).join('')}</div>`));
    }
    const writes = n.type === 'db' && n.op !== 'SELECT' && n.op !== 'DELETE';
    const mutations = mergeMutations(writes && caller ? caller.mutations : n.mutations);
    if (mutations.length) p.push(block(writes && caller ? `Set before ${head.title.replace(/\(\)$/, '')}` : 'Mutations', `<div class="muts">${mutations.map(m => `<span>${esc(m)}</span>`).join('')}</div>`));
    if (n.unresolved?.length) {
      p.push(section('Calls FlowLens could not follow', n.unresolved.map(u => `<li class="unres">${esc(u)}</li>`).join(''),
        'The receiver type could not be determined from source alone, so these calls are not in the graph.'));
    }
    if (!inProcess && outgoing.length) p.push(section('Calls', outgoing.map(([e, t]) => navItem(e, t, '→')).join('')));
    if (src.node.source) p.push(sourceBlock(src.node, src.line));
    if (n.type === 'db' && !via) p.push(`<p class="hint">Persistence sink resolved statically from the calls above.</p>`);

    this.el.innerHTML = p.join('');
    this.el.scrollTop = 0;
    const hit = this.el.querySelector('pre.source .hit');
    if (hit) hit.parentElement.scrollTop = Math.max(0, hit.offsetTop - 2 * hit.offsetHeight);
    this.el.querySelectorAll('[data-ide]').forEach(b => b.onclick = () => {
      saveState({ ide: Number(b.dataset.ide) });
      this.h.openExternal(b.dataset.href);
    });
    this.el.querySelectorAll('[data-copy]').forEach(b => b.onclick = () => this.h.copy(b.dataset.copy));
    this.el.querySelectorAll('[data-nav]').forEach(li => li.onclick = () => this.h.select(li.dataset.nav));
    this.el.querySelector('[data-act="callers"]')?.addEventListener('click', e => {
      const list = this.el.querySelector('.d-callers');
      list.hidden = !list.hidden;
      e.currentTarget.setAttribute('aria-expanded', String(!list.hidden));
    });
    this.el.querySelector('[data-act="focus"]')?.addEventListener('click', () => this.h.focus(n.id));
    this.el.querySelector('[data-act="collapse"]')?.addEventListener('click', () => this.h.collapse(n.id));
    this.el.querySelector('[data-act="table"]')?.addEventListener('click', () => this.h.table(n.table));
  }
}

/** The panel heading: what kind of step this is, its name, and the class with its caller. */
function describe(n, via, caller, incoming) {
  const [owner, method] = splitLabel(n.label);
  const from = caller || (incoming.length === 1 ? incoming[0][1] : null);
  const fromName = from ? `<span class="mono">${esc(from.type === 'endpoint' && from.http ? from.http : from.label)}</span>`
    : incoming.length > 1 ? `${incoming.length} places` : '';
  const sub = (lead, prefix = 'called from') => [lead && esc(lead), fromName && `${prefix} ${fromName}`].filter(Boolean).join(' · ');
  switch (n.type) {
    case 'endpoint':
      return { kind: ENTRY_KINDS[n.kind] || 'Entry point', title: n.http || n.label,
        sub: `<span class="mono">${esc(n.label)}</span>${n.returns ? ` returns <span class="mono">${esc(n.returns)}</span>` : ''}` };
    case 'db': {
      if (!via) return { kind: n.op === 'SELECT' ? 'Database read' : 'Database write', title: n.label, sub: sub('', 'Called from') };
      const call = splitCall(via.label);
      return { kind: 'Repository call', title: call.name, sub: sub(call.owner) };
    }
    case 'external':
      return { kind: 'External system', title: n.table || n.label, sub: sub(n.op) };
    case 'branch':
      return { kind: 'Decision', title: n.label, sub: sub('', 'In') };
    case 'throw':
      return { kind: 'Error end', title: n.label, sub: sub('', 'Thrown in') };
    default:
      return { kind: LAYER_KINDS[n.layer] || 'Method', title: method, sub: sub(owner) };
  }
}

function splitLabel(label) {
  const i = label.lastIndexOf('.', label.indexOf('('));
  return i < 0 ? ['', label] : [label.slice(0, i), label.slice(i + 1)];
}

/** Request → column mappings that this node (or, for a write step, its caller) sets. */
function lineageFor(n, caller, graph) {
  if (n.lineage?.length) return n.lineage;
  const owner = n.type === 'db' ? caller?.label : n.label;
  if (!owner) return [];
  const seen = new Set();
  return graph.nodes.flatMap(x => x.lineage || [])
    .filter(l => l.via === owner && (n.type !== 'db' || l.to.startsWith(n.table + '.')))
    .filter(l => !seen.has(l.from + '→' + l.to) && seen.add(l.from + '→' + l.to));
}

/** account.setStatus("ACTIVE"), account.setStatus("PENDING") → account.setStatus("ACTIVE" | "PENDING") */
function mergeMutations(list = []) {
  const merged = new Map();
  for (const m of list) {
    const at = m.indexOf('(');
    const key = at > 0 && m.endsWith(')') ? m.slice(0, at) : m;
    if (!merged.has(key)) merged.set(key, []);
    if (key !== m) merged.get(key).push(m.slice(at + 1, -1));
  }
  return [...merged].map(([key, args]) => (args.length ? `${key}(${args.join(' | ')})` : key));
}

function ideButtons(abs, line) {
  const pick = Math.min(IDE_LINKS.length - 1, Number(getState().ide) || 0);
  const [name, fn] = IDE_LINKS[pick];
  const others = IDE_LINKS.map((x, i) => [x, i]).filter(([, i]) => i !== pick)
    .map(([[other, link], i]) => `<button data-ide="${i}" data-href="${esc(link(abs, line))}">Open in ${other}</button>`).join('');
  return `<div class="split">
    <button class="primary" data-ide="${pick}" data-href="${esc(fn(abs, line))}" title="Open ${esc(abs)}:${line || 1} in ${name}">${OPEN_ICON}Open in ${name}</button>
    <details class="split-more"><summary class="primary" aria-label="More ways to open this file"></summary>
      <div class="menu-pop">${others}<button data-copy="${esc(abs + (line ? ':' + line : ''))}">Copy path</button></div></details>
  </div>`;
}

function sinkRow(t) {
  const tail = t.type === 'db' ? `<span class="op ${esc(t.op)}">${esc(t.op)}</span>` : t.op ? `<small>${esc(t.op)}</small>` : '';
  return `<button class="sink" data-nav="${esc(t.id)}">${t.type === 'db' ? DB_ICON : EXT_ICON}<b>${esc(t.table || t.label)}</b>${tail}</button>`;
}

/** The method's source with line numbers; the call line is highlighted when there is one. */
function sourceBlock(node, mark) {
  const start = node.line;
  const lines = splitHighlighted(highlight(node.source, 'java'));
  const where = start ? `<span class="loc">${esc(basename(node.file || ''))}:${mark || start}</span>` : '';
  const rows = lines.map((l, i) => {
    const no = start ? start + i : null;
    return `<span class="row${no && no === mark ? ' hit' : ''}">${no ? `<i>${no}</i>` : ''}${l || ' '}</span>`;
  }).join('');
  return `<section class="detail"><div class="title">Source ${where}</div><pre class="source">${rows}</pre></section>`;
}

/** Split highlighted HTML into lines, closing and reopening a token span that runs over a line break. */
function splitHighlighted(html) {
  const out = [];
  let open = null;
  let line = '';
  for (const part of html.split(/(<span class="[^"]+">|<\/span>|\n)/)) {
    if (part === '\n') {
      out.push(line + (open ? '</span>' : ''));
      line = open || '';
    } else {
      if (part.startsWith('<span')) open = part;
      else if (part === '</span>') open = null;
      line += part;
    }
  }
  out.push(line);
  return out;
}

function navItem(e, other, arrow) {
  const label = e.label ? `<small>${esc(e.label)}</small>` : '';
  return `<li class="nav" data-nav="${esc(other.id)}">${arrow} ${esc(other.type === 'endpoint' && other.http ? other.http : other.label)}${label}</li>`;
}

function block(title, inner) {
  return `<section class="detail"><div class="title">${esc(title)}</div>${inner}</section>`;
}

function section(title, inner, hint) {
  return `<section class="detail"><div class="title">${esc(title)}</div>${hint ? `<p class="hint">${esc(hint)}</p>` : ''}<ul class="list">${inner}</ul></section>`;
}
