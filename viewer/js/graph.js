// The Cytoscape-based flow view: styling from theme variables, view modes (all / endpoint /
// table / focus), layer filters, branch-node bridging, collapse, diff styling, layout.
import { cssVar } from './util.js';

export const LAYERS = [
  ['controller', 'Endpoint', '--controller'],
  ['service', 'Service', '--service'],
  ['repository', 'Repository', '--repository'],
  ['other', 'Other', '--other'],
  ['flow', 'Branches', '--branch'],
  ['db', 'Database', '--db-write'],
  ['external', 'External', '--external'],
];

const FLOW_TYPES = new Set(['branch', 'throw']);

export function layerOf(n) {
  if (n.type === 'endpoint') return 'controller';
  if (n.type === 'db') return 'db';
  if (n.type === 'external') return 'external';
  if (FLOW_TYPES.has(n.type)) return 'flow';
  if (n.layer === 'controller' || n.layer === 'service' || n.layer === 'repository') return n.layer;
  return 'other';
}

function opColor(op) {
  switch (op) {
    case 'INSERT': case 'UPSERT': return cssVar('--db-write');
    case 'UPDATE': return cssVar('--db-update');
    case 'DELETE': return cssVar('--db-delete');
    default: return cssVar('--db-read');
  }
}

export function nodeColor(n) {
  if (n.type === 'db') return opColor(n.op);
  if (n.type === 'external') return cssVar('--external');
  if (n.type === 'branch') return cssVar('--branch');
  if (n.type === 'throw') return cssVar('--throw');
  if (n.type === 'endpoint') return cssVar('--controller');
  return cssVar('--' + layerOf(n)) || cssVar('--other');
}

// Node sizes are computed from the wrapped label (Cytoscape's `width: label` is deprecated).
const measureCtx = document.createElement('canvas').getContext('2d');
const MAX_TEXT = 180;

function wrapLines(text, font) {
  measureCtx.font = font;
  const out = [];
  for (const para of String(text || '').split('\n')) {
    let line = '';
    for (const word of para.split(/(\s+)/)) {
      const next = line + word;
      if (line && measureCtx.measureText(next).width > MAX_TEXT && word.trim()) {
        out.push(line.trimEnd());
        line = word.trimStart();
      } else {
        line = next;
      }
    }
    out.push(line);
  }
  return out;
}

function sizeOf(display, type) {
  const fontSize = type === 'endpoint' ? 12 : type === 'branch' || type === 'throw' ? 10 : 11;
  const mono = type === 'db' || type === 'external';
  const font = `600 ${fontSize}px ${mono ? 'ui-monospace, Menlo, Consolas, monospace' : '-apple-system, Segoe UI, Roboto, sans-serif'}`;
  const lines = wrapLines(display, font);
  measureCtx.font = font;
  const textW = Math.min(MAX_TEXT, Math.max(...lines.map(l => measureCtx.measureText(l).width), 10));
  const textH = lines.length * (fontSize + 3);
  let w = textW + 22, h = textH + 18;
  if (type === 'branch') { w = textW * 1.45 + 30; h = textH * 1.7 + 30; }
  else if (type === 'db') { w += 8; h += 12; }
  else if (type === 'external') { w += 22; h += 8; }
  else if (type === 'throw') { w += 14; }
  return { w: Math.round(w), h: Math.round(h) };
}

export class FlowGraph {
  constructor(container, handlers) {
    this.container = container;
    this.handlers = handlers;
    this.cy = null;
    this.graph = null;
    this.dir = 'LR';
    this.layers = new Set(LAYERS.map(l => l[0]));
    this.showBranches = true;
    this.view = { mode: 'all' };
    this.moduleFilter = '';
    this.collapsed = new Set();
  }

  // ------------------------------------------------------------------------------------------
  // loading
  // ------------------------------------------------------------------------------------------

