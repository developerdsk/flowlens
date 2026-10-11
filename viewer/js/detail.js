// Right-hand detail panel for the selected node.
import { esc, joinPath } from './util.js';
import { highlight } from './highlight.js';
import { nodeColor } from './graph.js';

const IDE_LINKS = [
  ['VS Code', (abs, line) => `vscode://file/${encodeURI(abs.replace(/\\/g, '/'))}:${line || 1}`],
  ['IntelliJ', (abs, line) => `idea://open?file=${encodeURIComponent(abs)}&line=${line || 1}`],
  ['Cursor', (abs, line) => `cursor://file/${encodeURI(abs.replace(/\\/g, '/'))}:${line || 1}`],
];

export class DetailPanel {
  constructor(el, handlers) {
    this.el = el;
    this.h = handlers;
  }

  clear(message) {
    this.el.innerHTML = `<div class="empty">${message || 'Click a node to see its conditions, mutations, lineage and source.'}</div>`;
  }

  show(n, ctx) {
    const { graph, incoming, outgoing, collapsed, focused } = ctx;
    const p = [];
    p.push(`<h2>${esc(n.type === 'endpoint' && n.http ? n.http : n.label)}</h2>`);
    const badges = [n.type, n.kind, n.layer !== n.type ? n.layer : null, n.framework, n.tech, n.module].filter(Boolean)
      .map(b => `<span class="badge">${esc(b)}</span>`);
    if (n.confidence && n.confidence !== 'high') badges.push(`<span class="badge warn" title="FlowLens could not fully resolve this">${esc(n.confidence)} confidence</span>`);
    if (n.diff) badges.push(`<span class="badge diff-${esc(n.diff)}">${esc(n.diff)}</span>`);
    p.push(`<div class="badges">${badges.join('')}</div>`);

    const meta = [];
    if (n.type === 'endpoint') meta.push(`<span>${esc(n.label)}</span>`);
    if (n.type === 'db') meta.push(`<span><b style="color:${nodeColor(n)}">${esc(n.op)}</b> → <b>${esc(n.table)}</b></span>`);
    if (n.type === 'external') meta.push(`<span><b>${esc(n.op)}</b> → <b>${esc(n.table)}</b></span>`);
    if (n.file) meta.push(`<span class="loc">${esc(n.file)}${n.line ? ':' + esc(n.line) : ''}</span>`);
    if (meta.length) p.push(`<div class="meta">${meta.join('')}</div>`);

    if (n.file && graph.sourcePath && !graph.ref) {
      const abs = joinPath(graph.sourcePath, n.file);
      p.push(`<div class="links">${IDE_LINKS.map(([name, fn], i) =>
        `<button data-ide="${i}" data-href="${esc(fn(abs, n.line))}" title="Open ${esc(abs)} in ${name}">Open in ${name}</button>`).join('')}
        <button data-copy="${esc(abs + (n.line ? ':' + n.line : ''))}" title="Copy file path">Copy path</button></div>`);
    }

    const actions = [];
    actions.push(`<button data-act="focus">${focused ? 'Exit focus' : 'Focus neighbourhood'}</button>`);
    if (outgoing.length) actions.push(`<button data-act="collapse">${collapsed ? 'Expand' : 'Collapse'} subtree</button>`);
    if (n.type === 'db' || n.type === 'external') actions.push(`<button data-act="table">Who uses ${esc(n.table)}?</button>`);
    p.push(`<div class="actions">${actions.join('')}</div>`);

    if (n.lineage && n.lineage.length) {
      p.push(section('Field lineage (request → column)', n.lineage.map(l =>
        `<li class="lin">${esc(l.from)} <span class="arrow">→</span> <b>${esc(l.to)}</b><small>set in ${esc(l.via || '')}</small></li>`).join('')));
    }
    if (n.detail) p.push(`<section class="detail"><div class="title">${n.type === 'db' ? 'Query' : 'Call'}</div><pre class="sql">${highlight(n.detail, 'sql')}</pre></section>`);
    if (n.conditions && n.conditions.length) p.push(section('Branch conditions', n.conditions.map(c => `<li class="cond">${esc(c)}</li>`).join('')));
    if (n.mutations && n.mutations.length) p.push(section('Mutations', n.mutations.map(m => `<li class="mut">${esc(m)}</li>`).join('')));
    if (n.unresolved && n.unresolved.length) {
      p.push(section('Calls FlowLens could not follow', n.unresolved.map(u => `<li class="unres">${esc(u)}</li>`).join(''),
        'The receiver type could not be determined from source alone, so these calls are not in the graph.'));
    }
    if (outgoing.length) p.push(section('Calls', outgoing.map(([e, t]) => navItem(e, t, '→')).join('')));
    if (incoming.length) p.push(section('Called from', incoming.map(([e, s]) => navItem(e, s, '←')).join('')));
    if (n.source) p.push(`<section class="detail"><div class="title">Source</div><pre class="source">${highlight(n.source, 'java')}</pre></section>`);
    if (n.type === 'db') p.push(`<p class="hint">Persistence sink resolved statically from the calls above.</p>`);

    this.el.innerHTML = p.join('');
    this.el.scrollTop = 0;
    this.el.querySelectorAll('[data-ide]').forEach(b => b.onclick = () => this.h.openExternal(b.dataset.href));
    this.el.querySelectorAll('[data-copy]').forEach(b => b.onclick = () => this.h.copy(b.dataset.copy));
    this.el.querySelectorAll('[data-nav]').forEach(li => li.onclick = () => this.h.select(li.dataset.nav));
    this.el.querySelector('[data-act="focus"]').onclick = () => this.h.focus(n.id);
    this.el.querySelector('[data-act="collapse"]')?.addEventListener('click', () => this.h.collapse(n.id));
    this.el.querySelector('[data-act="table"]')?.addEventListener('click', () => this.h.table(n.table));
  }
}

function navItem(e, other, arrow) {
  const label = e.label ? `<small>${esc(e.label)}</small>` : '';
  return `<li class="nav" data-nav="${esc(other.id)}">${arrow} ${esc(other.type === 'endpoint' && other.http ? other.http : other.label)}${label}</li>`;
}

function section(title, inner, hint) {
  return `<section class="detail"><div class="title">${esc(title)}</div>${hint ? `<p class="hint">${esc(hint)}</p>` : ''}<ul class="list">${inner}</ul></section>`;
}
