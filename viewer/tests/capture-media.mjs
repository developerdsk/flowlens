// Captures the README screenshots and the demo walkthrough video from the real UI.
//
//   node viewer/tests/capture-media.mjs <sample-git-repo> <large-project> <out-dir>
//
// <sample-git-repo>: a git repo containing a copy of examples/sample-app (used for compare mode)
// <large-project>:   a bigger real project, e.g. a clone of macrozheng/mall
// Produces <out-dir>/*.png and <out-dir>/walkthrough.mp4 + walkthrough.gif (needs ffmpeg with libx264).
// ONLY=video or ONLY=screenshots limits the run.
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const [, , sample, large, outArg] = process.argv;
const out = resolve(outArg || 'docs/images');
mkdirSync(out, { recursive: true });
const here = dirname(fileURLToPath(import.meta.url));
const jar = resolve(here, '../../analyzer/target/flowlens-analyzer.jar');
const port = 7700 + Math.floor(Math.random() * 200);

const engine = spawn('java', ['-jar', jar, '--serve', String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((ok, fail) => {
  engine.stdout.on('data', d => { if (String(d).includes('FLOWLENS_READY')) ok(); });
  setTimeout(() => fail(new Error('engine did not start')), 20000);
});
const fail = e => { console.error(e); engine.kill(); process.exit(1); };
process.on('unhandledRejection', fail);
process.on('uncaughtException', fail);
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const url = `http://127.0.0.1:${port}/`;
const pause = ms => new Promise(r => setTimeout(r, ms));

async function newPage(theme, opts = {}) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 860 }, deviceScaleFactor: opts.scale || 2, colorScheme: theme, ...opts.context });
  const page = await context.newPage();
  await page.goto(url);
  await page.waitForSelector('[data-ep]');
  return { context, page };
}

async function index(page, path) {
  await page.click('#openMenu [data-menu-toggle]');
  await page.fill('#pathInput', path);
  await page.click('#indexBtn');
  await page.waitForFunction(() => /Indexed/.test(document.querySelector('#status').textContent), null, { timeout: 120000 });
  await pause(300);
}

const endpoint = (page, http) => page.evaluate(h => {
  const n = window.flowlens.flow.graph.nodes.find(x => x.type === 'endpoint' && x.http === h);
  document.querySelector(`[data-ep="${CSS.escape(n.id)}"]`).click();
}, http);
const selectByLabel = (page, label) => page.evaluate(l => {
  const n = window.flowlens.flow.graph.nodes.find(x => x.label === l);
  window.flowlens.flow.cy.getElementById(n.id).emit('tap');
}, label);
const step = (page, label) => page.evaluate(l => {
  const n = window.flowlens.flow.graph.nodes.find(x => x.label === l);
  document.querySelector(`#process [data-node-id="${CSS.escape(n.id)}"]`).click();
}, label);
const processShown = page => page.waitForSelector('#process:not([hidden]) .pv-node');

// ---------------------------------------------------------------- screenshots
const shots = process.env.ONLY !== 'video';
if (shots) {
  const { context, page } = await newPage('dark');
  await index(page, sample);
  await page.click('[data-all]');
  await page.waitForSelector('#catalog:not([hidden]) .cat-card');
  await pause(300);
  await page.screenshot({ path: join(out, 'catalog.png') });

  await endpoint(page, 'POST /api/accounts');
  await processShown(page);
  await pause(500);
  await page.screenshot({ path: join(out, 'endpoint-flow.png') });

  await step(page, 'AuditService.record()');
  await pause(400);
  await page.screenshot({ path: join(out, 'detail-source.png') });

  // compare with the committed version after a change
  const audit = join(sample, 'src/main/java/com/example/demo/AuditService.java');
  const original = readFileSync(audit, 'utf8');
  writeFileSync(audit, original.replace('INSERT INTO audit_log', 'INSERT INTO audit_events'));
  await page.click('#reindexBtn');
  await page.waitForFunction(() => /Indexed/.test(document.querySelector('#status').textContent));
  await page.click('#compareMenu [data-menu-toggle]');
  await page.click('[data-action="compare-ref"]');
  await page.fill('#promptInput', 'HEAD');
  await page.click('#modalActions button[value="ok"]');
  await page.waitForFunction(() => /Compared with/.test(document.querySelector('#status').textContent));
  await endpoint(page, 'POST /api/accounts');
  await processShown(page);
  await page.click('[aria-label="Expand record()"]');
  await page.waitForSelector('.pv-container');
  await pause(500);
  await page.screenshot({ path: join(out, 'compare.png') });
  writeFileSync(audit, original);

  await page.click('#bannerExit');
  await page.click('#helpMenu [data-menu-toggle]');
  await page.click('[data-action="help-mcp"]');
  await pause(300);
  await page.screenshot({ path: join(out, 'mcp.png') });
  await page.keyboard.press('Escape');
  await context.close();
}
if (shots) {
  const { context, page } = await newPage('light');
  await index(page, sample);
  await endpoint(page, 'POST /api/accounts');
  await processShown(page);
  await step(page, 'if (accountRepository.existsByEmail(request.getEmail()))');
  await pause(400);
  await page.screenshot({ path: join(out, 'light-theme.png') });
  await context.close();
}
if (shots) {
  const { context, page } = await newPage('dark');
  await index(page, large);
  await endpoint(page, 'POST /order/cancelUserOrder');
  await processShown(page);
  await page.click('[aria-label="Expand cancelOrder()"]');
  await page.waitForSelector('.pv-container');
  for (let i = 0; i < 2; i++) await page.click('[data-pv-zoom="1"]');
  await pause(600);
  await page.screenshot({ path: join(out, 'large-project.png') });
  await page.click('.tab[data-tab="tables"]');
  await page.click('[data-table="oms_order"]');
  await pause(600);
  await page.screenshot({ path: join(out, 'tables-view.png') });
  await context.close();
}

