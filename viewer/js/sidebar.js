// Left sidebar: endpoint tree (grouped by class, like a Postman collection), tables, issues.
import { $, $$, esc } from './util.js';

const verbLabel = v => ({ SCHEDULED: 'CRON', CHANNEL: 'CHAN' }[v] || v || 'ANY');
const verbClass = v => String(v || 'ANY').toUpperCase().replace(/[^A-Z]/g, '');

export class Sidebar {
  constructor(handlers) {
    this.h = handlers;
    this.graph = null;
    this.filter = '';
    this.module = '';
    this.active = null;
    this.tab = 'endpoints';
    this.collapsedGroups = new Set();
    $$('.tab').forEach(t => t.addEventListener('click', () => this.setTab(t.dataset.tab)));
  }

  load(graph, reachingFn) {
    this.graph = graph;
    this.reaching = reachingFn;
    this.tableIndex = null;
    const sel = $('#moduleSelect');
    if (graph.modules && graph.modules.length) {
      sel.hidden = false;
      sel.innerHTML = `<option value="">All modules (${graph.modules.length})</option>` +
        graph.modules.map(m => `<option value="${esc(m)}">${esc(m)}</option>`).join('');
      sel.value = this.module && graph.modules.includes(this.module) ? this.module : '';
      this.module = sel.value;
    } else {
      sel.hidden = true;
      this.module = '';
    }
    this.render();
  }

  setTab(tab) {
    this.tab = tab;
    $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
    ['endpoints', 'tables', 'issues'].forEach(k => { $('#tab-' + k).hidden = k !== tab; });
    this.h.onTab?.(tab);
  }

  setFilter(q) { this.filter = q.trim().toLowerCase(); this.render(); }
  setModule(m) { this.module = m; this.render(); }
  setActive(kind, id) { this.active = kind && id ? kind + ':' + id : null; this.render(); }

  render() {
    if (!this.graph) return;
    this.renderEndpoints();
    this.renderTables();
    this.renderIssues();
  }

  matches(...fields) {
    if (!this.filter) return true;
    return fields.some(f => String(f || '').toLowerCase().includes(this.filter));
  }

