// FlowLens viewer — wires the toolbar, sidebar, graph, detail panel, and desktop integration.
import { $, $$, esc, debounce, basename, modKey } from './util.js';
import { api, runJob, engineInfo, isDesktop, invoke } from './api.js';
import { loadState, saveState, flushState, getState, addRecent } from './state.js';
import { FlowGraph, LAYERS } from './graph.js';
import { Sidebar } from './sidebar.js';
import { DetailPanel } from './detail.js';
import { Minimap } from './minimap.js';
import { toPng, toSvg, toMermaid, toSnapshot, parseSnapshot } from './export.js';
import * as platform from './desktop.js';

const app = {
  graph: null,          // graph on screen (may be a diff)
  baseGraph: null,      // the indexed graph (not a diff)
  projectPath: null,
  compare: null,        // { label } while comparing
  busy: false,
  job: {},
  watchTimer: null,
  fingerprint: null,
  selected: null,
  engine: null,
};

let flow, sidebar, detail, minimap;

// ------------------------------------------------------------------------------------------
// boot
// ------------------------------------------------------------------------------------------

(async function boot() {
  cytoscape.use(cytoscapeDagre);
  const state = await loadState();
  applyTheme(state.theme);

  flow = new FlowGraph($('#cy'), {
    onSelect: n => selectNode(n.id, { fromGraph: true }),
    onBackground: () => { app.selected = null; detail.clear(); },
    onViewport: () => minimap.schedule(),
    onLayout: () => minimap.invalidate(),
  });
  flow.dir = state.layoutDir || 'LR';
  flow.showBranches = state.showBranches !== false;
  if (Array.isArray(state.layers)) flow.layers = new Set(state.layers);
  $('#dirBtn').textContent = flow.dir === 'LR' ? '→' : '↓';
  $('#optTests').checked = !!state.includeTests;
  if (isDesktop) {
    $$('.desktop-only').forEach(el => { el.hidden = false; });
    $('#optHeap').value = state.maxHeapMb ? String(state.maxHeapMb) : '';
  }

  sidebar = new Sidebar({
    onAll: () => showAll(),
    onEndpoint: id => showEndpoint(id),
    onTable: t => showTable(t),
    onNode: id => selectNode(id),
    onTab: tab => saveState({ sidebarTab: tab }),
  });
  sidebar.setTab(state.sidebarTab || 'endpoints');
  detail = new DetailPanel($('#panel'), {
    select: id => selectNode(id),
    focus: id => toggleFocus(id),
    collapse: id => { flow.toggleCollapse(id); selectNode(id); },
    table: t => showTable(t),
    openExternal: url => platform.openExternal(url).catch(e => status('Could not open: ' + e)),
    copy: text => navigator.clipboard?.writeText(text).then(() => status('Copied ' + text)),
  });
  minimap = new Minimap($('#minimap'), () => flow.cy);
  window.flowlens = { app, flow, sidebar };   // handle for end-to-end tests and debugging

  buildLayerFilters();
  wireToolbar();
  wireKeyboard();
  wireDesktop();
  renderRecent();

  app.engine = await engineInfo();
  $('#versionLabel').textContent = app.engine ? `FlowLens ${app.engine.version}` : '';
  if (!app.engine) {
    status(isDesktop ? 'The FlowLens engine is not running — see Help → About for logs.'
      : 'Engine not reachable. Start it with:  java -jar flowlens-analyzer.jar --serve');
  }

  // Start-up content: the last graph is not re-indexed automatically (projects can be big).
  const sample = await loadStatic();
  if (sample) {
    setGraph(sample, { sample: true });
    status('Showing the sample project. Open your own project folder to index it.');
  } else {
    $('#welcome').hidden = false;
  }
})();

async function loadStatic() {
  // graph.json is only present when the viewer is served statically next to a CLI-generated graph
  for (const url of app.engine ? ['graph.sample.json'] : ['graph.json', 'graph.sample.json']) {
    try {
      const r = await fetch(url, { cache: 'no-store' });
      if (r.ok) return await r.json();
    } catch { /* not bundled */ }
  }
  return null;
}

// ------------------------------------------------------------------------------------------
// indexing
// ------------------------------------------------------------------------------------------

