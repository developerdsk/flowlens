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
  });

  await step('index a folder by path', async () => {
    await page.fill('#pathInput', project);
    await page.click('#indexBtn');
    await waitStatus(/Indexed sample-app/);
    assert.match(await statusText(), /5 entry points/);
  });

  await step('select an endpoint shows its flow and lineage', async () => {
    await page.click('[data-ep]:has-text("/api/accounts") >> nth=0');
    await page.waitForSelector('#panel h2');
    assert.equal(await page.textContent('#panel h2'), 'POST /api/accounts');
    assert.match(await page.textContent('#panel'), /request\.email\s*→\s*account\.email/);
    const visible = await page.evaluate(() => window.flowlens.flow.visibleNodes().length);
    assert.ok(visible >= 8 && visible < 20, 'visible nodes: ' + visible);
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

  await step('watch mode re-indexes on save', async () => {
    await page.check('#watchToggle');
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
    await page.fill('#pathInput', join(work, 'big'));
    await page.click('#indexBtn');
    await waitStatus(/Indexed big/);
    const nodes = await page.evaluate(() => window.flowlens.flow.graph.nodes.length);
    assert.ok(nodes > 700, 'nodes: ' + nodes);
    await page.click('.tab[data-tab="endpoints"]');
    await page.click('[data-all]');
    await page.waitForTimeout(500);
  });

  await step('no console errors', async () => assert.deepEqual(errors, []));
} finally {
  await browser.close();
  engine.kill();
}
console.log(failures ? `\n${failures} step(s) failed (screenshots in ${work})` : '\nAll viewer e2e steps passed.');
process.exit(failures ? 1 : 0);
