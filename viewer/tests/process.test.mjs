// Unit tests for the process model (js/process.js): `npm test`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { buildProcess, shortCondition, splitCall, MAX_DEPTH, MAX_STEPS } from '../js/process.js';

const sample = JSON.parse(readFileSync(new URL('../graph.sample.json', import.meta.url), 'utf8'));
const endpoint = (g, http) => g.nodes.find(n => n.http === http).id;

/** The flow read off the connectors as sorted "a -kind-> b" lines, using step names, decision labels and end labels. */
function describe(p) {
  const name = id => {
    if (id === 'start') return 'start';
    const s = p.steps.find(x => x.id === id);
    if (s) return s.kind === 'publish' ? `publish ${s.owner}` : s.name;
    const d = p.decisions.find(x => x.id === id);
    if (d) return `<${d.label}>`;
    const e = p.ends.find(x => x.id === id);
    if (e) return e.kind === 'error' ? `error ${e.label}` : `return ${e.label}`;
    const t = p.tables.find(x => x.id === id);
    if (t) return `${t.op} ${t.table}`;
    const sys = p.systems.find(x => x.id === id);
    return sys ? sys.name : id;
  };
  return p.connectors.map(c => `${name(c.from)} -${c.kind}${c.label ? ':' + c.label : ''}-> ${name(c.to)}`).sort();
}

test('POST /api/accounts matches the design board', () => {
  const p = buildProcess(sample, endpoint(sample, 'POST /api/accounts'));
  assert.equal(p.start.handler, 'createAccount()');
  assert.equal(p.start.delegate, 'M:com.example.demo.AccountServiceImpl#create(CreateAccountRequest)');
  assert.deepEqual(describe(p), [
    'SELECT account -read-> existsByEmail()',
    'start -sequence-> existsByEmail()',
    'existsByEmail() -sequence-> <existsByEmail(…)>',
    'save() -write-> UPSERT account',
    'record() -write-> INSERT audit_log',
    'onAccountCreated() -write-> account-events',
    'onAccountCreated() -write-> mail-service',
    'publish AccountCreatedEvent -async-> onAccountCreated()',
    '<existsByEmail(…)> -branch:yes-> error IllegalStateException',
    '<existsByEmail(…)> -branch:no-> save()',
    'save() -sequence-> record()',
    'record() -sequence-> publish AccountCreatedEvent',
    'publish AccountCreatedEvent -sequence-> return Account',
  ].sort());
  assert.deepEqual(p.lanes.map(l => l.label), ['Service', 'Database', 'Async listeners', 'External systems']);
  assert.deepEqual(p.steps.map(s => [s.name, s.owner, s.lane, s.kind]), [
    ['existsByEmail()', 'AccountRepository', 'service', 'data'],
    ['save()', 'AccountRepository', 'service', 'data'],
    ['record()', 'AuditService', 'service', 'subprocess'],
    ['publishEvent', 'AccountCreatedEvent', 'service', 'publish'],
    ['onAccountCreated()', 'WelcomeMailer', 'async', 'subprocess'],
  ]);
  assert.deepEqual(p.systems.map(s => [s.kind, s.name, s.detail]), [
    ['topic', 'account-events', 'Kafka topic'],
    ['http', 'mail-service', 'POST /api/welcome'],
  ]);
  assert.deepEqual(p.subprocesses.map(s => s.expanded), [false, false]);
  assert.deepEqual(p.summary, {
    reads: ['account'], writes: ['account', 'audit_log'], deletes: [],
    ops: { account: ['SELECT', 'UPSERT'], audit_log: ['INSERT'] },
    publishes: ['account-events'], calls: ['mail-service'],
    decisions: 1, errorEnds: 1, subprocesses: 2,
  });
});

test('steps carry the call line for the detail panel', () => {
  const p = buildProcess(sample, endpoint(sample, 'POST /api/accounts'));
  assert.deepEqual(p.steps.map(s => s.line), [22, 36, 37, 38, 38]);
  assert.equal(p.ends.find(e => e.kind === 'error').line, 23);
});