async function openProject() {
  if (app.busy) return;
  try {
    status('Choose a project folder…');
    const path = await platform.pickFolder();
    if (!path) { status('Folder selection cancelled.'); return; }
    await indexProject(path);
  } catch (e) {
    status(e.message.includes('folder chooser') || e.status === 400 ? e.message : 'Could not open a folder chooser: ' + e.message);
    $('#pathInput').focus();
  }
}

async function indexProject(path, { ref = null, silent = false, keepView = false } = {}) {
  path = String(path || '').trim();
  if (!path) { status('Enter a project path first.'); $('#pathInput').focus(); return null; }
  if (app.busy) return null;
  setBusy(true, silent ? null : (ref ? `Indexing ${basename(path)} at ${ref}…` : `Indexing ${basename(path)}…`));
  const opts = { path, includeTests: $('#optTests').checked };
  if (ref) opts.ref = ref;
  try {
    const graph = await runJob(opts, s => showProgress(s), app.job);
    if (!graph) { status('Indexing cancelled.'); return null; }
    if (ref) return graph;
    app.projectPath = path;
    $('#pathInput').value = path;
    addRecent(path);
    renderRecent();
    setGraph(graph, { keepView });
    const s = graph.stats || {};
    const eps = (graph.nodes || []).filter(n => n.type === 'endpoint').length;
    status(`${silent ? 'Re-indexed' : 'Indexed'} ${basename(path)}: ${eps} entry points, ${(graph.nodes || []).length} nodes` +
      `${s.filesSkipped ? `, ${s.filesSkipped} files skipped` : ''} in ${((s.durationMs || 0) / 1000).toFixed(1)} s.`);
    return graph;
  } catch (e) {
    status('Error: ' + e.message);
    if (!silent) showModal(`<h3>Indexing failed</h3><p>${esc(e.message)}</p>`);
    return null;
  } finally {
    setBusy(false);
  }
}

function showProgress(s) {
  if ($('#progress').hidden) return;
  const phases = { queued: 'Queued…', collect: 'Finding source files…', parse: 'Parsing', index: 'Indexing types…', analyze: 'Following call chains', lineage: 'Tracing field lineage…' };
  const label = phases[s.phase] || s.phase;
  const bar = $('#progressBar').parentElement;
  if (s.total > 0) {
    bar.classList.remove('indeterminate');
    $('#progressBar').style.width = Math.round((s.done / s.total) * 100) + '%';
    $('#progressDetail').textContent = `${label} ${s.done.toLocaleString()} / ${s.total.toLocaleString()}${s.phase === 'parse' ? ' files' : s.phase === 'analyze' ? ' entry points' : ''}`;
  } else {
    bar.classList.add('indeterminate');
    $('#progressDetail').textContent = label;
  }
}

function setBusy(busy, title) {
  app.busy = busy;
  ['#openBtn', '#indexBtn', '#reindexBtn'].forEach(s => { $(s).disabled = busy || (s === '#reindexBtn' && !app.projectPath); });
  if (busy && title) {
    $('#progressTitle').textContent = title;
    $('#progressDetail').textContent = '';
    $('#progressBar').style.width = '0';
    $('#progressBar').parentElement.classList.add('indeterminate');
    $('#progress').hidden = false;
  }
  if (!busy) $('#progress').hidden = true;
  if (busy && !title) status('Re-indexing…');
}

// ------------------------------------------------------------------------------------------
// showing graphs
// ------------------------------------------------------------------------------------------

function setGraph(graph, { keepView = false, sample = false, diff = false } = {}) {
  graph.nodes = graph.nodes || [];
  graph.edges = graph.edges || [];
  const prevView = keepView ? flow.view : null;
  const prevSelected = keepView ? app.selected : null;
  app.graph = graph;
  if (!diff) app.baseGraph = graph;
  app.sample = sample;
  $('#welcome').hidden = true;
  flow.moduleFilter = sidebar.module;
  flow.load(graph);
  sidebar.load(graph, ids => flow.reaching(ids));
  ['#exportBtn', '#compareBtn'].forEach(s => { $(s).disabled = false; });
  $('#compareBtn').disabled = sample || !app.projectPath;
  $('#reindexBtn').disabled = !app.projectPath;
  $('#watchToggle').disabled = !app.projectPath;
  renderLegend();
  platform.setWindowTitle(sample ? 'FlowLens — sample' : app.projectPath ? `FlowLens — ${basename(app.projectPath)}` : 'FlowLens');

  if (prevView && (prevView.mode !== 'endpoint' || flow.byId.has(prevView.id))) {
    flow.view = prevView;
    flow.apply();
  } else {
    const last = getState().lastEndpoint[app.projectPath || ''];
    const eps = flow.endpoints();
    if (last && flow.byId.has(last)) showEndpoint(last, { quiet: true });
    else if (graph.nodes.length > 700 && eps.length) {
      showEndpoint(eps[0].id, { quiet: true });
      status(`Large project: showing one entry point. Pick another in the sidebar, or "All entry points".`);
    } else showAll({ quiet: true });
  }
  if (prevSelected && flow.byId.has(prevSelected)) selectNode(prevSelected, { fromGraph: true });
  else detail.clear();
  minimap.schedule();
}

