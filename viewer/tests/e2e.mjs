// End-to-end test of the viewer against a real engine.
//   cd analyzer && ./mvnw -q -DskipTests package && cd ../viewer && npm ci && npm run e2e
// Set CHROMIUM_PATH to use a specific Chromium (otherwise Playwright's managed browser is used).
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, cpSync, readFileSync, writeFileSync, appendFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../..');
const jar = join(repo, 'analyzer/target/flowlens-analyzer.jar');
const port = 7300 + Math.floor(Math.random() * 500);

// A throwaway git repo with the sample app, so compare-with-ref and watch mode have something to do.
const work = mkdtempSync(join(tmpdir(), 'flowlens-e2e-'));
const project = join(work, 'sample-app');
cpSync(join(repo, 'examples/sample-app'), project, { recursive: true });
const git = (...a) => execFileSync('git', ['-C', project, ...a], { stdio: 'pipe' });
git('init', '-q');
git('-c', 'user.email=e2e@flowlens.dev', '-c', 'user.name=e2e', 'add', '.');
git('-c', 'user.email=e2e@flowlens.dev', '-c', 'user.name=e2e', 'commit', '-qm', 'base');

const engine = spawn('java', ['-jar', jar, '--serve', String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((ok, fail) => {
  engine.stdout.on('data', d => { if (String(d).includes('FLOWLENS_READY')) ok(); });
  engine.on('exit', c => fail(new Error('engine exited ' + c)));
  setTimeout(() => fail(new Error('engine did not start')), 20000);
});

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1400, height: 900 }, acceptDownloads: true });
const page = await context.newPage();
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', e => errors.push(e.message));
const statusText = () => page.textContent('#status');
const waitStatus = re => page.waitForFunction(r => new RegExp(r).test(document.querySelector('#status').textContent), re.source, { timeout: 60000 });

// the folder-path field lives in the project menu
async function indexPath(path) {
  await page.click('#openMenu [data-menu-toggle]');
  await page.fill('#pathInput', path);
  await page.click('#indexBtn');
}

let failures = 0;
async function step(name, fn) {
  try { await fn(); console.log('  ok  ', name); }
  catch (e) { failures++; console.log('  FAIL', name, '\n       ', e.message.split('\n')[0]); await page.screenshot({ path: join(work, name.replace(/\W+/g, '_') + '.png') }); }
}