test('an expanded sub-process shows its own steps; tables stay visible when collapsed', () => {
  const ep = endpoint(sample, 'POST /api/accounts');
  const listener = buildProcess(sample, ep).steps.find(s => s.name === 'onAccountCreated()');
  const p = buildProcess(sample, ep, { expanded: [listener.id] });
  const inner = p.steps.filter(s => s.parent === listener.id);
  assert.deepEqual(inner.map(s => [s.name, s.owner, s.lane]), [['send()', 'KafkaTemplate', 'async'], ['postForObject()', 'RestTemplate', 'async']]);
  assert.deepEqual(p.subprocesses.find(s => s.id === listener.id).steps, inner.map(s => s.id));
  assert.ok(describe(p).includes('send() -sequence-> postForObject()'));
  assert.ok(p.tables.some(t => t.table === 'audit_log'), 'collapsed record() keeps its table');
});

test('the summaries match the all-endpoints catalog', () => {
  const s = http => buildProcess(sample, endpoint(sample, http)).summary;
  assert.deepEqual([s('GET /api/accounts/{id}').reads, s('GET /api/accounts/{id}').writes], [['account'], []]);
  assert.deepEqual(s('PATCH /api/accounts/{id}/status').ops, { account: ['UPDATE'], audit_log: ['INSERT'] });
  assert.equal(s('PATCH /api/accounts/{id}/status').subprocesses, 1);
  assert.equal(s('DELETE /api/accounts/{id}').subprocesses, 2);
  const cron = buildProcess(sample, endpoint(sample, 'SCHEDULED cron=0 0 3 * * *'));
  assert.equal(cron.start.delegate, null, 'a job that does the work itself is not a delegating controller');
  assert.deepEqual(cron.summary.deletes, ['account']);
  assert.deepEqual(cron.steps.map(x => x.name), ['deleteByStatus()']);
});

test('snapshots without seq still build, in edge-list order', () => {
  const old = structuredClone(sample);
  for (const e of old.edges) { delete e.seq; delete e.line; }
  const p = buildProcess(old, endpoint(old, 'POST /api/accounts'));
  assert.equal(p.steps.length, 5);
  assert.equal(p.decisions.length, 1);
  assert.equal(p.summary.subprocesses, 2);
  assert.equal(p.steps[0].line, undefined);
});

test('a recursive graph terminates and marks the recursive call', () => {
  const g = {
    nodes: [
      { id: 'EP', label: 'C.go()', type: 'endpoint', http: 'GET /go', returns: 'void' },
      { id: 'A', label: 'S.a()', type: 'method' },
      { id: 'B', label: 'S.b()', type: 'method' },
      { id: 'T', label: 'SELECT t', type: 'db', op: 'SELECT', table: 't' },
    ],
    edges: [
      { source: 'EP', target: 'A', label: 'a', kind: 'call', seq: 1 },
      { source: 'A', target: 'B', label: 'b', kind: 'call', seq: 1 },
      { source: 'B', target: 'A', label: 'a', kind: 'call', seq: 1 },
      { source: 'B', target: 'T', label: 'R.find()', kind: 'sink', seq: 2 },
    ],
  };
  const p = buildProcess(g, 'EP', { expanded: true });
  const rec = p.steps.filter(s => s.recursive);
  assert.deepEqual(rec.map(s => s.name), ['a()']);
  assert.equal(rec[0].kind, 'call');
  assert.equal(p.ends.at(-1).label, null, 'void returns have no label');
  const collapsed = buildProcess(g, 'EP');
  assert.deepEqual(collapsed.steps.map(s => [s.name, s.kind]), [['b()', 'subprocess']]);
  assert.deepEqual(collapsed.tables.map(t => t.table), ['t']);
});