  load(graph) {
    this.graph = graph;
    graph.nodes = graph.nodes || [];
    graph.edges = graph.edges || [];
    this.byId = new Map(graph.nodes.map(n => [n.id, n]));
    this.out = new Map();
    this.in = new Map();
    for (const e of graph.edges) {
      if (!this.byId.has(e.source) || !this.byId.has(e.target)) continue;
      (this.out.get(e.source) || this.out.set(e.source, []).get(e.source)).push(e);
      (this.in.get(e.target) || this.in.set(e.target, []).get(e.target)).push(e);
    }
    this.collapsed.clear();
    this.bridges = this.computeBridges();

    if (this.cy) this.cy.destroy();
    this.cy = cytoscape({
      container: this.container,
      elements: this.elements(),
      style: this.style(),
      layout: { name: 'preset' },
      minZoom: 0.01,
      maxZoom: 3,
    });
    this.cy.on('tap', 'node', evt => this.handlers.onSelect?.(evt.target.data('raw')));
    this.cy.on('tap', 'edge', evt => this.handlers.onEdge?.(evt.target.data()));
    this.cy.on('tap', evt => { if (evt.target === this.cy) this.handlers.onBackground?.(); });
    this.cy.on('dbltap', 'node', evt => this.toggleCollapse(evt.target.id()));
    this.cy.on('viewport render', () => this.handlers.onViewport?.());
    if (!this.resizeObserver) {
      // keep Cytoscape's idea of its container size in sync (sidebars, window resizes)
      this.resizeObserver = new ResizeObserver(() => this.cy && this.cy.resize());
      this.resizeObserver.observe(this.container);
    }
  }

  elements() {
    const els = [];
    for (const n of this.graph.nodes) {
      let display = n.type === 'endpoint' && n.http ? `${n.http}\n${n.label}` : n.label;
      if ((n.type === 'branch' || n.type === 'throw') && display.length > 46) display = display.slice(0, 44) + '…';
      const { w, h } = sizeOf(display, n.type);
      const i = els.length;
      els.push({
        group: 'nodes',
        // distinct start positions: overlapping nodes trip Cytoscape's edge geometry before layout
        position: { x: (i % 40) * 220, y: Math.floor(i / 40) * 90 },
        data: { id: n.id, display, w, h, type: n.type, op: n.op, confidence: n.confidence, diff: n.diff, layerKey: layerOf(n), raw: n },
      });
    }
    this.graph.edges.forEach((e, i) => {
      if (!this.byId.has(e.source) || !this.byId.has(e.target)) return;
      els.push({ group: 'edges', data: { id: 'e' + i, source: e.source, target: e.target, label: e.label || '', kind: e.kind, confidence: e.confidence, diff: e.diff } });
    });
    this.bridges.forEach((b, i) => {
      els.push({ group: 'edges', classes: 'bridge', data: { id: 'b' + i, source: b.source, target: b.target, label: b.label, kind: b.kind, confidence: b.confidence, diff: b.diff } });
    });
    return els;
  }

  /** Direct edges that skip branch/throw nodes, shown when branches are hidden. */
  computeBridges() {
    const res = [];
    const seen = new Set();
    for (const n of this.graph.nodes) {
      if (FLOW_TYPES.has(n.type)) continue;
      const stack = [...(this.out.get(n.id) || [])];
      const visited = new Set();
      while (stack.length) {
        const e = stack.pop();
        const t = this.byId.get(e.target);
        if (!t || visited.has(e.target + '|' + e.label)) continue;
        visited.add(e.target + '|' + e.label);
        if (FLOW_TYPES.has(t.type)) {
          for (const nx of this.out.get(t.id) || []) stack.push(nx);
          continue;
        }
        if (e.kind === 'branch' || (this.byId.get(e.source) && FLOW_TYPES.has(this.byId.get(e.source).type))) {
          const label = String(e.label || '').replace(/^(then|else|each|loop|default|on error|case [^·]+) · /, '');
          const key = n.id + '>' + t.id + '>' + label;
          if (!seen.has(key)) {
            seen.add(key);
            res.push({ source: n.id, target: t.id, label, kind: e.kind, confidence: e.confidence, diff: e.diff });
          }
        }
      }
    }
    return res;
  }

  // ------------------------------------------------------------------------------------------
  // styling
  // ------------------------------------------------------------------------------------------

