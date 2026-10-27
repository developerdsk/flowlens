// The process view: an endpoint's flow (js/process.js) drawn as steps in lanes. ELK lays out the
// service lane (opened sub-processes are compound nodes); tables, async listeners and external
// systems sit in their lanes under the step that uses them, so their connectors stay short and straight.
import { esc } from './util.js';
import { buildProcess, MAX_DEPTH } from './process.js';

const BOX = { w: 184, h: 64 };
const EVENT = 36;
const DECISION = 48;
const CARD = { w: 184, h: 44 };
const SIDE = 48;
const LANE_TOP = 44;
const LANE_BOTTOM = 32;
const GAP = 20;
const FLOW_KINDS = new Set(['sequence', 'branch', 'inferred']);
const CONTAINER_PADDING = '[top=52,left=20,bottom=24,right=20]';
const ELK_OPTIONS = {
  'elk.algorithm': 'layered', 'elk.direction': 'RIGHT', 'elk.edgeRouting': 'ORTHOGONAL',
  'elk.spacing.nodeNode': '36', 'elk.layered.spacing.nodeNodeBetweenLayers': '48',
  'elk.layered.spacing.edgeNodeBetweenLayers': '24', 'elk.layered.nodePlacement.bk.fixedAlignment': 'BALANCED',
  'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
};

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
    this.open = new Set();
    this.openAll = false;
    this.last = null;
    this.stage.addEventListener('click', e => {
      const toggle = e.target.closest('[data-toggle]');
      if (toggle) return this.toggle(toggle.dataset.toggle);
      const el = e.target.closest('[data-node-id]');
      if (el) this.h.onSelect(el.dataset.nodeId, this.via(el.dataset.pv));
    });
    this.stage.addEventListener('keydown', e => this.onKey(e));
  }

  /**
   * Build, lay out and draw the process of an endpoint. A new endpoint starts with every sub-process
   * collapsed and is fitted to the screen; redrawing the same one keeps what is open and the zoom.
   */
  async show(graph, endpointId) {
    const fresh = endpointId !== this.endpointId || graph !== this.graph;
    if (endpointId !== this.endpointId) { this.open = new Set(); this.openAll = false; this.last = null; }
    this.graph = graph;
    this.endpointId = endpointId;
    const token = ++this.token;
    const p = buildProcess(graph, endpointId, { expanded: this.openAll || [...this.open] });
    if (!p) return null;
    const placed = await this.layout(p);
    if (token !== this.token) return null;
    this.model = p;
    this.render(p, placed);
    this.fit({ maxScale: fresh ? 1 : this.scale });
    this.h.onModel?.(p, this.path(p));
    return p;
  }

  /** Open or close one sub-process in place. */
  async toggle(id) {
    if (this.openAll) {
      this.open = new Set(this.model.subprocesses.filter(s => s.expanded).map(s => s.id));
      this.openAll = false;
    }
    const opening = !this.open.has(id);
    if (opening) this.open.add(id); else this.open.delete(id);
    this.last = opening ? id : this.model.steps.find(s => s.id === id)?.parent ?? null;
    await this.show(this.graph, this.endpointId);
    this.stage.querySelector(`[data-toggle="${CSS.escape(id)}"]`)?.focus({ preventScroll: true });
  }

  expandAll() {
    this.openAll = true;
    this.last = null;
    return this.show(this.graph, this.endpointId);
  }

  collapseAll() {
    this.openAll = false;
    this.open = new Set();
    this.last = null;
    return this.show(this.graph, this.endpointId);
  }

  get anyOpen() {
    return !!this.model?.subprocesses.some(s => s.expanded);
  }

  /** The names of the open sub-processes from the outermost down to the one opened last, for the breadcrumb. */
  path(p) {
    const names = [];
    for (let s = p.steps.find(x => x.id === this.last); s; s = p.steps.find(x => x.id === s.parent)) {
      if (s.expanded) names.unshift(s.name);
    }
    return names;
  }

  // ------------------------------------------------------------------------------------------
  // layout
  // ------------------------------------------------------------------------------------------

  async layout(p) {
    const errorUnder = new Map();
    for (const d of p.decisions) {
      if (d.parent != null) continue;
      for (const br of d.branches) {
        const end = br.items.length === 1 && p.ends.find(e => e.id === br.items[0] && e.kind === 'error');
        if (end) errorUnder.set(end.id, d.id);
      }
    }
    const items = new Map();
    const children = new Map();
    const add = (parent, item) => {
      items.set(item.id, item);
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent).push(item);
    };
    const opened = new Set(p.subprocesses.filter(s => s.expanded && s.steps.length).map(s => s.id));
    add('service', { id: 'start', kind: 'start', w: EVENT, h: EVENT });
    for (const s of p.steps) {
      const parent = s.parent ?? (s.lane === 'async' ? null : 'service');
      const item = opened.has(s.id) ? { id: s.id, kind: 'container' }
        : s.kind === 'publish' ? { id: s.id, kind: 'publish', w: EVENT, h: EVENT } : { id: s.id, kind: 'step', ...BOX };
      add(parent ?? `async:${s.id}`, item);
    }
    for (const d of p.decisions) add(d.parent ?? 'service', { id: d.id, kind: 'decision', w: DECISION, h: DECISION });
    for (const e of p.ends) if (!errorUnder.has(e.id)) add(e.parent ?? 'service', { id: e.id, kind: 'end', w: EVENT, h: EVENT });

    const box = new Map();
    const paths = [];
    const placeFlow = async (roots, dx, dy) => {
      const ids = new Set();
      const node = it => {
        ids.add(it.id);
        if (it.kind !== 'container') return { id: it.id, width: it.w, height: it.h };
        return {
          id: it.id, children: (children.get(it.id) || []).map(node),
          layoutOptions: { ...ELK_OPTIONS, 'elk.padding': CONTAINER_PADDING, 'elk.nodeSize.constraints': 'MINIMUM_SIZE', 'elk.nodeSize.minimum': `(${BOX.w + 40}, ${BOX.h + 76})` },
        };
      };
      const graph = roots.map(node);
      const edges = p.connectors.map((c, i) => ({ ...c, i })).filter(c => FLOW_KINDS.has(c.kind) && ids.has(c.from) && ids.has(c.to));
      const nested = roots.some(r => r.kind === 'container') || [...ids].some(id => items.get(id).kind === 'container');
      const run = order => {
        const withOrder = n => (n.children ? { ...n, children: n.children.map(withOrder), layoutOptions: { ...n.layoutOptions, 'elk.layered.considerModelOrder.strategy': order } } : n);
        return new window.ELK().layout({
          id: 'root', children: graph.map(withOrder),
          layoutOptions: {
            ...ELK_OPTIONS, 'elk.layered.considerModelOrder.strategy': order, 'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
            'elk.json.shapeCoords': 'ROOT', 'elk.json.edgeCoords': 'ROOT', 'elk.padding': '[top=0,left=0,bottom=0,right=0]',
          },
          edges: edges.map(c => ({ id: 'c' + c.i, sources: [c.from], targets: [c.to] })),
        });
      };
      // ELK 0.11 throws inside nested layouts when model order covers nodes, so containers order by edges only.
      const res = await run(nested ? 'PREFER_EDGES' : 'NODES_AND_EDGES').catch(() => run('NONE'));
      const walk = list => {
        for (const n of list || []) {
          box.set(n.id, { x: n.x + dx, y: n.y + dy, w: n.width, h: n.height, kind: items.get(n.id).kind });
          walk(n.children);
        }
      };
      walk(res.children);
      for (const e of res.edges || []) {
        const c = p.connectors[Number(e.id.slice(1))];
        for (const sec of e.sections || []) {
          paths.push({ ...c, points: [sec.startPoint, ...(sec.bendPoints || []), sec.endPoint].map(q => ({ x: q.x + dx, y: q.y + dy })) });
        }
      }
      return res;
    };

    await placeFlow(children.get('service') || [], SIDE, LANE_TOP + 28);
    for (const [endId, decId] of errorUnder) {
      const d = box.get(decId);
      if (!d) continue;
      const b = { x: d.x + (d.w - EVENT) / 2, y: d.y + d.h + 48, w: EVENT, h: EVENT, kind: 'end' };
      box.set(endId, b);
      const c = p.connectors.find(x => x.from === decId && x.to === endId);
      paths.push({ ...c, points: [{ x: d.x + d.w / 2, y: d.y + d.h }, { x: d.x + d.w / 2, y: b.y }] });
    }

    const listenerRoots = p.steps.filter(s => s.parent == null && s.lane === 'async');
    const listenerSizes = new Map();
    for (const s of listenerRoots) {
      const res = await placeFlow(children.get(`async:${s.id}`), 0, 0);
      listenerSizes.set(s.id, { w: res.width, h: res.height });
    }

    const lanes = [];
    let y = 0;
    const service = [...box.values()];
    const serviceH = Math.max(160, ...service.map(b => b.y + b.h + (b.kind === 'step' || b.kind === 'container' ? 0 : 40))) + LANE_BOTTOM;
    const laneOf = { service: { top: 0, height: serviceH } };
    y = serviceH;

    const center = id => { const b = box.get(id); return b ? b.x + b.w / 2 : null; };
    const pack = items => {
      items.sort((a, b) => a.want - b.want);
      let right = SIDE - GAP;
      for (const it of items) { it.x = Math.max(it.want - it.w / 2, right + GAP); right = it.x + it.w; }
      return items;
    };
    const listeners = pack(listenerRoots.map(s => {
      const pub = p.connectors.find(c => c.kind === 'async' && c.to === s.id);
      return { id: s.id, want: center(pub?.from) ?? SIDE, ...listenerSizes.get(s.id), kind: items.get(s.id).kind };
    }));
    const cards = list => pack(list.filter(c => box.has(c.step)).map(c => ({ id: c.id, want: center(c.step), w: CARD.w, h: CARD.h, kind: 'card' })));
    for (const it of listeners) shiftFlow(it.id, it.x, 0);

    for (const lane of p.lanes) {
      if (lane.id === 'service') { lanes.push({ ...lane, ...laneOf.service }); continue; }
      const placed = lane.id === 'database' ? cards(p.tables) : lane.id === 'external' ? cards(p.systems) : listeners;
      const h = Math.max(...placed.map(i => i.h), CARD.h);
      const height = LANE_TOP + h + LANE_BOTTOM;
      for (const it of placed) {
        if (lane.id === 'async') shiftFlow(it.id, it.x, y + LANE_TOP);
        else box.set(it.id, { x: it.x, y: y + LANE_TOP, w: it.w, h: it.h, kind: it.kind });
      }
      lanes.push({ ...lane, top: y, height });
      y += height;
    }

    function shiftFlow(rootId, x, top) {
      const inside = new Set([rootId]);
      for (const s of p.steps) if (s.parent && inside.has(s.parent)) inside.add(s.id);
      for (const list of [p.decisions, p.ends]) for (const x of list) if (x.parent && inside.has(x.parent)) inside.add(x.id);
      const dx = x - box.get(rootId).x, dy = top - box.get(rootId).y;
      for (const id of inside) { const b = box.get(id); if (b) { b.x += dx; b.y += dy; } }
      for (const c of paths) {
        if (inside.has(c.from) && inside.has(c.to)) c.points = c.points.map(q => ({ x: q.x + dx, y: q.y + dy }));
      }
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
    const at = (id, cls, html) => {
      const b = box.get(id);
      return b ? `<div class="pv-at ${cls}" style="left:${b.x}px;top:${b.y}px;width:${b.w}px;height:${b.h}px">${html}</div>` : '';
    };
    const button = (id, nodeId, cls, label, inner) =>
      `<button class="pv-node ${cls}" data-node-id="${esc(nodeId)}" data-pv="${esc(id)}" aria-label="${esc(label)}">${inner}</button>`;
    const nodeBtn = (id, nodeId, cls, label, inner, extra = '') => at(id, '', button(id, nodeId, cls, label, inner) + extra);
    const subs = new Map(p.subprocesses.map(x => [x.id, x]));
    const toggle = (st, open) => {
      const sub = subs.get(st.id);
      if (sub?.capped) {
        return `<button class="pv-toggle" disabled title="Sub-processes open ${MAX_DEPTH} levels deep at most, and a process holds a limited number of steps" aria-label="${esc(st.name)} cannot open further">+</button>`;
      }
      return `<button class="pv-toggle${open ? ' open' : ''}" data-toggle="${esc(st.id)}" aria-expanded="${open}" aria-label="${open ? 'Collapse' : 'Expand'} ${esc(st.name)}">${open ? '−' : '+'}</button>`;
    };
    const out = [];
    for (const l of lanes) out.push(`<div class="pv-lane" style="top:${l.top}px;height:${l.height}px"><span class="pv-lane-label">${esc(l.label)}</span></div>`);
    for (const st of p.steps) {
      if (box.get(st.id)?.kind !== 'container') continue;
      out.push(at(st.id, 'pv-container' + (st.lane === 'async' ? ' async' : ''),
        `<div class="pv-ctitle">${button(st.id, st.nodeId, 'pv-cname', `${st.name} — ${st.owner || ''}`,
          `<b>${esc(st.name)}</b><small>${esc(st.owner || '')}${st.listener ? ' · listener' : ''}</small>`)}${toggle(st, true)}</div>`));
    }
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
      if (!box.has(st.id) || box.get(st.id).kind === 'container') continue;
      if (st.kind === 'publish') {
        out.push(nodeBtn(st.id, st.nodeId, 'pv-event publish', `Publishes ${st.owner}`, icon('async')));
        out.push(caption(st.id, box, `<b>publishEvent</b><small>${esc(st.owner)}</small>`));
        continue;
      }
      const kind = st.kind === 'data' ? 'data' : st.listener ? 'async' : 'call';
      const flags = [st.inferred ? 'inferred' : '', st.recursive ? 'recursive' : '', st.kind === 'subprocess' ? 'sub' : ''].join(' ');
      const nodeId = st.kind === 'data' ? st.edge.target : st.nodeId;
      out.push(nodeBtn(st.id, nodeId, `pv-step k-${kind} ${flags}`, `${st.name} — ${st.owner || ''}`,
        `${icon(kind, 'tile')}<span class="pv-text"><b>${esc(st.name)}</b><small>${esc(st.owner || '')}${st.recursive ? ' · recursive call' : ''}</small></span>`,
        st.kind === 'subprocess' ? toggle(st, false) : ''));
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

  /** The call that reached a drawn element: its edge and call line, for the detail panel. */
  via(pvId) {
    const p = this.model;
    const stepId = p.tables.find(t => t.id === pvId)?.step ?? p.systems.find(s => s.id === pvId)?.step ?? pvId;
    const step = p.steps.find(s => s.id === stepId);
    return step?.edge && step.kind !== 'publish' ? { ...step.edge, line: step.line } : null;
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
