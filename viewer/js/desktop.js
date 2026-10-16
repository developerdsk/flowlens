// Platform services: native dialogs, files, menus and drag & drop in the desktop app,
// with browser fallbacks so the same UI works when served by `flowlens --serve`.
import { api, isDesktop, invoke } from './api.js';

const tauri = window.__TAURI__;

/** Ask the user for a project folder. Resolves to an absolute path, or null if cancelled. */
export async function pickFolder() {
  if (isDesktop) return await invoke('pick_folder');
  const r = await api.pick();       // server-side chooser (same machine)
  return r.cancelled ? null : r.path;
}

/** Save text or base64 content. Desktop: native save dialog. Browser: download. */
export async function saveFile({ name, mime, text, base64, filterName, extensions }) {
  if (isDesktop) {
    return await invoke('save_file', {
      defaultName: name, filterName: filterName || 'File', extensions: extensions || [],
      contents: base64 ?? text, isBase64: base64 != null,
    });
  }
  let blob;
  if (base64 != null) {
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    blob = new Blob([bytes], { type: mime });
  } else {
    blob = new Blob([text], { type: mime });
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
  return name;
}

/** Let the user choose a .flowlens / graph.json file. Resolves to { name, text } or null. */
export function openSnapshotFile() {
  if (isDesktop) return invoke('open_snapshot');
  return new Promise(resolve => {
    const input = document.getElementById('fileInput');
    input.value = '';
    input.onchange = async () => {
      const f = input.files && input.files[0];
      resolve(f ? { name: f.name, text: await f.text() } : null);
    };
    input.click();
  });
}

export async function readTextFile(path) {
  return await invoke('read_text_file', { path });
}

export async function pathKind(path) {
  return isDesktop ? await invoke('path_kind', { path }) : 'unknown';
}

/** Open a URL with the OS (vscode://, idea://, https://). */
export function openExternal(url) {
  if (isDesktop) return invoke('open_external', { url });
  window.location.href = url;
  return Promise.resolve();
}

export function openLogs() {
  return isDesktop ? invoke('open_logs') : Promise.resolve();
}

export function mcpInfo() {
  return isDesktop ? invoke('mcp_info') : Promise.resolve(null);
}

export function onMenu(cb) {
  if (isDesktop) tauri.event.listen('menu', e => cb(e.payload));
}

/** Files/folders the OS asked us to open (file association, second launch, dock drop). */
export async function onOpenPaths(cb) {
  if (!isDesktop) return;
  tauri.event.listen('open-paths', e => cb(e.payload || []));
  const pending = await invoke('take_pending_open').catch(() => []);
  if (pending && pending.length) cb(pending);
}

export function onEngineEvent(cb) {
  if (isDesktop) tauri.event.listen('engine', e => cb(e.payload));
}

/** Drag & drop of folders/files onto the window. cb(paths) for desktop; browser gets File objects. */
export function onDrop(onPaths, onFiles, setHover) {
  if (isDesktop) {
    tauri.event.listen('tauri://drag-enter', () => setHover(true));
    tauri.event.listen('tauri://drag-leave', () => setHover(false));
    tauri.event.listen('tauri://drag-drop', e => { setHover(false); onPaths((e.payload && e.payload.paths) || []); });
    return;
  }
  let depth = 0;
  window.addEventListener('dragenter', e => { e.preventDefault(); depth++; setHover(true); });
  window.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; setHover(false); } });
  window.addEventListener('dragover', e => e.preventDefault());
  window.addEventListener('drop', e => {
    e.preventDefault();
    depth = 0;
    setHover(false);
    onFiles([...(e.dataTransfer?.files || [])]);
  });
}

export function setWindowTitle(title) {
  document.title = title;
  if (isDesktop) invoke('set_title', { title }).catch(() => {});
}