  style() {
    const v = cssVar;
    return [
      { selector: 'node', style: {
        'background-color': ele => nodeColor(ele.data('raw')),
        'shape': 'round-rectangle', 'label': 'data(display)', 'color': '#fff',
        'font-size': 11, 'font-weight': 600, 'text-valign': 'center', 'text-halign': 'center',
        'text-wrap': 'wrap', 'text-max-width': MAX_TEXT + 2, 'width': 'data(w)', 'height': 'data(h)',
        'border-width': 1.5, 'border-color': 'rgba(255,255,255,0.18)',
      } },
      { selector: 'node[type="endpoint"]', style: { 'border-width': 3, 'border-color': v('--text'), 'font-size': 12 } },
      { selector: 'node[type="branch"]', style: { 'shape': 'diamond', 'font-size': 10 } },
      { selector: 'node[type="throw"]', style: { 'shape': 'tag', 'font-size': 10 } },
      { selector: 'node[type="db"]', style: { 'shape': 'barrel', 'font-family': 'ui-monospace, Menlo, Consolas, monospace' } },
      { selector: 'node[type="external"]', style: { 'shape': 'hexagon', 'font-family': 'ui-monospace, Menlo, Consolas, monospace' } },
      { selector: 'node[confidence="low"]', style: { 'border-style': 'dashed', 'border-width': 2.5, 'border-color': v('--db-update') } },
      { selector: 'edge', style: {
        'width': 1.6, 'line-color': v('--edge'), 'target-arrow-color': v('--edge-arrow'), 'target-arrow-shape': 'triangle',
        'curve-style': 'bezier', 'label': 'data(label)', 'font-size': 9, 'color': v('--edge-label'),
        'text-background-color': v('--bg'), 'text-background-opacity': 0.9, 'text-background-padding': 2,
        'arrow-scale': 0.9,
      } },
      { selector: 'edge[kind="branch"]', style: { 'line-color': v('--branch'), 'target-arrow-color': v('--branch'), 'width': 1.3 } },
      { selector: 'edge[kind="event"]', style: { 'line-style': 'dashed', 'line-color': v('--external'), 'target-arrow-color': v('--external') } },
      { selector: 'edge[confidence="medium"]', style: { 'line-style': 'dashed' } },
      { selector: 'edge[confidence="low"]', style: { 'line-style': 'dotted', 'width': 2 } },
      // diff mode
      { selector: 'node[diff="added"]', style: { 'border-width': 4, 'border-color': v('--diff-added'), 'border-style': 'solid' } },
      { selector: 'node[diff="changed"]', style: { 'border-width': 4, 'border-color': v('--diff-changed'), 'border-style': 'solid' } },
      { selector: 'node[diff="affected"]', style: { 'border-width': 4, 'border-color': v('--diff-affected'), 'border-style': 'double' } },
      { selector: 'node[diff="removed"]', style: { 'opacity': 0.5, 'border-width': 3, 'border-color': v('--diff-removed'), 'border-style': 'dashed' } },
      { selector: 'edge[diff="added"]', style: { 'line-color': v('--diff-added'), 'target-arrow-color': v('--diff-added'), 'width': 2.4 } },
      { selector: 'edge[diff="removed"]', style: { 'line-color': v('--diff-removed'), 'target-arrow-color': v('--diff-removed'), 'line-style': 'dashed', 'opacity': 0.6 } },
      { selector: 'node.collapsed', style: { 'border-style': 'double', 'border-width': 6, 'border-color': v('--muted') } },
      { selector: 'node.dim', style: { 'opacity': 0.25 } },
      { selector: 'node.hit', style: { 'border-width': 4, 'border-color': v('--accent') } },
      { selector: 'node:selected', style: { 'border-width': 4, 'border-color': v('--selected'), 'border-style': 'solid' } },
      { selector: 'edge:selected', style: { 'width': 3, 'line-color': v('--selected'), 'target-arrow-color': v('--selected') } },
    ];
  }

  restyle() {
    if (this.cy) this.cy.style(this.style());
  }

  // ------------------------------------------------------------------------------------------
  // traversal
  // ------------------------------------------------------------------------------------------

  /** Forward reachability; collapsed nodes are shown but not expanded. */
  reachable(roots, blockers = this.collapsed) {
    const seen = new Set(roots);
    const stack = roots.filter(r => !blockers.has(r));
    while (stack.length) {
      const cur = stack.pop();
      for (const e of this.out.get(cur) || []) {
        if (seen.has(e.target)) continue;
        seen.add(e.target);
        if (!blockers.has(e.target)) stack.push(e.target);
      }
    }
    return seen;
  }

  reaching(targets) {
    const seen = new Set(targets);
    const stack = [...targets];
    while (stack.length) {
      const cur = stack.pop();
      for (const e of this.in.get(cur) || []) if (!seen.has(e.source)) { seen.add(e.source); stack.push(e.source); }
    }
    return seen;
  }

  endpoints() {
    return this.graph.nodes.filter(n => n.type === 'endpoint' && (!this.moduleFilter || n.module === this.moduleFilter));
  }

  // ------------------------------------------------------------------------------------------
  // view modes
  // ------------------------------------------------------------------------------------------

  showAll() { this.view = { mode: 'all' }; return this.apply(); }
  showEndpoint(id) { this.view = { mode: 'endpoint', id }; return this.apply(); }
  showTable(table) { this.view = { mode: 'table', table }; return this.apply(); }
  focus(id) {
    if (this.view.mode !== 'focus') this.prevView = this.view;
    this.view = { mode: 'focus', id };
    return this.apply();
  }
  exitFocus() { this.view = this.prevView || { mode: 'all' }; return this.apply(); }