function showAll({ quiet } = {}) {
  sidebar.setActive(null);
  const n = flow.showAll();
  if (!quiet) status(`Showing all entry points (${n} nodes).`);
  if (n > 1500) status(`Showing ${n} nodes — pick an entry point in the sidebar for a clearer view.`);
  minimap.schedule();
}

function showEndpoint(id, { quiet } = {}) {
  sidebar.setActive('ep', id);
  flow.showEndpoint(id);
  const ep = flow.byId.get(id);
  if (ep) {
    if (!quiet) status(`${ep.http || ep.label}`);
    selectNode(id, { fromGraph: true });
    const last = { ...getState().lastEndpoint, [app.projectPath || '']: id };
    saveState({ lastEndpoint: last });
  }
  minimap.schedule();
}

function showTable(table) {
  sidebar.setTab('tables');
  sidebar.setActive('table', table);
  const n = flow.showTable(table);
  status(`Everything that reaches ${table} (${n} nodes).`);
  detail.clear(`Showing every entry point that reads or writes <b>${esc(table)}</b>, and the path it takes.`);
  minimap.schedule();
}

function toggleFocus(id) {
  if (flow.view.mode === 'focus') flow.exitFocus(); else flow.focus(id);
  selectNode(id, { fromGraph: true });
  minimap.schedule();
}

function selectNode(id, { fromGraph = false } = {}) {
  const n = flow.byId?.get(id);
  if (!n) return;
  app.selected = id;
  if (!fromGraph) {
    // make sure it is visible: switch to a view that contains it
    const node = flow.cy.getElementById(id);
    if (!node.nonempty() || node.style('display') === 'none') flow.focus(id);
  }
  flow.select(id);
  const outgoing = (flow.out.get(id) || []).map(e => [e, flow.byId.get(e.target)]).filter(x => x[1]);
  const incoming = (flow.in.get(id) || []).map(e => [e, flow.byId.get(e.source)]).filter(x => x[1]);
  detail.show(n, {
    graph: app.graph, outgoing, incoming,
    collapsed: flow.collapsed.has(id), focused: flow.view.mode === 'focus' && flow.view.id === id,
  });
}

// ------------------------------------------------------------------------------------------
// compare (diff mode)
// ------------------------------------------------------------------------------------------

async function compareWithRef() {
  if (!app.projectPath) return;
  const ref = await prompt('Compare with a git ref', 'Branch, tag or commit to compare the working tree against:', 'main');
  if (!ref) return;
  const base = await indexProject(app.projectPath, { ref });
  if (!base) return;
  try {
    await showDiff(base, ref);
  } catch (e) {
    status('Compare failed: ' + e.message);
    showModal(`<h3>Could not compare</h3><p>${esc(e.message)}</p>`);
  }
}

async function compareWithSnapshot() {
  const file = await platform.openSnapshotFile().catch(e => { status('Could not open: ' + e); return null; });
  if (!file) return;
  try {
    await showDiff(parseSnapshot(file.text), basename(file.name || file.path || 'snapshot'));
  } catch (e) {
    showModal(`<h3>Could not compare</h3><p>${esc(e.message)}</p>`);
  }
}

async function showDiff(base, label) {
  status('Comparing…');
  const d = await api.diff(base, app.baseGraph, label);
  app.compare = { label };
  setGraph(d, { diff: true });
  const count = k => d.nodes.filter(n => n.diff === k && n.type !== 'branch' && n.type !== 'throw').length;
  const affected = d.nodes.filter(n => n.type === 'endpoint' && n.diff).length;
  const banner = $('#banner');
  banner.hidden = false;
  banner.innerHTML = `<b>Comparing with ${esc(label)}</b>
    <span><span class="diffmark added"></span> ${count('added')} added</span>
    <span><span class="diffmark changed"></span> ${count('changed')} changed</span>
    <span><span class="diffmark removed"></span> ${count('removed')} removed</span>
    <span><span class="diffmark affected"></span> ${affected} entry points affected</span>
    <button id="bannerExit">Exit compare</button>`;
  $('#bannerExit').onclick = exitCompare;
  $('#compareExit').disabled = false;
  status(`Compared with ${label}: ${affected} entry points affected.`);
}

