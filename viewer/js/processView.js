// The process view: an endpoint's flow (js/process.js) drawn as steps in lanes. ELK lays out the
// service lane; tables, async listeners and external systems sit in their lanes under the step
// that uses them, so their connectors stay short and straight.
import { esc } from './util.js';
import { buildProcess } from './process.js';

const BOX = { w: 184, h: 64 };
const EVENT = 36;
const DECISION = 48;
const CARD = { w: 184, h: 44 };
const SIDE = 48;
const LANE_TOP = 44;
const LANE_BOTTOM = 32;
const GAP = 20;
const FLOW_KINDS = new Set(['sequence', 'branch', 'inferred']);

const ICONS = {
  data: '<ellipse cx="8" cy="3.5" rx="5" ry="2"/><path d="M3 3.5v9c0 1.1 2.2 2 5 2s5-.9 5-2v-9M3 8c0 1.1 2.2 2 5 2s5-.9 5-2"/>',
  call: '<path d="M8 1.5 14 5v6l-6 3.5L2 11V5z"/><path d="M2 5l6 3.5L14 5M8 8.5V14"/>',
  async: '<path d="M2 4h12v8H2z"/><path d="M2 4l6 5 6-5"/>',
  topic: '<path d="M3 4h10M3 8h10M3 12h6"/>',
  http: '<circle cx="8" cy="8" r="6"/><path d="M2 8h12M8 2c2 2 2 10 0 12M8 2c-2 2-2 10 0 12"/>',
  start: '<path d="M2 8h8M7 4.5 10.5 8 7 11.5M13.5 3v10"/>',
  error: '<path d="M9.5 1 3.5 9H8l-1.5 6L12.5 7H8z"/>',
  decision: '<path d="M5.5 5.5l5 5M10.5 5.5l-5 5"/>',
};
const icon = (name, cls = '') => `<svg class="pv-ico ${cls}" viewBox="0 0 16 16" aria-hidden="true">${ICONS[name]}</svg>`;

export class ProcessView {
  constructor(root, handlers) {
    this.root = root;
    this.h = handlers;
    this.viewport = root.querySelector('.pv-viewport');
    this.sizer = root.querySelector('.pv-sizer');
    this.stage = root.querySelector('.pv-stage');
    this.scale = 1;
    this.token = 0;
    this.model = null;
    this.stage.addEventListener('click', e => {
      const el = e.target.closest('[data-node-id]');
      if (el) this.h.onSelect(el.dataset.nodeId);
    });
    this.stage.addEventListener('keydown', e => this.onKey(e));
  }

  /** Build, lay out and draw the process of an endpoint. Resolves to the model (null if superseded). */
  async show(graph, endpointId, options = {}) {
    const token = ++this.token;
    const p = buildProcess(graph, endpointId, options);
    if (!p) return null;
    const placed = await this.layout(p);
    if (token !== this.token) return null;
    this.model = p;
    this.render(p, placed);
    this.fit({ maxScale: 1 });
    return p;
  }

  // ------------------------------------------------------------------------------------------
  // layout
  // ------------------------------------------------------------------------------------------

