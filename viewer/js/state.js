// Persisted preferences + recent projects.
// Desktop: a JSON file in the OS app-data folder (via the shell). Browser: localStorage.
import { isDesktop, invoke } from './api.js';

const KEY = 'flowlens.state.v1';
const defaults = {
  theme: 'auto', layoutDir: 'LR', showBranches: true, includeTests: false,
  recent: [], lastEndpoint: {}, layers: null, sidebarTab: 'endpoints', processPreview: false, ide: 0,
};
let state = { ...defaults };

export async function loadState() {
  try {
    const raw = isDesktop ? await invoke('load_state') : localStorage.getItem(KEY);
    if (raw) state = { ...defaults, ...JSON.parse(raw) };
  } catch { /* first run, private mode, or corrupt: use defaults */ }
  return state;
}

let saveTimer;
async function writeState() {
  const json = JSON.stringify(state);
  try {
    if (isDesktop) await invoke('save_state', { json });
    else localStorage.setItem(KEY, json);
  } catch { /* non-fatal */ }
}

export function saveState(patch) {
  Object.assign(state, patch);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(writeState, 250);
  return state;
}

/** Write pending changes now (e.g. before restarting the engine, which reads the file). */
export async function flushState() {
  clearTimeout(saveTimer);
  await writeState();
}

export function getState() { return state; }

export function addRecent(path) {
  const recent = [path, ...state.recent.filter(p => p !== path)].slice(0, 10);
  const changed = recent.join('\n') !== state.recent.join('\n');
  saveState({ recent });
  if (isDesktop && changed) invoke('set_recent', { paths: recent }).catch(() => {});
  return recent;
}