function exitCompare() {
  app.compare = null;
  $('#banner').hidden = true;
  $('#compareExit').disabled = true;
  if (app.baseGraph) setGraph(app.baseGraph, { keepView: true });
}

// ------------------------------------------------------------------------------------------
// watch mode
// ------------------------------------------------------------------------------------------

function setWatch(on) {
  clearInterval(app.watchTimer);
  app.watchTimer = null;
  app.fingerprint = null;
  if (!on || !app.projectPath) return;
  const tick = async () => {
    if (app.busy || app.compare || !app.projectPath) return;
    const path = app.projectPath;
    try {
      const { fingerprint } = await api.fingerprint({ path, includeTests: $('#optTests').checked });
      // compare only with the last fingerprint of the same project (a newly opened one starts fresh)
      const previous = app.fingerprint && app.fingerprint.path === path ? app.fingerprint.value : null;
      app.fingerprint = { path, value: fingerprint };
      if (previous && previous !== fingerprint) await indexProject(path, { silent: true, keepView: true });
    } catch { /* engine busy or folder gone */ }
  };
  tick();
  app.watchTimer = setInterval(tick, 2000);
  status('Watching for changes…');
}

// ------------------------------------------------------------------------------------------
// export
// ------------------------------------------------------------------------------------------

function baseName() {
  return (app.projectPath ? basename(app.projectPath) : 'flowlens') + (flow.view.mode === 'endpoint' ? '-' + (flow.byId.get(flow.view.id)?.path || 'endpoint').replace(/[^\w-]+/g, '_').replace(/^_+|_+$/g, '') : '');
}

async function doExport(kind) {
  if (!flow.cy) return;
  try {
    let saved;
    if (kind === 'png') {
      const uri = toPng(flow.cy);
      saved = await platform.saveFile({ name: baseName() + '.png', mime: 'image/png', base64: uri.split(',')[1], filterName: 'PNG image', extensions: ['png'] });
    } else if (kind === 'svg') {
      const svg = toSvg(flow.cy);
      if (!svg) return status('Nothing to export.');
      saved = await platform.saveFile({ name: baseName() + '.svg', mime: 'image/svg+xml', text: svg, filterName: 'SVG image', extensions: ['svg'] });
    } else if (kind === 'mermaid') {
      const text = toMermaid(flow.cy, flow.dir);
      saved = await platform.saveFile({ name: baseName() + '.mmd', mime: 'text/plain', text, filterName: 'Mermaid', extensions: ['mmd', 'md'] });
      navigator.clipboard?.writeText(text).catch(() => {});
    } else if (kind === 'snapshot') {
      saved = await platform.saveFile({ name: (app.projectPath ? basename(app.projectPath) : 'flowlens') + '.flowlens', mime: 'application/json', text: toSnapshot(app.baseGraph || app.graph), filterName: 'FlowLens snapshot', extensions: ['flowlens'] });
    }
    if (saved) status(`Saved ${saved}${kind === 'mermaid' ? ' (also copied to the clipboard)' : ''}.`);
  } catch (e) {
    status('Export failed: ' + e.message);
  }
}

async function openSnapshot(file) {
  try {
    const f = file || await platform.openSnapshotFile();
    if (!f) return;
    const graph = parseSnapshot(f.text);
    app.projectPath = graph.sourcePath || null;
    if (app.projectPath) $('#pathInput').value = app.projectPath;
    setGraph(graph);
    status(`Opened snapshot ${basename(f.name || f.path || '')}.`);
  } catch (e) {
    showModal(`<h3>Could not open snapshot</h3><p>${esc(e.message)}</p>`);
  }
}

// ------------------------------------------------------------------------------------------
// toolbar, keyboard, desktop wiring
// ------------------------------------------------------------------------------------------