test('dispatch and low-confidence calls become inferred connectors', () => {
  const g = {
    nodes: [
      { id: 'EP', label: 'C.go()', type: 'endpoint' },
      { id: 'X', label: 'EmailNotifier.send()', type: 'method' },
      { id: 'Y', label: 'SmsNotifier.send()', type: 'method' },
    ],
    edges: [
      { source: 'EP', target: 'X', label: 'send', kind: 'dispatch', confidence: 'medium', seq: 1 },
      { source: 'EP', target: 'Y', label: 'send', kind: 'dispatch', confidence: 'medium', seq: 1 },
    ],
  };
  const p = buildProcess(g, 'EP');
  assert.equal(p.start.delegate, null);
  assert.deepEqual(describe(p), ['send() -inferred-> send()', 'send() -sequence-> return null', 'start -inferred-> send()']);
  assert.ok(p.steps.every(s => s.inferred));
});

test('if/else guarding two writes is one decision with both branches', () => {
  const g = {
    nodes: [
      { id: 'EP', label: 'C.x()', type: 'endpoint' },
      { id: 'BR', label: 'if (vip)', type: 'branch' },
      { id: 'S', label: 'UPSERT a', type: 'db', op: 'UPSERT', table: 'a' },
      { id: 'D', label: 'DELETE a', type: 'db', op: 'DELETE', table: 'a' },
    ],
    edges: [
      { source: 'EP', target: 'BR', label: '', kind: 'branch', seq: 1 },
      { source: 'BR', target: 'S', label: 'then · R.save()', kind: 'sink', seq: 1 },
      { source: 'BR', target: 'D', label: 'else · R.delete()', kind: 'sink', seq: 2 },
    ],
  };
  const p = buildProcess(g, 'EP');
  assert.deepEqual(p.decisions.map(d => [d.label, d.branches.map(b => b.label)]), [['vip', ['yes', 'no']]]);
  assert.ok(describe(p).includes('<vip> -branch:yes-> save()'));
  assert.ok(describe(p).includes('<vip> -branch:no-> delete()'));
  assert.ok(describe(p).includes('save() -sequence-> return null'));
  assert.ok(describe(p).includes('delete() -sequence-> return null'));
});

test('a branch that guards only plain calls is not a decision', () => {
  const g = {
    nodes: [
      { id: 'EP', label: 'C.x()', type: 'endpoint' },
      { id: 'BR', label: 'if (n > 0)', type: 'branch' },
      { id: 'M', label: 'S.log()', type: 'method' },
    ],
    edges: [
      { source: 'EP', target: 'BR', label: '', kind: 'branch', seq: 1 },
      { source: 'BR', target: 'M', label: 'then · log', kind: 'call', seq: 1 },
    ],
  };
  const p = buildProcess(g, 'EP');
  assert.equal(p.decisions.length, 0);
  assert.deepEqual(p.steps.map(s => s.name), ['log()']);
});

/** 40 levels where each method calls the next level's two methods: 2^40 paths, 80 methods. */
function diamond() {
  const nodes = [{ id: 'EP', label: 'C.x()', type: 'endpoint' }, { id: 'T', label: 'SELECT t', type: 'db', op: 'SELECT', table: 't' }];
  const edges = [];
  for (let i = 0; i < 40; i++) {
    nodes.push({ id: `L${i}`, label: `S.l${i}()`, type: 'method' }, { id: `R${i}`, label: `S.r${i}()`, type: 'method' });
    for (const from of i ? [`L${i - 1}`, `R${i - 1}`] : ['EP']) {
      edges.push({ source: from, target: `L${i}`, label: 'l', kind: 'call', seq: 1 }, { source: from, target: `R${i}`, label: 'r', kind: 'call', seq: 2 });
    }
  }
  edges.push({ source: 'L39', target: 'T', label: 'R.find()', kind: 'sink', seq: 1 });
  return { nodes, edges };
}

const depthOf = (p, s) => (s.parent ? 1 + depthOf(p, p.steps.find(x => x.id === s.parent)) : 0);