try {
  await page.goto(`http://127.0.0.1:${port}/`);

  await step('sample graph is shown on start', async () => {
    await page.waitForSelector('[data-ep]');
    assert.equal(await page.textContent('#countEndpoints'), '5');
    await page.waitForSelector('#catalog:not([hidden]) .cat-card');
    assert.equal(await page.textContent('#panel'), 'Pick an entry point to open its process.');
    assert.equal(await page.$('#optProcess'), null, 'no preview switch');
  });

  await step('bundled fonts load (no network fonts)', async () => {
    const loaded = await page.evaluate(async () => {
      await document.fonts.ready;
      return [...document.fonts].filter(f => f.status === 'loaded').map(f => `${f.family.replace(/"/g, '')} ${f.weight}`).sort();
    });
    assert.ok(loaded.includes('IBM Plex Sans 600') && loaded.includes('IBM Plex Mono 600'), loaded.join(', '));
  });

  await step('index a folder by path', async () => {
    await indexPath(project);
    await waitStatus(/Indexed sample-app/);
    assert.match(await statusText(), /5 entry points/);
    assert.equal(await page.textContent('#projectName'), 'sample-app');
    assert.equal(await page.isVisible('#openMenu .menu-pop'), false, 'menu closes after indexing');
  });

  await step('select an endpoint opens its process, with lineage', async () => {
    await page.click('[data-ep]:has-text("/api/accounts") >> nth=0');
    await page.waitForSelector('#process:not([hidden]) .pv-step');
    await page.waitForSelector('#panel h2');
    assert.equal(await page.textContent('#panel h2'), 'POST /api/accounts');
    assert.match(await page.textContent('#panel'), /request\.email\s*→\s*account\.email/);
    const visible = await page.evaluate(() => window.flowlens.flow.visibleNodes().length);
    assert.ok(visible >= 8 && visible < 20, 'visible nodes: ' + visible);
  });

  await step('process view: steps in order, lanes, error end', async () => {
    const steps = await page.$$eval('#process .pv-step', els => els
      .sort((a, b) => a.dataset.order - b.dataset.order).map(e => e.querySelector('b').textContent));
    assert.deepEqual(steps, ['existsByEmail()', 'save()', 'record()', 'onAccountCreated()']);
    assert.deepEqual(await page.$$eval('.pv-lane-label', els => els.map(e => e.textContent)),
      ['Service', 'Database', 'Async listeners', 'External systems']);
    assert.match(await page.getAttribute('.pv-event.end.error', 'aria-label'), /IllegalStateException/);
    assert.match(await page.textContent('#pvContext'), /1 decision · 1 error end · 2 sub-processes/);
  });

  await step('step detail panel: kind, caller, writes, lineage, source at the call line', async () => {
    await page.click('.pv-step:has-text("save()")');
    assert.equal(await page.textContent('#panel .eyebrow'), 'Repository call');
    assert.equal(await page.textContent('#panel .sub'), 'AccountRepository · called from AccountServiceImpl.create()');
    assert.match(await page.textContent('#panel .sink'), /account\s*UPSERT/);
    assert.match(await page.textContent('#panel .lineage'), /request\.email\s*→\s*account\.email/);
    assert.match(await page.textContent('#panel .muts'), /account\.setStatus\("ACTIVE" \| "PENDING"\)/);
    assert.match(await page.textContent('#panel pre.source .hit'), /^36\s*Account saved = accountRepository\.save\(account\);/);
    assert.match(await page.getAttribute('#panel .split button.primary', 'data-href'), /AccountServiceImpl\.java:36$/);
    await page.click('#panel [data-act="callers"]');
    assert.equal(await page.isVisible('#panel .d-callers'), true);
    await page.click('.pv-step:has-text("record()")');
    assert.equal(await page.textContent('#panel .eyebrow'), 'Service call');
    assert.match(await page.textContent('#panel pre.source .hit'), /^37\s*auditService\.record/);
    assert.match(await page.textContent('#panel .sink'), /audit_log\s*INSERT/);
    await page.click('.pv-event.end.error');
    assert.equal(await page.textContent('#panel .eyebrow'), 'Error end');
  });

  await step('process view: open a sub-process in place, collapse it, expand and collapse all', async () => {
    const names = () => page.$$eval('#process .pv-step b', els => els.map(e => e.textContent));
    await page.click('[aria-label="Expand onAccountCreated()"]');
    await page.waitForSelector('.pv-container');
    assert.deepEqual(await names(), ['existsByEmail()', 'save()', 'record()', 'send()', 'postForObject()']);
    assert.match(await page.textContent('#pvCrumbs'), /POST \/api\/accounts\s*\/\s*onAccountCreated\(\)/);
    assert.equal(await page.textContent('#pvExpand'), 'Collapse all');
    await page.click('[aria-label="Collapse onAccountCreated()"]');
    await page.waitForSelector('[aria-label="Expand onAccountCreated()"]');
    assert.deepEqual(await names(), ['existsByEmail()', 'save()', 'record()', 'onAccountCreated()']);
    assert.equal(await page.$$eval('.pv-card', els => els.length), 5, 'collapsing hides steps, never the tables and systems they touch');
    await page.click('#pvExpand');
    await page.waitForSelector('.pv-container');
    assert.equal(await page.$$eval('.pv-container', els => els.length), 2);
    assert.ok((await names()).includes('update()'));
    await page.click('#pvExpand');
    await page.waitForSelector('.pv-container', { state: 'detached' });
    assert.equal(await page.textContent('#pvExpand'), 'Expand all');
  });

  await step('process view: select, keyboard, switch to the call graph and back', async () => {
    await page.click('.pv-step:has-text("save()")');
    assert.equal(await page.textContent('#panel h2'), 'save()');
    assert.equal(await page.$$eval('.pv-node.selected', els => els.length), 2, 'the step and its table card');
    await page.focus('.pv-node[data-order="0"]');
    await page.keyboard.press('ArrowRight');
    assert.equal(await page.evaluate(() => document.activeElement.dataset.order), '1');
    await page.click('[data-pv-view="graph"]');
    assert.equal(await page.isHidden('#process'), true);
    await page.click('[data-ep]:has-text("/api/accounts/{id}/status")');
    assert.equal(await page.isHidden('#process'), true, 'the call graph stays chosen for the next endpoint');
    await page.click('[data-ep]:has-text("/api/accounts") >> nth=0');
    assert.equal(await page.textContent('#panel h2'), 'POST /api/accounts');
    const visible = await page.evaluate(() => window.flowlens.flow.visibleNodes().length);
    assert.ok(visible >= 8 && visible < 20, 'visible nodes: ' + visible);
    await page.click('#toProcess');
    await page.waitForSelector('#process:not([hidden]) .pv-step');
  });

  await step('catalog: cards, filters, search, map, open a process', async () => {
    const cards = () => page.$$eval('#catalog .cat-card', els => els.length);
    await page.click('[data-all]');
    await page.waitForSelector('#catalog:not([hidden]) .cat-card');
    assert.equal(await cards(), 5);
    assert.match(await page.textContent('#catalog .cat-totals'), /5 entry points · 2 tables · 1 topic · 1 HTTP service/);
    assert.match(await page.textContent('#catalog .cat-card:has-text("purgeClosedAccounts")'), /Runs daily at 03:00/);
    await page.click('[data-cat-filter="writes"]');
    assert.equal(await cards(), 4);
    await page.click('[data-cat-filter="errors"]');
    assert.equal(await cards(), 1);
    await page.click('[data-cat-filter="all"]');
    await page.fill('#searchInput', 'status');
    await page.waitForFunction(() => document.querySelectorAll('#catalog .cat-card').length === 1);
    await page.fill('#searchInput', '');
    await page.waitForFunction(() => document.querySelectorAll('#catalog .cat-card').length === 5);
    await page.click('[data-cat-view="map"]');
    assert.equal(await page.isHidden('#catalog'), true);
    assert.equal(await page.textContent('#toProcess'), 'Catalog');
    await page.click('#toProcess');
    await page.waitForSelector('#catalog:not([hidden])');
    await page.click('#catalog .cat-card:has-text("/api/accounts/{id}/status")');
    await page.waitForFunction(() => /PATCH \/api\/accounts\/\{id\}\/status/.test(document.querySelector('#pvCrumbs').textContent));
    assert.equal(await page.isHidden('#catalog'), true);
    assert.deepEqual(await page.$$eval('#process .pv-step b', els => els.map(e => e.textContent)), ['updateStatus()', 'record()']);
    await page.click('#pvCrumbs [data-action="show-all"]');
    await page.waitForSelector('#catalog:not([hidden])');
    await page.click('[data-cat-view="map"]');
    assert.equal(await page.isHidden('#catalog'), true);
    assert.ok(await page.evaluate(() => window.flowlens.flow.visibleNodes().length) > 10, 'the map is back');
  });

  await step('search is in the toolbar and opens with Ctrl/Cmd+K', async () => {
    await page.click('#cy');
    await page.keyboard.press(process.platform === 'darwin' ? 'Meta+k' : 'Control+k');
    assert.equal(await page.evaluate(() => document.activeElement.id), 'searchInput');
    await page.keyboard.type('{id}');
    await page.waitForFunction(() => document.querySelectorAll('[data-ep]').length === 3);
    await page.fill('#searchInput', '');
    await page.dispatchEvent('#searchInput', 'input');
    await page.keyboard.press('Escape');
  });

  await step('tables view lists who touches a table', async () => {
    await page.click('.tab[data-tab="tables"]');
    await page.click('[data-table="account"]');
    assert.match(await statusText(), /reaches account/);
  });

  await step('focus and exit focus', async () => {
    await page.evaluate(() => window.flowlens.flow.focus(window.flowlens.flow.graph.nodes.find(n => n.label === 'AuditService.record()').id));
    const n = await page.evaluate(() => window.flowlens.flow.visibleNodes().length);
    assert.ok(n >= 3, 'focus shows neighbourhood: ' + n);
    await page.keyboard.press('Escape');
  });

  for (const [action, ext, check] of [
    ['export-png', '.png', b => assert.equal(b.subarray(1, 4).toString(), 'PNG')],
    ['export-svg', '.svg', b => assert.match(b.toString(), /^<svg/)],
    ['export-mermaid', '.mmd', b => assert.match(b.toString(), /^flowchart (LR|TD)/)],
    ['save-snapshot', '.flowlens', b => assert.equal(JSON.parse(b.toString()).flowlensSnapshot, 1)],
  ]) {
    await step('export ' + ext, async () => {
      await page.click('#exportMenu [data-menu-toggle]');
      const [dl] = await Promise.all([page.waitForEvent('download'), page.click(`[data-action="${action}"]`)]);
      assert.ok(dl.suggestedFilename().endsWith(ext), dl.suggestedFilename());
      check(readFileSync(await dl.path()));
    });
  }

  await step('compare with a git ref marks changes', async () => {
    const svc = join(project, 'src/main/java/com/example/demo/AuditService.java');
    writeFileSync(svc, readFileSync(svc, 'utf8').replace('INSERT INTO audit_log', 'INSERT INTO audit_events'));
    await page.click('#reindexBtn');
    await waitStatus(/Indexed sample-app/);
    await page.click('#compareMenu [data-menu-toggle]');
    await page.click('[data-action="compare-ref"]');
    await page.fill('#promptInput', 'HEAD');
    await page.click('#modalActions button[value="ok"]');
    await waitStatus(/Compared with HEAD/);
    const diff = await page.evaluate(() => Object.fromEntries(window.flowlens.flow.graph.nodes.filter(n => n.diff).map(n => [n.label, n.diff])));
    assert.equal(diff['INSERT audit_events'], 'added');
    assert.equal(diff['INSERT audit_log'], 'removed');
    assert.equal(diff['AuditService.record()'], 'changed');
    await page.click('#bannerExit');
  });

  await step('compare and export in the process view', async () => {
    await page.click('#compareMenu [data-menu-toggle]');
    await page.click('[data-action="compare-ref"]');
    await page.fill('#promptInput', 'HEAD');
    await page.click('#modalActions button[value="ok"]');
    await waitStatus(/Compared with HEAD/);
    await page.click('.tab[data-tab="endpoints"]');
    await page.click('[data-ep]:has-text("/api/accounts") >> nth=0');
    await page.waitForSelector('#process:not([hidden]) .pv-card.diff-added');
    assert.ok(await page.isVisible('#bannerExit'), 'the compare banner stays on top of the process view');
    assert.match(await page.textContent('.pv-card.diff-added'), /audit_events/);
    assert.match(await page.textContent('.pv-card.diff-removed'), /audit_log/);
    assert.match(await page.textContent('.pv-step.diff-changed'), /record\(\)/);
    assert.deepEqual(await page.$$eval('.pv-diff', els => els.map(e => e.textContent).sort()), ['added', 'changed', 'removed']);
    assert.equal(await page.$$eval('.pv-step.diff-added, .pv-step.diff-removed', els => els.length), 0, 'collapsed record() hides its steps');
    await page.click('[aria-label="Expand record()"]');
    await page.waitForSelector('.pv-container');
    assert.deepEqual(await page.$$eval('.pv-step.diff-added b, .pv-step.diff-removed b', els => els.map(e => e.textContent)), ['update()', 'update()']);
    assert.equal(await page.$$eval('.pv-step.diff-removed', els => els.length), 1);
    const download = async action => {
      await page.click('#exportMenu [data-menu-toggle]');
      const [dl] = await Promise.all([page.waitForEvent('download'), page.click(`[data-action="${action}"]`)]);
      return readFileSync(await dl.path());
    };
    const svg = (await download('export-svg')).toString();
    assert.match(svg, /^<svg xmlns/);
    for (const want of ['audit_events', 'audit_log', 'ADDED', 'REMOVED', 'CHANGED', 'line-through', 'publishEvent', 'IllegalStateException']) assert.ok(svg.includes(want), want);
    const png = await download('export-png');
    assert.equal(png.subarray(1, 4).toString(), 'PNG');
    assert.ok(png.readUInt32BE(16) > 1000 && png.readUInt32BE(20) > 500, `png ${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`);
    await page.click('#bannerExit');
    await page.waitForFunction(() => !document.querySelector('.pv-card.diff-added'));
  });

  await step('watch mode re-indexes on save', async () => {
    await page.check('#watchToggle');
    assert.equal(await page.textContent('#watchLabel'), 'Watching');
    await page.waitForTimeout(2500);
    appendFileSync(join(project, 'src/main/java/com/example/demo/AccountController.java'), '\n// touched\n');
    await waitStatus(/Re-indexed sample-app/);
    await page.uncheck('#watchToggle');
  });

  await step('large project (800+ nodes, branches, loops) renders', async () => {
    // regression: Cytoscape's rounded shapes crashed on big graphs before layout
    const big = join(work, 'big/src/main/java/big');
    mkdirSync(big, { recursive: true });
    for (let i = 0; i < 90; i++) {
      writeFileSync(join(big, `S${i}.java`), `package big;
        @org.springframework.stereotype.Service public class S${i} {
          Repo repo; S${(i + 1) % 90} next;
          public void run(int n) { if (n > ${i}) { repo.save(null); } else { for (int k = 0; k < n; k++) { repo.deleteById(1L); } }
            switch (n) { case 1 -> repo.findAll(); default -> next.run(n - 1); }
            try { repo.count(); } catch (RuntimeException e) { throw new IllegalStateException(e); } } }`);
      writeFileSync(join(big, `C${i}.java`), `package big;
        @org.springframework.web.bind.annotation.RestController public class C${i} { S${i} s;
          @org.springframework.web.bind.annotation.PostMapping("/c${i}") public void c(int n) { if (n > 0) s.run(n); } }`);
    }
    writeFileSync(join(big, 'E.java'), 'package big; @jakarta.persistence.Entity public class E { @jakarta.persistence.Id Long id; }');
    writeFileSync(join(big, 'Repo.java'), 'package big; public interface Repo extends org.springframework.data.jpa.repository.JpaRepository<E, Long> {}');
    await indexPath(join(work, 'big'));
    await waitStatus(/Indexed big/);
    const nodes = await page.evaluate(() => window.flowlens.flow.graph.nodes.length);
    assert.ok(nodes > 700, 'nodes: ' + nodes);
    await page.click('.tab[data-tab="endpoints"]');
    await page.click('[data-all]');
    assert.equal(await page.isHidden('#catalog'), true, 'the map stays chosen');
    await page.waitForTimeout(500);
  });

  await step('large project: process view of a looping flow', async () => {
    await page.click('[data-ep]:has-text("/c7")');
    await page.waitForSelector('#process:not([hidden]) .pv-step');
    assert.ok(await page.$$eval('.pv-card', els => els.length) >= 3, 'tables of the collapsed sub-process stay visible');
    await page.click('#pvExpand');
    await page.waitForSelector('.pv-container');
    const depth = await page.evaluate(() => Math.max(...[...document.querySelectorAll('.pv-container')].map(el => {
      let d = 0;
      for (let c = el; c; c = [...document.querySelectorAll('.pv-container')].find(o => o !== c && contains(o, c))) d++;
      return d;
      function contains(o, c) {
        const a = o.getBoundingClientRect(), b = c.getBoundingClientRect();
        return a.left <= b.left && a.top <= b.top && a.right >= b.right && a.bottom >= b.bottom;
      }
    })));
    assert.ok(depth >= 1 && depth <= 4, 'nesting depth ' + depth);
    await page.click('#pvExpand');
    await page.click('[data-all]');
    await page.click('#toProcess');
    await page.waitForSelector('#catalog:not([hidden]) .cat-card');
    const eps = await page.evaluate(() => window.flowlens.app.graph.nodes.filter(n => n.type === 'endpoint').length);
    assert.equal(await page.$$eval('#catalog .cat-card', els => els.length), eps);
  });

  await step('no console errors', async () => assert.deepEqual(errors, [], errors.join(' | ')));
} finally {
  await browser.close();
  engine.kill();
}
console.log(failures ? `\n${failures} step(s) failed (screenshots in ${work})` : '\nAll viewer e2e steps passed.');
process.exit(failures ? 1 : 0);