function wireToolbar() {
  $('#openBtn').onclick = openProject;
  $('#indexBtn').onclick = () => indexProject($('#pathInput').value);
  $('#pathInput').addEventListener('keydown', e => { if (e.key === 'Enter') indexProject(e.target.value); });
  $('#reindexBtn').onclick = () => app.projectPath && indexProject(app.projectPath, { keepView: true });
  $('#cancelBtn').onclick = () => app.job.cancel && app.job.cancel();
  $('#watchToggle').onchange = e => setWatch(e.target.checked);
  $('#optTests').onchange = e => saveState({ includeTests: e.target.checked });
  $('#optHeap').onchange = async e => {
    saveState({ maxHeapMb: e.target.value ? Number(e.target.value) : null });
    status('Restarting the analysis engine…');
    await flushState(); // the engine reads the heap setting from the state file
    try { await invoke('restart_engine'); status('Engine restarted with the new memory limit.'); }
    catch (err) { status('Could not restart the engine: ' + err); }
  };
  $('#fitBtn').onclick = () => flow.fit();
  $('#tidyBtn').onclick = () => flow.relayout();
  $('#dirBtn').onclick = () => {
    flow.dir = flow.dir === 'LR' ? 'TB' : 'LR';
    $('#dirBtn').textContent = flow.dir === 'LR' ? '→' : '↓';
    saveState({ layoutDir: flow.dir });
    flow.relayout();
  };
  $('#themeBtn').onclick = () => {
    const order = ['auto', 'light', 'dark'];
    const next = order[(order.indexOf(getState().theme) + 1) % order.length];
    saveState({ theme: next });
    applyTheme(next);
    status(`Theme: ${next}`);
  };
  $('#searchInput').addEventListener('input', debounce(e => search(e.target.value), 120));
  $('#moduleSelect').onchange = e => {
    sidebar.setModule(e.target.value);
    flow.moduleFilter = e.target.value;
    if (flow.view.mode === 'all') showAll();
  };
  $('#issuesBadge').onclick = () => sidebar.setTab('issues');

  // menus
  document.addEventListener('click', e => {
    const toggle = e.target.closest('[data-menu-toggle]');
    const insideMenu = e.target.closest('.menu-pop') && !e.target.closest('[data-action]');
    if (!insideMenu) $$('.menu.open').forEach(m => { if (!toggle || m.id !== toggle.dataset.menuToggle) m.classList.remove('open'); });
    if (toggle && !toggle.disabled) $('#' + toggle.dataset.menuToggle).classList.toggle('open');
    const action = e.target.closest('[data-action]');
    if (action && !action.disabled) {
      $$('.menu.open').forEach(m => m.classList.remove('open'));
      runAction(action.dataset.action, action.dataset);
    }
  });
}

function runAction(action, data = {}) {
  switch (action) {
    case 'open': return openProject();
    case 'open-recent': return indexProject(data.path);
    case 'open-snapshot': return openSnapshot();
    case 'reindex': return app.projectPath && indexProject(app.projectPath, { keepView: true });
    case 'compare-ref': return compareWithRef();
    case 'compare-snapshot': return compareWithSnapshot();
    case 'compare-exit': return exitCompare();
    case 'export-png': return doExport('png');
    case 'export-svg': return doExport('svg');
    case 'export-mermaid': return doExport('mermaid');
    case 'save-snapshot': return doExport('snapshot');
    case 'fit': return flow.fit();
    case 'tidy': return flow.relayout();
    case 'toggle-dir': return $('#dirBtn').click();
    case 'toggle-theme': return $('#themeBtn').click();
    case 'toggle-branches': { const cb = $('#layerFilters input[data-layer="flow"]'); cb.checked = !cb.checked; cb.dispatchEvent(new Event('change')); return; }
    case 'show-all': return showAll();
    case 'find': $('#searchInput').focus(); $('#searchInput').select(); return;
    case 'help-mcp': return showMcpHelp();
    case 'help-shortcuts': return showShortcuts();
    case 'help-about': return showAbout();
    case 'open-logs': return platform.openLogs();
    default: return null;
  }
}

function wireKeyboard() {
  window.addEventListener('keydown', e => {
    const mod = e.ctrlKey || e.metaKey;
    const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName || '');
    // in the desktop app the native menu owns these accelerators
    if (isDesktop && mod && ['o', 'r', 'f', 's', 'b', '0'].includes(e.key.toLowerCase())) return;
    if (mod && e.key.toLowerCase() === 'o') { e.preventDefault(); openProject(); }
    else if (mod && e.key.toLowerCase() === 'r') { e.preventDefault(); runAction('reindex'); }
    else if (mod && e.key.toLowerCase() === 'f') { e.preventDefault(); runAction('find'); }
    else if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); doExport('snapshot'); }
    else if (e.key === 'Escape') {
      if (flow.view.mode === 'focus') { flow.exitFocus(); minimap.schedule(); }
      else if (typing) document.activeElement.blur();
      else { flow.cy?.$(':selected').unselect(); detail.clear(); }
    } else if (!typing && !mod && e.key === 'f') flow.fit();
  });
}