  renderEndpoints() {
    const eps = this.graph.nodes.filter(n => n.type === 'endpoint' && (!this.module || n.module === this.module));
    $('#countEndpoints').textContent = eps.length || '';
    const shown = eps.filter(n => this.matches(n.http, n.label, n.file, n.module));
    const groups = new Map();
    for (const n of shown) {
      const owner = n.label.split(/[.\s]/)[0] || 'Other';
      (groups.get(owner) || groups.set(owner, []).get(owner)).push(n);
    }
    const parts = [];
    parts.push(`<div class="item ${this.active === null ? 'active' : ''}" data-all="1"><span class="verb ANY">ALL</span><span class="path">All entry points</span><span class="sub">${eps.length}</span></div>`);
    if (!shown.length) parts.push(`<div class="empty">${eps.length ? 'No endpoint matches the search.' : 'No entry points found.<br>Is this a Spring / JAX-RS / Micronaut project?'}</div>`);
    for (const [owner, list] of [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
      list.sort((a, b) => (a.path || '').localeCompare(b.path || '') || (a.verb || '').localeCompare(b.verb || ''));
      const collapsed = this.collapsedGroups.has(owner) && !this.filter;
      parts.push(`<div class="tree-group ${collapsed ? 'collapsed' : ''}" data-group="${esc(owner)}">
        <div class="group-head"><span class="chev">▾</span>${esc(owner)}<span class="sub muted">${list.length}</span></div>
        <div class="group-items">${list.map(n => `
          <div class="item ${this.active === 'ep:' + n.id ? 'active' : ''}" data-ep="${esc(n.id)}" title="${esc(n.http + '  —  ' + n.label)}">
            <span class="verb ${verbClass(n.verb)}">${esc(verbLabel(n.verb))}</span>
            <span class="path">${esc(n.path || n.http || n.label)}</span>
            ${n.diff ? `<span class="diffmark ${esc(n.diff)}" title="${esc(n.diff)}"></span>` : ''}
          </div>`).join('')}</div></div>`);
    }
    const body = $('#tab-endpoints');
    body.innerHTML = parts.join('');
    body.querySelector('[data-all]').onclick = () => this.h.onAll();
    $$('.group-head', body).forEach(h => h.onclick = () => {
      const g = h.parentElement.dataset.group;
      if (this.collapsedGroups.has(g)) this.collapsedGroups.delete(g); else this.collapsedGroups.add(g);
      h.parentElement.classList.toggle('collapsed');
    });
    $$('[data-ep]', body).forEach(el => el.onclick = () => this.h.onEndpoint(el.dataset.ep));
  }

  tables() {
    if (this.tableIndex) return this.tableIndex;
    const map = new Map();
    for (const n of this.graph.nodes) {
      if (n.type !== 'db' && n.type !== 'external') continue;
      const key = n.type + '|' + n.table;
      const t = map.get(key) || { type: n.type, table: n.table, tech: n.tech, ops: new Set(), ids: [], diff: null };
      t.ops.add(n.op);
      t.ids.push(n.id);
      if (n.diff) t.diff = t.diff || n.diff;
      map.set(key, t);
    }
    for (const t of map.values()) {
      const up = this.reaching(t.ids);
      t.endpoints = this.graph.nodes.filter(n => n.type === 'endpoint' && up.has(n.id)).length;
    }
    this.tableIndex = [...map.values()].sort((a, b) => (a.type === b.type ? 0 : a.type === 'db' ? -1 : 1) || String(a.table).localeCompare(String(b.table)));
    return this.tableIndex;
  }

  renderTables() {
    const all = this.tables();
    $('#countTables').textContent = all.length || '';
    const list = all.filter(t => this.matches(t.table, t.tech));
    const opChip = op => `<span class="chip ${op === 'SELECT' ? '' : op === 'DELETE' ? 'd' : op === 'UPDATE' ? 'u' : 'w'}">${esc(op)}</span>`;
    const section = (type, title) => {
      const items = list.filter(t => t.type === type);
      if (!items.length) return '';
      return `<div class="group-head"><span class="chev">▾</span>${title}<span class="sub muted">${items.length}</span></div>` +
        items.map(t => `<div class="item ${this.active === 'table:' + t.table ? 'active' : ''}" data-table="${esc(t.table)}" title="${esc(t.table)} — reached by ${t.endpoints} entry point(s)">
          <span class="path">${esc(t.table)}</span>
          ${t.diff ? `<span class="diffmark ${esc(t.diff)}"></span>` : ''}
          <span class="sub">${[...t.ops].map(opChip).join(' ')} · ${t.endpoints}</span>
        </div>`).join('');
    };
    const html = section('db', 'Database tables & collections') + section('external', 'Outbound HTTP & messaging');
    const body = $('#tab-tables');
    body.innerHTML = html || `<div class="empty">${all.length ? 'Nothing matches the search.' : 'No database or external sinks found.'}</div>`;
    $$('[data-table]', body).forEach(el => el.onclick = () => this.h.onTable(el.dataset.table));
  }

  renderIssues() {
    const g = this.graph;
    const diags = g.diagnostics || [];
    const unresolved = g.nodes.filter(n => n.unresolved && n.unresolved.length);
    const lowConf = g.nodes.filter(n => n.confidence === 'low');
    const warn = diags.filter(d => d.severity === 'warning').length;
    const count = warn + unresolved.length;
    $('#countIssues').textContent = count || '';
    const badge = $('#issuesBadge');
    badge.hidden = !warn;
    badge.textContent = `⚠ ${warn} file${warn === 1 ? '' : 's'} skipped`;
    const s = g.stats || {};
    const parts = [`<div class="stats">${s.filesParsed ?? '?'} of ${s.filesFound ?? '?'} Java files analyzed` +
      `${s.filesSkipped ? `, <b>${s.filesSkipped} skipped</b>` : ''}${s.xmlMappers ? `, ${s.xmlMappers} MyBatis XML files` : ''}` +
      `${s.durationMs ? ` · ${(s.durationMs / 1000).toFixed(1)} s` : ''}</div>`];
    const sorted = [...diags].sort((a, b) => (a.severity === 'warning' ? 0 : 1) - (b.severity === 'warning' ? 0 : 1));
    for (const d of sorted.filter(d => this.matches(d.file, d.message))) {
      parts.push(`<div class="issue ${esc(d.severity)}"><span class="sev">${d.severity === 'warning' ? '⚠' : 'ℹ'}</span>
        <span class="file">${esc(d.file || '')}</span><div>${esc(d.message)}</div></div>`);
    }
    if (unresolved.length) {
      parts.push(`<div class="group-head"><span class="chev">▾</span>Calls FlowLens couldn't follow<span class="sub muted">${unresolved.length}</span></div>`);
      for (const n of unresolved.filter(n => this.matches(n.label, ...n.unresolved))) {
        parts.push(`<div class="item" data-node="${esc(n.id)}" title="${esc(n.unresolved.join('\n'))}"><span class="path">${esc(n.label)}</span><span class="sub">${n.unresolved.length}</span></div>`);
      }
    }
    if (lowConf.length) {
      parts.push(`<div class="group-head"><span class="chev">▾</span>Low-confidence sinks<span class="sub muted">${lowConf.length}</span></div>`);
      for (const n of lowConf.filter(n => this.matches(n.label))) {
        parts.push(`<div class="item" data-node="${esc(n.id)}"><span class="path">${esc(n.label)}</span></div>`);
      }
    }
    if (parts.length === 1) parts.push('<div class="empty">No issues — every file parsed and every call was followed.</div>');
    const body = $('#tab-issues');
    body.innerHTML = parts.join('');
    $$('[data-node]', body).forEach(el => el.onclick = () => this.h.onNode(el.dataset.node));
  }
}
