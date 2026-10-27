// The process model: one endpoint's flow as ordered steps, decisions, ends, lanes and data cards,
// following the design's Notation board. Pure data (no DOM), so `node --test` can run it.

export const LANES = [
  ['service', 'Service'],
  ['database', 'Database'],
  ['async', 'Async listeners'],
  ['external', 'External systems'],
];

const OWNERS = {
  jdbc: 'JdbcTemplate', jdbcClient: 'JdbcClient', em: 'EntityManager', mongo: 'MongoTemplate',
  dsl: 'DSLContext', redis: 'RedisTemplate', rest: 'RestTemplate', http: 'HTTP client',
  kafka: 'KafkaTemplate', rabbit: 'RabbitTemplate', jms: 'JmsTemplate', stream: 'StreamBridge',
};
const BROKERS = { kafka: 'Kafka topic', rabbit: 'RabbitMQ queue', jms: 'JMS queue', stream: 'Stream binding' };
const READS = new Set(['SELECT']);
const DELETES = new Set(['DELETE']);
const SIDE_LABEL = { then: 'yes', else: 'no' };
const OTHER_SIDE = { then: 'no', else: 'yes', each: 'done', loop: 'done' };

/** Nodes by id and each node's outgoing edges in call order (`seq`, else edge-list order). */
export function indexGraph(graph) {
  const byId = new Map((graph.nodes || []).map(n => [n.id, n]));
  const out = new Map();
  (graph.edges || []).forEach((e, i) => {
    if (!byId.has(e.source) || !byId.has(e.target)) return;
    if (!out.has(e.source)) out.set(e.source, []);
    out.get(e.source).push({ e, order: i });
  });
  for (const [id, list] of out) {
    list.sort((a, b) => (a.e.seq ?? Infinity) - (b.e.seq ?? Infinity) || a.order - b.order);
    out.set(id, list.map(x => x.e));
  }
  return { byId, out, outOf: id => out.get(id) || [] };
}

/** "AuditService.record()" -> { owner: "AuditService", name: "record()" }. */
export function splitCall(label) {
  const text = String(label || '').replace(/^.* · /, '');
  const dot = text.lastIndexOf('.', text.indexOf('(') < 0 ? text.length : text.indexOf('('));
  if (dot < 0) return { owner: null, name: text };
  const owner = text.slice(0, dot);
  return { owner: OWNERS[owner] || owner, name: text.slice(dot + 1) };
}