test('shared sub-flows do not blow up the summary', () => {
  const t0 = performance.now();
  const p = buildProcess(diamond(), 'EP');
  assert.ok(performance.now() - t0 < 1000, 'built in ' + (performance.now() - t0) + 'ms');
  assert.equal(p.summary.subprocesses, 79);
  assert.deepEqual(p.summary.reads, ['t']);
});

test('opening everything stops at the depth cap', () => {
  const t0 = performance.now();
  const p = buildProcess(diamond(), 'EP', { expanded: true });
  assert.ok(performance.now() - t0 < 1000, 'built in ' + (performance.now() - t0) + 'ms');
  assert.equal(Math.max(...p.steps.map(s => depthOf(p, s))), MAX_DEPTH);
  const capped = p.subprocesses.filter(s => s.capped);
  assert.ok(capped.length > 0);
  assert.ok(capped.every(s => !s.expanded && depthOf(p, p.steps.find(x => x.id === s.id)) === MAX_DEPTH));
  assert.deepEqual(p.tables.map(t => t.table).filter((t, i, a) => a.indexOf(t) === i), ['t'], 'a capped sub-process keeps its tables');
});

test('opening everything stops at the step budget', () => {
  const nodes = [{ id: 'EP', label: 'C.x()', type: 'endpoint' }];
  const edges = [];
  const fan = (from, level, prefix) => {
    if (level === 3) return;
    for (let i = 0; i < 30; i++) {
      const id = `${prefix}${i}`;
      nodes.push({ id, label: `S${level}.m${id}()`, type: 'method' });
      edges.push({ source: from, target: id, label: 'm', kind: 'call', seq: i + 1 });
      fan(id, level + 1, id + '.');
    }
  };
  fan('EP', 0, 'n');
  const p = buildProcess({ nodes, edges }, 'EP', { expanded: true });
  assert.ok(p.steps.length <= MAX_STEPS + 30 * (MAX_DEPTH + 1), 'past the budget only the siblings already being drawn are added; steps: ' + p.steps.length);
  assert.ok(p.subprocesses.some(s => s.capped));
});

test('ends inside an opened sub-process carry their parent', () => {
  const g = {
    nodes: [
      { id: 'EP', label: 'C.x()', type: 'endpoint' },
      { id: 'A', label: 'S.a()', type: 'method' },
      { id: 'BR', label: 'if (bad)', type: 'branch' },
      { id: 'X', label: 'throw IllegalStateException', type: 'throw' },
      { id: 'M', label: 'S.b()', type: 'method' },
    ],
    edges: [
      { source: 'EP', target: 'A', label: 'a', kind: 'call', seq: 1 },
      { source: 'EP', target: 'M', label: 'b', kind: 'call', seq: 2 },
      { source: 'A', target: 'BR', label: '', kind: 'branch', seq: 1 },
      { source: 'BR', target: 'X', label: 'then · throws', kind: 'throw', seq: 1 },
    ],
  };
  const sub = buildProcess(g, 'EP').steps.find(s => s.name === 'a()');
  const p = buildProcess(g, 'EP', { expanded: [sub.id] });
  const error = p.ends.find(e => e.kind === 'error');
  assert.equal(error.parent, sub.id);
  assert.equal(p.decisions[0].parent, sub.id);
  assert.equal(p.ends.find(e => e.kind === 'return').parent, null);
});

test('labels', () => {
  assert.equal(shortCondition('if (accountRepository.existsByEmail(request.getEmail()))'), 'existsByEmail(…)');
  assert.equal(shortCondition('if (vip)'), 'vip');
  assert.equal(shortCondition('switch (order.getStatus())'), 'getStatus()');
  assert.deepEqual(splitCall('then · AccountRepository.save()'), { owner: 'AccountRepository', name: 'save()' });
  assert.deepEqual(splitCall('jdbc.update()'), { owner: 'JdbcTemplate', name: 'update()' });
  assert.equal(buildProcess(sample, 'nope'), null);
});