  async layout(p) {
    const top = s => s.parent == null;
    const errorUnder = new Map();
    for (const d of p.decisions) {
      for (const br of d.branches) {
        const end = br.items.length === 1 && p.ends.find(e => e.id === br.items[0] && e.kind === 'error');
        if (end) errorUnder.set(end.id, d.id);
      }
    }
    const flow = [
      { id: 'start', kind: 'start', w: EVENT, h: EVENT },
      ...p.steps.filter(s => top(s) && s.lane === 'service')
        .map(s => (s.kind === 'publish' ? { id: s.id, kind: 'publish', w: EVENT, h: EVENT } : { id: s.id, kind: 'step', ...BOX })),
      ...p.decisions.filter(top).map(d => ({ id: d.id, kind: 'decision', w: DECISION, h: DECISION })),
      ...p.ends.filter(e => !errorUnder.has(e.id)).map(e => ({ id: e.id, kind: 'end', w: EVENT, h: EVENT })),
    ];
    const inFlow = new Set(flow.map(n => n.id));
    const flowEdges = p.connectors.map((c, i) => ({ ...c, i }))
      .filter(c => FLOW_KINDS.has(c.kind) && inFlow.has(c.from) && inFlow.has(c.to));
    const elk = new window.ELK();
    const res = await elk.layout({
      id: 'root',
      layoutOptions: {
        'elk.algorithm': 'layered', 'elk.direction': 'RIGHT', 'elk.edgeRouting': 'ORTHOGONAL',
        'elk.spacing.nodeNode': '36', 'elk.layered.spacing.nodeNodeBetweenLayers': '48',
        'elk.layered.spacing.edgeNodeBetweenLayers': '24', 'elk.layered.nodePlacement.bk.fixedAlignment': 'BALANCED',
        'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      },
      children: flow.map(n => ({ id: n.id, width: n.w, height: n.h })),
      edges: flowEdges.map(c => ({ id: 'c' + c.i, sources: [c.from], targets: [c.to] })),
    });

    const box = new Map();
    const kinds = new Map(flow.map(n => [n.id, n.kind]));
    for (const c of res.children) box.set(c.id, { x: c.x + SIDE, y: c.y + LANE_TOP + 28, w: c.width, h: c.height, kind: kinds.get(c.id) });
    const paths = [];
    for (const e of res.edges || []) {
      const c = p.connectors[Number(e.id.slice(1))];
      for (const s of e.sections || []) {
        paths.push({ ...c, points: [s.startPoint, ...(s.bendPoints || []), s.endPoint].map(q => ({ x: q.x + SIDE, y: q.y + LANE_TOP + 28 })) });
      }
    }
    for (const [endId, decId] of errorUnder) {
      const d = box.get(decId);
      if (!d) continue;
      const b = { x: d.x + (d.w - EVENT) / 2, y: d.y + d.h + 48, w: EVENT, h: EVENT, kind: 'end' };
      box.set(endId, b);
      const c = p.connectors.find(x => x.from === decId && x.to === endId);
      paths.push({ ...c, points: [{ x: d.x + d.w / 2, y: d.y + d.h }, { x: d.x + d.w / 2, y: b.y }] });
    }

    const lanes = [];
    let y = 0;
    const service = [...box.values()];
    const serviceH = Math.max(160, ...service.map(b => b.y + b.h + (b.kind === 'step' ? 0 : 40))) + LANE_BOTTOM;
    const laneOf = { service: { top: 0, height: serviceH } };
    y = serviceH;

    const center = id => { const b = box.get(id); return b ? b.x + b.w / 2 : null; };
    const pack = items => {
      items.sort((a, b) => a.want - b.want);
      let right = SIDE - GAP;
      for (const it of items) { it.x = Math.max(it.want - it.w / 2, right + GAP); right = it.x + it.w; }
      return items;
    };
    const listeners = pack(p.steps.filter(s => top(s) && s.lane === 'async').map(s => {
      const pub = p.connectors.find(c => c.kind === 'async' && c.to === s.id);
      return { id: s.id, want: center(pub?.from) ?? SIDE, w: BOX.w, h: BOX.h, kind: 'step' };
    }));
    for (const it of listeners) box.set(it.id, { x: it.x, w: it.w, h: it.h, kind: 'step', lane: 'async' });
    const cards = list => pack(list.filter(c => box.has(c.step)).map(c => ({ id: c.id, want: center(c.step), w: CARD.w, h: CARD.h, kind: 'card' })));
    const tables = cards(p.tables);
    const systems = cards(p.systems);

    for (const lane of p.lanes) {
      if (lane.id === 'service') { lanes.push({ ...lane, ...laneOf.service }); continue; }
      const items = lane.id === 'database' ? tables : lane.id === 'external' ? systems : listeners;
      const h = Math.max(...items.map(i => i.h), CARD.h);
      const height = LANE_TOP + h + LANE_BOTTOM;
      for (const it of items) box.set(it.id, { ...box.get(it.id), x: it.x, y: y + LANE_TOP, w: it.w, h: it.h, kind: it.kind });
      lanes.push({ ...lane, top: y, height });
      y += height;
    }

    for (const c of p.connectors) {
      if (FLOW_KINDS.has(c.kind)) continue;
      const a = box.get(c.from), b = box.get(c.to);
      if (a && b) paths.push({ ...c, points: elbow(a, b) });
    }
    const width = Math.max(...[...box.values()].map(b => b.x + b.w)) + SIDE + 60;
    return { box, paths, lanes, width, height: y };
  }

  // ------------------------------------------------------------------------------------------
  // drawing
  // ------------------------------------------------------------------------------------------

  render(p, { box, paths, lanes, width, height }) {
    this.size = { width, height };
    const at = (id, cls, html, attrs = '') => {
      const b = box.get(id);
      return b ? `<div class="pv-at ${cls}" style="left:${b.x}px;top:${b.y}px;width:${b.w}px;height:${b.h}px"${attrs}>${html}</div>` : '';
    };
    const nodeBtn = (id, nodeId, cls, label, inner) =>
      at(id, '', `<button class="pv-node ${cls}" data-node-id="${esc(nodeId)}" data-pv="${esc(id)}" aria-label="${esc(label)}">${inner}</button>`);
    const out = [];
    for (const l of lanes) out.push(`<div class="pv-lane" style="top:${l.top}px;height:${l.height}px"><span class="pv-lane-label">${esc(l.label)}</span></div>`);
    out.push(`<svg class="pv-wires" width="${width}" height="${height}" aria-hidden="true"><defs>${
      ['sequence', 'branch', 'inferred', 'read', 'write', 'async', 'error'].map(k =>
        `<marker id="pv-arrow-${k}" class="k-${k}" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0 8 4 0 8z"/></marker>`).join('')
    }</defs>${paths.map(c => {
      const kind = c.kind === 'branch' && p.ends.some(e => e.id === c.to && e.kind === 'error') ? 'error' : c.kind;
      const pts = c.points.map(q => `${q.x},${q.y}`).join(' ');
      return `<polyline class="k-${kind}" points="${pts}" marker-end="url(#pv-arrow-${kind})"/>`;
    }).join('')}</svg>`);
    for (const c of paths) {
      if (!c.label) continue;
      const [a, b] = c.points;
      const vertical = Math.abs(a.x - b.x) < 1;
      out.push(`<span class="pv-elabel" style="left:${a.x + (vertical ? 8 : 10)}px;top:${a.y + (vertical ? 6 : -20)}px">${esc(c.label)}</span>`);
    }

    const s = p.start;
    out.push(nodeBtn('start', s.nodeId, 'pv-event start', `Start: ${s.http || s.handler}`, icon('start')));
    out.push(caption('start', box, `<b title="${esc(s.http || s.handler)}">${esc(s.http || s.handler)}</b><small>${esc(s.handler)}</small>`, 'below start'));
    for (const st of p.steps) {
      if (!box.has(st.id)) continue;
      if (st.kind === 'publish') {
        out.push(nodeBtn(st.id, st.nodeId, 'pv-event publish', `Publishes ${st.owner}`, icon('async')));
        out.push(caption(st.id, box, `<b>publishEvent</b><small>${esc(st.owner)}</small>`));
        continue;
      }
      const kind = st.kind === 'data' ? 'data' : st.listener ? 'async' : 'call';
      const flags = [st.inferred ? 'inferred' : '', st.recursive ? 'recursive' : '', st.kind === 'subprocess' ? 'sub' : ''].join(' ');
      const nodeId = st.kind === 'data' ? st.edge.target : st.nodeId;
      out.push(nodeBtn(st.id, nodeId, `pv-step k-${kind} ${flags}`, `${st.name} — ${st.owner || ''}`,
        `${icon(kind, 'tile')}<span class="pv-text"><b>${esc(st.name)}</b><small>${esc(st.owner || '')}${st.recursive ? ' · recursive call' : ''}</small></span>${st.kind === 'subprocess' ? '<span class="pv-plus" title="Sub-process">+</span>' : ''}`));
    }
    for (const d of p.decisions) {
      if (!box.has(d.id)) continue;
      out.push(nodeBtn(d.id, d.nodeId, 'pv-decision', `Decision: ${d.condition}`,
        `<svg viewBox="0 0 48 48" aria-hidden="true"><path class="diamond" d="M24 1.5 46.5 24 24 46.5 1.5 24Z"/></svg>${icon('decision', 'mark')}`));
      out.push(caption(d.id, box, `<b>${esc(d.label)}</b>`, 'above'));
    }
    for (const e of p.ends) {
      if (!box.has(e.id)) continue;
      const error = e.kind === 'error';
      out.push(nodeBtn(e.id, e.nodeId, `pv-event end ${error ? 'error' : ''}`, error ? `Error end: ${e.label}` : `End: returns ${e.label || 'nothing'}`, error ? icon('error') : ''));
      out.push(caption(e.id, box, error ? `<b>${esc(e.label)}</b>` : `<small>returns</small><b>${esc(e.label || 'void')}</b>`));
    }
    for (const t of p.tables) {
      out.push(nodeBtn(t.id, t.nodeId, 'pv-card', `${t.op} ${t.table}`,
        `${icon('data', 'tile k-data')}<span class="pv-text"><b>${esc(t.table)}</b></span><span class="op ${esc(t.op)}">${esc(t.op)}</span>`));
    }
    for (const sys of p.systems) {
      out.push(nodeBtn(sys.id, sys.nodeId, 'pv-card', `${sys.name} ${sys.detail}`,
        `${icon(sys.kind === 'topic' ? 'topic' : 'http', 'tile k-async')}<span class="pv-text"><b>${esc(sys.name)}</b><small>${esc(sys.detail)}</small></span>`));
    }
    this.stage.style.width = width + 'px';
    this.stage.style.height = height + 'px';
    this.stage.innerHTML = out.join('');
    this.order = [...this.stage.querySelectorAll('.pv-node')].sort((a, b) =>
      a.parentElement.offsetLeft - b.parentElement.offsetLeft || a.parentElement.offsetTop - b.parentElement.offsetTop);
    this.order.forEach((el, i) => { el.dataset.order = i; el.tabIndex = i === 0 ? 0 : -1; });
  }

  /** Highlight every element drawn for a graph node. */
  select(nodeId) {
    for (const el of this.stage.querySelectorAll('.pv-node.selected')) el.classList.remove('selected');
    for (const el of this.stage.querySelectorAll('.pv-node')) if (el.dataset.nodeId === nodeId) el.classList.add('selected');
  }

  onKey(e) {
    const keys = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    const el = e.target.closest('.pv-node');
    if (!el || !(e.key in keys)) return;
    e.preventDefault();
    const next = this.order[Number(el.dataset.order) + keys[e.key]];
    if (!next) return;
    el.tabIndex = -1;
    next.tabIndex = 0;
    next.focus({ preventScroll: true });
    next.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  // ------------------------------------------------------------------------------------------
  // zoom
  // ------------------------------------------------------------------------------------------

  setScale(s) {
    this.scale = Math.min(2, Math.max(0.25, s));
    this.stage.style.transform = `scale(${this.scale})`;
    if (this.size) {
      this.sizer.style.width = this.size.width * this.scale + 'px';
      this.sizer.style.height = this.size.height * this.scale + 'px';
    }
    this.h.onZoom?.(this.scale);
  }

  zoom(step) {
    this.setScale(Math.round((this.scale + step * 0.1) * 10) / 10);
  }

  fit({ maxScale = 2 } = {}) {
    if (!this.size) return;
    const w = this.viewport.clientWidth - 24, h = this.viewport.clientHeight - 24;
    this.setScale(w > 0 && h > 0 ? Math.min(maxScale, w / this.size.width, h / this.size.height) : 1);
  }
}

function caption(id, box, html, where = 'below') {
  const b = box.get(id);
  if (!b) return '';
  const y = where === 'above' ? b.y - 22 : where.includes('start') ? b.y + (b.h + BOX.h) / 2 + 6 : b.y + b.h + 6;
  const x = where.includes('start') ? b.x : b.x + b.w / 2;
  return `<div class="pv-caption ${where}" style="left:${x}px;top:${y}px">${html}</div>`;
}

/** An orthogonal connector between two boxes in different lanes: down/up, across, down/up. */
function elbow(a, b) {
  const ax = a.x + a.w / 2, bx = b.x + b.w / 2;
  const down = b.y > a.y;
  const y1 = down ? a.y + a.h : a.y, y2 = down ? b.y : b.y + b.h;
  if (Math.abs(ax - bx) < 1) return [{ x: ax, y: y1 }, { x: bx, y: y2 }];
  const mid = down ? y2 - 18 : y2 + 18;
  return [{ x: ax, y: y1 }, { x: ax, y: mid }, { x: bx, y: mid }, { x: bx, y: y2 }];
}
