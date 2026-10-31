// Small DOM/text helpers shared by the viewer modules.

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

export function basename(p) {
  return String(p || '').replace(/[\\/]+$/, '').split(/[\\/]/).pop() || p;
}

/** Join an absolute project folder with a '/'-relative file path, keeping the OS separator. */
export function joinPath(root, rel) {
  if (!root) return rel;
  const sep = root.includes('\\') && !root.includes('/') ? '\\' : '/';
  return root.replace(/[\\/]+$/, '') + sep + String(rel).split('/').join(sep);
}

export const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
export const modKey = isMac ? '⌘' : 'Ctrl';