  toggleCollapse(id) {
    const n = this.byId.get(id);
    if (!n || !(this.out.get(id) || []).length) return;
    if (this.collapsed.has(id)) this.collapsed.delete(id); else this.collapsed.add(id);
    this.apply();
  }

  visibleSet() {
    const v = this.view;
    let ids;
    if (v.mode === 'endpoint') {
      ids = this.reachable([v.id]);
    } else if (v.mode === 'table') {
      const sinks = this.graph.nodes.filter(n => (n.type === 'db' || n.type === 'external') && n.table === v.table).map(n => n.id);
      const up = this.reaching(sinks);
      const eps = this.endpoints().filter(n => up.has(n.id)).map(n => n.id);
      const down = this.reachable(eps, new Set());
      ids = new Set([...down].filter(id => up.has(id)));
      sinks.forEach(s => ids.add(s));
    } else if (v.mode === 'focus') {
      ids = new Set([v.id]);
      let frontier = [v.id];
      for (let hop = 0; hop < 2; hop++) {
        const next = [];
        for (const id of frontier) {
          for (const e of this.out.get(id) || []) if (!ids.has(e.target)) { ids.add(e.target); next.push(e.target); }
          for (const e of this.in.get(id) || []) if (!ids.has(e.source)) { ids.add(e.source); next.push(e.source); }
        }
        frontier = next;
      }
    } else {
      ids = this.reachable(this.endpoints().map(n => n.id));
    }
    // layers + branch toggle
    const out = new Set();
    for (const id of ids) {
      const n = this.byId.get(id);
      if (!n) continue;
      const layer = layerOf(n);
      if (layer === 'flow' && !this.showBranches) continue;
      if (layer !== 'controller' && !this.layers.has(layer)) continue;
      out.add(id);
    }
    if (v.id && this.byId.has(v.id)) out.add(v.id);
    return out;
  }

  apply() {
    if (!this.cy) return 0;
    const vis = this.visibleSet();
    this.cy.batch(() => {
      this.cy.nodes().forEach(n => {
        n.style('display', vis.has(n.id()) ? 'element' : 'none');
        n.toggleClass('collapsed', this.collapsed.has(n.id()));
      });
      this.cy.edges().forEach(e => {
        const both = vis.has(e.data('source')) && vis.has(e.data('target'));
        const isBridge = e.hasClass('bridge');
        const show = both && (isBridge ? !this.showBranches : true);
        e.style('display', show ? 'element' : 'none');
      });
    });
    this.relayout();
    return vis.size;
  }

  relayout() {
    if (!this.cy) return;
    this.cy.resize();
    const eles = this.cy.elements().filter(e => e.style('display') !== 'none');
    if (!eles.length) return;
    eles.layout({
      name: 'dagre', rankDir: this.dir, nodeSep: 26, rankSep: 80, edgeSep: 10,
      nodeDimensionsIncludeLabels: true, animate: false, fit: false,
    }).run();
    this.fit();
  }

  /** Fit the visible elements, but never zoom in so far that a small flow looks oversized. */
  fit() {
    if (!this.cy) return;
    const eles = this.cy.elements().filter(e => e.style('display') !== 'none');
    if (!eles.length) return;
    this.cy.fit(eles, 40);
    if (this.cy.zoom() > 1.3) {
      this.cy.zoom(1.3);
      this.cy.center(eles);
    }
  }

  select(id) {
    if (!this.cy) return;
    this.cy.$(':selected').unselect();
    const n = this.cy.getElementById(id);
    if (n.nonempty()) {
      n.select();
      // pan only if the node is (partly) off-screen
      const bb = n.renderedBoundingBox();
      const off = bb.x1 < 0 || bb.y1 < 0 || bb.x2 > this.cy.width() || bb.y2 > this.cy.height();
      if (n.visible() && off) this.cy.animate({ center: { eles: n }, duration: 200 });
    }
  }

  highlight(ids) {
    if (!this.cy) return;
    this.cy.batch(() => this.cy.nodes().forEach(n => n.toggleClass('hit', ids.has(n.id()))));
  }

  visibleNodes() {
    return this.cy ? this.cy.nodes().filter(n => n.style('display') !== 'none') : [];
  }

  visibleEdges() {
    return this.cy ? this.cy.edges().filter(e => e.style('display') !== 'none') : [];
  }
}