// ---------------------------------------------------------------- walkthrough video
if (process.env.ONLY !== 'screenshots') {
  // Frames come from Chromium's screencast (CDP) and are assembled by ffmpeg with their real timing.
  const frameDir = join(out, '.frames');
  rmSync(frameDir, { recursive: true, force: true });
  mkdirSync(frameDir, { recursive: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 760 }, deviceScaleFactor: 1.5, colorScheme: 'dark' });
  const page = await context.newPage();
  await page.goto(url);
  await page.waitForSelector('[data-ep]');
  const cdp = await context.newCDPSession(page);
  const frames = [];
  cdp.on('Page.screencastFrame', async f => {
    const file = join(frameDir, `f${String(frames.length).padStart(5, '0')}.jpg`);
    frames.push({ file, t: f.metadata.timestamp });
    writeFileSync(file, Buffer.from(f.data, 'base64'));
    await cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
  });
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 88, everyNthFrame: 1 });
  // a caption bar for the narration
  await page.addStyleTag({ content: `#demoCaption{position:fixed;left:50%;bottom:42px;transform:translateX(-50%);z-index:1000;
    background:rgba(124,58,237,.95);color:#fff;font:600 17px -apple-system,Segoe UI,Roboto,sans-serif;padding:10px 18px;border-radius:10px;
    box-shadow:0 8px 30px rgba(0,0,0,.45);max-width:80%;text-align:center;transition:opacity .25s}` });
  const caption = async (text, ms = 2200) => {
    await page.evaluate(t => {
      let el = document.getElementById('demoCaption');
      if (!el) { el = document.createElement('div'); el.id = 'demoCaption'; document.body.appendChild(el); }
      el.textContent = t;
    }, text);
    await pause(ms);
  };
  const slowType = async (sel, text) => { await page.click(sel); await page.fill(sel, ''); await page.type(sel, text, { delay: 18 }); };

  await caption('FlowLens: see how each request flows through your Java code', 2600);
  await caption('1. Point it at a project folder (here: macrozheng/mall, 524 files)', 1200);
  await page.click('#openMenu [data-menu-toggle]');
  await slowType('#pathInput', large);
  await page.click('#indexBtn');
  await page.waitForFunction(() => /Indexed/.test(document.querySelector('#status').textContent), null, { timeout: 120000 });
  await caption('248 entry points found, grouped by controller, like a Postman collection', 2200);
  await page.click('[data-all]');
  await caption('All endpoints at a glance: what each one does, step by step', 2600);
  await page.fill('#searchInput', 'order');
  await page.dispatchEvent('#searchInput', 'input');
  await caption('2. Search and pick an endpoint', 1500);
  await endpoint(page, 'POST /order/cancelUserOrder');
  await processShown(page);
  for (let i = 0; i < 2; i++) await page.click('[data-pv-zoom="1"]');
  await caption('3. Its flow as a process: steps in order, decisions, the tables and queues it touches', 3400);
  await step(page, 'OmsPortalOrderServiceImpl.cancelOrder()');
  await caption('Click any step for conditions, mutations and source, then open it in your IDE', 3000);
  await page.click('[aria-label="Expand cancelOrder()"]');
  await page.waitForSelector('.pv-container');
  for (let i = 0; i < 3; i++) await page.click('[data-pv-zoom="1"]');
  await caption('Open a sub-process in place to see its own steps', 2800);
  await page.click('[data-pv-view="graph"]');
  await caption('Or switch to the full call graph', 2400);
  await page.click('#toProcess');
  await page.fill('#searchInput', '');
  await page.dispatchEvent('#searchInput', 'input');
  await page.click('.tab[data-tab="tables"]');
  await caption('4. Or start from a table: who reads and writes oms_order?', 1500);
  await page.click('[data-table="oms_order"]');
  await caption('Every entry point that touches the table, and the path it takes', 3200);
  await caption('5. Re-index a branch to compare flows; export to PNG, SVG or Mermaid', 2400);
  await page.click('.tab[data-tab="endpoints"]');
  await page.click('#openMenu [data-menu-toggle]');
  await slowType('#pathInput', sample);
  await page.click('#indexBtn');
  await page.waitForFunction(() => /Indexed/.test(document.querySelector('#status').textContent));
  await endpoint(page, 'POST /api/accounts');
  await caption('Field lineage: which request field lands in which column', 2400);
  await page.click('#themeBtn');
  await caption('Runs locally: desktop app for Windows, macOS and Linux. Nothing is uploaded.', 3000);
  await cdp.send('Page.stopScreencast');
  await context.close();
  // concat list with each frame's real duration
  frames.push({ file: frames[frames.length - 1].file, t: frames[frames.length - 1].t + 1.5 });
  const list = frames.slice(0, -1).map((f, i) => `file '${f.file}'\nduration ${Math.max(0.01, frames[i + 1].t - f.t).toFixed(3)}`).join('\n')
    + `\nfile '${frames[frames.length - 1].file}'\n`;
  writeFileSync(join(frameDir, 'list.txt'), list);
  const ff = (...a) => execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...a], { stdio: 'inherit' });
  const mp4 = join(out, 'walkthrough.mp4');
  ff('-f', 'concat', '-safe', '0', '-i', join(frameDir, 'list.txt'), '-vf', 'scale=1280:-2,fps=30', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '23', '-movflags', '+faststart', mp4);
  ff('-i', mp4, '-vf', 'fps=8,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=3', join(out, 'walkthrough.gif'));
  rmSync(frameDir, { recursive: true, force: true });
}

await browser.close();
engine.kill();
console.log('media written to', out);