/** "if (accountRepository.existsByEmail(request.getEmail()))" -> "existsByEmail(…)". */
export function shortCondition(label) {
  let s = String(label || '').replace(/^(if|switch|while|for|do … while|catch)\s*\((.*)\)$/s, '$2').replace(/ \?$/, '');
  for (let prev; prev !== s;) {
    prev = s;
    s = s.replace(/\(([^()]*\([^()]*\)[^()]*|[^()]+)\)/g, '(…)');
  }
  return s.replace(/\b[a-z_$][\w$]*\.(?=[a-z_$][\w$]*\()/g, '');
}

function sideOf(edge) {
  const label = edge.label || '';
  const cut = label.indexOf(' · ');
  return cut < 0 ? label : label.slice(0, cut);
}

function system(node) {
  if (node.tech === 'http') {
    const target = node.table || node.detail || node.label;
    let name = target, path = '';
    try {
      const u = new URL(target);
      name = u.host;
      path = u.pathname;
    } catch {
      const m = /^(?:[a-z]+:\/\/)?([^/]+)(\/.*)?$/i.exec(target || '');
      if (m) { name = m[1]; path = m[2] || ''; }
    }
    return { kind: 'http', name, detail: `${node.op || ''} ${path}`.trim() };
  }
  return { kind: 'topic', name: node.table || node.label, detail: BROKERS[node.tech] || 'Message channel' };
}

/** How deep sub-processes open inside each other, and how many steps a process may hold, before the rest stays collapsed. */
export const MAX_DEPTH = 4;
export const MAX_STEPS = 400;

/**
 * Build the process for an endpoint. `expanded` holds the ids of sub-process steps to open in
 * place (or `true` for all of them). Returns null when the endpoint is not in the graph.
 */
export function buildProcess(graph, endpointId, { expanded = [], index } = {}) {
  const g = index || indexGraph(graph);
  const ep = g.byId.get(endpointId);
  if (!ep) return null;
  const openAll = expanded === true;
  const open = new Set(openAll ? [] : expanded);

  const p = {
    start: null, steps: [], decisions: [], ends: [], lanes: [], connectors: [],
    tables: [], systems: [], subprocesses: [], summary: null,
  };
  const usedIds = new Set();
  const uniqueId = base => {
    let id = base, n = 1;
    while (usedIds.has(id)) id = `${base}~${++n}`;
    usedIds.add(id);
    return id;
  };

  const epOut = g.outOf(ep.id);
  const only = epOut.length === 1 ? g.byId.get(epOut[0].target) : null;
  const delegate = only && only.type === 'method' && (epOut[0].kind === 'call' || epOut[0].kind === 'dispatch') ? only : null;
  const handler = splitCall(ep.label);
  p.start = {
    id: 'start', nodeId: ep.id, kind: ep.kind || 'http', verb: ep.verb, path: ep.path, http: ep.http,
    owner: handler.owner, handler: handler.name, returns: ep.returns, delegate: delegate ? delegate.id : null,
  };
  usedIds.add('start');

  function addCard(stepId, node, edge) {
    const id = uniqueId(`${stepId}|${node.id}`);
    if (node.type === 'db') p.tables.push({ id, step: stepId, nodeId: node.id, table: node.table, op: node.op, tech: node.tech, edge });
    else p.systems.push({ id, step: stepId, nodeId: node.id, tech: node.tech, op: node.op, ...system(node), edge });
    p.connectors.push(node.type === 'db' && READS.has(node.op)
      ? { from: id, to: stepId, kind: 'read' } : { from: stepId, to: id, kind: 'write' });
  }

  /** Every table and system a collapsed sub-process touches, attached to its step. */
  function attachHiddenData(stepId, nodeId) {
    const seen = new Set([nodeId]);
    const cards = new Set();
    const queue = [nodeId];
    while (queue.length) {
      for (const e of g.outOf(queue.shift())) {
        const t = g.byId.get(e.target);
        if (t.type === 'db' || t.type === 'external') {
          if (!cards.has(t.id)) { cards.add(t.id); addCard(stepId, t, null); }
        } else if (!seen.has(t.id)) {
          seen.add(t.id);
          queue.push(t.id);
        }
      }
    }
  }

  function step(fields) {
    const s = { inferred: false, recursive: false, ...fields };
    p.steps.push(s);
    return s;
  }

  /** The ordered items (step / publish / decision / end ids) a node's outgoing edges produce. */
  function sequence(nodeId, ctx) {
    const items = [];
    for (const e of g.outOf(nodeId)) items.push(...itemsFor(e, ctx));
    return items;
  }

  function itemsFor(e, ctx) {
    const t = g.byId.get(e.target);
    const inferred = e.kind === 'dispatch' || e.confidence === 'low';
    if (t.type === 'db' || t.type === 'external') {
      const call = splitCall(e.label);
      const s = step({
        id: uniqueId(`${ctx.prefix}/${e.label}`), kind: 'data', nodeId: ctx.nodeId, edge: edgeRef(e),
        name: call.name, owner: call.owner, lane: ctx.lane, parent: ctx.parent, line: e.line, inferred,
      });
      addCard(s.id, t, edgeRef(e));
      return [{ type: 'step', id: s.id, inferred }];
    }
    if (t.type === 'throw') {
      const end = {
        id: uniqueId(`${ctx.prefix}/${t.id}`), kind: 'error', label: t.label.replace(/^throw /, ''), nodeId: t.id, line: e.line ?? t.line,
        lane: ctx.lane, parent: ctx.parent,
      };
      p.ends.push(end);
      return [{ type: 'end', id: end.id }];
    }
    if (t.type === 'branch') return branchItems(t, ctx);
    if (e.kind === 'event') {
      const pub = step({
        id: uniqueId(`${ctx.prefix}/publish ${e.label}`), kind: 'publish', nodeId: ctx.nodeId, edge: edgeRef(e),
        name: 'publishEvent', owner: e.label.replace(/^.*?event /, ''), lane: ctx.lane, parent: ctx.parent, line: e.line, inferred: false,
      });
      const listener = callStep(e, t, { ...ctx, lane: 'async', parent: null }, true);
      p.connectors.push({ from: pub.id, to: listener.id, kind: 'async' });
      return [{ type: 'step', id: pub.id }];
    }
    const s = callStep(e, t, ctx, false);
    return [{ type: 'step', id: s.id, inferred }];
  }

  function edgeRef(e) { return { source: e.source, target: e.target, label: e.label || '' }; }

  function callStep(e, t, ctx, listener) {
    const call = splitCall(t.label);
    const id = uniqueId(`${ctx.prefix}/${t.id}`);
    const recursive = ctx.stack.includes(t.id);
    const hasSteps = !recursive && g.outOf(t.id).length > 0;
    const s = step({
      id, kind: hasSteps ? 'subprocess' : 'call', nodeId: t.id, edge: edgeRef(e), name: call.name, owner: call.owner,
      lane: ctx.lane, parent: ctx.parent, line: e.line, inferred: e.kind === 'dispatch' || e.confidence === 'low',
      recursive, listener, returns: t.returns,
    });
    if (!hasSteps) return s;
    const wanted = openAll || open.has(id);
    const isOpen = wanted && ctx.depth < MAX_DEPTH && p.steps.length < MAX_STEPS;
    const sub = { id, nodeId: t.id, expanded: isOpen, capped: wanted && !isOpen, steps: [] };
    p.subprocesses.push(sub);
    s.expanded = isOpen;
    if (isOpen) {
      const before = p.steps.length;
      const items = sequence(t.id, { prefix: id, lane: ctx.lane, parent: id, stack: [...ctx.stack, t.id], nodeId: t.id, depth: ctx.depth + 1 });
      chain(items, []);
      sub.steps = p.steps.slice(before).filter(x => x.parent === id).map(x => x.id);
    } else {
      attachHiddenData(id, t.id);
    }
    return s;
  }

  function branchItems(b, ctx) {
    const edges = g.outOf(b.id);
    if (!guardsData(g, b.id)) return edges.flatMap(e => itemsFor(e, ctx));
    const sides = new Map();
    for (const e of edges) {
      const side = sideOf(e);
      if (!sides.has(side)) sides.set(side, []);
      sides.get(side).push(e);
    }
    const id = uniqueId(`${ctx.prefix}/${b.id}`);
    const d = { id, nodeId: b.id, label: shortCondition(b.label), condition: b.label, line: b.line, lane: ctx.lane, parent: ctx.parent, branches: [] };
    p.decisions.push(d);
    for (const [side, list] of sides) {
      d.branches.push({ side, label: SIDE_LABEL[side] || side, items: list.flatMap(e => itemsFor(e, ctx)) });
    }
    const missing = Object.entries(OTHER_SIDE).find(([s]) => sides.has(s))?.[1];
    if (sides.size === 1 && missing) d.branches.push({ side: null, label: missing, items: [] });
    return [{ type: 'decision', id, decision: d }];
  }

  /** Connect items in order; returns the open tails (where the flow continues). */
  function chain(items, tails) {
    for (const item of items) {
      if (item.type === 'decision') {
        link(tails, item.id);
        const next = [];
        for (const br of item.decision.branches) {
          const from = [{ from: item.id, label: br.label, kind: 'branch' }];
          next.push(...(br.items.length ? chain(br.items, from) : from));
        }
        tails = next;
        for (const br of item.decision.branches) br.items = br.items.map(x => x.id);
      } else {
        link(tails, item.id, item.inferred ? 'inferred' : null);
        tails = item.type === 'end' ? [] : [{ from: item.id }];
      }
    }
    return tails;
  }

  function link(tails, to, kind) {
    for (const t of tails) {
      p.connectors.push({ from: t.from, to, kind: kind || t.kind || 'sequence', ...(t.label ? { label: t.label } : {}) });
    }
  }

  const root = delegate || ep;
  const stack = delegate ? [ep.id, delegate.id] : [ep.id];
  const items = sequence(root.id, { prefix: root.id, lane: 'service', parent: null, stack, nodeId: root.id, depth: 0 });
  const tails = chain(items, [{ from: 'start' }]);
  const ret = { id: 'end', kind: 'return', label: ep.returns && ep.returns !== 'void' ? ep.returns : null, nodeId: ep.id, lane: 'service', parent: null };
  usedIds.add('end');
  p.ends.push(ret);
  link(tails, 'end');

  const used = new Set(p.steps.map(s => s.lane).concat(p.decisions.map(d => d.lane)));
  if (p.tables.length) used.add('database');
  if (p.systems.length) used.add('external');
  p.lanes = LANES.filter(([id]) => used.has(id)).map(([id, label]) => ({ id, label }));
  p.summary = summarize(g, ep.id, root.id);
  return p;
}

function guardsData(g, nodeId, seen = new Set()) {
  if (seen.has(nodeId)) return false;
  seen.add(nodeId);
  return g.outOf(nodeId).some(e => {
    const t = g.byId.get(e.target);
    return t.type === 'throw' || t.type === 'db' || t.type === 'external' || (t.type === 'branch' && guardsData(g, t.id, seen));
  });
}

/** What an endpoint's whole flow touches, counted once per node (linear, unlike a fully opened tree). */
export function summarize(g, endpointId, rootId = endpointId) {
  const seen = new Set([endpointId, rootId]);
  const queue = [rootId];
  const reached = [];
  while (queue.length) {
    for (const e of g.outOf(queue.shift())) {
      if (seen.has(e.target)) continue;
      seen.add(e.target);
      reached.push(g.byId.get(e.target));
      queue.push(e.target);
    }
  }
  const tables = reached.filter(n => n.type === 'db');
  const systems = reached.filter(n => n.type === 'external').map(system);
  const ops = {};
  for (const t of tables) {
    ops[t.table] ||= [];
    if (!ops[t.table].includes(t.op)) ops[t.table].push(t.op);
  }
  const tablesWith = test => [...new Set(tables.filter(t => test(t.op)).map(t => t.table))];
  const names = kind => [...new Set(systems.filter(s => s.kind === kind).map(s => s.name))];
  return {
    reads: tablesWith(op => READS.has(op)),
    writes: tablesWith(op => !READS.has(op) && !DELETES.has(op)),
    deletes: tablesWith(op => DELETES.has(op)),
    ops,
    publishes: names('topic'),
    calls: names('http'),
    decisions: reached.filter(n => n.type === 'branch' && guardsData(g, n.id)).length,
    errorEnds: reached.filter(n => n.type === 'throw').length,
    subprocesses: reached.filter(n => n.type === 'method' && g.outOf(n.id).length > 0).length,
  };
}