function wireDesktop() {
  platform.onMenu(payload => {
    if (payload && payload.startsWith('recent:')) indexProject(payload.slice(7));
    else runAction(payload);
  });
  platform.onOpenPaths(paths => handleDropped(paths));
  platform.onEngineEvent(ev => status(ev.message || String(ev)));
  if (isDesktop) {
    invoke('engine_error').then(err => { if (err) showModal(`<h3>The analysis engine could not start</h3><p>${esc(err)}</p>`, [['logs', 'Open logs folder'], ['close', 'Close', true]]).then(v => { if (v === 'logs') platform.openLogs(); }); });
  }
  platform.onDrop(paths => handleDropped(paths), files => {
    const f = files[0];
    if (f && /\.(flowlens|json)$/i.test(f.name)) f.text().then(text => openSnapshot({ name: f.name, text }));
    else status('Drop a .flowlens snapshot here. To index a folder, use Open project (browsers cannot read dropped folders).');
  }, hover => document.body.classList.toggle('drop-target', hover));
}

async function handleDropped(paths) {
  for (const p of paths) {
    const kind = await platform.pathKind(p);
    if (kind === 'dir') return indexProject(p);
    if (/\.(flowlens|json)$/i.test(p)) {
      try { return await openSnapshot({ path: p, name: basename(p), text: await platform.readTextFile(p) }); }
      catch (e) { status('Could not open ' + p + ': ' + e); }
    }
  }
  if (paths.length) status('Drop a project folder or a .flowlens snapshot.');
}

function search(q) {
  sidebar.setFilter(q);
  const term = q.trim().toLowerCase();
  const hits = new Set();
  if (term.length >= 2 && app.graph) {
    for (const n of app.graph.nodes) {
      if (`${n.label} ${n.http || ''} ${n.table || ''}`.toLowerCase().includes(term)) hits.add(n.id);
    }
  }
  flow.highlight(hits);
}

function buildLayerFilters() {
  const wrap = $('#layerFilters');
  wrap.innerHTML = '';
  for (const [key, label, color] of LAYERS) {
    if (key === 'controller') continue;
    const l = document.createElement('label');
    l.title = `Show ${label.toLowerCase()} nodes`;
    const on = key === 'flow' ? flow.showBranches : flow.layers.has(key);
    l.innerHTML = `<input type="checkbox" data-layer="${key}" ${on ? 'checked' : ''}><span class="dot" style="background:var(${color})"></span>${key === 'flow' ? 'Branches &amp; throws' : label}`;
    l.querySelector('input').addEventListener('change', e => {
      if (e.target.checked) flow.layers.add(key); else flow.layers.delete(key);
      if (key === 'flow') { flow.showBranches = e.target.checked; saveState({ showBranches: flow.showBranches }); renderLegend(); }
      saveState({ layers: [...flow.layers] });
      flow.apply();
      minimap.schedule();
    });
    wrap.appendChild(l);
  }
}

function renderLegend() {
  const items = [
    ['--controller', 'entry point'], ['--service', 'service'], ['--repository', 'repository'],
    ['--db-write', 'write'], ['--db-update', 'update'], ['--db-delete', 'delete'], ['--db-read', 'read'],
    ['--external', 'HTTP / messaging'],
  ];
  if (flow.showBranches) items.push(['--branch', 'branch'], ['--throw', 'throw']);
  $('#legend').innerHTML = items.map(([v, l]) => `<span><span class="dot" style="background:var(${v})"></span>${l}</span>`).join('') +
    '<span>┄ uncertain</span>';
}

function renderRecent() {
  const recent = getState().recent || [];
  $('#recentList').innerHTML = recent.length
    ? recent.map(p => `<button class="recent-item" data-action="open-recent" data-path="${esc(p)}" title="${esc(p)}">${esc(basename(p))}<small>${esc(p)}</small></button>`).join('')
    : '<div class="menu-empty">None yet</div>';
}

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme || 'auto';
  $('#themeBtn').textContent = theme === 'light' ? '☀' : theme === 'dark' ? '☾' : '◐';
  // let the CSS variables settle, then restyle the canvas
  requestAnimationFrame(() => { flow?.restyle(); minimap?.invalidate(); });
}
window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', () => applyTheme(getState().theme));

function status(text) {
  $('#status').textContent = text;
}

// ------------------------------------------------------------------------------------------
// dialogs
// ------------------------------------------------------------------------------------------

function showModal(html, actions = [['close', 'Close', true]]) {
  const dlg = $('#modal');
  $('#modalContent').innerHTML = html;
  $('#modalActions').innerHTML = actions.map(([v, l, primary]) => `<button value="${v}" class="${primary ? 'primary' : ''}">${esc(l)}</button>`).join('');
  return new Promise(resolve => {
    dlg.onclose = () => resolve(dlg.returnValue);
    dlg.showModal();
  });
}

async function prompt(title, text, placeholder) {
  const r = await showModal(`<h3>${esc(title)}</h3><p>${esc(text)}</p><input type="text" id="promptInput" value="${esc(placeholder || '')}" spellcheck="false">`,
    [['cancel', 'Cancel'], ['ok', 'Compare', true]]);
  const value = $('#promptInput')?.value.trim();
  return r === 'ok' && value ? value : null;
}

async function showMcpHelp() {
  const info = await platform.mcpInfo().catch(() => null);
  const cmd = info ? info.command : 'java';
  const args = info ? info.args : ['-jar', '/path/to/flowlens-analyzer.jar', '--mcp'];
  const config = JSON.stringify({ mcpServers: { flowlens: { command: cmd, args: [...args, app.projectPath || '/path/to/your/project'] } } }, null, 2);
  const cli = `claude mcp add flowlens -- ${[cmd, ...args].map(a => /\s/.test(a) ? JSON.stringify(a) : a).join(' ')} ${app.projectPath ? JSON.stringify(app.projectPath) : '/path/to/project'}`;
  showModal(`<h3>Connect AI coding agents (MCP)</h3>
    <p>FlowLens includes a Model Context Protocol server, so agents such as Claude Code can ask
    “what writes to the account table?” or “which endpoints does changing AccountService.create affect?”.</p>
    <p><b>Claude Code</b></p><pre>${esc(cli)}</pre>
    <p><b>Other MCP clients</b> (JSON config)</p><pre>${esc(config)}</pre>
    <p>Tools: index_project, list_endpoints, endpoint_flow, table_access, impact_of_change, search, compare_with_ref.</p>`,
  [['copy', 'Copy JSON'], ['close', 'Close', true]]).then(v => { if (v === 'copy') navigator.clipboard?.writeText(config); });
}

function showShortcuts() {
  const k = s => s.split('+').map(x => `<kbd>${esc(x)}</kbd>`).join('+');
  showModal(`<h3>Keyboard shortcuts</h3><table class="keys">
    <tr><td>${k(modKey + '+O')}</td><td>Open project</td></tr>
    <tr><td>${k(modKey + '+R')}</td><td>Re-index</td></tr>
    <tr><td>${k(modKey + '+F')}</td><td>Search</td></tr>
    <tr><td>${k(modKey + '+S')}</td><td>Save snapshot</td></tr>
    <tr><td>${k('F')}</td><td>Fit graph to screen</td></tr>
    <tr><td>${k('Esc')}</td><td>Exit focus / clear selection</td></tr>
    <tr><td>Double-click node</td><td>Collapse / expand its subtree</td></tr></table>`);
}

function showAbout() {
  const v = app.engine ? app.engine.version : 'unknown';
  showModal(`<h3>FlowLens ${esc(v)}</h3>
    <p>Static request-flow graphs for Java: entry point → services → database, from source only.</p>
    <p>Everything runs on this machine. No code or telemetry leaves it.</p>
    <p>Apache-2.0 · <a href="#" id="aboutRepo">github.com/developerdsk/flowlens</a></p>`,
  isDesktop ? [['logs', 'Open logs folder'], ['close', 'Close', true]] : undefined).then(r => { if (r === 'logs') platform.openLogs(); });
  setTimeout(() => { const a = $('#aboutRepo'); if (a) a.onclick = e => { e.preventDefault(); platform.openExternal('https://github.com/developerdsk/flowlens'); }; });
}
